(ns clerkenwell.store-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clerkenwell.store :as store]))

(defn- tmp-root []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "ckw-store-" (random-uuid)))]
    (.mkdirs d)
    d))

(defn- sample [nb-id page-id]
  {:result-id (str (random-uuid))
   :notebook-id nb-id
   :page-id page-id
   :source "(+ 1 2)"
   :namespace "user"
   :presentation {:kind :text :text "=> 3" :truncated? false}
   :asset nil})

(deftest immutable-record-mutable-delivery
  (let [root (tmp-root)
        nb (store/notebook-dir root "nb-1")
        r (store/create-result! nb (sample "nb-1" "page-1"))
        id (:result-id r)]
    (testing "the record lands with its content"
      (is (= "(+ 1 2)" (:source r)))
      (is (= :queued (get-in r [:delivery :state]))))
    (testing "delivery moves without touching the immutable record"
      (store/set-delivery! nb id :transferred)
      (store/set-delivery! nb id :inserted)
      (let [on-disk (edn/read-string
                     (slurp (io/file (store/result-dir nb id) "record.edn")))
            m (store/read-manifest nb)]
        (is (= "(+ 1 2)" (:source on-disk)))
        (is (= :queued (get-in on-disk [:delivery :state]))
            "record.edn keeps the state it was created with")
        (is (= :inserted (get-in m [id :delivery :state]))
            "current delivery state comes from the event log")))))

(deftest rejects-unknown-delivery-state
  (let [root (tmp-root)
        nb (store/notebook-dir root "nb-1")
        r (store/create-result! nb (sample "nb-1" "page-1"))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/set-delivery! nb (:result-id r) :teleported)))))

(deftest recovery-rebuilds-from-events
  (testing "a manifest lost entirely is rebuilt from the log"
    (let [root (tmp-root)
          nb (store/notebook-dir root "nb-1")
          r (store/create-result! nb (sample "nb-1" "page-1"))
          id (:result-id r)]
      (store/set-delivery! nb id :transferred)
      (.delete (io/file nb "manifest.edn"))
      (let [m (store/read-manifest nb)]
        (is (contains? m id))
        (is (= :transferred (get-in m [id :delivery :state])))
        (is (= "(+ 1 2)" (get-in m [id :source])))))))

(deftest tolerates-interrupted-manifest-write
  (testing "a stale manifest is overridden by the event log"
    (let [root (tmp-root)
          nb (store/notebook-dir root "nb-1")
          r (store/create-result! nb (sample "nb-1" "page-1"))
          id (:result-id r)]
      ;; Simulate a crash after the event write but before the manifest write:
      ;; the manifest still shows the older state.
      (let [stale (store/read-manifest nb)]
        (store/set-delivery! nb id :inserted)
        (store/publish! (io/file nb "manifest.edn") (pr-str stale)))
      (is (= :inserted (get-in (store/read-manifest nb) [id :delivery :state]))
          "the log wins over the stale cache"))))

(deftest ignores-partially-published-events
  (testing "a torn event file does not make history unreadable"
    (let [root (tmp-root)
          nb (store/notebook-dir root "nb-1")
          r (store/create-result! nb (sample "nb-1" "page-1"))
          id (:result-id r)]
      (spit (io/file nb "events" "999.edn") "{:event :delivery :id \"x\" :sta")
      (let [m (store/read-manifest nb)]
        (is (contains? m id) "the good events still replay")))))

(deftest recover-marks-uncertain-never-replays
  (testing "a result interrupted mid-insertion becomes :uncertain"
    (let [root (tmp-root)
          nb (store/notebook-dir root "nb-1")
          r (store/create-result! nb (sample "nb-1" "page-1"))
          id (:result-id r)]
      (store/set-delivery! nb id :insertion-started)
      (let [stuck (store/recover! nb)]
        (is (= [id] stuck))
        (is (= :uncertain (get-in (store/read-manifest nb) [id :delivery :state]))))
      (testing "and a second recovery does not resurrect it"
        (is (= [] (store/recover! nb)))))))

(deftest assets-are-immutable-and-hashed
  (let [root (tmp-root)
        nb (store/notebook-dir root "nb-1")
        r (store/create-result! nb (sample "nb-1" "page-1"))
        id (:result-id r)
        src (io/file (System/getProperty "java.io.tmpdir") (str "ckw-a-" (random-uuid) ".png"))]
    (spit src "not-really-a-png-but-bytes-are-bytes")
    (let [a (store/attach-asset! nb id {:path src :width 400 :height 300})
          dest (io/file nb "results" id "result.png")]
      (is (.exists dest))
      (is (= (store/sha256 dest) (:sha256 a)))
      (is (= 400 (:width a)))
      (testing "the asset path is relative to the notebook directory"
        (is (= (str "results/" id "/result.png") (:path a))))
      (testing "a delivery retry reuses the same bytes"
        (let [before (store/sha256 dest)]
          (store/set-delivery! nb id :failed)
          (store/set-delivery! nb id :queued)
          (is (= before (store/sha256 dest))))))
    (.delete src)))

(deftest sequences-are-monotonic
  (let [root (tmp-root)
        nb (store/notebook-dir root "nb-1")]
    (store/create-result! nb (sample "nb-1" "page-1"))
    (store/create-result! nb (sample "nb-1" "page-2"))
    (let [seqs (map :sequence (store/read-events nb))]
      (is (= seqs (sort seqs)))
      (is (apply distinct? seqs)))))
