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
            [clerkenwell.html :as html]
            [clerkenwell.image :as image]
            [clerkenwell.markdown :as markdown]
            [clerkenwell.table :as table]
            [scicloj.kindly-advice.v1.api :as advice])
  (:import (java.awt Color Font FontMetrics Graphics2D)
           (java.awt.image BufferedImage)))

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
   :right-margin 64
   ;; Pixels a chart is drawn across: the page between its margins, unless
   ;; the caller says otherwise. Charts are scaled up as well as down, so a
   ;; small one is still legible on paper.
   :chart-width 1284
   ;; Drawing a chart can take far longer than printing a value, and the
   ;; first one also waits for the JIT.
   :chart-timeout-ms 20000
   ;; How much of a table is shown: see clerkenwell.table.
   :max-rows 30
   :max-columns 12
   :max-cell-chars 40})

;; Inconsolata rather than the handwriting face Edsger used: on the page,
;; output in a monospace font is immediately distinguishable from your own
;; writing, which a handwriting font deliberately is not.
(def default-font (:font html/default-style))
(def default-font-size (:font-size html/default-style))

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

;; -------- images --------

(def max-image-pixels
  "Images larger than this are not decoded at all. Far beyond anything the
   page can show, and a bound on the memory one result can take."
  50000000)

(defn- capture-image
  "`value` -- a BufferedImage, or a file holding an image, as Plotje's `save`
   returns -- as it will appear: grayscale on white, no larger than the page.
   Returns {:png-bytes :width :height :original [w h]}, {:image-problem text}
   when it cannot be shown, or nil when it is not an image after all and should
   be printed."
  [value]
  (let [too-large (fn [w h] {:image-problem (str "=> #<image " w "×" h
                                                 ", too large to show>")})
        img (cond
              (instance? BufferedImage value) value
              (instance? java.io.File value)
              (let [[w h] (image/file-dimensions value)]
                (cond (nil? w) nil
                      (> (* (long w) h) max-image-pixels) (too-large w h)
                      :else (javax.imageio.ImageIO/read ^java.io.File value))))]
    (if-not (instance? BufferedImage img)
      img
      (let [w (.getWidth ^BufferedImage img) h (.getHeight ^BufferedImage img)]
        (if (> (* (long w) h) max-image-pixels)
          (too-large w h)
          (let [[tw th] (image/fit w h page-width page-height)
                out (image/scale img tw th)]
            {:png-bytes (image/png-bytes out)
             :width tw :height th
             :original [w h]}))))))

