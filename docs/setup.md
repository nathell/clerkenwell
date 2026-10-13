# Clerkenwell setup

## Prerequisites

You'll need:

- a reMarkable 2, firmware version 3.27.1.0;
- Emacs (28.1+) on a computer running macOS or Linux;
- CIDER installed in Emacs.

## Preparation

You'll need to follow these steps once only.

1. Enable mDNS on your reMarkable so that you can SSH to it with a symbolic name.
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

The port (14242 by default) needs to align with `clerkenwell-port` defined in Emacs.

3. Install [Vellum](https://vellum.delivery/). The easiest way to do this is via [reManager](https://remanager.io/).

4. From Vellum, install `qt-resource-rebuilder`. This will also install Xovi and xovi-rm-extensions as dependencies. If Vellum or reManager asks you to restart UI with mods enabled, **don't do it** yet.

5. Clone Clerkenwell to your computer. I'm going to assume that you have it in `/home/you/clerkenwell`.

6. Optional but recommended: add a `:clerkenwell` alias to your user-wide `deps.edn` so that you can use Clerkenwell from any project:

```clojure
{:aliases {:clerkenwell {:extra-deps {clerkenwell/clerkenwell {:local/root "/home/you/clerkenwell"}}}}}
```

7. Review the settings at the beginning of `emacs/clerkenwell.el`. The defaults should make sense for most users.

8. Make Emacs load `emacs/clerkenwell.el`. Either symlink it to your `.emacs.d` and have your `init.el` load it, or (if you're just toying around) `M-x load-file RET /home/you/clerkenwell/emacs/clerkenwell.el`.

## Starting

Do this every time you want to use Clerkenwell.

1. In a terminal, `ssh rmtunnel`. SSH might start emitting messages like `connect_to 127.0.0.1 port 14242: failed`; this is normal and just means that you haven't started the JVM part of Clerkenwell yet. Keep this session running.
2. In another terminal, `ssh rm`.
3. From within this session, `./xovi/debug`. This will restart Xochitl (the device's UI) with the Clerkenwell plugin.
4. On the reMarkable, create an empty notebook and have it open.
5. In Emacs, launch a CIDER REPL with the `clerkenwell` profile active. In your project of choice, `C-u M-x cider-jack-in` and add `:clerkenwell` to the aliases list.
6. Once CIDER starts up, `M-x clerkenwell-open`. Shortly after, your minibuffer will say `Clerkenwell: tunnel ok, ssh(rm) ok, copy on tablet ok` and Emacs will open a new buffer, named `*Clerkenwell: Your Notebook*`.
7. Use Clerkenwell! Read [using.md](using.md) to learn how.
8. To quit cleanly, kill the Clerkenwell buffer in Emacs, then terminate the Xovi-enabled Xochitl process (with C-c in the rm terminal), and restart Xochitl pristinely with `systemctl restart xochitl`.

## Troubleshooting
