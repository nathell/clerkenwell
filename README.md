# Clerkenwell

An e-paper notebook for thinking with Clojure.

## What it does

Clerkenwell gives you a two-way binding between your paper tablet and a Clojure REPL in Emacs. Anything you write on tablet is automatically reflected in Emacs, and you can evaluate expressions from the REPL and view results on the tablet.

Clerkenwell is a computational notebook, like [Jupyter], [Clerk] or [Clay]; except the thinking / textual part happens on e-paper rather than on the computer. You are free to mix and match REPL output and handwriting. Doodle over the output!

In addition to Clojure data, Clerkenwell supports multiple types of output (discriminated with [Kindly]): plain text, images, tables, plots (via [Plotje]), Markdown.

## Demo

Insert demo video...

## Running it

Refer to separate documents about [setup](docs/setup.md) and [using Clerkenwell](docs/using.md).

## Supported devices

Currently, the only supported device is the reMarkable 2, running firmware 3.27.1.0. I.e., my device. :)

Getting Clerkenwell to run on other devices or other firmware versions presents various levels of difficulty:

- Other firmware versions of the rM2 should only require minor modifications. It might even work unchanged. If you have a rM2, please try it out and let me know!

- Other reMarkable tablets (1, Paper Pro, Paper Pure) almost certainly won't work out of the box, but it should be relatively easy to port Clerkenwell to them. Ask your LLM for help in porting, or lend me your device.

- Other devices (Supernote, Onyx, etc.) will require a major architectural adaptation.

## LLM disclosure

Clerkenwell has been written with an LLM, with human supervision. Almost all code and some parts of the design are LLM-generated.

Human review status: **Casually screened.** (I can confirm the code isn't malicious, but haven't yet attempted to fully understand it.) Expect bugs.

Full human review is ongoing. I intend to weed out LLM-isms and simplify the code so that it's easier to read and contribute to for humans.

This README was written by a human.

## Steward

The person responsible for indeed (the equivalent of “author” for human-written projects, overseeing LLMs and verifying their output) is [Daniel Janus].

## See also

The idea for injecting images into reMarkable data has been first explored in [Edsger]. Clerkenwell inherits some code from Edsger.

## Name

Clerkenwell is named for [a neighbourhood in London] that used to be famous for its watchmaking industry – a craft that requires precision and careful thinking.

The name Clerkenwell is a nod to [Clerk], another computational notebook environment for Clojure. It also evokes [James Clerk Maxwell], a prolific XIX-century physicist who must have kept a lot of notebooks!

## License

MIT (see LICENSE).

Copyright (c) 2026 Daniel Janus.

Portions based on [rmscene], copyright (c) 2023 Rick Lupton (under the same MIT license).

 [Jupyter]: https://jupyter.org/
 [Clerk]: https://clerk.vision/
 [Clay]: https://scicloj.github.io/clay/
 [Kindly]: https://github.com/scicloj/kindly
 [Plotje]: https://github.com/scicloj/plotje
 [Daniel Janus]: https://danieljanus.pl
 [Edsger]: https://codeberg.org/nathell/edsger
 [rmscene]: https://pypi.org/project/rmscene/
 [a neighbourhood in London]: https://en.wikipedia.org/wiki/Clerkenwell
 [James Clerk Maxwell]: https://en.wikipedia.org/wiki/James_Clerk_Maxwell
