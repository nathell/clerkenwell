# Running Clerkenwell end to end

State of play: this delivers a computed result to a page. Handwriting does not
yet come back (that is increment 3), and there is no Emacs integration, so
expressions are typed at a prompt and evaluated in the driver's own JVM rather
than in your CIDER session.

## Prerequisites

- The Edsger prerequisites from the README: reMarkable 2 on firmware 3.27.1.0,
  Vellum, `qt-resource-rebuilder`, xovi.
- An SSH host alias for the device, so no addresses, users or keys appear in
  any command here. If you would otherwise type `ssh root@rm`, either put the
  user in this config or set `clerkenwell-ssh-user` to `"root"`. In
  `~/.ssh/config`:

  ```
  Host rm
      HostName 10.11.99.1
      User root
      # Connection reuse keeps the asset pushes off a fresh handshake.
      ControlMaster auto
      ControlPath ~/.ssh/cm-%r@%h:%p
      ControlPersist 60
  ```

  **Do not let the tunnel in step 2 use that shared connection.** A persisted
  master outlives the session that opened it and keeps holding the forwarded
  port, so the next `ssh -R` cannot bind it; and a multiplexed session cannot
  add a remote forward at all. Step 2 opts out explicitly.

- A JVM on the host, and the Inconsolata font installed. Output is drawn in a
  monospace face so that on the page it is immediately distinguishable from your
  own writing. Images are drawn with Java2D, so no external image tool is
  needed. To use a font that is not installed, set
  `clerkenwell.render/default-font` to the path of a `.ttf` file.

## 1. Install the adapter on the device

```sh
scp xovi/clerkenwell.qmd rm:xovi/exthome/qt-resource-rebuilder/
ssh rm './xovi/rebuild_hashtable'
```

Then restart the UI with mods, in an SSH session you keep open:

```sh
ssh rm
./xovi/debug
```

Leave that running; it is also where the adapter's `CKW` log lines appear.

## 2. Open the reverse tunnel

The adapter reaches the host on the device's own loopback, so nothing is exposed
on a network. In another terminal:

```sh
ssh -S none -o ControlMaster=no -o ExitOnForwardFailure=yes \
    -R 127.0.0.1:14242:127.0.0.1:14242 rm
```

Leave it open. It only needs to exist; you do not type anything into it.

`-S none -o ControlMaster=no` keeps this session off the shared connection, so
it owns its forward and releases the port when it exits. `ExitOnForwardFailure`
makes a failed forward fatal: without it, ssh prints a warning, connects anyway,
and the driver then hangs on "waiting for the device" with no obvious cause.

If it reports `remote port forwarding failed for listen port 14242`, something
still holds the port on the device:

```sh
ssh rm 'netstat -tlnp 2>/dev/null | grep 14242; ps w | grep dropbear'
```

The usual cause is a **stale dropbear process** from an earlier tunnel that was
never closed cleanly -- most often because the reMarkable went to sleep while
the tunnel was up. The device runs dropbear, not OpenSSH's sshd, and the orphan
keeps the forwarded port bound until it is killed:

```sh
ssh rm 'kill <pid>'
```

If you are using the shared-connection config above, also check that a persisted
master is not holding it (`ssh -O check rm`, then `ssh -O exit rm`). That only
applies when a `ControlPath` is configured; without one there is no master and
`-O check` just reports that.

Letting the tablet sleep with a tunnel open is the reliable way to reproduce
this, so expect it after any suspend.

## 3a. From Emacs (the normal way)

Put this repository on your project's classpath -- it has no dependencies beyond
Clojure itself -- then, with a CIDER session connected:

```
M-x load-file RET emacs/clerkenwell.el RET
M-x clerkenwell-open RET
```

`clerkenwell-open` connects *your* REPL to the tablet, so submissions see
your namespaces and live state. `C-c C-s` evaluates the draft and commits it;
ordinary CIDER evaluation in the buffer sends nothing to the tablet.

Each notebook has its own buffer, named as the tablet names it:
`*clerkenwell: Fourier scratch*`. Opening another notebook on the tablet
brings up its buffer in place of the one you were looking at; the others keep
their history, and say `not open on the tablet` until their notebook is open
again.

