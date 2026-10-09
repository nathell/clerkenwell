(ns clerkenwell.chart-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clerkenwell.chart :as chart]
            [clerkenwell.render :as render]
            [scicloj.plotje.api :as pj]))

(use-fixtures :each (fn [f] (pj/set-config! nil) (f) (pj/set-config! nil)))

(def data {:x (range 30)
           :y (map #(Math/sin (/ % 4.0)) (range 30))
           :g (take 30 (cycle ["a" "b" "c"]))})

(defn- grays
  "How many pixels of each gray value the presentation's image has, as stored
   (getRGB would report them lightened, through Java's linear gray)."
  [p]
  (let [img (javax.imageio.ImageIO/read (java.io.ByteArrayInputStream. (:png-bytes p)))
        r (.getRaster img)]
    (frequencies (for [x (range 0 (.getWidth img) 3) y (range 0 (.getHeight img) 3)]
                   (.getSample r x y 0)))))

(defn- panel-gray?
  "Whether a good part of the image is `gray`, as a chart's panel would be."
  [p gray]
  (let [g (grays p)]
    (> (reduce + (keep (fn [[v n]] (when (<= (abs (- v gray)) 1) n)) g))
       (* 0.2 (reduce + (vals g))))))

(def ^:private plotje-panel 232)          ; #E8E8E8, Plotje's own default

(deftest a-pose-is-a-chart
  (let [p (render/present (pj/lay-point data :x :y {:color :g}))]
    (is (= :image (:kind p)))
    (is (= "=> #<chart>" (:text p)))
    (testing "drawn across the page, larger than Plotje's 600 pixels"
      (is (= 1284 (:width p)))
      (is (= 856 (:height p)) "keeping its proportions"))))

(deftest a-chart-width-can-be-given
  (let [p (render/present (pj/lay-point data :x :y)
                          (assoc render/default-limits :chart-width 900))]
    (is (= 900 (:width p)))))

(deftest composites-are-drawn-whole
  (let [p (render/present (pj/arrange [(pj/lay-point data :x :y)
                                       (pj/lay-line data :x :y)]))]
    (is (= :image (:kind p)))
    (is (= 1284 (:width p)))))

(deftest e-ink-defaults-sit-beneath-the-author
  (testing "with nothing configured, the gray panel is gone"
    (is (not (panel-gray? (render/present (pj/lay-point data :x :y)) plotje-panel))))
  (testing "a set-config! of the author's wins over them"
    (pj/set-config! {:theme {:bg "#A0A0A0"}})
    (is (panel-gray? (render/present (pj/lay-point data :x :y)) 160)))
  (testing "so does a with-config the pose was made in"
    (pj/set-config! nil)
    (let [pose (pj/with-config {:theme {:bg "#A0A0A0"}} (pj/lay-point data :x :y))]
      (is (panel-gray? (render/present pose) 160))))
  (testing "and options on the pose itself"
    (let [pose (pj/options (pj/lay-point data :x :y) {:config {:theme {:bg "#A0A0A0"}}})]
      (is (panel-gray? (render/present pose) 160)))))

(deftest no-defaults-when-the-authors-settings-cannot-be-read
  (with-redefs [chart/config (constantly nil)]
    (is (panel-gray? (render/present (pj/lay-point data :x :y)) plotje-panel)
        "Plotje's own look, rather than risk overriding the author"))
  (testing "which is what a Plotje that has moved its internals gets"
    (with-redefs [resolve (fn [_] nil)]
      (is (nil? (chart/config))))))

(deftest a-chart-plotje-cannot-draw-says-why
  (let [p (render/present (pj/lay-point data :nonexistent :y))]
    (is (= :text (:kind p)))
    (is (str/starts-with? (:text p) "=> #<chart, could not be drawn:"))))

(deftest a-hidden-chart-stays-hidden
  (is (= :hidden (:kind (render/present (scicloj.kindly.v4.kind/hidden
                                         (pj/lay-point data :x :y)))))))
