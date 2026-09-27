(ns clerkenwell.watch-test
  "The SSH calls themselves need a device; these cover the logic around them."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clerkenwell.ink :as ink]
            [clerkenwell.lines :as lines]
            [clerkenwell.store :as store]
            [clerkenwell.watch :as watch])
  (:import [java.nio.file Files]))

(def fixture "test/resources/sample-page.rm")

(defn- tmp-nb []
  (store/notebook-dir
   (doto (io/file (System/getProperty "java.io.tmpdir") (str "ckw-w-" (random-uuid)))
     (.mkdirs))
   "nb-1"))

(defn- fixture-bytes [] (Files/readAllBytes (.toPath (io/file fixture))))

(def doc "f58d5b10-01db-46f9-ad1e-8dce7da245b0")
(def page-a "78429c78-bc3b-4484-a3b6-78064a17f55b")
(def page-b "1b6b7032-617d-4657-bac7-5d0d5568504f")

(deftest identities-are-validated-before-reaching-a-command
  (testing "a malformed notebook id is refused rather than interpolated"
    (doseq [bad ["../../etc" "nb-1; rm -rf /" "" nil]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (watch/remote-digests "no-such-host" bad))))))

(deftest processes-every-changed-page
  (testing "not just one per pass, which is what Edsger did"
    (let [nb (tmp-nb)
          bs (fixture-bytes)
          parsed (lines/parse bs)
          pulled (atom [])]
      (with-redefs [watch/remote-digests (fn [_ _] {page-a "aaa" page-b "bbb"})
                    watch/stable-pull! (fn [_ _ page-id _]
                                         (swap! pulled conj page-id)
                                         {:ok? true :bytes bs :parsed parsed
                                          :digest (if (= page-id page-a) "aaa" "bbb")})]
        (let [r (watch/poll-once! {:nb nb :ssh-host "h" :notebook-id doc
                                   :seen {} :config nil})]
          (is (= #{page-a page-b} (set @pulled)))
          (is (= 2 (count (:recorded r))))
          (testing "both pages are now marked seen"
            (is (= {page-a "aaa" page-b "bbb"} (:seen r)))))))))

(deftest unchanged-pages-are-not-pulled
  (let [nb (tmp-nb)
        pulled (atom [])]
    (with-redefs [watch/remote-digests (fn [_ _] {page-a "aaa"})
                  watch/stable-pull! (fn [_ _ p _] (swap! pulled conj p)
                                       {:ok? false :reason :no-page-file})]
      (watch/poll-once! {:nb nb :ssh-host "h" :notebook-id doc
                         :seen {page-a "aaa"} :config nil})
      (is (empty? @pulled) "a page whose digest is unchanged is skipped entirely"))))

(deftest a-page-that-will-not-settle-is-retried-next-pass
  (testing "an unsettled page must not be marked seen, or its ink is lost"
    (let [nb (tmp-nb)]
      (with-redefs [watch/remote-digests (fn [_ _] {page-a "aaa"})
                    watch/stable-pull! (fn [_ _ _ _] {:ok? false :reason :would-not-settle})]
        (let [r (watch/poll-once! {:nb nb :ssh-host "h" :notebook-id doc
                                   :seen {} :config nil})]
          (is (empty? (:recorded r)))
          (is (= 1 (count (:skipped r))))
          (is (= :would-not-settle (:reason (first (:skipped r)))))
          (is (nil? (get (:seen r) page-a))
              "not seen, so the next pass tries again"))))))

(deftest a-blank-page-is-distinct-from-a-failure
  (let [nb (tmp-nb)]
    (with-redefs [watch/remote-digests (fn [_ _] {page-a "aaa"})
                  watch/stable-pull! (fn [_ _ _ _] {:ok? false :reason :no-page-file})]
      (let [r (watch/poll-once! {:nb nb :ssh-host "h" :notebook-id doc
                                 :seen {} :config nil})]
        (is (= :no-page-file (:reason (first (:skipped r))))
            "a blank page reports as blank, not as a transfer error")))))

(deftest a-save-with-no-new-ink-is-seen-but-records-nothing
  (testing "which is what an inserted result image produces"
    (let [nb (tmp-nb)
          bs (fixture-bytes)
          parsed (lines/parse bs)]
      (with-redefs [watch/remote-digests (fn [_ _] {page-a "aaa"})
                    watch/stable-pull! (fn [_ _ _ _]
                                         {:ok? true :bytes bs :parsed parsed :digest "aaa"})]
        (watch/poll-once! {:nb nb :ssh-host "h" :notebook-id doc :seen {} :config nil}))
      ;; Second pass: new digest, same strokes.
      (let [bs2 (byte-array (concat (seq bs) [0]))]
        (with-redefs [watch/remote-digests (fn [_ _] {page-a "bbb"})
                      watch/stable-pull! (fn [_ _ _ _]
                                           {:ok? true :bytes bs2 :parsed parsed :digest "bbb"})]
          (let [r (watch/poll-once! {:nb nb :ssh-host "h" :notebook-id doc
                                     :seen {page-a "aaa"} :config nil})]
            (is (empty? (:recorded r)))
            (is (= 1 (count (:unchanged r))))
            (is (= "bbb" (get (:seen r) page-a))
                "still marked seen, so we do not re-pull it forever")))))))

(deftest observations-feed-placement
  (testing "ink-bottom becomes available after a pass, which is the whole point"
    (let [nb (tmp-nb)
          bs (fixture-bytes)
          parsed (lines/parse bs)]
      (is (nil? (ink/ink-bottom nb page-a)))
      (with-redefs [watch/remote-digests (fn [_ _] {page-a "aaa"})
                    watch/stable-pull! (fn [_ _ _ _]
                                         {:ok? true :bytes bs :parsed parsed :digest "aaa"})]
        (watch/poll-once! {:nb nb :ssh-host "h" :notebook-id doc :seen {} :config nil}))
      (is (< 319.53 (ink/ink-bottom nb page-a) 319.54)))))
