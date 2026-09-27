(ns clerkenwell.core-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clerkenwell.core :as core]
            [clerkenwell.placement :as placement]
            [clerkenwell.store :as store]))

(defn- tmp-root []
  (doto (io/file (System/getProperty "java.io.tmpdir") (str "ckw-core-" (random-uuid)))
    (.mkdirs)))

;; -------- reading exactly one form --------

(deftest single-form-validation
  (testing "one form reads"
    (is (= '(+ 1 2) (:ok (core/read-single-form "(+ 1 2)" 'user)))))
  (testing "surrounding whitespace and comments are fine"
    (is (= '(+ 1 2) (:ok (core/read-single-form "  ; note\n (+ 1 2) \n" 'user)))))
  (testing "empty input is rejected"
    (is (= :empty (:error (core/read-single-form "" 'user))))
    (is (= :empty (:error (core/read-single-form "   \n  " 'user)))))
  (testing "extra forms are rejected -- read-string alone would accept this"
    (is (= :extra-forms (:error (core/read-single-form "(+ 1 2) (+ 3 4)" 'user))))
    (is (= :extra-forms (:error (core/read-single-form "1 2" 'user)))))
  (testing "unreadable input is rejected"
    (is (= :unreadable (:error (core/read-single-form "(+ 1" 'user)))))
  (testing "a do form carrying several operations is one form"
    (is (= 'do (first (:ok (core/read-single-form "(do (+ 1 2) (+ 3 4))" 'user))))))
  (testing "reader evaluation is disabled"
    (let [r (core/read-single-form "#=(System/exit 1)" 'user)]
      (is (= :unreadable (:error r))))))

;; -------- at most one evaluation --------

(def ^:dynamic *side-effects* nil)

(deftest side-effecting-form-runs-exactly-once
  (testing "a submission evaluates its form once, and retries never re-evaluate"
    (let [root (tmp-root)
          counter (atom 0)]
      (intern 'user 'ckw-counter counter)
      (let [r (core/submit! {:state-root root
                             :notebook-id "nb-1" :page-id "page-1"
                             :source "(swap! user/ckw-counter inc)"
                             :namespace "user"
                             :ink-bottom nil :outputs-bottom nil})]
        (is (:ok? r))
        (is (= 1 @counter))
        (testing "delivery retries touch delivery state only"
          (let [nb (store/notebook-dir root "nb-1")]
            (store/set-delivery! nb (:result-id r) :failed "transfer blew up")
            (store/set-delivery! nb (:result-id r) :queued)
            (store/set-delivery! nb (:result-id r) :transferred)
            (is (= 1 @counter) "no re-evaluation")))))))

(deftest nil-and-false-are-committed-results
  (let [root (tmp-root)]
    (doseq [[src expected] [["nil" "=> nil"] ["false" "=> false"]]]
      (let [r (core/submit! {:state-root root :notebook-id "nb-1" :page-id "page-1"
                             :source src :namespace "user"
                             :ink-bottom nil :outputs-bottom nil})]
        (is (:ok? r) (str src " should commit"))
        (is (= expected (get-in r [:presentation :text])))))))

(deftest infinite-sequence-is-bounded
  (let [root (tmp-root)
        r (core/submit! {:state-root root :notebook-id "nb-1" :page-id "page-1"
                         :source "(range)" :namespace "user"
                         :ink-bottom nil :outputs-bottom nil})]
    (is (:ok? r))
    (is (< (count (get-in r [:presentation :text])) 11000))))

(deftest evaluation-error-keeps-the-draft-and-sends-nothing
  (let [root (tmp-root)
        r (core/submit! {:state-root root :notebook-id "nb-1" :page-id "page-1"
                         :source "(/ 1 0)" :namespace "user"
                         :ink-bottom nil :outputs-bottom nil})]
    (is (false? (:ok? r)))
    (is (= :eval (:stage r)))
    (is (:result-id r) "the id exists even though evaluation failed")
    (testing "no result record was committed"
      (is (empty? (store/read-manifest (store/notebook-dir root "nb-1")))))))

(deftest rejected-input-never-evaluates
  (let [root (tmp-root)
        counter (atom 0)]
    (intern 'user 'ckw-counter2 counter)
    (let [r (core/submit! {:state-root root :notebook-id "nb-1" :page-id "page-1"
                           :source "(swap! user/ckw-counter2 inc) (swap! user/ckw-counter2 inc)"
                           :namespace "user"
                           :ink-bottom nil :outputs-bottom nil})]
      (is (false? (:ok? r)))
      (is (= :extra-forms (:reason r)))
      (is (zero? @counter) "rejection happens before evaluation"))))

;; -------- placement --------

(deftest placement-accounts-for-previous-outputs
  (let [cfg placement/default-config]
    (testing "an empty page starts at the top margin"
      (is (= 60 (placement/output-top nil nil cfg))))
    (testing "below the handwriting"
      (is (= 320 (placement/output-top 300 nil cfg))))
    (testing "below previous outputs when there is no new ink"
      (is (= 520 (placement/output-top 300 500 cfg))))
    (testing "consecutive results without new handwriting cannot overlap"
      (let [first-top (placement/output-top 300 nil cfg)
            first-bottom (+ first-top 100)
            second-top (placement/output-top 300 first-bottom cfg)]
        (is (>= second-top first-bottom))))))

(deftest placement-refuses-overflow
  (testing "too tall for the remaining page is refused, not cropped"
    (let [r (placement/place {:ink-bottom 1700 :outputs-bottom nil
                              :width 400 :height 400 :config nil})]
      (is (false? (:ok? r)))
      (is (= :insufficient-space (:reason r)))
      (is (= :height (:axis r)))))
  (testing "too wide is refused"
    (let [r (placement/place {:ink-bottom nil :outputs-bottom nil
                              :width 1400 :height 100 :config nil})]
      (is (false? (:ok? r)))
      (is (= :width (:axis r)))))
  (testing "a result that fits is placed at the left margin"
    (let [r (placement/place {:ink-bottom 300 :outputs-bottom nil
                              :width 400 :height 100 :config nil})]
      (is (:ok? r))
      (is (= 60 (:dx r)))
      (is (= 320 (:dy r)))
      (is (= 420 (:bottom r))))))

(deftest strokes-bottom-uses-all-points
  (is (nil? (placement/strokes-bottom [])))
  (is (nil? (placement/strokes-bottom [{:points []}])))
  (is (= 42 (placement/strokes-bottom [{:points [{:y 10} {:y 42}]}
                                       {:points [{:y 7}]}]))))
