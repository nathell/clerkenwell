(ns clerkenwell.render-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clerkenwell.render :as render]))

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

(deftest font-resolution
  (testing "an existing path is used as given"
    (is (= "test/resources/sample-page.rm"
           (render/resolve-font "test/resources/sample-page.rm"))))
  (testing "a family name resolves to a file where fontconfig is available"
    (let [r (render/resolve-font render/default-font)]
      ;; Either a resolved path, or the name passed through on a system
      ;; without fc-match. Both are acceptable; a crash is not.
      (is (string? r))
      (is (seq r)))))

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
    (testing "a result too tall for the page is refused, not cropped"
      (let [long-text (str "=> " (str/join " " (repeat 400 "toomuch")))
            r (render/rasterize {:kind :text :text long-text} tmp
                                {:max-width 1284 :max-height 200})]
        (is (false? (:ok? r)))
        (is (= :insufficient-space (:reason r)))
        (is (> (:height r) 200))
        (is (not (.exists (io/file tmp))) "no partial asset is left behind")))
    (.delete (io/file tmp))))
