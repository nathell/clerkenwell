# Rich output: tables, charts, Markdown

Status: design, not yet implemented.

Today Clerkenwell produces one kind of output: a value is printed, bounded,
drawn as text onto the page, and shown as text in Emacs. This document
describes how to add other kinds of output -- images, charts, tables, rich text
-- using the [Kindly](https://scicloj.github.io/kindly/) conventions to decide
what a value is, and Clerkenwell's own renderers to decide what it looks like.

## Goals

- A notebook author can return a table, a chart, an image or a piece of
  Markdown and see it drawn sensibly on the page and in Emacs.
- Authors say what they mean the way the Clojure data science ecosystem already
  does (`kind/table`, `kind/md`, ...), so code written for Clay or Portal works
  here unchanged.
- Kinds are added one at a time. A kind Clerkenwell does not support yet looks
  exactly as it does today.
- Everything is drawn in the JVM. No browser, no external image tools.

## Non-goals, for now

- Kinds that need a JavaScript engine to draw: Vega-Lite, Plotly, ECharts,
  Cytoscape, KaTeX. These show as their pretty-printed spec until there is a
  reason to do more (see *Later* below).
- Interactivity of any sort. The page is paper.
- Colour on the tablet. It is grayscale e-ink.

## Kindly in one paragraph

Kindly is a convention, not a renderer. A value or the form that produced it
carries a *kind* -- `:kind/md`, `:kind/table`, `:kind/image`, `:kind/hidden`
and so on -- either explicitly, through metadata the `kind/*` functions attach,
or implicitly, inferred by `kindly-advice` from the value's type. Each tool
(Clay, Portal, and now Clerkenwell) renders the kinds it understands in its own
way. The agreed fallback for a kind a tool does not understand is to
pretty-print the value, which is what Clerkenwell already does.

`kindly-advice` takes both the form and the value:
`(advise {:form f :value v})` adds a `:kind`. Clerkenwell has the form, because
it reads the submitted source, so metadata on the form (`^:kind/md "..."`) is
honoured as well as metadata on the value. Out of the box it recognises a
`BufferedImage` as `:kind/image` and a tech.ml.dataset dataset as
`:kind/dataset`.

## Where this fits

The pipeline keeps the two stages it has now, and the invariant that separates
them.

```
value --present--> presentation --persist--> rasterize --> PNG for the page
                   (bounded EDN)                      \--> Emacs rendering
```

1. **`present`** looks at the live value once, classifies it with
   `kindly-advice`, and reduces it to a bounded, EDN-serialisable
   *presentation*. The presentation is persisted before anything is drawn, and
   the live value is never consulted again. A failed rasterisation is retried
   from the stored presentation, exactly as now.
2. **`rasterize`** becomes a multimethod dispatching on
   `[presentation-kind target]`, where the target is `:tablet`. Emacs renders
   presentations itself (below), so it is not a rasterisation target.

`present` remains bounded in time and work, as `print-bounded` is today: each
kind's presentation step runs on a daemon thread with a timeout.

### Presentations

A presentation is a map with a `:kind`. Existing results in the event log are
`{:kind :text ...}` and must keep rendering unchanged, so `:text` stays a kind
in its own right and is also the fallback for everything else.

| Kindly kind | Presentation | Notes |
|---|---|---|
| anything unrecognised, `:kind/pprint` | `{:kind :text :text ... :tablet-text ...}` | As today, but pretty-printed. Also closes the *pretty-printing* TODO. |
| `:kind/code` | `{:kind :text ...}` with the code as given | |
| `:kind/hidden` | `{:kind :hidden}` | Nothing on the page. Emacs shows a one-line note. Useful for `def`s of large data. |
| `:kind/image` | `{:kind :image :asset ... :width :height}` | The PNG bytes are written to an asset during `present`. The image is the presentation. |
| Plotje pose | `{:kind :image ...}` | Drawn to a PNG during `present` (see *Charts*). A pose holds a live dataset, so it cannot be persisted as data. |
| `:kind/table`, `:kind/dataset` | `{:kind :table :columns [...] :rows [[...]] :omitted {:rows n :columns m}}` | Every cell already printed to a bounded string. |
| `:kind/md` | `{:kind :md :markdown "..."}` | Bounded like text. |
| `:kind/fragment` | `{:kind :fragment :items [presentation ...]}` | Items stacked vertically. |
| `:kind/vega-lite` and other JavaScript kinds | `{:kind :text ...}` of the spec | The Kindly fallback. |

Rasterising images during `present` breaks the pattern that presentations are
cheap to store and expensive to draw. It is still the right call: the stored
PNG preserves the property that matters, that a failure later on can be retried
without the original value.

## Targets

### The tablet

Every presentation becomes one grayscale PNG that fits the usable width and the
height remaining on the page. What happens when it does not fit depends on the
kind:

- **Text, tables, Markdown are refused**, not shrunk, as text is today. Shrunk
  text is unreadable, and a refused result stays pending rather than lost.
- **Images and charts are scaled down to fit.** Shrinking loses nothing a
  reader needs, and a chart is often wider than the page by default.

### Emacs

Emacs is good at text, so it gets text wherever text will do:

- **`:text`, `:hidden`**: as today.
- **`:table`**: an aligned text table, so it can be selected and copied.
- **`:md`**: the Markdown source, fontified with `markdown-mode` when it is
  installed and inserted as plain text otherwise.
- **`:image`**, including charts: the tablet's grayscale PNG, sized to the
  window as handwriting already is. No second, theme-coloured rendering.
- **`:fragment`**: each item in turn.

An image view of tables and Markdown, showing exactly what the page shows, can
be added later as an option.

This requires `history` to return each computation's whole presentation
instead of only its `:text`, and `clerkenwell--render-entry` to dispatch on the
presentation's `:kind`.

## Rendering, kind by kind

| Kind | How | Effort |
|---|---|---|
| `:image` | `ImageIO`; scale to fit. | Trivial |
| Charts | Plotje, straight to a `BufferedImage` (below). | Small |
| `:table` | Our own Java2D grid: column widths from font metrics, truncated cells, rules between rows. | Small to medium |
| `:md` | commonmark-java for the parse tree, then our own Java2D layout of styled runs: headings, paragraphs with emphasis and inline code, lists, code blocks, block quotes. Builds on the word wrap `render` already has. | Medium |

An alternative for Markdown, and the obvious route to `:kind/hiccup` and
`:kind/html`, is to go through HTML and openhtmltopdf's Java2D backend, which
handles CSS and tables. It is a heavier dependency with uncertain fit, and
deserves a spike before anything depends on it.

### Charts

Plotje (`org.scicloj/plotje`) renders through membrane and Java2D, and returns
a `BufferedImage` directly:

```clojure
(pj/plot pose {:format :bufimg})
```

No browser is involved, so charts fit the rest of the pipeline. Any other
library that can produce a `BufferedImage` is covered by `:kind/image` for free.

A pose is recognised as such during `present`, and drawn at its own width if it
sets one, or at the usable page width otherwise; it is then scaled down if it
is still too wide.

**Grayscale defaults.** Plotje's default theme and palette are designed for
colour screens: a gray background, and a categorical palette whose hues become
indistinguishable grays on e-ink. Plotje has layered configuration, from
weakest to strongest:

1. library defaults
2. `plotje.edn` on the classpath or in the working directory
3. `pj/set-config!`
4. a thread-local binding of `*config*` (`pj/with-config`)
5. options on the plot itself

Clerkenwell would bind an e-ink configuration around its own rendering: a white
background, a light grid, a larger font, and a palette of a few well-separated
grays. Which palette works is to be decided on the device.

The catch is precedence. A `with-config` binding sits *above* the author's
`plotje.edn` and `set-config!`, so a plain binding would silently override
settings the author made on purpose. What we want is a layer between the
library defaults and the author's. So Clerkenwell builds its binding as its
e-ink defaults, deep-merged under the contents of `plotje.edn` and under the
`set-config!` overrides. `plotje.edn` is a file we can read. The `set-config!`
value lives in a private atom, so reading it means either reaching into
Plotje's internals or asking upstream for a public accessor (or, better, a
"tool defaults" layer). Options on the plot itself win regardless, so an author
can always override per chart.

