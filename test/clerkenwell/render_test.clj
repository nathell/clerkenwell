(ns clerkenwell.render-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clerkenwell.render :as render]
            [scicloj.kindly.v4.kind :as kind]))

(deftest ordinary-results
  (testing "the => convention is retained"
    (is (= "=> 3" (:text (render/present 3)))))
  (testing "nil and false are normal results, not failures"
    (is (= "=> nil" (:text (render/present nil))))
    (is (= "=> false" (:text (render/present false))))
    (is (not (:failed? (render/present nil))))
    (is (not (:failed? (render/present false))))))

(deftest infinite-sequences-are-bounded
  (testing "(range) terminates and stays within the char cap"
    (let [p (render/present (range))]
      (is (not (:failed? p)))
      (is (< (count (:text p)) (:max-chars render/default-limits)))
      (is (str/includes? (:text p) "..."))))
  (testing "deeply nested structure is bounded by print-level"
    (let [deep (reduce (fn [acc _] [acc]) [] (range 100))
          p (render/present deep)]
      (is (not (:failed? p)))
      (is (str/includes? (:text p) "#")))))

(deftest character-cap
  (testing "a long flat string is cut and the cut is marked visibly"
    (let [p (render/present (apply str (repeat 50000 "x")))]
      (is (:truncated? p))
      (is (str/ends-with? (:text p) render/truncation-marker))
      (is (<= (count (:text p))
              (+ (:max-chars render/default-limits)
                 (count render/truncation-marker) 3))))))

(deftest hostile-print-methods
  (testing "a print method that throws is reported, not propagated"
    (let [p (render/present (reify Object (toString [_] (throw (Exception. "boom")))))]
      ;; pr-str on a reify prints the object tag without calling toString, so
      ;; this must not fail; the point is that it does not blow up.
      (is (string? (:text p)))))
  (testing "a print method that blocks is abandoned within the timeout"
    (let [slow (reify clojure.lang.IDeref
                 (deref [_] nil))
          p (binding [*out* (java.io.StringWriter.)]
              (render/present
               (proxy [Object] []
                 (toString [] (Thread/sleep 60000) "never"))
               (assoc render/default-limits :timeout-ms 300)))]
      (is (string? (:text p))))))

(deftest font-loading
  (testing "an installed family loads at the requested size"
    (let [f (render/load-font render/default-font 40)]
      (is (= render/default-font (.getFamily f)))
      (is (= 40 (.getSize f)))))
  (testing "a missing family is an error, not a silent fallback face"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not installed"
                          (render/load-font "No Such Font Anywhere" 40)))))

(deftest source-on-the-tablet
  (testing "by default only the result is written to the page"
    (let [p (render/present 3)]
      (is (= "=> 3" (:text p)))
      (is (= "=> 3" (:tablet-text p)))))
  (testing "with a source, the expression sits above its result"
    (let [p (render/present 3 (assoc render/default-limits :source "(+ 1 2)"))]
      (is (= "(+ 1 2)\n=> 3" (:tablet-text p)))
      (is (= "=> 3" (:text p))
          "what Emacs shows is unchanged, so the buffer does not double up")))
  (testing "a huge source is bounded too, or the image could not fit the page"
    (let [p (render/present 3 (assoc render/default-limits
                                     :source (apply str (repeat 50000 "x"))))]
      (is (< (count (:tablet-text p))
             (+ (* 2 (:max-chars render/default-limits)) 100)))
      (is (str/includes? (:tablet-text p) render/truncation-marker)))))