A notebook remembers the namespace its submissions are evaluated in, and the
mode line shows it. One that has none yet takes the namespace current where
you ran `clerkenwell-open`; `C-c C-n` (`clerkenwell-set-namespace`) changes it.
Ordinary CIDER evaluation in the buffer uses the same namespace.

Handwriting images follow your Emacs theme -- they are shown in the buffer and
never sent to the device, so they have no reason to be ink-on-paper.
`clerkenwell-ink-colors` tracks the current faces by default; after changing
theme, `M-x clerkenwell-redraw` redraws existing entries from their
retained page snapshots so the history is not left half in one scheme.

Handwriting is drawn at `clerkenwell-ink-resolution` image pixels per page
unit (2 by default) and shown at `clerkenwell-image-scale` screen pixels per
page unit (0.8: a page-wide scribble is about 1120 pixels across). An image wider
than the window is shrunk to fit, and refitted when the window is resized.
Changing the scale takes effect on the next refresh; changing the resolution
needs `M-x clerkenwell-redraw`.

Ink that overlaps an existing handwriting entry vertically extends it, so a
pause mid-paragraph does not split the paragraph in the buffer. History
recorded before this behaviour can be brought into line with
`M-x clerkenwell-regroup`; it only appends, and a second run does nothing.

The buffer is quiet by default: it shows handwriting images and computations
much as the page does, without per-entry headers. Delivery state still appears
whenever it is anything but `inserted`, so a result still in flight, failed or
uncertain never looks like one that is on the page. Set `clerkenwell-quiet` to
nil for the annotated view.

By default the page receives the expression above its result. `C-u C-c C-s`
sends the result alone, and `clerkenwell-send-source` flips which of those is
the default. Emacs records both either way -- this only controls what is
written on the tablet.

A result that does not fit in what is left of the screen is refused and stays
pending. Pages can now be scrolled to be taller than the screen; set
`clerkenwell-strict-height` to nil and such a result goes below the bottom of
the screen instead, up to ten screens down, and images are no longer shrunk to
the room left.

The tunnel from step 2 is a separate SSH process and is unrelated to CIDER. It
can be started before or after `clerkenwell-open`, and it survives a REPL
restart -- after restarting, just run `clerkenwell-open` again. Conversely, if
the tunnel drops (the tablet sleeping is the usual cause), your REPL and history
are unaffected; only re-establish the ssh.

`C-c C-d` (`clerkenwell-check-device`) reports on both paths to the device,
which fail **independently**:

- The **tunnel** carries the adapter's own outbound requests and needs no SSH
  from the REPL's JVM.
- The **watcher and asset pushes** shell out to `ssh` and `scp` from that JVM,
  and so need working credentials there.

So a page can be reported while every SSH from the JVM is failing. That
combination looks healthy in the status line but no handwriting ever arrives and
no result is ever delivered; `C-c C-d` names it explicitly. The usual cause is
the SSH user: without `clerkenwell-ssh-user`, SSH uses your local username,
which the device rejects.

If the status shows `no page`, the host is not hearing from the adapter: check
the tunnel first, then that Xochitl is running with the adapter installed.

## 3b. Run the development driver

`clerkenwell.main` is a standalone driver for exercising the device without a
project. It evaluates in its own JVM, so it cannot see your namespaces; prefer
3a for real work.


```sh
clojure -M -m clerkenwell.main --ssh-host rm
```

It starts the handshake server, then waits for the adapter to report which
notebook and page are open. Open a notebook on the tablet if it has not
reported within 30 seconds.

```
clerkenwell transport on 127.0.0.1:14242
waiting for the device to report its active page...
device is on notebook f58d5b10-... page 78429c78-...

Type a Clojure expression and press enter. Ctrl-D to quit.
clerkenwell> (reduce + (range 1 101))
   => 5050  at dx=60 dy=60  (175x32)
   8a91caaf-...  transferred
   8a91caaf-...  inserted
```

The result should appear on the page within about a second of `transferred`.

## Starting from a clean slate

History is persistent, kept per notebook UUID under the state root, and is
never cleared automatically -- it is meant to be a durable record, so a command
that silently discarded it would defeat the purpose.

