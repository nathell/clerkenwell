(ns clerkenwell.markdown
  "Markdown, typeset for the page: parsed by nextjournal/markdown, turned into
   hiccup and then XHTML, and laid out by openhtmltopdf in the same faces as
   other output. See docs/rich-output.md.

   Nothing is fetched while drawing: an image is shown as its alt text. Raw
   HTML is shown as the text it was written as, and formulas as their source,
   since maths is better written by hand on the page."
  (:require [clojure.string :as str]
            [clerkenwell.html :as html]
            [clerkenwell.image :as image]
            [nextjournal.markdown :as md])
  (:import (java.awt.image BufferedImage)))

(def renderers
  (assoc md/default-hiccup-renderers
         :image (fn [_ node] [:span.image (str "[" (md/node->text node) "]")])
         :html-inline (fn [_ node] (md/node->text node))
         :html-block (fn [_ node] [:p (md/node->text node)])
         :formula (fn [_ {:keys [text]}] [:span.formula (str "$" text "$")])
         :block-formula (fn [_ {:keys [text]}] [:p.formula (str "$$" text "$$")])
         ;; A tag is not a link anywhere on paper.
         :hashtag (fn [_ {:keys [text]}] (str "#" text))
         :todo-item (fn [ctx {:as node :keys [attrs]}]
                      (md/into-hiccup [:li (if (:checked attrs) "[x] " "[ ] ")] ctx node))))

(defn ->xhtml
  "Markdown `src` as an XHTML fragment."
  [src]
  (html/xhtml (md/->hiccup renderers src)))

(defn css
  "The style sheet for `style`, as in `html/default-style`."
  [{:keys [font prose-font font-size]}]
  (str "body { font-family: '" prose-font "', serif; font-size: " font-size "px;"
       " line-height: 1.25; word-wrap: break-word; }"
       "body > div > *:first-child { margin-top: 0; }"
       "p, ul, ol, blockquote, pre, table { margin: 0 0 0.5em 0; }"
       "h1, h2, h3, h4, h5, h6 { margin: 0.6em 0 0.3em 0; line-height: 1.1; }"
       "h1 { font-size: 1.6em; } h2 { font-size: 1.35em; } h3 { font-size: 1.15em; }"
       "h4, h5, h6 { font-size: 1em; }"
       "ul, ol { padding-left: 1.2em; }"
       ;; The [x] or [ ] is the marker.
       "ul.contains-task-list { list-style: none; padding-left: 0; }"
       "code { font-family: '" font "', monospace; font-size: 0.9em; }"
       ;; A long line of code wraps rather than being cut off at the edge.
       "pre { white-space: pre-wrap; }"
       "blockquote { padding-left: 0.6em; border-left: 3px solid black; }"
       "a { text-decoration: underline; }"
       "hr { border: 0; border-top: 2px solid black; }"
       "table { border-collapse: collapse; }"
       "th, td { padding: 0.1em 0.5em; text-align: left; vertical-align: top; }"
       "th:first-child, td:first-child { padding-left: 0; }"
       "th { border-bottom: 2px solid black; }"))

(defn image
  "Markdown `src` typeset `max-width` pixels wide, trimmed to what is drawn,
   in `style` (see `html/default-style`). Text wraps to the width, so only
   its height can be too much."
  (^BufferedImage [src max-width] (image src max-width nil))
  (^BufferedImage [src max-width style]
  (let [img (html/render (html/document max-width (css (merge html/default-style style))
                                        (->xhtml src)))]
    (image/trim (image/scale img (.getWidth img) (.getHeight img))))))