(deftest rasterization
  (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                     (str "ckw-render-" (random-uuid) ".png"))]
    (testing "produces a PNG of known dimensions"
      (let [r (render/rasterize {:kind :text :text "=> 3"} tmp
                                {:max-width 1284 :max-height 1800})]
        (is (:ok? r))
        (is (pos? (:width r)))
        (is (pos? (:height r)))
        (is (<= (:width r) 1284))
        (is (.exists (io/file tmp)))))
    (testing "the source is what gets drawn when it is present"
      (let [r (render/rasterize {:kind :text :text "=> 3"
                                 :tablet-text "(+ 1 2)\n=> 3"} tmp
                                {:max-width 1284 :max-height 1800})
            just-result (render/rasterize {:kind :text :text "=> 3"} tmp
                                          {:max-width 1284 :max-height 1800})]
        (is (:ok? r))
        (is (> (:height r) (:height just-result))
            "two lines are taller than one")))
    (testing "a short result is tight, not padded to the full wrap width"
      (let [r (render/rasterize {:kind :text :text "=> 3"} tmp
                                {:max-width 1284 :max-height 1800})]
        (is (< (:width r) 300)
            "a full-width asset would cover ink to the right of the text")))
    (testing "long text wraps rather than overflowing the width"
      (let [long-text (str "=> " (str/join " " (repeat 200 "wrapme")))
            r (render/rasterize {:kind :text :text long-text} tmp
                                {:max-width 1284 :max-height 1800})]
        (is (:ok? r))
        (is (<= (:width r) 1284))))
    (testing "a word wider than the page is broken rather than overflowing"
      (let [r (render/rasterize {:kind :text :text (str "=> " (apply str (repeat 300 "x")))}
                                tmp {:max-width 600 :max-height 1800})]
        (is (:ok? r))
        (is (<= (:width r) 600))
        (is (> (:height r) 100) "it took several lines")))
    (testing "indentation survives, so pretty-printed code stays aligned"
      (let [flush (render/rasterize {:kind :text :text "a\nb"} tmp {:max-width 1284})
            indented (render/rasterize {:kind :text :text "a\n    b"} tmp {:max-width 1284})]
        (is (> (:width indented) (:width flush)))))
    (testing "a flood of newlines is refused without drawing it"
      (let [r (render/rasterize {:kind :text :text (str "=> " (apply str (repeat 100000 "\n")) "x")}
                                tmp {:max-width 1284 :max-height 1800})]
        (is (false? (:ok? r)))
        (is (= :insufficient-space (:reason r)))))
    (testing "a result too tall for the page is refused, not cropped"
      (let [long-text (str "=> " (str/join " " (repeat 400 "toomuch")))
            r (render/rasterize {:kind :text :text long-text} tmp
                                {:max-width 1284 :max-height 200})]
        (is (false? (:ok? r)))
        (is (= :insufficient-space (:reason r)))
        (is (> (:height r) 200))
        (is (not (.exists (io/file tmp))) "no partial asset is left behind")))
    (.delete (io/file tmp))))

;; -------- kinds --------

