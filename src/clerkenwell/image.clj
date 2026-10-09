(ns clerkenwell.image
  "Java2D plumbing shared by the renderers: colours, canvases and PNG output.
   Everything is drawn in-process, so there is no external image tool to
   install and no process boundary for text or paths to cross."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.awt Color Graphics2D RenderingHints)
           (java.awt.image BufferedImage)
           (javax.imageio ImageIO)))

;; Rendering never needs a display. Without this, touching AWT on macOS puts a
;; Java icon in the Dock of the REPL's JVM. An explicit setting is respected.
(when-not (System/getProperty "java.awt.headless")
  (System/setProperty "java.awt.headless" "true"))

(def ^:private named-colors
  {"black" Color/black "white" Color/white "gray" Color/gray "grey" Color/gray
   "red" Color/red "green" Color/green "blue" Color/blue})

(defn parse-color
  "A colour from `#rgb`, `#rrggbb`, Emacs's `#rrrrggggbbbb`, or a basic name."
  ^Color [s]
  (let [s (str/lower-case (str/trim (str s)))
        hex (second (re-matches #"#([0-9a-f]+)" s))
        n (count hex)]
    (cond
      (and hex (zero? (mod n 3)) (<= 3 n 12))
      (let [d (quot n 3)
            ;; Each channel keeps its two most significant hex digits; a
            ;; one-digit channel is doubled, as CSS does.
            chan (fn [i] (let [c (subs hex (* i d) (* (inc i) d))
                               c (if (= 1 d) (str c c) (subs c 0 2))]
                           (Integer/parseInt c 16)))]
        (Color. (int (chan 0)) (int (chan 1)) (int (chan 2))))

      (named-colors s) (named-colors s)

      :else (throw (ex-info (str "Unrecognised colour: " (pr-str s)) {:color s})))))

(defn canvas
  "A `w` x `h` image of `image-type` filled with `background`, and an
   antialiased Graphics2D for it. Dispose of the graphics when done."
  [w h image-type ^Color background]
  (let [img (BufferedImage. (int w) (int h) (int image-type))
        g (.createGraphics img)]
    (doto g
      (.setRenderingHint RenderingHints/KEY_ANTIALIASING
                         RenderingHints/VALUE_ANTIALIAS_ON)
      (.setRenderingHint RenderingHints/KEY_TEXT_ANTIALIASING
                         RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
      (.setRenderingHint RenderingHints/KEY_STROKE_CONTROL
                         RenderingHints/VALUE_STROKE_PURE)
      (.setColor background)
      (.fillRect 0 0 (int w) (int h)))
    [img g]))

(defn scale
  "`img` resized to `w` x `h` as a grayscale image on white: transparency is
   flattened, since the page has none. A large reduction is made in halving
   steps, which a single bilinear pass would alias badly."
  ^BufferedImage [^BufferedImage img w h]
  (loop [^BufferedImage src img]
    (let [sw (.getWidth src) sh (.getHeight src)
          ;; Halve while still more than twice the target; then one last step.
          nw (if (> sw (* 2 w)) (quot sw 2) w)
          nh (if (> sh (* 2 h)) (quot sh 2) h)
          [^BufferedImage out ^Graphics2D g]
          (canvas nw nh BufferedImage/TYPE_BYTE_GRAY Color/white)]
      (try
        (.setRenderingHint g RenderingHints/KEY_INTERPOLATION
                           RenderingHints/VALUE_INTERPOLATION_BILINEAR)
        (.setRenderingHint g RenderingHints/KEY_RENDERING
                           RenderingHints/VALUE_RENDER_QUALITY)
        (.drawImage g src 0 0 (int nw) (int nh) nil)
        (finally (.dispose g)))
      (if (and (= nw w) (= nh h)) out (recur out)))))

(defn fit
  "The size `w` x `h` scaled down, never up, to fit `max-w` x `max-h`,
   keeping its proportions, as [w h]."
  [w h max-w max-h]
  (let [k (min 1.0 (/ (double max-w) w) (/ (double max-h) h))]
    [(max 1 (Math/round (* k w))) (max 1 (Math/round (* k h)))]))

(defn file-dimensions
  "[w h] of the image in file `f`, read from its header without decoding the
   pixels, or nil if no reader recognises it."
  [f]
  (with-open [in (ImageIO/createImageInputStream (io/file f))]
    (when in
      (let [readers (ImageIO/getImageReaders in)]
        (when (.hasNext readers)
          (let [^javax.imageio.ImageReader r (.next readers)]
            (try
              (.setInput r in)
              [(.getWidth r 0) (.getHeight r 0)]
              (finally (.dispose r)))))))))

(defn png-bytes
  "`img` encoded as PNG."
  ^bytes [^BufferedImage img]
  (let [out (java.io.ByteArrayOutputStream.)]
    (when-not (ImageIO/write img "png" out)
      (throw (ex-info "No PNG writer available" {})))
    (.toByteArray out)))

(defn write-png!
  "Write `img` to `out` as a PNG, creating parent directories."
  [^BufferedImage img out]
  (let [f (io/file out)]
    (io/make-parents f)
    (when-not (ImageIO/write img "png" f)
      (throw (ex-info "No PNG writer available" {:path (str f)})))
    (str f)))
