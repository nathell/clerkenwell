# Clerkenwell setup

## Prerequisites

You'll need:

- a reMarkable 2, firmware version 3.27.1.0;
- Emacs (28.1+) on a computer running macOS or Linux;
- OpenSSH, Clojure CLI, and a Java JRE or JDK installed;
- packages installed in Emacs: `cider` and its dependencies `clojure-mode` and `parseedn`.

## Preparation

You'll need to follow these steps once only.

1. Enable mDNS on your reMarkable so that you can SSH to it with a symbolic name and without a password.
   Follow [this guide](https://blog.danieljanus.pl/connecting-to-remarkable/).
2. Edit your `~/.ssh/config` to add a `rmtunnel` entry. Here are the two reMarkable entries I use:
```
Host rm
  HostName remarkable.local
  User root
  ServerAliveInterval 30
  ServerAliveCountMax 3

Host rmtunnel
  HostName remarkable.local
  User root
  ServerAliveInterval 30
  ServerAliveCountMax 3
  RemoteForward 14242 127.0.0.1:14242
  ControlMaster no
  ExitOnForwardFailure yes
```
   The port (14242 by default) needs to align with `clerkenwell-port` defined in Emacs, and also with the `base` string in `xovi/clerkenwell.qmd`.
3. Install [Vellum](https://vellum.delivery/). The easiest way to do this is via [reManager](https://remanager.io/).
4. From Vellum, install `qt-resource-rebuilder`. This will also install Xovi and xovi-extensions as dependencies. If Vellum or reManager asks you to restart UI with mods enabled, **don't do it** yet.
5. Run `xovi/rebuild_hashtable`. Repeat after each firmware upgrade (but note that an upgrade might break Clerkenwell compatibility).
6. Clone Clerkenwell to your computer. I'm going to assume that you have it in `/home/you/clerkenwell`.
7. Copy `xovi/clerkenwell.qmd` onto the reMarkable, into `xovi/exthome/qt-resource-rebuilder`.
8. Optional but recommended: add a `:clerkenwell` alias to your user-wide `deps.edn` so that you can use Clerkenwell from any project:
```clojure
{:aliases {:clerkenwell {:extra-deps {clerkenwell/clerkenwell {:local/root "/home/you/clerkenwell"}}}}}
```
9. Review the settings at the beginning of `emacs/clerkenwell.el`. The defaults should make sense for most users.
10. Make Emacs load `emacs/clerkenwell.el`. Either symlink it to your `.emacs.d` and have your `init.el` load it, or (if you're just toying around) `M-x load-file RET /home/you/clerkenwell/emacs/clerkenwell.el`.

## Starting

Do this every time you want to use Clerkenwell.

1. In a terminal, `ssh rmtunnel`. SSH might start emitting messages like `connect_to 127.0.0.1 port 14242: failed`; this is normal and just means that you haven't started the JVM part of Clerkenwell yet. Keep this session running.
2. In another terminal, `ssh rm`.
3. From within this session, `./xovi/debug`. This will restart Xochitl (the device's UI) with the Clerkenwell plugin.
4. On the reMarkable, create an empty notebook and have it open.
5. In Emacs, launch a CIDER REPL with the `clerkenwell` alias active. In your project of choice, `C-u M-x cider-jack-in` and add `:clerkenwell` to the aliases list.
6. Once CIDER starts up, `M-x clerkenwell-open`. Shortly after, your minibuffer will say `Clerkenwell: tunnel ok, ssh(rm) ok, copy on tablet ok` and Emacs will open a new buffer, named `*clerkenwell: <your notebook>*`.
7. Use Clerkenwell! Read [using.md](using.md) to learn how.
8. To quit cleanly, `M-x clerkenwell-disconnect` in Emacs (bound to `C-c C-q`), then kill the buffer in Emacs, then terminate the Xovi-enabled Xochitl process (with C-c in the rm terminal), and restart Xochitl pristinely with `systemctl restart xochitl`.

(It is possible to run Xovi from within the `rmtunnel` ssh session, but it is not recommended as it's harder to recover from failures).

## Troubleshooting

### If your tunnel dies

SSH into the device (`ssh rm`) and do:

```
killall dropbear
```

This will terminate all SSH connections, including the hanging tunnel, and the tablet's UI will become unresponsive. Then, you can do `ssh rmtunnel` again and repeat the startup sequence.

### If all else fails

Just restart the device. Xochitl will launch without Xovi, and you'll be able to repeat the startup procedure.
