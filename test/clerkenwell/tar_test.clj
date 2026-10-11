(ns clerkenwell.tar-test
  (:require [clojure.test :refer [deftest is testing]]
            [clerkenwell.tar :as tar]))

(defn- round-trip [entries]
  (map (fn [[p bs]] [p (vec bs)]) (tar/read-entries (tar/write entries))))

(deftest files-survive-a-round-trip
  (let [entries [["events/1.edn" (.getBytes "{:a 1}")]
                 ["results/x/result.png" (byte-array (map unchecked-byte (range 700)))]
                 ["empty" (byte-array 0)]]]
    (is (= (map (fn [[p bs]] [p (vec bs)]) entries) (round-trip entries)))))

(deftest long-names-are-kept-whole
  (testing "a page snapshot's path is longer than the 100 bytes a header holds"
    (let [p (str "pages/0c51c0c2-5063-4612-abb8-e45137017d73/" (apply str (repeat 64 "a")) ".rm")]
      (is (> (count p) 100))
      (is (= [[p [1 2 3]]] (round-trip [[p (byte-array [1 2 3])]]))))))

(deftest an-archive-is-whole-blocks
  (is (zero? (mod (alength (tar/write [["a" (byte-array 3)]])) 512))))
