(ns clerkenwell.render
  "Actual JVM value -> bounded textual presentation -> PNG of known dimensions.

   Two deliberately separate stages. `present` bounds the value into text and is
   the thing that gets persisted first; `rasterize` turns already-captured text
   into an image. If rasterization fails, it can be retried from the stored text
   without going anywhere near the original value, which no longer needs to
   exist. Arbitrary print methods can block or throw, so presentation is bounded
   in work and time independently of evaluation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clerkenwell.image :as image])
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
   :timeout-ms 2000})

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

;; The verified page geometry (docs/investigation-results.md): 1404 x 1872,
;; scene origin top-centre. Callers pass the usable box; these are the outer
;; bounds nothing may exceed.
(def page-width 1404)
(def page-height 1872)

;; -------- presentation --------

(def truncation-marker " ...[truncated]")

(defn- print-bounded
  "pr-str under print-level/print-length. Runs on another thread because a user
   print method may block or throw: we bound how long we WAIT, which is not the
   same as stopping it. The thread may still be running after we give up, so it
   is a daemon and we never claim to have killed it."
  [value {:keys [print-level print-length timeout-ms]}]
  (let [result (promise)
        t (doto (Thread.
                 (fn []
                   (deliver result
                            (try
                              (binding [*print-level* print-level
                                        *print-length* print-length]
                                {:ok (pr-str value)})
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

(defn present
  "Bound `value` into a presentation map:

     {:kind :text :text \"=> 3\" :truncated? false}

   Retains Edsger's `=>` convention. `nil` and `false` are ordinary results and
   print as themselves. On a print method that throws or outstays its welcome,
   returns :failed? true with a reason -- the caller must not treat that as a
   committed result.

   The map is intentionally open so explicit :table/:chart/:image presentations
   can be added later without changing callers."
  ([value] (present value default-limits))
  ([value limits]
   (let [{:keys [max-chars source]} (merge default-limits limits)
         r (print-bounded value (merge default-limits limits))]
     (cond
       (:timeout r)
       {:kind :text :failed? true :reason :timeout
        :text "=> <presentation timed out>" :truncated? false}

       (:threw r)
       {:kind :text :failed? true :reason :print-threw
        :text (str "=> <presentation failed: " (:threw r) ">") :truncated? false}

       :else
       (let [s (:ok r)
             over? (> (count s) max-chars)
             body (if over? (str (subs s 0 max-chars) truncation-marker) s)
             result-text (str "=> " body)
             ;; The source is bounded too: a long draft must not produce an
             ;; image that cannot fit the page.
             src (when source
                   (if (> (count source) max-chars)
                     (str (subs source 0 max-chars) truncation-marker)
                     source))]
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
          :truncated? over?})))))

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