The state root is `$XDG_CACHE_HOME/clerkenwell`, or `~/.cache/clerkenwell`,
unless `clerkenwell-state-root` (or the driver's `--state-root`) says
otherwise. It is deliberately outside your project: a notebook's history
belongs to the document on the tablet, not to the code (see
`docs/sessions.md`). Within it, `handwriting/` and `manifest.edn` are derived
and can be deleted; they are rebuilt when the notebook is next opened.

Each notebook's history is also kept on the tablet, under
`/home/root/clerkenwell/<notebook-uuid>/`. When the tablet opens a notebook,
anything the tablet has that this computer lacks is fetched; from then on,
new history is copied to it every couple of seconds, and in full on
`clerkenwell-disconnect`. A result is only inserted once its attempt has
reached the tablet's copy, so another computer can never insert it a second
time. `C-c C-d` reports how the copy stands, and the mode line says
`⚠not copied to tablet` when it is failing.

Only one connection writes a notebook at a time: the one holding its lease,
which is kept on the tablet. The tablet reports what it has open only through
the tunnel, which one computer can have at a time, and the connection it
reports to takes the lease, without asking. So switching computers is just
opening the tunnel from the other one: it fetches what it lacks and
continues. A computer that lost the tunnel without noticing -- it went to
sleep, say -- has its copies refused by the tablet from then on, and the mode
line there says `read-only`; anything it recorded meanwhile that differs
from the tablet's is set aside under `stranded/`, never merged.

`clerkenwell-reset-history` sets the tablet's copy aside as well, and refuses
if it cannot reach the tablet: otherwise the next attach would fetch the
whole history back.

Two separate things decide what you see:

- **Entries from earlier sessions** live in `<state-root>/<notebook-uuid>/`.
  `M-x clerkenwell-reset-history` moves that aside (renamed, not deleted), or
  point `clerkenwell-state-root` somewhere new for a throwaway experiment.
- **Ink already on the page.** With a fresh state root, the first observation
  records everything currently on the page as one initial entry. That is
  deliberate: it is also what makes the first result land below your existing
  writing rather than on top of it.

So a fresh state root alone does not give you an empty buffer if the page still
has writing on it. For a genuinely blank slate, use a blank page as well.

## What it targets

The page is whatever the adapter last reported, and a report older than five
seconds is refused rather than guessed at. `--notebook` pins a notebook and
makes the driver refuse if the device is showing a different one.

Results are placed below both your handwriting and any previous output on that
page, with the configured 20px gap.

Placement uses the last *saved* state. Xochitl saves lazily, so ink written in
the preceding few seconds may not be accounted for; pause before evaluating if
you want a result placed below something you just wrote. Erased ink keeps its
high-water mark, because native movement is not tracked -- erasing does not
reclaim the space below it.

## When something does not appear

The adapter logs to the `xovi/debug` console with a `CKW` prefix, and the driver
logs every state transition. Read them together.

| Symptom | Meaning |
| --- | --- |
| No `waiting...` progress | tunnel down, or adapter not installed |
| `CKW asset unusable (missing or empty)` | the push failed; check `--ssh-host` |
| `CKW asset unusable (size N != M)` | truncated transfer; it will not insert |
| `CKW rejecting command` | malformed command; a bug, please capture it |
| `aborted` in the driver | you turned the page mid-handshake; it stays pending for the original page |
| `-> :uncertain` on startup | a previous run died mid-insertion; see below |

An insertion that returns successfully is reported `inserted`. That means the
call returned, **not** that Xochitl has flushed it to disk, and not that
anything is visibly on the page -- a missing asset used to produce exactly that
outcome, which is why the asset is now verified first.

## Uncertain results

If the host dies between recording an attempt and hearing the acknowledgement,
the result may or may not have been inserted. It is marked `:uncertain` and is
never retried automatically. Look at the page, then from a REPL:

```clojure
(require '[clerkenwell.transport :as transport] '[clerkenwell.store :as store])
(def nb (store/notebook-dir (store/default-root) "<notebook-uuid>"))
(transport/reconcile! nb "<result-id>" true)   ; it is there
(transport/reconcile! nb "<result-id>" false)  ; it is not; deliver again
(transport/abandon! nb "<result-id>")          ; give up on it
```

## Cleaning up

```sh
ssh rm 'rm -f xovi/exthome/qt-resource-rebuilder/clerkenwell.qmd && ./xovi/rebuild_hashtable'
ssh rm 'systemctl start xochitl'
```
