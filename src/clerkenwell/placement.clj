(ns clerkenwell.placement
  "Where on the page a result goes.

   One canonical page coordinate system: display coordinates, measured from the
   page's top-left corner. `display->scene` below converts to the scene centre
   point the insertion call wants; that conversion is verified on device and
   lives here only, so no caller has to remember the half-width term.

     output-top = max(bottom of all parsed handwriting,
                      recorded bottom of previous Clerkenwell outputs) + gap

   All parsed strokes count, not only the latest diff, so consecutive results
   without new handwriting cannot overlap. The output high-water mark is
   retained after native deletion: we do not track native movement, and the spec
   is explicit that we must not pretend to."
  (:require [clojure.string :as str]))

(def default-config
  {:left-margin 60
   :gap 20
   :top-margin 60
   :page-width 1404
   :page-height 1872
   :bottom-margin 20})

;; Verified on device 2026-09-18: insertImageFileAsSceneItem takes the image
;; CENTRE in scene coordinates, the page is 1404 wide centred on x=0 (left edge
;; -702), and y runs from 0 at the page top. A 400x300 image flush left needed
;; x = -702 + 400/2 = -502.
(defn display->scene
  "Display top-left (dx, dy) plus size -> the scene centre point to insert at."
  [dx dy w h config]
  (let [{:keys [page-width]} (merge default-config config)]
    [(+ dx (- (/ page-width 2.0)) (/ w 2.0))
     (+ dy (/ h 2.0))]))

(defn strokes-bottom
  "Bottom-most y across every point of every stroke, or nil when there is none.
   Takes parsed strokes in the shape `edsger.lines` produces."
  [strokes]
  (let [ys (mapcat (fn [s] (map :y (:points s))) strokes)]
    (when (seq ys) (reduce max ys))))

(defn output-top
  "The y at which the next output's top edge belongs.

   `ink-bottom` is the bottom of all parsed handwriting (nil if none) and
   `outputs-bottom` the recorded bottom of previous Clerkenwell outputs on this
   page (nil if none). An empty page starts at the configured top margin."
  [ink-bottom outputs-bottom config]
  (let [{:keys [gap top-margin]} (merge default-config config)
        marks (remove nil? [ink-bottom outputs-bottom])]
    (if (seq marks)
      (+ (reduce max marks) gap)
      top-margin)))

(defn place
  "Decide the display top-left for an output of `width` x `height`.

   Returns {:ok? true :dx :dy :bottom} or, when it will not fit the supported
   page area, {:ok? false :reason :insufficient-space ...}. It never overlaps
   ink, shrinks text, inserts off-screen, creates pages or retargets -- the
   caller keeps the result pending instead."
  [{:keys [ink-bottom outputs-bottom width height config]}]
  (let [cfg (merge default-config config)
        {:keys [left-margin page-width page-height bottom-margin]} cfg
        dy (output-top ink-bottom outputs-bottom cfg)
        dx left-margin
        bottom (+ dy height)
        usable-width (- page-width (* 2 left-margin))]
    (cond
      (> width usable-width)
      {:ok? false :reason :insufficient-space :axis :width
       :width width :usable usable-width}

      (> bottom (- page-height bottom-margin))
      {:ok? false :reason :insufficient-space :axis :height
       :bottom bottom :usable (- page-height bottom-margin)}

      :else
      {:ok? true :dx dx :dy dy :bottom bottom})))