If that turns out to be impractical, the fallback is to apply no defaults and
leave grayscale styling to the notebook author.

## Dependencies

- `org.scicloj/kindly` and `org.scicloj/kindly-advice`: small; hard
  dependencies.
- Plotje: large (tablecloth, fastmath, clojure2d, membrane); **optional**.
  Clerkenwell loads its integration with `requiring-resolve` only when a value
  is a pose, and a notebook that produced a pose has Plotje loaded already.
- commonmark-java: added when `:md` is.

`kindly-render` was considered and is not used. It produces HTML, hiccup and
JavaScript for a browser, so it helps as a reference for which kinds exist, but
not for drawing on the page.

## Bounds

Each kind bounds its own presentation, alongside today's character limit:

- Tables: maximum rows and columns, with the omitted counts recorded and shown.
- Markdown: maximum characters, as text.
- Images: maximum pixel dimensions before scaling, so a huge image is not
  decoded only to be shrunk.
- Charts: a time limit on drawing, since plotting a large dataset can be slow.
  Drawing runs on its own thread, as printing does.

## Plan

Each increment adds one kind end to end, on both targets, with tests:

1. **Kind dispatch.** `kindly-advice` in `present`, today's text as the
   fallback, pretty-printing for `:kind/pprint` and unknown kinds,
   `:kind/hidden`. History carries whole presentations; Emacs dispatches on
   them.
2. **Images.** `:kind/image`, scaled to fit.
3. **Charts.** Plotje, optional, with e-ink defaults.
4. **Tables.**
5. **Markdown.**

## Later

- An image view in Emacs of tables and Markdown, as an alternative to text.
- JavaScript kinds, by rendering a Clay page in a headless browser and taking a
  screenshot. Full fidelity for every kind at once, but it brings back an
  external dependency far heavier than the ImageMagick that has just been
  removed, so it should stay optional.
- `:kind/hiccup` and `:kind/html`, if the openhtmltopdf spike succeeds.

## Decisions taken

- Plotje gets Clerkenwell's grayscale defaults where its configuration allows
  them to sit beneath the author's; otherwise styling is left to the author.
- Emacs shows the tablet's grayscale image for charts, not a second,
  theme-coloured rendering.
- Emacs shows tables and Markdown as native text; an image view may come later.
