# Sessions on the device

Status: implemented, steps 1 to 4; see *Later* for what remains.

Today a notebook's history lives on the computer, under
`clerkenwell-state-root`, relative to the REPL's working directory: it sits next
to the code, on one machine. This document describes moving the record of a
session onto the reMarkable, so that any computer connected to the tablet can
pick up where another left off, and so that a session belongs to a document
rather than to a project.

## Goals

- Switch computers and continue: the history, delivery states and handwriting
  of a notebook are the same whichever computer is attached.
- One session per reMarkable document. Several documents may be worked on
  against the same code, each with its own history.
- Which REPL a session is attached to is ephemeral. Reattaching a session to a
  different REPL, on the same computer or another, loses nothing.
- Never insert a result twice, and never lose track of a result that may have
  been inserted, however computers come and go.

## Non-goals, for now

- Two computers writing to one session at the same time. One writes: the
  one the tablet is reporting to.
- Migrating existing histories onto the device (see *Later*).
- Syncing through anything but the tablet: no cloud, no service on the network.

## Terms

The code currently calls the JVM-side state a "session". This design splits
the word:

- A **session** is a document's history: its events, results and page
  snapshots. It is keyed by the notebook's UUID and lives on the device. It
  outlives any REPL.
- A **connection** is a REPL attached to the tablet: the transport server, the
  identity poller and the handwriting watcher. It lives in one JVM and is gone
  when that JVM is.

A connection serves whichever session the tablet has open. `clerkenwell-open`
starts a connection, or reuses the one already in this REPL; it never asks
whether to reopen or start afresh, because the session is chosen by the
document on the tablet, not by the command.

## What a session holds

Measured on a well-used notebook (289 events): 15 MB in all.

| Part | Size | |
| --- | --- | --- |
| `events/` | 1.1 MB | canonical: the source of truth, and it holds the records |
| `results/` | 0.7 MB | canonical: the PNGs, which the adapter reads anyway |
| `pages/` (`.rm` snapshots) | 9.1 MB | canonical: a new computer needs each page's last snapshot to tell new ink from old, and the history to redraw |
| `handwriting/` | 3.9 MB | derived, in the computer's Emacs theme |
| `manifest.edn` | 32 KB | derived, replayed from the events |

Canonical parts live on the device. Derived parts are rebuilt by each computer
into a local cache, so a computer with a dark theme and one with a light theme
do not fight over them.

## Layout on the device

```
/home/root/clerkenwell/<notebook-uuid>/
  lease.edn
  events/<sequence>.edn
  results/<result-uuid>/record.edn
  results/<result-uuid>/result.png
  pages/<page-uuid>/<snapshot-id>.rm
```

It is outside Xochitl's document directory, so neither Xochitl nor cloud sync
ever sees a file it does not know, and the live `.rm` files are never touched.
`/home` survives firmware updates. A notebook's UUID is stable across renames
and moves; duplicating a notebook gives a new UUID, and so a new session.

A session's namespace is part of its history, not a file of its own: every
result's record names the namespace it was evaluated in, and changing it
appends a `:namespace-set` event. The last of either is the session's
namespace. So every file stays immutable, and the copy needs nothing beyond
what it does for events.

