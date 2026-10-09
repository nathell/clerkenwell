(ns clerkenwell.render
  "Actual JVM value -> bounded textual presentation -> PNG of known dimensions.

   Two deliberately separate stages. `present` bounds the value into text and is
   the thing that gets persisted first; `rasterize` turns already-captured text
   into an image. If rasterization fails, it can be retried from the stored text
   without going anywhere near the original value, which no longer needs to
   exist. Arbitrary print methods can block or throw, so presentation is bounded
   in work and time independently of evaluation.

   What a value is -- text, hidden, and later tables, charts and so on -- is
   decided by the Kindly conventions; see docs/rich-output.md."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clerkenwell.image :as image]
            [scicloj.kindly-advice.v1.api :as advice])
  (:import (java.awt Color Font FontMetrics Graphics2D)
           (java.awt.image BufferedImage DataBufferByte)))

;; -------- limits --------

(def default-limits
  "Configurable per clerkenwell.md. `print-level`/`print-length` bound structural
   and sequential size (so `(range)` terminates); `max-chars` is the final
   backstop on the printed string."
  {:print-level 10
   :print-length 100
   :max-chars 10000
   :timeout-ms 2000
   ;; Columns a long value is pretty-printed to. Callers that know the page
   ;; width pass what fits it (see `columns`).
   :right-margin 64})

;; Inconsolata rather than the handwriting face Edsger used: on the page,
;; output in a monospace font is immediately distinguishable from your own
;; writing, which a handwriting font deliberately is not.
(def default-font "Inconsolata")
(def default-font-size 40)

(defn load-font
  "The font to draw output in: a font file when `font` names one that exists,
   otherwise an installed family. A missing family is an error rather than a
   silent fallback, which would draw in a proportional face."
  ^Font [font size]
  (let [f (io/file font)]
    (if (.isFile f)
      (.deriveFont (Font/createFont Font/TRUETYPE_FONT f) (float size))
      (let [ft (Font. ^String font Font/PLAIN (int size))]
        (when-not (or (.equalsIgnoreCase (.getFamily ft) font)
                      (.equalsIgnoreCase (.getFontName ft) font))
          (throw (ex-info (str "Font not installed: " font) {:font font})))
        ft))))

(defn columns
  "How many characters of `font` at `size` fit `width` pixels. The font is
   monospace, so one glyph's advance stands for all of them.

   Called after evaluation and before the result is persisted, so it must not
   throw: a missing font falls back to the default margin here, and is
   reported by rasterisation, once the result is safely recorded."
  [width font size]
  (let [[_ ^Graphics2D g] (image/canvas 1 1 BufferedImage/TYPE_BYTE_GRAY Color/white)]
    (try
      (quot width (max 1 (.charWidth (.getFontMetrics g (load-font font size)) \M)))
      (catch Exception _ (:right-margin default-limits))
      (finally (.dispose g)))))

;; The verified page geometry (docs/investigation-results.md): 1404 x 1872,
;; scene origin top-centre. Callers pass the usable box; these are the outer
;; bounds nothing may exceed.
(def page-width 1404)
(def page-height 1872)

;; -------- presentation --------

(def truncation-marker " ...[truncated]")

(defn- run-bounded
  "Call `f` on another thread, because a user print method or kind advisor may
   block or throw: we bound how long we WAIT, which is not the same as stopping
   it. The thread may still be running after we give up, so it is a daemon and
   we never claim to have killed it. Returns {:ok v}, {:threw s} or
   {:timeout true}."
  [f timeout-ms]
  (let [result (promise)
        t (doto (Thread.
                 (fn []
                   (deliver result
                            (try
                              {:ok (f)}
                              (catch Throwable e
                                {:threw (str (.getSimpleName (class e))
                                             ": " (.getMessage e))})))))
            (.setDaemon true)
            (.start))
        v (deref result timeout-ms ::timeout)]
    (when (= v ::timeout)
      ;; Interrupt is a request, not a guarantee.
      (.interrupt t))
    (if (= v ::timeout) {:timeout true} v)))

;; -------- kinds --------

