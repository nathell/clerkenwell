(ns clerkenwell.lines-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clerkenwell.lines :as lines]))

(def fixture "test/resources/sample-page.rm")

(deftest parses-a-real-page
  (let [{:keys [strokes other]} (lines/parse-file fixture)]
    (testing "every line block is found"
      (is (= 55 (count strokes))))
    (testing "geometry matches an independent parse of the same file"
      ;; Cross-checked against a separate Python implementation.
      (is (= 895 (reduce + (map (comp count :points) strokes))))
      (let [b (lines/bbox strokes)]
        (is (< -590.62 (:min-x b) -590.61))
        (is (< 180.35 (:max-x b) 180.36))
        (is (< 171.69 (:min-y b) 171.70))
        (is (< 319.53 (:max-y b) 319.54))))
    (testing "non-line blocks are retained rather than dropped"
      (is (pos? (count other))))))

(deftest stroke-identity
  (let [strokes (:strokes (lines/parse-file fixture))
        snap (lines/snapshot strokes)]
    (testing "every stroke carries an item-id"
      (is (= (count strokes) (count snap))))
    (testing "item-ids are values, so they work as map keys"
      (is (every? vector? (keys snap))))))

(deftest erased-strokes-are-not-ink
  (let [strokes (:strokes (lines/parse-file fixture))
        erased (remove lines/ink? strokes)]
    (testing "the fixture contains one fully erased stroke"
      (is (= 1 (count erased)))
      (is (pos? (:deleted-length (first erased)))))
    (testing "an erased stroke is never reported as new ink"
      (is (not-any? #(= (:item-id (first erased)) (:item-id %))
                    (lines/new-strokes #{} strokes))))
    (testing "and so new-strokes sees only the strokes that render"
      (is (= 54 (count (lines/new-strokes #{} strokes)))))))

(deftest diffing-against-a-snapshot
  (let [strokes (:strokes (lines/parse-file fixture))
        all-ids (set (keys (lines/snapshot strokes)))]
    (testing "nothing is new when everything is known"
      (is (empty? (lines/new-strokes all-ids strokes))))
    (testing "forgetting one id makes exactly that stroke new"
      (let [victim (:item-id (first (filter lines/ink? strokes)))
            known (disj all-ids victim)
            fresh (lines/new-strokes known strokes)]
        (is (= 1 (count fresh)))
        (is (= victim (:item-id (first fresh))))))
    (testing "bbox of no strokes is nil, not an error"
      (is (nil? (lines/bbox []))))))

(deftest rejects-malformed-input
  (testing "a non-lines file is rejected by its header"
    (is (thrown? clojure.lang.ExceptionInfo
                 (lines/parse (.getBytes "definitely not a reMarkable file")))))
  (testing "a truncated header is rejected as too short"
    (is (thrown? clojure.lang.ExceptionInfo (lines/parse (byte-array 10))))))
