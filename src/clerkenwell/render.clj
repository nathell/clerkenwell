(ns clerkenwell.render
  "Actual JVM value -> bounded textual presentation -> PNG of known dimensions.

   Two deliberately separate stages. `present` bounds the value into text and is
   the thing that gets persisted first; `rasterize` turns already-captured text
   into an image. If rasterization fails, it can be retried from the stored text
   without going anywhere near the original value, which no longer needs to
   exist. Arbitrary print methods can block or throw, so presentation is bounded
   in work and time independently of evaluation."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

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

(defn resolve-font
  "Turn a font family name into something ImageMagick will accept.

   Builds without fontconfig support cannot look a family up by name, so a
   family is resolved to a file with fc-match when that is available. An
   existing path is passed through untouched."
  [font]
  (if (.exists (io/file font))
    font
    (or (try
          (let [{:keys [exit out]} (sh/sh "fc-match" "-f" "%{file}" font)]
            (when (and (zero? exit) (seq out) (.exists (io/file out)))
              out))
          (catch Exception _ nil))
        ;; Let ImageMagick try, and fail with its own message if it cannot.
        font)))

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

(defn- magick [args]
  (let [{:keys [exit out err]} (apply sh/sh args)]
    (when-not (zero? exit)
      (throw (ex-info "ImageMagick failed" {:exit exit :err err :args args})))
    out))

(defn- identify-dims [path]
  (let [out (magick ["magick" "identify" "-format" "%w %h" (str path)])
        [w h] (map parse-long (str/split (str/trim out) #"\s+"))]
    [w h]))

(defn rasterize
  "Render `presentation`'s text to a PNG at `out-path`.

   Wrapping is done by ImageMagick's `caption:`, bounded to :max-width, so long
   results wrap rather than run off the page. The text is passed as a process
   argument, never interpolated into a shell command.

   Returns {:ok? true :path :width :height} or, when the result does not fit the
   supported page area, {:ok? false :reason :insufficient-space ...}. It never
   silently crops or shrinks -- the caller keeps the result pending instead."
  [presentation out-path {:keys [max-width max-height font font-size]
                          :or {font default-font font-size default-font-size}}]
  (let [max-width (min (or max-width page-width) page-width)
        max-height (min (or max-height page-height) page-height)
        out (io/file out-path)]
    (io/make-parents out)
    ;; `caption:` pads to the full -size width, which would insert a full-width
    ;; white block over whatever sits to the right of the text. `-trim` removes
    ;; only the uniform border, so the asset is tight around the glyphs. That is
    ;; not cropping content: the wrap width above is what bounds the text, and
    ;; an over-large result is refused below rather than shrunk.
    (magick ["magick"
             "-background" "white"
             "-fill" "black"
             "-font" (resolve-font font)
             "-pointsize" (str font-size)
             "-size" (str max-width "x")
             (str "caption:" (or (:tablet-text presentation) (:text presentation)))
             "-trim" "+repage"
             (str out)])
    (let [[w h] (identify-dims out)]
      (if (or (> w max-width) (> h max-height))
        (do (.delete out)
            {:ok? false :reason :insufficient-space
             :width w :height h
             :max-width max-width :max-height max-height})
        {:ok? true :path (str out) :width w :height h}))))
