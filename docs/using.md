# How to use Clerkenwell

## General usage

You interact with Clerkenwell from within Emacs, via a buffer called `*clerkenwell: <your notebook>*`. Clerkenwell buffers are divided into two parts:

- **History**: an immutable sequence of everything you've sent to the tablet so far;
- **Draft**: an editable area to type in Clojure code.

Every time you write something on the tablet, it will show up automatically in Clerkenwell as an image. This may take up to approximately 16 seconds, but you can type in the draft area in the meantime.

Once you type a Clojure expression in the draft area, you can execute `M-x clerkenwell-evaluate-and-send` (bound to `C-c C-s` by default). Clerkenwell will evaluate the expression, pretty-print the result, render it as an image and paste the resulting image into the notebook, below your bottommost stroke on the current page. After that, the expression and its result move into the history and become immutable. If you made an error, evaluate another expression.

The idea is that the buffer _reflects_ the notebook, but it is not a 100% faithful _rendition_ of it. For example, any text you've sent to the tablet remains text in the Clerkenwell buffer (while to the tablet it's an image). If you scribble over the evaluated expression (a common thing to do), the buffer will render those scribbles below the expression, rather than on top of it. If you write bottom-to-top, the buffer will observe the writes in chronological order rather than page order (but see below).

## Evaluating

By default, Clerkenwell will precede the rendered result by the expression itself. You can make it render only the result by running `clerkenwell-evaluate-and-send` with a prefix argument (`C-u C-c C-s`).

By default, Clerkenwell will evaluate expressions in the namespace that was current at the time of `clerkenwell-open`. Change it with `M-x clerkenwell-set-namespace`. Note that it's distinct from the CIDER REPL's current namespace setting.

You can also render arbitrary text and send it to the tablet _without_ evaluating. This is done by `M-x clerkenwell-send-text` and works from any buffer, not just the Clerkenwell one.

Tip: While you're editing your expression in the draft area, you can use normal CIDER tooling (`cider-eval-last-sexp` etc) to preview evaluation without sending the result to the notebook, and only do `C-c C-s` when you're happy.

## Page management

Clerkenwell assumes a top-to-bottom flow of the document you're writing. By default, any rendered result will be inserted 20 pixels below the y-coordinate of your bottommost stroke (or the bottommost evaluation result, whichever is lower). You can change that gap by setting the variable `clerkenwell-gap`.

By default, Clerkenwell assumes a "strict height" mode where it will refuse to insert a result if it's taller than available blank space on the current page. Switch to a blank page and resubmit. However, reMarkable's document pages are scrollable so they can be made taller than the tablet's screen; set `clerkenwell-strict-height` to `nil` to switch to a lenient mode.

Clerkenwell tries to merge your scribbles if they overlap vertically. If you write something, wait until it shows up in Emacs, then write something else next to it (e.g., continue a sentence after pausing), Clerkenwell will render those Emacs-side as a single image.

Clerkenwell tries to be robust and keep track of what it's doing. It never re-evaluates things unless specifically asked to.

## Session management

Clerkenwell treats the tablet as _the_ source of truth. Sessions travel with the tablet: you can close the Clerkenwell session on one computer, then connect to it from another – things will just work. Your history of scribbles, evaluations, and text will be fetched onto the second computer session, even if it hadn't seen it before.

Clerkenwell maintains a 1:1 correspondence between a notebook and a session. It constantly monitors which document is currently open in the reMarkable's UI, and which document page is active. If you close a document and create a new one, Clerkenwell will automatically create a new Emacs buffer as soon as it notices.

## Output kinds

Clerkenwell builds on the [Kindly] library that allows to annotate Clojure values with _kinds_. Kinds tell the tooling how the value should be rendered. Furthermore, [kindly-advice] tries to auto-detect the kind.

This means that if your expression returns a `java.awt.image.BufferedImage`, Clerkenwell will paste that image onto the tablet. Other supported kinds include:

- Images (`kind/image`): See above.
- Tabular data (`kind/table` or `kind/dataset`): These will be typeset as a table in a proportional font.
- [Plotje] [poses] (pose-shaped `kind/map`): Clerkenwell will call Plotje to render the plot using a grayscale theme, then send the resulting image to the tablet.
- Markdown (`kind/md`): Clerkenwell will convert it to HTML with [nextjournal/markdown], then render to image with [openhtmltopdf] and insert the resulting image.

 [Kindly]: https://github.com/scicloj/kindly
 [kindly-advice]: https://github.com/scicloj/kindly-advice
 [Plotje]: https://scicloj.github.io/plotje/
 [poses]: https://scicloj.github.io/plotje/plotje_book.pose_model.html
 [nextjournal/markdown]: https://github.com/nextjournal/markdown
 [openhtmltopdf]: https://github.com/danfickle/openhtmltopdf

## Fonts and colours

By default, Clerkenwell will typeset the Clojure code, data, and raw text in the same font that your Emacs uses. To change that, set `clerkenwell-font`. The default is nil, meaning autodetect.

To change the font used for tables and Markdown, change `clerkenwell-prose-font`. `clerkenwell-font-size` controls size for both.

By default, Clerkenwell will inspect your Emacs theme to infer the foreground and background colours used to draw your scribbles. However, should you change theme mid-session, the new colours will _not_ be observed immediately. You can explicitly force a re-render of every scribble so far and enforce new colours with `M-x clerkenwell-redraw`. You can also explicitly set colours by setting `clerkenwell-ink-colors` to a cons cell of `(foreground . background)`.

## Settings

All configuration happens via Emacs. Runtime settings handled by the JVM, such as the font and layout, will be carried over from Emacs automatically in every interaction, so you don't have to reload after setting some configuration variable. Connection-related settings (`clerkenwell-ssh-host`, `clerkenwell-port`, `clerkenwell-state-root`) require a reconnect.

There are more tweakable knobs besides the ones listed in this document. Review them all at the top of `clerkenwell.el`.

## Limitations and tips

- For best results, use the fineliner tool with medium stroke width.
- Erasing strokes on rM is not yet supported (erased stuff will still show up).
- Clerkenwell might use your reMarkable's battery faster than usual while it's in use. That's because the Xovi plugin makes a HTTP request to your REPL every second. Consider plugging it in while in use, and remember to `systemctl restart xochitl` once you're done.

## FAQ

**Why is the tablet-to-buffer latency so high?**\
Because Clerkenwell relies on reMarkable's firmware periodically saving the notebook to storage, and that happens approximately 12 seconds after you've stopped writing. Same reason why [Edsger](https://codeberg.org/nathell/edsger) is slow.

**I evaluated something before my scribble came in, and the result overlaps the scribble!**\
No big deal. Just move it below the scribble with a hold-and-drag gesture.