The local cache moves out of the project, to `~/.cache/clerkenwell/` (or the
platform's equivalent), with the same layout plus the derived parts.

## Writing

Every file is published locally as now, then copied to the device. There is
no outbox: canonical files are immutable and named by what they are, so what
remains to send is the local files the device does not have yet. Every couple
of seconds they travel as one tar on stdin of a single SSH call, are unpacked
aside, and moved into place a file at a time, events last and in order. The
remote command is a small fixed script whose only arguments are a validated
path, UUID and hex lease token; file names travel inside the tar. No user
source and no notebook name ever reaches a shell.

Most copying is asynchronous: a lost tail costs little, because nothing in it
has reached the page, and ink observations are recovered by the next
computer's diff against the last snapshot on the device.

Delivery is the exception. `:insertion-started` is copied **synchronously,
before** the insert command is sent, with everything before it. So whatever may be on the page is always in the
device's log, and a computer that takes over sees an unacknowledged insertion
as `:uncertain` rather than replaying it.

## Attaching

When the tablet reports a notebook, the connection:

1. reads the lease (below);
2. pulls every event, result and snapshot it does not already have;
3. replays the manifest and redraws handwriting it is missing, locally;
4. a notebook with no namespace yet gets one with its first result: the
   namespace current where `clerkenwell-open` was run.

Because only one connection writes at a time and it syncs fully before
writing, the local copy of the log is always a prefix of the device's, plus
what this connection has not copied yet.

## The lease

Sequence numbers stay linear only if one connection appends. The holder is
named in `lease.edn`, whose first line repeats the token as a comment so the
device's shell can read it:

```clojure
;; 9f2c...
{:token "9f2c..."            ; random per connection
 :host "laptop" :pid 52114
 :acquired-at "..."}
```

Who holds it is decided by the reverse tunnel. The adapter reports what the
tablet has open only through it, and only one computer can bind its port, so
only one connection hears the tablet at a time; two live connections never
contend. The lease does not arbitrate between them, then. It is a fencing
token against the connection that lost its tunnel without noticing -- a
laptop that went to sleep and woke up with its copier still running, which
would otherwise push events under numbers another computer has since used.

- A report for a notebook this connection does not hold makes it take the
  lease, without asking: it syncs fully, writes its token, and marks any
  insertion the previous holder left unacknowledged as uncertain. Reports
  resuming after a gap check the lease again, since another computer may
  have had the tunnel meanwhile.
- Every change to the lease is a compare-and-set under a lock on the device
  (a directory, which `mkdir` either creates or does not).
- Every push checks the token on the device, under the same lock, before
  moving anything into place: a connection that has lost the lease cannot
  write, even if it has not noticed yet. It notices then, stops watching and
  copying, and the mode line says `read-only` until the tablet reports to it
  again.
- Nothing judges a lease's age, so it is never renewed. Disconnecting sends
  what the device lacks and removes the lease, which only tidies up.

A computer that lost the lease before its last events reached the device may
hold events under numbers another holder has since used. Each computer
records how far its events are known to match the device's (`copied.edn`,
local); above that, an event the device also has is compared before it is
trusted. Where they differ, the device's are kept, and this computer's, from
the first that differs, are moved to `stranded/<stamp>/` and reported. By the
delivery rule none of them was ever inserted.

The lease is held per connection, not per computer: two REPLs on one machine
are two holders, though they cannot both serve the tablet's port either.

## Code and namespaces

A session remembers its namespace; `clerkenwell-open` no longer asks for one.
Which REPL that namespace is evaluated in is whichever the buffer is connected
to, which is always visible. Nothing more binds a session to a project: if the
namespace cannot be found in this REPL, submitting says so, and
`clerkenwell-set-namespace` changes it.

## Emacs

- One buffer per session, named from the notebook's visible name in its
  `.metadata`: `*clerkenwell: Fourier scratch*`. The tablet's open document
  decides which buffer is live, and its buffer is shown in place of the one
  in view when the tablet changes notebook; the others keep their history,
  and accept no submissions until their document is open again.
- `clerkenwell-open` attaches this REPL; run again, it reuses the connection.
- A connection that has lost the lease is marked `read-only` in the mode
  line, until the tablet reports to it again.
- `clerkenwell-reset-history` archives the session on the device, renamed,
  never deleted, as it does locally now.

## Plan

1. **Local split.** Separate canonical from derived parts, move the cache out
   of the project, and rename the JVM state to *connection*. No visible
   change otherwise.
2. **Device copy, one computer.** The outbox and remote publish script; pull
   on attach; synchronous `:insertion-started`.
3. **The lease.** Take on report, check on push, set diverged events aside.
4. **Session identity.** The remembered namespace, one buffer per session.

## Later

- Migrating existing local histories onto the device: a one-off copy plus a
  check that the sequences are contiguous.
- Removing sessions whose notebook has been deleted from the tablet, by moving
  them aside.
- Copying page snapshots on the device instead of uploading them, verified by
  digest, to save the transfer.

## Decisions taken

- The device holds the record; computers hold a cache.
- One session per reMarkable document; a session's attachment to a REPL is
  ephemeral.
- The tunnel decides who writes; the lease only fences out a connection that
  lost it. (Asking before taking over was tried first, and dropped: the
  computer the tablet reports to is the one in use.)
- Binding to code is loose: the session remembers its namespace, nothing more.
  It is kept in the event log rather than a `session.edn`, so that every file
  on the device stays immutable.
- Existing histories stay where they are until the rest works.
