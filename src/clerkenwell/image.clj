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

(defn write-png!
  "Write `img` to `out` as a PNG, creating parent directories."
  [^BufferedImage img out]
  (let [f (io/file out)]
    (io/make-parents f)
    (when-not (ImageIO/write img "png" f)
      (throw (ex-info "No PNG writer available" {:path (str f)})))
    (str f)))
