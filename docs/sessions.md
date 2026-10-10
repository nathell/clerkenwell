# Sessions on the device

Status: design, not yet implemented.

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

- Two computers writing to one session at the same time. One writes, and
  taking over is explicit.
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
  session.edn
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

`session.edn` says what the session is:

```clojure
{:schema-version 1
 :notebook-id "0c51c0c2-..."
 :namespace "fourier.scratch"   ; submissions are evaluated here
 :created-at "2026-10-10T..."}
```

The local cache moves out of the project, to `~/.cache/clerkenwell/` (or the
platform's equivalent), with the same layout plus the derived parts.

## Writing

Every event is published locally as now, then pushed to the device, where it is
published the same way: a temp file in the same directory, then a rename. The
bytes travel on stdin; the remote command is a small fixed script whose only
arguments are a validated UUID, a sequence number and a hex lease token. No
user source and no notebook name ever reaches a shell.

Pushes go through an outbox, in order. Most are asynchronous: a lost tail
costs little, because nothing in it has reached the page, and ink observations
are recovered by the next computer's diff against the last snapshot on the
device.

Delivery is the exception. `:insertion-started` is pushed **synchronously,
before** the insert command is sent, and a synchronous push first flushes
everything queued before it. So whatever may be on the page is always in the
device's log, and a computer that takes over sees an unacknowledged insertion
as `:uncertain` rather than replaying it.

## Attaching

When the tablet reports a notebook, the connection:

1. reads the lease (below);
2. pulls every event, result and snapshot it does not already have;
3. replays the manifest and redraws handwriting it is missing, locally;
4. if `session.edn` does not exist, the notebook has no session yet: one is
   created when the first result is submitted, with the REPL's current
   namespace.

Because only one connection writes at a time and it syncs fully before
writing, the local copy of the log is always a prefix of the device's, plus
this connection's own unflushed outbox.

## The lease

Sequence numbers stay linear only if one connection appends. The holder is
named in `lease.edn`:

```clojure
{:token "9f2c..."            ; random per connection
 :holder "laptop / nREPL 52114"
 :acquired-at "..."}
```

- The holder renews it every 30 seconds by rewriting it. Freshness is judged
  by the file's modification time against the **device's** clock, so the
  computers' clocks never have to agree.
- Every push checks the token on the device before publishing, in the same
  script: a connection that has lost the lease cannot write, even if it has
  not noticed yet.
- A connection that finds a lease held by another, fresh or stale, is
  read-only: the buffer shows the history, the status line says
  `held by laptop, last seen 10:42`, and submitting asks whether to take
  over. Taking over writes a new token, then syncs fully before the first
  write.
- A connection that loses the lease keeps its unflushed outbox aside rather
  than discarding it, and says so. By the rule above nothing in it was ever
  inserted.

The lease is held per connection, not per computer: two REPLs on one machine
are two holders.

The reverse tunnel can be bound by only one computer at a time, which is
nearly a lock already; but a stale dropbear process can hold the port long
after its owner is gone, so the tunnel is a hint, not the mechanism.

## Code and namespaces

A session remembers its namespace; `clerkenwell-open` no longer asks for one.
Which REPL that namespace is evaluated in is whichever the buffer is connected
to, which is always visible. Nothing more binds a session to a project: if the
namespace cannot be found in this REPL, submitting says so, and
`clerkenwell-set-namespace` changes it.

## Emacs

- One buffer per session, named from the notebook's visible name in its
  `.metadata`: `*clerkenwell: Fourier scratch*`. The tablet's open document
  decides which buffer is live; the others keep their history, read-only
  until their document is open again.
- `clerkenwell-open` attaches this REPL; run again, it reuses the connection.
- A read-only session (lease held elsewhere) is marked in the mode line, and
  `clerkenwell-take-over` takes the lease.
- `clerkenwell-reset-history` archives the session on the device, renamed,
  never deleted, as it does locally now.

## Plan

1. **Local split.** Separate canonical from derived parts, move the cache out
   of the project, and rename the JVM state to *connection*. No visible
   change otherwise.
2. **Device copy, one computer.** The outbox and remote publish script; pull
   on attach; synchronous `:insertion-started`.
3. **The lease.** Acquire, renew, check-on-push, read-only mode, explicit
   takeover.
4. **Session identity.** `session.edn`, the remembered namespace, one buffer
   per session.

## Later

- Migrating existing local histories onto the device: a one-off copy plus a
  check that the sequences are contiguous.
- Automatic takeover of a lease that has been stale for some minutes, if
  asking turns out to be tedious.
- Removing sessions whose notebook has been deleted from the tablet, by moving
  them aside.
- Copying page snapshots on the device instead of uploading them, verified by
  digest, to save the transfer.

## Decisions taken

- The device holds the record; computers hold a cache.
- One session per reMarkable document; a session's attachment to a REPL is
  ephemeral.
- Takeover asks first, for now.
- Binding to code is loose: the session remembers its namespace, nothing more.
- Existing histories stay where they are until the rest works.
