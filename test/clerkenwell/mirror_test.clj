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

(def token "aaaa1111")

(defn- held
  "A handle for `nb` holding its session's lease."
  [nb device]
  (let [m (mirror/mirror nb doc device token)]
    (is (:ok? (mirror/write-lease! m :none)))
    m))

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
        m (held nb device)
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
        _ (mirror/flush! (held nb device))
        elsewhere (store/notebook-dir (tmp-dir "ckw-m-") doc)
        m (mirror/mirror elsewhere doc device "bbbb2222")]
    (is (= {:ok? true :pulled 5} (mirror/pull! m)))
    (is (= (files-under nb) (files-under elsewhere)))
    (is (= (store/read-events nb) (store/read-events elsewhere)))
    (testing "and the manifest is replayed from what arrived"
      (is (= (store/read-manifest nb) (store/read-manifest elsewhere))))
    (testing "a second pull fetches nothing"
      (is (= {:ok? true :pulled 0} (mirror/pull! m))))))

(deftest a-session-the-device-has-never-seen-pulls-nothing
  (let [m (mirror/mirror (store/notebook-dir (tmp-dir "ckw-m-") doc) doc
                         {:local-root (str (tmp-dir "ckw-dev-"))} token)]
    (is (= {:ok? true :pulled 0} (mirror/pull! m)))))

(deftest an-unreachable-device-is-reported-and-retried
  (let [nb (a-session)
        m (mirror/mirror nb doc {:ssh-host "definitely-no-such-host"} token)
        r (mirror/flush! m)]
    (is (false? (:ok? r)))
    (is (re-find #"failed" (:message r)))
    (is (false? (:ok? (mirror/status m))))))

(deftest a-closed-handle-copies-nothing
  (let [nb (a-session)
        device {:local-root (str (tmp-dir "ckw-dev-"))}
        m (held nb device)]
    (mirror/close! m)
    (is (false? (:ok? (mirror/flush! m))))
    (is (not (.exists (io/file (:local-root device) doc "events"))))))

(deftest archiving-sets-the-copy-aside
  (let [nb (a-session)
        device {:local-root (str (tmp-dir "ckw-dev-"))}]
    (mirror/flush! (held nb device))
    (is (:ok? (mirror/archive! doc device "20261011-120000")))
    (is (not (.exists (io/file (:local-root device) doc))))
    (is (.isDirectory (io/file (:local-root device) (str doc ".archived-20261011-120000"))))
    (testing "archiving a copy that does not exist is not an error"
      (is (:ok? (mirror/archive! doc device "20261011-120001"))))))

(deftest odd-arguments-never-reach-a-shell
  (is (false? (:ok? (mirror/archive! "x; rm -rf /" {:local-root "/tmp"} "1"))))
  (is (false? (:ok? (mirror/archive! doc {:local-root "/tmp"} "../x")))))

;; -------- what the tablet calls a notebook --------

(deftest the-name-comes-from-the-notebooks-metadata
  (let [xochitl (tmp-dir "ckw-x-")
        device {:local-root (str (tmp-dir "ckw-d-")) :xochitl-root (str xochitl)}]
    (testing "nil when the tablet has none"
      (is (nil? (mirror/notebook-name device doc))))
    (spit (io/file xochitl (str doc ".metadata"))
          "{\n    \"type\": \"DocumentType\",\n    \"visibleName\": \"Fourier \\\"scratch\\\" \\u00e9\"\n}\n")
    (is (= "Fourier \"scratch\" \u00e9" (mirror/notebook-name device doc)))
    (testing "an odd id never reaches the shell"
      (is (thrown? Exception (mirror/notebook-name device "x; rm -rf /"))))))

;; -------- the lease --------

(deftest the-lease-is-taken-renewed-and-released
  (let [device {:local-root (str (tmp-dir "ckw-dev-"))}
        nb (a-session)
        m (mirror/mirror nb doc device token)
        other (mirror/mirror nb doc device "cccc3333")]
    (is (nil? (mirror/read-lease m)) "nobody holds a new session")
    (is (= {:ok? true} (mirror/write-lease! m :none)))
    (let [l (mirror/read-lease m)]
      (is (= token (:token l)))
      (is (string? (:host l)))
      (is (<= 0 (:age-s l) 2)))
    (testing "taking it as if free fails once it is held, and says by whom"
      (is (= {:ok? false :held-by token} (mirror/write-lease! other :none))))
    (testing "renewing is taking it from oneself"
      (is (:ok? (mirror/write-lease! m token))))
    (testing "only the holder can release it"
      (mirror/release-lease! other)
      (is (= token (:token (mirror/read-lease m))))
      (mirror/release-lease! m)
      (is (nil? (mirror/read-lease m))))))

(deftest a-push-without-the-lease-is-refused-on-the-device
  (let [device {:local-root (str (tmp-dir "ckw-dev-"))}
        nb (a-session)
        holder (held nb device)
        other (mirror/mirror nb doc device "cccc3333")
        r (mirror/flush! other)]
    (is (false? (:ok? r)))
    (is (:lease-lost? r))
    (is (not (.exists (io/file (:local-root device) doc "events"))))
    (testing "and nothing is left unpacked"
      (is (empty? (filter #(.startsWith (.getName %) ".incoming")
                          (.listFiles (io/file (:local-root device) doc))))))
    (testing "until it is taken over, after which the old holder is refused"
      (is (:ok? (mirror/write-lease! other token)))
      (is (:ok? (mirror/flush! other)))
      (store/append-event! nb {:event :delivery :id "r1" :state :transferred})
      (is (:lease-lost? (mirror/flush! holder))))))

(deftest events-that-diverged-are-set-aside-not-lost
  (testing "a computer that lost the lease before its last events reached the device"
    (let [device {:local-root (str (tmp-dir "ckw-dev-"))}
          here (a-session)
          a (held here device)
          _ (mirror/flush! a)
          ;; Another computer continues the session...
          there (store/notebook-dir (tmp-dir "ckw-m-") doc)
          b (mirror/mirror there doc device "bbbb2222")]
      (mirror/pull! b)
      (is (:ok? (mirror/write-lease! b token)))
      (store/append-event! there {:event :delivery :id "r1" :state :transferred})
      (mirror/flush! b)
      ;; ...while this one recorded an event of its own, never sent.
      (store/append-event! here {:event :delivery :id "r1" :state :failed})
      (let [r (mirror/pull! a)]
        (is (= 1 (:stranded r)))
        (testing "the device's version is the one kept"
          (is (= (store/read-events there) (store/read-events here))))
        (testing "and this computer's is still on disk"
          (is (.exists (io/file (:stranded-to r) "events" "3.edn"))))))))

(deftest identical-events-are-not-set-aside
  (testing "an event sent before its confirmation was recorded matches the device's"
    (let [device {:local-root (str (tmp-dir "ckw-dev-"))}
          nb (a-session)
          m (held nb device)]
      (mirror/flush! m)
      (io/delete-file (io/file nb "copied.edn"))
      (let [r (mirror/pull! (mirror/mirror nb doc device token))]
        (is (:ok? r))
        (is (nil? (:stranded r)))))))