(def hidden-form-starters
  "Forms whose values are not shown, matched on their first symbol as written.
   Clay's list (scicloj.clay.v2.notebook, as of 2.0.23), which is broader than
   kindly-advice's default, plus `defn-`, which Clay leaves out though it
   hides `defn`."
  '#{;; namespace setup
     ns require import use refer refer-clojure alias
     ;; namespace manipulation
     in-ns create-ns remove-ns
     ;; explicitly hidden
     comment
     ;; var creation and mutation
     def defonce intern alter-var-root
     ;; function-like definitions
     defn defn- defmacro definline defmulti defmethod
     ;; type definitions
     defrecord defprotocol deftype definterface
     ;; type extensions
     extend extend-protocol extend-type reify
     ;; deprecated but still there
     defstruct})

(defn classify
  "What Kindly says `value` is, given the `form` that produced it, as
   {:kind :value :options}. :kind is a Kindly kind such as :kind/hidden, or nil.

   A value that cannot carry metadata was wrapped in a vector by Kindly to carry
   its kind; :value is the unwrapped original.

   As in Clay, the values of forms that define or set things up -- `def`,
   `defn`, `require` and so on, per `hidden-form-starters` -- are hidden too:
   on paper, `#'user/x` is noise. A notebook can choose its own set with
   Kindly options, e.g. `^{:kindly/options {:hide-value-syms #{}}}` on its ns
   form to show them all."
  [form value]
  (let [{:keys [kind] :as ctx}
        ;; :value must always be present, even when nil: without it,
        ;; kindly-advice evaluates the form again.
        (advice/advise {:form form :value value
                        :kindly/options {:hide-value-syms hidden-form-starters}})
        options (:kindly/options ctx)
        value (if (and (:wrapped-value options) (vector? value))
                (first value)
                value)]
    {:kind (if (:hide-value options) :kind/hidden kind)
     :value value
     :options options}))

;; -------- printing --------

(defn- scalar? [x]
  (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (symbol? x)
      (char? x)))

(defn- flat-coll?
  "A vector, list, seq or set of plain values, judged on the elements that
   would be printed: the collection may be infinite."
  [x]
  (and (or (sequential? x) (set? x))
       (every? scalar? (take (or *print-length* 100) x))))

(defn- pprint-fill
  "Print `xs` filling each line, as prose is, rather than one element a line:
   a hundred numbers then take a few lines instead of a hundred."
  [prefix suffix xs]
  (pp/pprint-logical-block :prefix prefix :suffix suffix
    (pp/print-length-loop [xs (seq xs)]
      (when xs
        (pp/write-out (first xs))
        (when-let [more (next xs)]
          (.write ^java.io.Writer *out* " ")
          (pp/pprint-newline :fill)
          (recur more))))))

(defn- pprint-dispatch
  "Clojure's pretty printer, with two changes. A record keeps the tag that
   `pr` shows, where `pprint` prints a bare map. A collection of plain values
   fills its lines instead of putting each element on its own."
  [x]
  (cond
    (record? x)
    (do (.write ^java.io.Writer *out* (str "#" (.getName (class x))))
        (pp/simple-dispatch (into {} x)))

    (flat-coll? x)
    (cond (vector? x) (pprint-fill "[" "]" x)
          (set? x) (pprint-fill "#{" "}" x)
          :else (pprint-fill "(" ")" x))

    :else (pp/simple-dispatch x)))

