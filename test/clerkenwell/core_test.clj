(ns clerkenwell.core-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clerkenwell.core :as core]
            [clerkenwell.html :as html]
            [clerkenwell.placement :as placement]
            [clerkenwell.render :as render]
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

(deftest a-hidden-value-is-recorded-but-not-sent
  (let [root (tmp-root)
        nb (store/notebook-dir root "nb-1")]
    (testing "without its source, nothing is drawn or queued"
      (let [r (core/submit! {:state-root root :notebook-id "nb-1" :page-id "page-1"
                             :source "(scicloj.kindly.v4.kind/hidden {:a 1})"
                             :namespace "user" :ink-bottom nil :outputs-bottom nil})]
        (is (:ok? r))
        (is (= :skipped (:delivery r)))
        (is (nil? (:asset r)))
        (is (= :hidden (get-in (store/read-manifest nb) [(:result-id r) :presentation :kind])))
        (is (= :skipped (get-in (store/read-manifest nb) [(:result-id r) :delivery :state])))))
    (testing "with its source, the source alone is sent"
      (let [r (core/submit! {:state-root root :notebook-id "nb-1" :page-id "page-1"
                             :source "^:kind/hidden {:a 1}"
                             :namespace "user" :ink-bottom nil :outputs-bottom nil
                             :include-source? true})]
        (is (:ok? r))
        (is (some? (:asset r)))
        (is (= "^:kind/hidden {:a 1}" (get-in r [:presentation :tablet-text])))))))

(deftest long-values-are-pretty-printed-to-the-page-width
  (let [root (tmp-root)
        r (core/submit! {:state-root root :notebook-id "nb-1" :page-id "page-1"
                         :source "(vec (range 1000 1080))"
                         :namespace "user" :ink-bottom nil :outputs-bottom nil})
        lines (clojure.string/split-lines (get-in r [:presentation :text]))
        width (render/columns (- (:page-width placement/default-config)
                                 (* 2 (:left-margin placement/default-config)))
                              (:font html/default-style) (:font-size html/default-style))]
    (is (:ok? r))
    (is (> (count lines) 1))
    ;; Each element takes five columns, so a full line is within five of the width.
    (is (< (- width 6) (apply max (map count lines)) (inc width))
        "wrapped to what fits the usable page width, not pprint's default 72")))

(deftest an-image-is-kept-and-drawn
  (let [root (tmp-root)
        nb (store/notebook-dir root "nb-1")
        r (core/submit! {:state-root root :notebook-id "nb-1" :page-id "page-1"
                         :source "(java.awt.image.BufferedImage. 200 100 java.awt.image.BufferedImage/TYPE_INT_RGB)"
                         :namespace "user" :ink-bottom nil :outputs-bottom nil})
        rec (get (store/read-manifest nb) (:result-id r))]
    (is (:ok? r))
    (is (= :image (get-in rec [:presentation :kind])))
    (testing "its bytes are a file before the record names it, and not in the record"
      (is (.exists (io/file nb (get-in rec [:presentation :image]))))
      (is (not (contains? (:presentation rec) :png-bytes))))
    (testing "and it is drawn for the page"
      (is (= [200 100] [(get-in r [:asset :width]) (get-in r [:asset :height])])))))

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

(deftest relaxed-height-lets-outputs-run-below-the-screen
  (let [relaxed {:strict-height? false}]
    (testing "what strict height refuses goes below the screen instead"
      (let [r (placement/place {:ink-bottom 1700 :outputs-bottom nil
                                :width 400 :height 400 :config relaxed})]
        (is (:ok? r))
        (is (= 1720 (:dy r)))
        (is (= 2120 (:bottom r)) "past the 1872 of one screen")))
    (testing "the next output follows on below it"
      (is (= 2140 (placement/output-top 1700 2120 relaxed))))
    (testing "handwriting already below the screen is cleared as usual"
      (is (:ok? (placement/place {:ink-bottom 3000 :outputs-bottom nil
                                  :width 400 :height 100 :config relaxed}))))
    (testing "there is still a limit, so no one result is absurdly tall"
      (is (= (- (* 10 1872) 20) (placement/max-bottom relaxed)))
      (is (= :height (:axis (placement/place {:ink-bottom 18000 :outputs-bottom nil
                                              :width 400 :height 1000 :config relaxed})))))
    (testing "width is as strict as ever"
      (is (= :width (:axis (placement/place {:ink-bottom nil :outputs-bottom nil
                                             :width 1400 :height 100 :config relaxed})))))
    (testing "strict is the default"
      (is (= (- 1872 20) (placement/max-bottom nil))))))

(deftest relaxed-height-end-to-end
  (let [root (tmp-root)
        tall "(vec (range 1000 1400))"
        submit #(core/submit! (merge {:state-root root :notebook-id "nb-1" :page-id "page-1"
                                      :source tall :namespace "user"
                                      :ink-bottom 1500 :outputs-bottom nil}
                                     %))]
    (testing "strict: thirty-odd lines will not fit under ink at 1500"
      (is (false? (:ok? (submit {})))))
    (testing "relaxed: they go below the screen"
      (let [r (submit {:config {:strict-height? false}})]
        (is (:ok? r))
        (is (> (get-in r [:placement :bottom]) 1872))))))

(deftest strokes-bottom-uses-all-points
  (is (nil? (placement/strokes-bottom [])))
  (is (nil? (placement/strokes-bottom [{:points []}])))
  (is (= 42 (placement/strokes-bottom [{:points [{:y 10} {:y 42}]}
                                       {:points [{:y 7}]}]))))