(defn- chart?
  "Whether `value` is a Plotje pose. Plotje is optional: nothing of it is
   loaded here unless the notebook has loaded it already."
  [value]
  (boolean (and (map? value)
                (find-ns 'scicloj.plotje.api)
                ((requiring-resolve 'clerkenwell.chart/pose?) value))))

(defn- capture-chart
  "A Plotje pose drawn `width` pixels wide and captured as an image. A pose
   Plotje cannot draw is a result in its own right, which says why."
  [pose width]
  (try
    (assoc (capture-image ((requiring-resolve 'clerkenwell.chart/draw)
                           pose width page-height))
           :text "=> #<chart>")
    (catch Exception e
      {:image-problem (str "=> #<chart, could not be drawn: " (.getMessage e) ">")})))

(defn present
  "Bound `value` into a presentation map, the thing persisted before anything is
   drawn:

     {:kind :text :text \"=> 3\" :tablet-text \"=> 3\" :truncated? false
      :kindly-kind nil}

   The value is classified with Kindly first (`form`, under :form in the
   limits, is what produced it). A hidden value becomes {:kind :hidden}, whose
   :tablet-text is only the source, or nil when there is nothing at all to put
   on the page. An image, or a Plotje chart, becomes {:kind :image}, carrying
   its PNG bytes for the caller to persist. A table becomes {:kind :table}
   and Markdown {:kind :md}, both typeset only when rasterised. Every other kind is printed as
   text for now; :kindly-kind records what it was, so it can be drawn better
   once that kind is supported.

   Retains Edsger's `=>` convention. `nil` and `false` are ordinary results and
   print as themselves. On a print method that throws or outstays its welcome,
   returns :failed? true with a reason -- the caller must not treat that as a
   committed result."
  ([value] (present value default-limits))
  ([value limits]
   (let [{:keys [max-chars source form print-level print-length timeout-ms
                 right-margin chart-width chart-timeout-ms]
          :as limits} (merge default-limits limits)
         ;; Classified first and on its own, because what the value is decides
         ;; how long drawing it may take.
         c (run-bounded
            (fn []
              ;; A broken advisor must not cost the result: without a kind it
              ;; is simply printed.
              (let [{:keys [kind value] :as c} (try (classify form value)
                                                    (catch Exception _ {:value value}))]
                (assoc c :chart? (and (not= kind :kind/hidden) (chart? value)))))
            timeout-ms)
         r (if-not (:ok c)
             c
             (let [{:keys [kind value chart?]} (:ok c)]
               (run-bounded
                (fn []
                  (binding [*print-level* print-level
                            *print-length* print-length]
                    (let [special
                          ;; Drawn and encoded here, on the bounded thread: a
                          ;; chart, or decoding and scaling a large image,
                          ;; takes real time.
                          (cond chart? (capture-chart value chart-width)
                                (= kind :kind/image) (capture-image value)
                                (#{:kind/table :kind/dataset} kind)
                                (some->> (table/table-data
                                          value (select-keys limits [:max-rows :max-columns
                                                                     :max-cell-chars]))
                                         (hash-map :table))
                                ;; Clay takes a string, or a sequence of them
                                ;; as lines.
                                (= kind :kind/md)
                                (cond (string? value) {:markdown value}
                                      (and (sequential? value) (every? string? (take 1000 value)))
                                      {:markdown (str/join "\n" (take 1000 value))}))]
                      (merge
                       {:kindly-kind kind}
                       special
                       ;; A value that is not the shape its kind says, such as
                       ;; kind/table on a number, is printed instead.
                       (when-not (or chart? (= kind :kind/hidden) (:table special)
                                     (:markdown special))
                         {:printed (if (and (= kind :kind/code) (string? value))
                                     value
                                     (print-value value right-margin))})))))
                (if chart? chart-timeout-ms timeout-ms))))
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

       (get-in r [:ok :table])
       (let [t (get-in r [:ok :table])]
         (merge {:kind :table}
                t
                {;; What Emacs shows: aligned text, as the buffer is monospace.
                 :text (table/text t)
                 ;; Only the source: the table itself is typeset beneath it.
                 :tablet-text src
                 :truncated? false
                 :kindly-kind (get-in r [:ok :kindly-kind])}))

       (get-in r [:ok :markdown])
       (let [m (cap (get-in r [:ok :markdown]) max-chars)]
         {:kind :md
          :markdown m
          ;; What Emacs shows: the Markdown as written.
          :text m
          ;; Only the source: the Markdown itself is typeset beneath it.
          :tablet-text src
          :truncated? (> (count (get-in r [:ok :markdown])) max-chars)
          :kindly-kind :kind/md})

       (get-in r [:ok :png-bytes])
       (let [{:keys [png-bytes width height original text]} (:ok r)]
         {:kind :image
          ;; The image bytes ride along only until the caller persists them as
          ;; a file and replaces them with its path, before the record is made.
          :png-bytes png-bytes
          :width width :height height
          :text (or text (str "=> #<image " (first original) "×" (second original) ">"))
          ;; Only the source: the image itself is drawn beneath it.
          :tablet-text src
          :truncated? false
          :kindly-kind (get-in r [:ok :kindly-kind])})

       :else
       (let [{:keys [printed kindly-kind]} (:ok r)
             printed (or (get-in r [:ok :image-problem]) printed)
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

(defn- text-image
  "`text` drawn in black on white, word-wrapped within `max-width` and trimmed
   tight around the glyphs. Returns {:image img} or, when it would be taller
   than `max-height`, {:refused {:width :height}}."
  [text {:keys [max-width max-height font font-size]}]
  (let [fnt (load-font font font-size)
        [_ ^Graphics2D pg] (image/canvas 1 1 BufferedImage/TYPE_BYTE_GRAY Color/white)
        fm (.getFontMetrics pg fnt)
        _ (.dispose pg)
        lines (->> (str/split-lines text)
                   (mapcat #(wrap-line fm max-width %))
                   ;; Blank edge lines would be trimmed away anyway.
                   (drop-while str/blank?) reverse (drop-while str/blank?) reverse
                   vec)
        lh (.getHeight fm)
        refuse (fn [w h] {:refused {:width w :height h}})]
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
        (let [[x y w h] (image/ink-bounds img)]
          (cond
            (nil? x) (refuse 0 0)               ; only invisible characters
            (> h max-height) (refuse w h)
            :else
            {:image (.getSubimage ^BufferedImage img x y w h)}))))))

(def ^:private source-gap
  "Pixels between the source and an image drawn beneath it."
  16)

(def ^:private min-image-height
  "An image is shrunk to fit the room left on the page, but not below this
   height (or its own, if smaller): past that it is unreadable, and the
   result is better refused, so it stays pending, than squeezed."
  100)

(defn- above-height
  "How far down the page a drawing starts beneath source text `src`
   (a text-image result, or nil when there is no source)."
  [src]
  (if-let [^BufferedImage img (:image src)]
    (+ (.getHeight img) source-gap)
    0))

(defn- stack
  "Source image `src` (or nil) above `img`, on white."
  ^BufferedImage [^BufferedImage src ^BufferedImage img]
  (if-not src
    img
    (let [above (+ (.getHeight src) source-gap)
          [canvas ^Graphics2D g] (image/canvas (max (.getWidth src) (.getWidth img))
                                               (+ above (.getHeight img))
                                               BufferedImage/TYPE_BYTE_GRAY
                                               Color/white)]
      (try
        (.drawImage g src 0 0 nil)
        (.drawImage g img 0 (int above) nil)
        (finally (.dispose g)))
      canvas)))

(defn rasterize
  "Render `presentation` to a PNG at `out-path`.

   Text word-wraps within :max-width, so results wrap rather than run off the
   page, and is trimmed tight around the glyphs; it is never shrunk. An image
   (read from :image, relative to :base-dir) is scaled down to the room left,
   beneath its source if that is to go on the page too. A table is typeset to
   the width, beneath its source likewise, and refused like text if too tall;
   so is Markdown.

   Text is set in :font at :font-size, Markdown and tables in :prose-font;
   see `html/default-style`.

   Returns {:ok? true :path :width :height} or, when the result does not fit the
   supported page area, {:ok? false :reason :insufficient-space ...}. Rather than
   squeeze it, the caller keeps the result pending."
  [presentation out-path {:keys [max-width max-height font font-size prose-font base-dir]
                          :or {font default-font font-size default-font-size
                               prose-font (:prose-font html/default-style)}}]
  (let [max-width (min (or max-width page-width) page-width)
        ;; Not clamped to the screen: a scrollable page can be taller, and
        ;; the caller says how much room there is.
        max-height (or max-height page-height)
        opts {:max-width max-width :max-height max-height
              :font font :font-size font-size}
        out (io/file out-path)
        refuse (fn [{:keys [width height]}]
                 (.delete out)
                 {:ok? false :reason :insufficient-space
                  :width width :height height
                  :max-width max-width :max-height max-height})
        done (fn [^BufferedImage img]
               {:ok? true :path (image/write-png! img out)
                :width (.getWidth img) :height (.getHeight img)})
        text (or (:tablet-text presentation) (:text presentation))]
    (case (:kind presentation)
      :image
      (let [src (when-let [t (:tablet-text presentation)] (text-image t opts))
            above (above-height src)
            ^BufferedImage img (javax.imageio.ImageIO/read
                                (io/file base-dir (:image presentation)))
            w (.getWidth img) h (.getHeight img)
            room (- max-height above)
            ;; Fitting the width is always fine; only shrinking further for
            ;; want of height can make an image too small to read.
            [_ wide-h] (image/fit w h max-width Long/MAX_VALUE)
            [tw th] (image/fit w h max-width (max 1 room))]
        (cond
          (:refused src) (refuse (:refused src))
          (and (< room wide-h) (< room (min wide-h min-image-height)))
          (refuse {:width w :height (+ above wide-h)})
          :else (done (stack (:image src) (image/scale img tw th)))))

      (:table :md)
      ;; Typeset text: fitted to the width, but, like text, never squeezed
      ;; to the height.
      (let [src (when-let [t (:tablet-text presentation)] (text-image t opts))
            style {:font font :prose-font prose-font :font-size font-size}
            ^BufferedImage typeset (if (= :table (:kind presentation))
                                     (table/image presentation max-width style)
                                     (markdown/image (:markdown presentation) max-width style))
            total (+ (above-height src) (.getHeight typeset))]
        (cond
          (:refused src) (refuse (:refused src))
          (> total max-height) (refuse {:width (.getWidth typeset) :height total})
          :else (done (stack (:image src) typeset))))

      (let [{:keys [image refused]} (text-image text opts)]
        (if refused (refuse refused) (done image))))))
