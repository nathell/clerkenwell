(ns clerkenwell.table-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clerkenwell.render :as render]
            [clerkenwell.table :as table]
            [scicloj.kindly.v4.kind :as kind]
            [tablecloth.api :as tc]))

(def people [{:name "Alice" :age 42} {:name "Bob" :age 37}])

(deftest every-shape-clay-accepts
  (let [expected {:columns ["name" "age"] :rows [["Alice" "42"] ["Bob" "37"]]}]
    (doseq [[label v] [["a sequence of maps" people]
                       ["a map of columns" {:name ["Alice" "Bob"] :age [42 37]}]
                       ["row vectors with names" {:column-names [:name :age]
                                                  :row-vectors [["Alice" 42] ["Bob" 37]]}]
                       ["row maps" {:row-maps people}]
                       ["row maps with names" {:column-names [:name :age] :row-maps people}]
                       ["a dataset" (tc/dataset {:name ["Alice" "Bob"] :age [42 37]})]]]
      (testing label
        (is (= expected (select-keys (table/table-data v) [:columns :rows]))))))
  (testing "a sequence of sequences has no header"
    (is (= {:columns nil :rows [["1" "2"] ["3" "4"]]}
           (select-keys (table/table-data [[1 2] [3 4]]) [:columns :rows]))))
  (testing "rows missing a key leave it blank, and keys come in order of appearance"
    (is (= {:columns ["a" "b"] :rows [["1" ""] ["" "2"]]}
           (select-keys (table/table-data [{:a 1} {:b 2}]) [:columns :rows]))))
  (testing "anything else is no table"
    (is (nil? (table/table-data 42)))
    (is (nil? (table/table-data {:a 1})))
    (is (nil? (table/table-data "text")))))

(deftest cells
  (let [t (table/table-data [{:s "plain" :k :kw :n nil :v [1 2] :q :ns/kw}])]
    (testing "strings plain, nil blank, everything else as Clojure prints it"
      (is (= [["plain" ":kw" "" "[1 2]" ":ns/kw"]] (:rows t))))
    (testing "a plain keyword heading drops its colon; a qualified one keeps it"
      (is (= ["s" "k" "n" "v" "q"] (:columns t)))
      (is (= [":x/y"] (:columns (table/table-data [{:x/y 1}]))))))
  (testing "long cells are capped, and newlines become spaces"
    (let [[[c]] (:rows (table/table-data [[(apply str (repeat 100 "x"))]]))]
      (is (= 40 (count c)))
      (is (str/ends-with? c "…")))
    (is (= [["a b"]] (:rows (table/table-data [["a\n  b"]])))))
  (testing "a column of numbers aligns right; anything mixed, left"
    (is (= [:left :right :left]
           (:align (table/table-data [{:a "x" :b 1 :c 1} {:a "y" :b 2.5 :c "z"}]))))))

(deftest bounds
  (testing "rows beyond the limit are left out, and counted"
    (let [t (table/table-data (vec (for [i (range 100)] {:i i})))]
      (is (= 30 (count (:rows t))))
      (is (= {:rows 70 :columns 0} (:omitted t)))
      (is (str/ends-with? (table/text t) "… 70 more rows"))))
  (testing "a dataset's rows are counted exactly"
    (is (= 70 (get-in (table/table-data (tc/dataset {:x (range 100)})) [:omitted :rows]))))
  (testing "an infinite table is fine; its remainder is just 'more'"
    (let [t (table/table-data (map (fn [i] {:i i}) (range)))]
      (is (= 30 (count (:rows t))))
      (is (= :some (get-in t [:omitted :rows])))
      (is (str/ends-with? (table/text t) "… more rows"))))
  (testing "columns beyond the limit too"
    (let [t (table/table-data [(vec (range 20))])]
      (is (= 12 (count (first (:rows t)))))
      (is (= 8 (get-in t [:omitted :columns])))))
  (testing "a single omitted row is not plural"
    (is (str/ends-with? (table/text (table/table-data (vec (repeat 31 [1])))) "… 1 more row"))))

(deftest text-for-emacs
  (is (= (str "| name  | age |\n"
              "|-------+-----|\n"
              "| Alice |  42 |\n"
              "| Bob   |  37 |")
         (table/text (table/table-data people))))
  (testing "without a header, no rule"
    (is (= "| 1 | 2 |" (table/text (table/table-data [[1 2]]))))))

(defn- width [img] (.getWidth ^java.awt.image.BufferedImage img))

(deftest typeset-for-the-page
  (testing "a narrow table is as wide as it needs"
    (is (< (width (table/image (table/table-data people) 1284)) 500)))
  (testing "a wide one still fits the width"
    (let [t (table/table-data {:column-names (map #(str "column number " %) (range 12))
                               :row-vectors [(map #(str "a value " %) (range 12))]})]
      (is (<= (width (table/image t 1284)) 1284))))
  (testing "a table only slightly too wide is set a little smaller, not wrapped"
    (let [t (table/table-data [{:name "Wolfgang Amadeus Mozart" :era :impressionist}])
          natural (width (table/image t 100000))
          img (table/image t (- natural 20))]
      (is (<= (width img) (- natural 20)))
      (is (< (.getHeight img) 120) "still one line per row")))
  (testing "markup in cells is text, not markup"
    (is (some? (table/image (table/table-data [["<b>&amp;</b>" "\u0001"]]) 1284)))))

(deftest tables-through-present-and-rasterize
  (testing "kind/table makes a table; Emacs gets the text"
    (let [p (render/present (kind/table people))]
      (is (= :table (:kind p)))
      (is (= (table/text (table/table-data people)) (:text p)))))
  (testing "a dataset is a table without asking"
    (is (= :table (:kind (render/present (tc/dataset {:a [1 2]}))))))
  (testing "a sequence of maps without kind/table still prints, as it always has"
    (is (= :text (:kind (render/present people)))))
  (testing "kind/table on something that is no table prints it instead"
    (is (= "=> 42" (:text (render/present (kind/table 42))))))
  (let [out (io/file (System/getProperty "java.io.tmpdir") (str "ckw-tbl-" (random-uuid) ".png"))]
    (testing "drawn beneath its source"
      (let [alone (render/rasterize (render/present (kind/table people)) out
                                    {:max-width 1284 :max-height 1800})
            with-src (render/rasterize (render/present (kind/table people)
                                                       (assoc render/default-limits
                                                              :source "(kind/table people)"))
                                       out {:max-width 1284 :max-height 1800})]
        (is (:ok? alone))
        (is (:ok? with-src))
        (is (> (:height with-src) (:height alone)))))
    (testing "too tall for the room left is refused, like text, not squeezed"
      (let [r (render/rasterize (render/present (kind/table (vec (for [i (range 30)] {:i i}))))
                                out {:max-width 1284 :max-height 300})]
        (is (false? (:ok? r)))
        (is (= :insufficient-space (:reason r)))))
    (.delete out)))