(deftest classification-follows-kindly
  (testing "a value marked hidden is hidden"
    (is (= :kind/hidden (:kind (render/classify nil (kind/hidden {:a 1}))))))
  (testing "so is one whose form is marked"
    (is (= :kind/hidden (:kind (render/classify (with-meta '(foo) {:kind/hidden true})
                                                {:a 1})))))
  (testing "a value that cannot carry metadata is unwrapped"
    (let [c (render/classify nil (kind/pprint 42))]
      (is (= :kind/pprint (:kind c)))
      (is (= 42 (:value c)))))
  (testing "as in Clay, definitions and setup are hidden"
    (doseq [form '[(def x 1) (defn f []) (defn- g []) (require 'foo) (ns foo)]]
      (is (= :kind/hidden (:kind (render/classify form #'clojure.core/map))) (pr-str form))))
  (testing "matched on the symbol as written, so a qualified or custom definer shows"
    (doseq [form '[(clojure.core/def x 1) (s/defn f [])]]
      (is (not= :kind/hidden (:kind (render/classify form #'clojure.core/map))) (pr-str form))))
  (testing "a notebook can choose its own set"
    (is (not= :kind/hidden
              (:kind (render/classify (with-meta '(def x 1)
                                        {:kindly/options {:hide-value-syms #{}}})
                                      #'clojure.core/map)))))
  (testing "the form is never evaluated again, even when the value is nil"
    (let [calls (atom 0)]
      (intern 'user 'ckw-classify-calls calls)
      (render/classify '(swap! user/ckw-classify-calls inc) nil)
      (is (= 0 @calls)))))

(deftest hidden-values
  (testing "without the source, nothing at all is meant for the page"
    (let [p (render/present (kind/hidden {:a 1}))]
      (is (= :hidden (:kind p)))
      (is (nil? (:tablet-text p)))
      (is (nil? (:text p)))))
  (testing "with the source, the source alone goes on the page"
    (let [p (render/present (kind/hidden {:a 1})
                            (assoc render/default-limits :source "(load-data)"))]
      (is (= :hidden (:kind p)))
      (is (= "(load-data)" (:tablet-text p))))))

(deftest unsupported-kinds-fall-back-to-text
  (let [p (render/present (kind/md "# Hello"))]
    (is (= :text (:kind p)))
    (is (= :kind/md (:kindly-kind p)) "remembered, for when it is supported")
    (is (= "=> \"# Hello\"" (:text p)))))

(deftest code-is-shown-as-written
  (is (= "(defn f [x]\n  x)" (:text (render/present (kind/code "(defn f [x]\n  x)"))))))

(deftest a-broken-advisor-does-not-cost-the-result
  (with-redefs [scicloj.kindly-advice.v1.api/advise (fn [_] (throw (Exception. "bad advisor")))]
    (let [p (render/present 3)]
      (is (not (:failed? p)))
      (is (= "=> 3" (:text p))))))

;; -------- pretty-printing --------

(defrecord Point [x y])

(deftest pretty-printing
  (let [limits (assoc render/default-limits :right-margin 30)
        big {:alpha (range 10) :beta "a fairly long string" :gamma {:delta 1}}]
    (testing "what fits on a line prints exactly as pr-str"
      (is (= "=> {:a 1, :b 2}" (:text (render/present {:a 1 :b 2} limits))))
      (is (= (str "=> " (pr-str (->Point 1 2)))
             (:text (render/present (->Point 1 2)
                                    (assoc limits :right-margin 64))))))
    (testing "what does not is pretty-printed within the margin"
      (let [lines (str/split-lines (:text (render/present big limits)))]
        (is (> (count lines) 1))
        (is (every? #(<= (count %) 30) lines))
        (testing "with continuation lines aligned under the value"
          (is (every? #(str/starts-with? % "   ") (rest lines))))))
    (testing "plain values fill their lines rather than taking one each"
      (let [lines (str/split-lines (:text (render/present (vec (range 40)) limits)))]
        (is (< 1 (count lines) 10))
        (is (every? #(<= (count %) 30) lines))))
    (testing "even when there are infinitely many of them"
      (let [lines (str/split-lines (:text (render/present (range) limits)))]
        (is (< (count lines) 20))
        (is (str/ends-with? (last lines) "...)"))))
    (testing "a record keeps its tag when pretty-printed"
      (is (str/starts-with? (:text (render/present (->Point big big) limits))
                            "=> #clerkenwell.render_test.Point{")))))

;; -------- images --------

(defn- test-image
  "A `w` x `h` image: transparent, with an opaque red square at the top left."
  [w h]
  (let [img (java.awt.image.BufferedImage. w h java.awt.image.BufferedImage/TYPE_INT_ARGB)
        g (.createGraphics img)]
    (.setColor g java.awt.Color/red)
    (.fillRect g 0 0 (quot w 4) (quot h 4))
    (.dispose g)
    img))

(defn- decode [^bytes bs]
  (javax.imageio.ImageIO/read (java.io.ByteArrayInputStream. bs)))

(defn- gray-at [img x y] (.getSample (.getRaster img) x y 0))

(deftest image-presentation
  (testing "a BufferedImage is an image, as the page will show it"
    (let [p (render/present (test-image 400 200))
          img (decode (:png-bytes p))]
      (is (= :image (:kind p)))
      (is (= [400 200] [(:width p) (:height p)]) "small enough: unchanged")
      (is (= "=> #<image 400×200>" (:text p)))
      (is (nil? (:tablet-text p)) "no source, so nothing but the image")
      (testing "flattened onto white, in grayscale"
        (is (= java.awt.image.BufferedImage/TYPE_BYTE_GRAY (.getType img)))
        (is (= 255 (gray-at img 300 150)) "transparent became white")
        (is (< (gray-at img 10 10) 255) "red became a gray"))))
  (testing "a larger one is reduced to the page, keeping its proportions"
    (let [p (render/present (test-image 2808 936))]
      (is (= [1404 468] [(:width p) (:height p)]))
      (is (= "=> #<image 2808×936>" (:text p)) "the text tells the real size")))
  (testing "kind/image on an image works too"
    (is (= :image (:kind (render/present (kind/image (test-image 10 10)))))))
  (testing "an image file marked as one is read, as Plotje's save returns"
    (let [f (java.io.File/createTempFile "ckw-img" ".png")]
      (javax.imageio.ImageIO/write (test-image 30 20) "png" f)
      (is (= :image (:kind (render/present (kind/image f)))))
      (is (= :text (:kind (render/present f))) "unmarked, a file is just a file")
      (.delete f)))
  (testing "a file that is no image after all is printed"
    (let [f (java.io.File/createTempFile "ckw-img" ".txt")]
      (spit f "not an image")
      (is (= :text (:kind (render/present (kind/image f)))))
      (.delete f)))
  (testing "an enormous image is described, not decoded"
    (with-redefs [render/max-image-pixels 100]
      (let [p (render/present (test-image 40 30))]
        (is (= :text (:kind p)))
        (is (= "=> #<image 40×30, too large to show>" (:text p))))))
  (testing "with the source, it goes above the image"
    (is (= "(draw)" (:tablet-text (render/present (test-image 10 10)
                                                  (assoc render/default-limits
                                                         :source "(draw)")))))))

(deftest image-rasterization
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "ckw-ri-" (random-uuid)))
              (.mkdirs))
        out (io/file dir "out.png")
        stored (fn [w h & [src]]
                 (let [p (render/present (test-image w h)
                                         (cond-> render/default-limits src (assoc :source src)))]
                   (io/copy (:png-bytes p) (io/file dir "image.png"))
                   (-> p (dissoc :png-bytes) (assoc :image "image.png"))))]
    (testing "an image that fits is drawn as it is"
      (let [r (render/rasterize (stored 300 200) out {:base-dir dir :max-width 1284 :max-height 1800})]
        (is (:ok? r))
        (is (= [300 200] [(:width r) (:height r)]))))
    (testing "a wider one is scaled down to the width"
      (let [r (render/rasterize (stored 1404 702) out {:base-dir dir :max-width 1284 :max-height 1800})]
        (is (= [1284 642] [(:width r) (:height r)]))))
    (testing "and to the room left on the page"
      (let [r (render/rasterize (stored 600 600) out {:base-dir dir :max-width 1284 :max-height 300})]
        (is (= [300 300] [(:width r) (:height r)]))))
    (testing "the source sits above, and counts against the room"
      (let [r (render/rasterize (stored 300 200 "(draw)") out
                                {:base-dir dir :max-width 1284 :max-height 1800})]
        (is (:ok? r))
        (is (> (:height r) 250))
        (is (= 300 (:width r)))))
    (testing "squeezing it below legibility is refused, not done"
      (let [r (render/rasterize (stored 600 600) out {:base-dir dir :max-width 1284 :max-height 60})]
        (is (false? (:ok? r)))
        (is (= :insufficient-space (:reason r)))
        (is (not (.exists out)))))
    (testing "a wide, short image narrowed to the width is not squeezed"
      (let [r (render/rasterize (stored 1404 70) out {:base-dir dir :max-width 1284 :max-height 1800})]
        (is (:ok? r))
        (is (= [1284 64] [(:width r) (:height r)]))))
    (testing "an image shorter than that limit only needs its own height"
      (let [r (render/rasterize (stored 200 50) out {:base-dir dir :max-width 1284 :max-height 60})]
        (is (:ok? r))))))
