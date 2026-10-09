(ns clerkenwell.chart
  "Plotje charts, drawn for the page.

   Plotje is an optional dependency: this namespace is only loaded, through
   `requiring-resolve`, once a notebook has loaded Plotje itself, which it must
   have to produce a pose. See docs/rich-output.md.

   A pose draws itself through Plotje's own `plot`, in a format registered
   here that returns its membrane drawing rather than SVG or pixels. That keeps
   Plotje's handling of composites and of the config a pose captured when it
   was made, and lets the drawing be scaled as vectors to the page width, so
   text and lines stay sharp at any size."
  (:require [membrane.java2d :as j2d]
            [membrane.ui :as ui]
            [scicloj.plotje.api :as pj]
            [scicloj.plotje.impl.defaults :as defaults]
            [scicloj.plotje.impl.render :as render]))

(defmethod render/membrane->plot ::membrane [tree _ _] tree)

(def e-ink-config
  "Defaults for grayscale e-ink, beneath anything the notebook author sets.
   Plotje's own are made for colour screens: a gray panel, which only costs
   contrast here, and a palette whose hues become near-identical grays."
  {:theme {:bg "#FFFFFF" :grid "#DDDDDD"}
   :color-values ["#000000" "#8C8C8C" "#C8C8C8"]
   :default-color "#000000"
   :point-opacity 1.0})

(defn- deep-merge [& ms]
  (apply merge-with (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b)) ms))

(defn- authors-config
  "What the author configured through `plotje.edn` and `pj/set-config!`, or
   ::unknown. Neither is public API, so they are reached by name, and a
   Plotje that has moved them is noticed rather than broken."
  []
  (let [edn (resolve 'scicloj.plotje.impl.defaults/read-plotje-edn)
        atom-var (resolve 'scicloj.plotje.impl.defaults/config-atom)]
    (if (and edn atom-var (instance? clojure.lang.IDeref @atom-var))
      (deep-merge (edn) @@atom-var)
      ::unknown)))

(defn config
  "The config to draw under. Plotje layers library defaults < `plotje.edn` <
   `set-config!` < a binding, so a binding of the e-ink defaults alone would
   override the author's choices. Instead it carries them on top, which
   places the e-ink defaults just above the library's and below everything
   else. If the author's settings cannot be read, no defaults are applied:
   overriding the author would be worse than a gray panel.

   A pose made inside `pj/with-config` restores that config when drawn, and
   options on a pose win regardless."
  []
  (let [a (authors-config)]
    (when-not (= ::unknown a)
      (deep-merge e-ink-config a))))

(defn pose?
  "Whether `x` is something Plotje can draw."
  [x]
  (and (map? x) (pj/pose? x)))

(defn draw
  "`pose` as a BufferedImage, scaled to `width` pixels wide -- larger or
   smaller than Plotje drew it -- unless that would be taller than
   `max-height`."
  [pose width max-height]
  (let [f (get-in (meta pose) [:kindly/options :kindly/f])
        tree (binding [defaults/*config* (config)]
               (if f
                 (f (assoc-in pose [:opts :format] ::membrane))
                 (pj/plot pose {:format ::membrane})))
        w (double (ui/width tree))
        h (double (ui/height tree))
        s (min (/ width w) (/ max-height h))]
    (j2d/draw-to-image (ui/scale s s tree)
                       [(max 1 (int (Math/round (* s w))))
                        (max 1 (int (Math/round (* s h))))])))
