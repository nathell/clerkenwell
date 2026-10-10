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

;; It logs every stage of every render at INFO, which would flood the REPL.
;; This is global to the JVM; nothing else in a notebook is likely to be
;; using openhtmltopdf.
(XRLog/setLoggingEnabled false)

(defn escape
  "`s` as XML text. Characters XML cannot carry at all are dropped."
  [s]
  (-> (str s)
      (str/replace #"[\x00-\x08\x0B\x0C\x0E-\x1F￾￿]" "")
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

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
