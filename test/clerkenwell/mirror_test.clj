(ns clerkenwell.mirror-test
  "Runs the device scripts with this machine's sh, against a directory standing
   in for the device."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clerkenwell.mirror :as mirror]
            [clerkenwell.store :as store]))

(def doc "0c51c0c2-5063-4612-abb8-e45137017d73")

(defn- tmp-dir [prefix]
  (doto (io/file (System/getProperty "java.io.tmpdir") (str prefix (random-uuid)))
    (.mkdirs)))

(defn- a-session
  "A session directory with an event, a result, a snapshot, and the derived
   parts that must stay behind."
  []
  (let [nb (store/notebook-dir (tmp-dir "ckw-m-") doc)]
    (store/publish! (io/file nb "results/r1/record.edn") "{:id \"r1\"}")
    (store/publish-bytes! (io/file nb "results/r1/result.png") (byte-array [1 2 3]))
    (store/publish-bytes! (io/file nb "pages/p1" (str (apply str (repeat 64 "a")) ".rm"))
                          (byte-array [4 5 6]))
    (store/append-event! nb {:event :result-created :id "r1"})
    (store/append-event! nb {:event :delivery :id "r1" :state :queued})
    (store/publish! (io/file nb "manifest.edn") "{}")
    (store/publish-bytes! (io/file nb "handwriting/e1.png") (byte-array [7]))
    nb))

(defn- files-under [dir]
  (set (mirror/local-files dir)))

(deftest only-canonical-files-are-copied
  (is (mirror/canonical? "events/12.edn"))
  (is (mirror/canonical? "pages/p1/abc.rm"))
  (is (not (mirror/canonical? "manifest.edn")))
  (is (not (mirror/canonical? "handwriting/e1.png")))
  (is (not (mirror/canonical? "events/.12.edn.tmp")) "a temp file is not")
  (is (not (mirror/canonical? "events/../../etc/passwd"))))

(deftest a-flush-copies-the-session-and-only-once
  (let [nb (a-session)
        device {:local-root (str (tmp-dir "ckw-dev-"))}
        m (mirror/mirror nb doc device)
        on-device (io/file (:local-root device) doc)]
    (is (= {:ok? true :pushed 5} (mirror/flush! m)))
    (is (= (files-under nb) (files-under on-device)))
    (testing "derived parts stay on this computer"
      (is (not (.exists (io/file on-device "manifest.edn"))))
      (is (not (.exists (io/file on-device "handwriting")))))
    (testing "nothing is left half unpacked"
      (is (empty? (filter #(.startsWith (.getName %) ".") (.listFiles on-device)))))
    (testing "the bytes are the same"
      (is (= [1 2 3] (vec (java.nio.file.Files/readAllBytes
                           (.toPath (io/file on-device "results/r1/result.png")))))))
    (testing "a second flush has nothing to send"
      (is (= {:ok? true :pushed 0} (mirror/flush! m))))
    (testing "and a new event is sent on its own"
      (store/append-event! nb {:event :delivery :id "r1" :state :transferred})
      (is (= 1 (:pushed (mirror/flush! m))))
      (is (= 0 (:pending (mirror/status m)))))))

(deftest another-computer-pulls-what-it-lacks
  (let [nb (a-session)
        device {:local-root (str (tmp-dir "ckw-dev-"))}
        _ (mirror/flush! (mirror/mirror nb doc device))
        elsewhere (store/notebook-dir (tmp-dir "ckw-m-") doc)
        m (mirror/mirror elsewhere doc device)]
    (is (= {:ok? true :pulled 5} (mirror/pull! m)))
    (is (= (files-under nb) (files-under elsewhere)))
    (is (= (store/read-events nb) (store/read-events elsewhere)))
    (testing "and the manifest is replayed from what arrived"
      (is (= (store/read-manifest nb) (store/read-manifest elsewhere))))
    (testing "a second pull fetches nothing"
      (is (= {:ok? true :pulled 0} (mirror/pull! m))))))

(deftest a-session-the-device-has-never-seen-pulls-nothing
  (let [m (mirror/mirror (store/notebook-dir (tmp-dir "ckw-m-") doc) doc
                         {:local-root (str (tmp-dir "ckw-dev-"))})]
    (is (= {:ok? true :pulled 0} (mirror/pull! m)))))

(deftest an-unreachable-device-is-reported-and-retried
  (let [nb (a-session)
        m (mirror/mirror nb doc {:ssh-host "definitely-no-such-host"})
        r (mirror/flush! m)]
    (is (false? (:ok? r)))
    (is (re-find #"failed" (:message r)))
    (is (false? (:ok? (mirror/status m))))))

(deftest a-closed-handle-copies-nothing
  (let [nb (a-session)
        device {:local-root (str (tmp-dir "ckw-dev-"))}
        m (mirror/mirror nb doc device)]
    (mirror/close! m)
    (is (false? (:ok? (mirror/flush! m))))
    (is (not (.exists (io/file (:local-root device) doc))))))

(deftest archiving-sets-the-copy-aside
  (let [nb (a-session)
        device {:local-root (str (tmp-dir "ckw-dev-"))}]
    (mirror/flush! (mirror/mirror nb doc device))
    (is (:ok? (mirror/archive! doc device "20261011-120000")))
    (is (not (.exists (io/file (:local-root device) doc))))
    (is (.isDirectory (io/file (:local-root device) (str doc ".archived-20261011-120000"))))
    (testing "archiving a copy that does not exist is not an error"
      (is (:ok? (mirror/archive! doc device "20261011-120001"))))))

(deftest odd-arguments-never-reach-a-shell
  (is (false? (:ok? (mirror/archive! "x; rm -rf /" {:local-root "/tmp"} "1"))))
  (is (false? (:ok? (mirror/archive! doc {:local-root "/tmp"} "../x")))))
