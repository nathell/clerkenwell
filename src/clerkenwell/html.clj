(ns clerkenwell.html
  "HTML and CSS laid out by openhtmltopdf, drawn with Java2D -- typeset
   output, such as tables, without a browser. See docs/rich-output.md.

   openhtmltopdf parses XHTML, so everything here is built as well-formed
   markup, with all text escaped."
  (:require [clojure.string :as str])
  (:import (com.openhtmltopdf.java2d.api BufferedImagePageProcessor
                                         Java2DRendererBuilder)
           (com.openhtmltopdf.util XRLog)
           (java.awt.image BufferedImage)))

(def default-style
  "How output is set on the page: :font for printed values and code, which
   should be monospace; :prose-font for Markdown and tables; :font-size, in
   page pixels, for both. A font is an installed family or a font file.
   The defaults are Java's logical fonts, which every JVM has; Emacs sends
   its own font in place of Monospaced."
  {:font "Monospaced"
   :prose-font "Serif"
   :font-size 40})

;; It logs every stage of every render at INFO, which would flood the REPL.
;; This is global to the JVM; nothing else in a notebook is likely to be
;; using openhtmltopdf.
(XRLog/setLoggingEnabled false)

(defn escape
  "`s` as XML text. Characters XML cannot carry at all are dropped."
  [s]
  (-> (str s)
      (str/replace #"[\x00-\x08\x0B\x0C\x0E-\x1F\uFFFE\uFFFF]" "")
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- tag-parts
  "A hiccup tag keyword split into its name, id and classes:
   :code.language-clojure is [\"code\" nil [\"language-clojure\"]]."
  [tag]
  (let [[_ nm id classes] (re-matches #"([^.#]+)(?:#([^.]+))?((?:\.[^.#]+)*)" (name tag))]
    [nm id (remove str/blank? (str/split (or classes "") #"\."))]))

(defn- attribute-value
  "A value as an attribute: a map, as hiccup gives :style, becomes CSS."
  [k v]
  (cond (true? v) (name k)
        (map? v) (apply str (for [[p x] v] (str (name p) ": " (if (keyword? x) (name x) x) "; ")))
        (keyword? v) (name v)
        :else v))

(defn- attributes [attrs]
  (apply str (for [[k v] attrs
                   :when (and (some? v) (not (false? v)))]
               (str " " (name k) "=\"" (escape (attribute-value k v)) "\""))))

(defn xhtml
  "Hiccup as well-formed XHTML: tags with #id and .class shorthand, attribute
   maps, nested seqs, and strings, which are always escaped. nil renders as
   nothing."
  [form]
  (cond
    (nil? form) ""
    (string? form) (escape form)
    (vector? form)
    (let [[tag & more] form
          [attrs children] (if (map? (first more)) [(first more) (rest more)] [nil more])
          [nm id classes] (tag-parts tag)
          attrs (cond-> (or attrs {})
                  id (assoc :id id)
                  (seq classes) (update :class #(str/join " " (cond-> (vec classes) % (conj %)))))
          inner (apply str (map xhtml children))]
      (if (empty? inner)
        (str "<" nm (attributes attrs) " />")
        (str "<" nm (attributes attrs) ">" inner "</" nm ">")))
    (sequential? form) (apply str (map xhtml form))
    :else (escape (str form))))

(defn document
  "A complete XHTML document: `css` in its head, `body` (markup) as its body.
   The page is `width` pixels wide with no margins, and as tall as needed."
  [width css body]
  (str "<html><head><style>"
       ;; One page, made as tall as its content by toSinglePage; the height
       ;; here only has to be something.
       "@page { size: " width "px 1000px; margin: 0; }"
       "body { margin: 0; }"
       css
       "</style></head><body>" body "</body></html>"))

(defn render
  "Lay out and draw `html` as an image, white where nothing is drawn, as wide
   as its @page and as tall as its content."
  ^BufferedImage [html]
  (let [proc (BufferedImagePageProcessor. BufferedImage/TYPE_INT_RGB 1.0)]
    (.runPaged (doto (Java2DRendererBuilder.)
                 (.withHtmlContent ^String html nil)
                 (.useFastMode)
                 ;; Fonts are found by family name among those installed.
                 (.useEnvironmentFonts true)
                 (.toSinglePage proc)))
    (first (.getPageImages proc))))