(defn- print-value
  "`value` as \"=> ...\". A value that fits on one line prints exactly as
   `pr-str` does; a longer one is pretty-printed to `right-margin` columns,
   continuation lines aligned under the value."
  [value right-margin]
  (let [one-line (pr-str value)]
    (if (and (<= (+ 3 (count one-line)) right-margin)
             (not (str/includes? one-line "\n")))
      (str "=> " one-line)
      (let [pretty (binding [pp/*print-right-margin* (max 20 (- right-margin 3))
                             pp/*print-pprint-dispatch* pprint-dispatch
                             ;; Miser mode breaks at every opportunity once
                             ;; under 40 columns remain, which on a page about
                             ;; 64 wide is most of the time.
                             pp/*print-miser-width* nil]
                     (str/trimr (with-out-str (pp/pprint value))))]
        (str "=> " (str/replace pretty "\n" "\n   "))))))

(defn- cap
  "`s` cut to `max-chars`, with a marker when anything was cut."
  [s max-chars]
  (if (> (count s) max-chars)
    (str (subs s 0 max-chars) truncation-marker)
    s))

(defn present
  "Bound `value` into a presentation map, the thing persisted before anything is
   drawn:

     {:kind :text :text \"=> 3\" :tablet-text \"=> 3\" :truncated? false
      :kindly-kind nil}

   The value is classified with Kindly first (`form`, under :form in the
   limits, is what produced it). A hidden value becomes {:kind :hidden}, whose
   :tablet-text is only the source, or nil when there is nothing at all to put
   on the page. Every other kind is printed as text for now; :kindly-kind
   records what it was, so it can be drawn better once that kind is supported.

   Retains Edsger's `=>` convention. `nil` and `false` are ordinary results and
   print as themselves. On a print method that throws or outstays its welcome,
   returns :failed? true with a reason -- the caller must not treat that as a
   committed result."
  ([value] (present value default-limits))
  ([value limits]
   (let [{:keys [max-chars source form print-level print-length timeout-ms
                 right-margin]
          :as limits} (merge default-limits limits)
         r (run-bounded
            (fn []
              ;; A broken advisor must not cost the result: without a kind it
              ;; is simply printed.
              (let [{:keys [kind value]} (try (classify form value)
                                              (catch Exception _ {:value value}))]
                {:kindly-kind kind
                 :printed (when-not (= kind :kind/hidden)
                            (binding [*print-level* print-level
                                      *print-length* print-length]
                              (if (and (= kind :kind/code) (string? value))
                                value
                                (print-value value right-margin))))}))
            timeout-ms)
         ;; The source is bounded too: a long draft must not produce an image
         ;; that cannot fit the page.
         src (when source (cap source max-chars))]
     (cond
       (:timeout r)
       {:kind :text :failed? true :reason :timeout
        :text "=> <presentation timed out>" :truncated? false}

       (:threw r)
       {:kind :text :failed? true :reason :print-threw
        :text (str "=> <presentation failed: " (:threw r) ">") :truncated? false}

       (= :kind/hidden (get-in r [:ok :kindly-kind]))
       {:kind :hidden
        :text nil
        ;; With the source on the page, it stays there alone, as Clay keeps
        ;; the code of a hidden value. Without it, there is nothing to draw.
        :tablet-text src
        :truncated? false
        :kindly-kind :kind/hidden}

       :else
       (let [{:keys [printed kindly-kind]} (:ok r)
             result-text (cap printed max-chars)]
         {:kind :text
          ;; What Emacs shows: the result alone.
          :text result-text
          ;; What is rasterised for the tablet, which may also carry the source.
          ;; Persisted, so a failed rasterisation can be retried from it without
          ;; re-evaluating or recomposing.
          :tablet-text (if src (str src "\n" result-text) result-text)
          ;; True when the character cap cut the string. Structural truncation
          ;; from print-level/print-length shows up as Clojure's own `...` in
          ;; the text rather than as this flag.
          :truncated? (> (count printed) max-chars)
          :kindly-kind kindly-kind})))))

;; -------- rasterization --------

;; Wrapping and trimming follow what ImageMagick's `caption:` + `-trim` did
;; before: word-wrap within the width, break a word only when it alone is too
;; wide, and keep the image tight around the glyphs.

(defn- break-word
  "Split `s` into pieces that each fit `max-width`, at least one char apiece."
  [^FontMetrics fm max-width s]
  (loop [s s acc []]
    (if (<= (.stringWidth fm s) max-width)
      (conj acc s)
      (let [n (max 1 (count (take-while #(<= (.stringWidth fm (subs s 0 %)) max-width)
                                        (range 1 (count s)))))]
        (recur (subs s n) (conj acc (subs s 0 n)))))))

(defn- wrap-line
  "Word-wrap one line of text to `max-width` pixels. Leading indentation is
   kept; the whitespace at a break is dropped."
  [^FontMetrics fm max-width line]
  (let [fits? #(<= (.stringWidth fm %) max-width)]
    (loop [[tok & more :as toks] (re-seq #"\s*\S+|\s+" line)
           cur "" acc []]
      (cond
        (empty? toks) (conj acc cur)
        (fits? (str cur tok)) (recur more (str cur tok) acc)
        (str/blank? cur)
        ;; Nothing on this line yet, so the word itself is too wide.
        (let [pieces (break-word fm max-width (str cur tok))]
          (recur more (peek pieces) (into acc (pop pieces))))
        :else (recur (cons (str/triml tok) more) "" (conj acc cur))))))

(defn- ink-bounds
  "[x y w h] of the non-white pixels in a gray image, or nil if it is blank."
  [^BufferedImage img]
  (let [w (.getWidth img) h (.getHeight img)
        ^bytes px (.getData ^DataBufferByte (.getDataBuffer (.getRaster img)))]
    (loop [i 0 x0 w y0 h x1 -1 y1 -1]
      (if (< i (alength px))
        (if (= -1 (aget px i))              ; 0xff, white
          (recur (inc i) x0 y0 x1 y1)
          (let [x (rem i w) y (quot i w)]
            (recur (inc i) (min x0 x) (min y0 y) (max x1 x) (max y1 y))))
        (when (>= x1 0)
          [x0 y0 (inc (- x1 x0)) (inc (- y1 y0))])))))

(defn rasterize
  "Render `presentation`'s text to a PNG at `out-path`.

   Long lines word-wrap within :max-width, so results wrap rather than run off
   the page, and the image is trimmed tight around the glyphs.

   Returns {:ok? true :path :width :height} or, when the result does not fit the
   supported page area, {:ok? false :reason :insufficient-space ...}. It never
   silently crops or shrinks -- the caller keeps the result pending instead."
  [presentation out-path {:keys [max-width max-height font font-size]
                          :or {font default-font font-size default-font-size}}]
  (let [max-width (min (or max-width page-width) page-width)
        max-height (min (or max-height page-height) page-height)
        out (io/file out-path)
        fnt (load-font font font-size)
        [_ ^Graphics2D pg] (image/canvas 1 1 BufferedImage/TYPE_BYTE_GRAY Color/white)
        fm (.getFontMetrics pg fnt)
        _ (.dispose pg)
        text (or (:tablet-text presentation) (:text presentation))
        lines (->> (str/split-lines text)
                   (mapcat #(wrap-line fm max-width %))
                   ;; Blank edge lines would be trimmed away anyway.
                   (drop-while str/blank?) reverse (drop-while str/blank?) reverse
                   vec)
        lh (.getHeight fm)
        refuse (fn [w h]
                 (.delete out)
                 {:ok? false :reason :insufficient-space
                  :width w :height h
                  :max-width max-width :max-height max-height})]
    (cond
      (empty? lines) (refuse 0 0)
      ;; The first and last lines carry ink, so the trimmed image is at least
      ;; this tall. Refusing here means never allocating a canvas for, say,
      ;; ten thousand newlines.
      (> (* (dec (count lines)) lh) max-height)
      (refuse max-width (* (dec (count lines)) lh))
      :else
      (let [[img ^Graphics2D g] (image/canvas max-width (* (count lines) lh)
                                              BufferedImage/TYPE_BYTE_GRAY Color/white)]
        (try
          (.setFont g fnt)
          (.setColor g Color/black)
          (doseq [[i line] (map-indexed vector lines)]
            (.drawString g ^String line (int 0) (int (+ (.getAscent fm) (* i lh)))))
          (finally (.dispose g)))
        (let [[x y w h] (ink-bounds img)]
          (cond
            (nil? x) (refuse 0 0)               ; only invisible characters
            (> h max-height) (refuse w h)
            :else
            {:ok? true
             :path (image/write-png! (.getSubimage ^BufferedImage img x y w h) out)
             :width w :height h}))))))
