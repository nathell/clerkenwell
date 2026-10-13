(ns clerkenwell.session-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clerkenwell.ink :as ink]
            [clerkenwell.lines :as lines]
            [clerkenwell.mirror :as mirror]
            [clerkenwell.session :as session]
            [clerkenwell.store :as store]
            [clerkenwell.transport :as transport])
  (:import [java.nio.file Files]))

(def doc "f58d5b10-01db-46f9-ad1e-8dce7da245b0")
(def page "78429c78-bc3b-4484-a3b6-78064a17f55b")
;; A fresh port per test: the JDK's HttpServer does not set SO_REUSEADDR, so
;; rebinding one port across start/stop cycles intermittently hits TIME_WAIT.
(defonce ^:private port-counter (atom 14555))
(defn- next-port [] (swap! port-counter inc))
(def ^:dynamic port nil)

(defn- tmp-root []
  (doto (io/file (System/getProperty "java.io.tmpdir") (str "ckw-s-" (random-uuid)))
    (.mkdirs)))

(defn- start!
  "Start a connection with a directory standing in for the device, unless the
   test says otherwise: without a reachable device, nothing can be written."
  [opts]
  (session/start! (merge {:device {:local-root (str (tmp-root))}} opts)))

(defn- report-identity!
  "Stand in for the adapter's poll, which is what tells the host what is open."
  []
  (reset! transport/active-page
          {:doc doc :page page :instance "test" :at (System/currentTimeMillis)}))

(defn- eventually
  "Whether `pred` holds within a few seconds: the device's copy is kept up to
   date in the background."
  ([pred] (eventually pred 5))
  ([pred seconds]
  (loop [n 0]
    (cond (pred) true
          (> n (* 10 seconds)) false
          :else (do (Thread/sleep 100) (recur (inc n)))))))

(use-fixtures :each (fn [f]
                      (session/stop!)
                      (reset! transport/active-page nil)
                      (binding [port (next-port)] (f))
                      (session/stop!)))

(deftest start-is-idempotent
  (testing "re-opening the buffer against a live connection is not an error"
    (let [root (str (tmp-root))
          a (start! {:state-root root :ssh-host "no-host" :port port})
          b (start! {:state-root root :ssh-host "no-host" :port port})]
      (is (:ok? a))
      (is (false? (:already-running? a)))
      (is (:ok? b))
      (is (true? (:already-running? b)))
      (testing "and the second call did not replace the running server"
        (is (= (:port a) (:port b))))
      (testing "the state root is reported absolute, as Emacs resolves it elsewhere"
        (is (= (.getAbsolutePath (java.io.File. root)) (:state-root a)))))))

(deftest submit-requires-a-connection
  (let [r (session/submit "(+ 1 2)" "user")]
    (is (false? (:ok? r)))
    (is (= :connection (:stage r)))))

(deftest submit-refuses-a-stale-identity
  (testing "aiming at a guessed page is worse than not submitting"
    (start! {:state-root (str (tmp-root)) :ssh-host "no-host" :port port})
    (reset! transport/active-page
            {:doc doc :page page :at (- (System/currentTimeMillis) 60000)})
    (let [r (session/submit "(+ 1 2)" "user")]
      (is (false? (:ok? r)))
      (is (= :identity (:stage r))))))

(deftest submit-refuses-an-unloaded-namespace
  (start! {:state-root (str (tmp-root)) :ssh-host "no-host" :port port})
  (report-identity!)
  (let [r (session/submit "(+ 1 2)" "no.such.namespace")]
    (is (false? (:ok? r)))
    (is (= :namespace (:stage r)))))

(deftest evaluates-in-this-jvm
  (testing "which is the whole point of running inside the user's REPL"
    (let [root (str (tmp-root))]
      (start! {:state-root root :ssh-host "no-host" :port port})
      (report-identity!)
      ;; A var that only exists in THIS process.
      (intern 'user 'ckw-session-marker 4242)
      (let [r (session/submit "user/ckw-session-marker" "user")]
        (is (:ok? r))
        (is (= "=> 4242" (:text r)))))))

(deftest submit-does-not-wait-for-delivery
  (testing "the transfer is SSH and can stall; it must not block the editor"
    (let [root (str (tmp-root))]
      (start! {:state-root root :ssh-host "definitely-no-such-host" :port port})
      (report-identity!)
      (let [started (System/currentTimeMillis)
            r (session/submit "(+ 1 2)" "user")
            elapsed (- (System/currentTimeMillis) started)
            nb (store/notebook-dir root doc)]
        (is (:ok? r) "the computation committed")
        (is (= :queued (:delivery r)) "delivery is pending, not attempted inline")
        (is (< elapsed 5000)
            "submit returned without waiting on an unreachable host")
        (testing "and the result is recorded, awaiting delivery"
          (is (= "=> 3" (get-in (get (store/read-manifest nb) (:result-id r))
                                [:presentation :text]))))))))

(deftest a-failed-push-leaves-the-result-deliverable
  (testing "a transfer failure is recorded on delivery state, never on the result"
    (let [root (str (tmp-root))
          nb (store/notebook-dir root doc)]
      (start! {:state-root root :ssh-host "no-host" :port port})
      (report-identity!)
      (let [r (session/submit "(+ 1 2)" "user")]
        ;; push-asset! refuses outright when the asset is not on the host, which
        ;; exercises the failure path without needing the network.
        (is (thrown? clojure.lang.ExceptionInfo
                     (transport/push-asset! nb "no-such-result-id" {:ssh-host "no-host"})))
        (testing "the committed result is untouched by delivery trouble"
          (is (= "=> 3" (get-in (get (store/read-manifest nb) (:result-id r))
                                [:presentation :text]))))))))

(deftest one-evaluation-at-a-time
  (testing "a second submission while one is in flight is refused"
    (let [root (str (tmp-root))]
      (start! {:state-root root :ssh-host "no-host" :port port})
      (report-identity!)
      (intern 'user 'ckw-gate (promise))
      (let [other (future (session/submit "(deref user/ckw-gate 2000 :timeout)" "user"))]
        (Thread/sleep 300)
        (let [blocked (session/submit "(+ 1 2)" "user")]
          (is (false? (:ok? blocked)))
          (is (= :connection (:stage blocked))))
        (deliver @(resolve 'user/ckw-gate) :done)
        @other))))

(deftest submit-controls-what-the-page-receives
  (let [root (str (tmp-root))
        nb (store/notebook-dir root doc)]
    (start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (testing "by default the page gets the result only"
      (let [r (session/submit "(+ 1 2)" "user")]
        (is (= "=> 3" (get-in r [:presentation :tablet-text])))))
    (testing "with :include-source? the expression goes above it"
      (let [r (session/submit "(+ 1 2)" "user" {:include-source? true})]
        (is (= "(+ 1 2)\n=> 3" (get-in r [:presentation :tablet-text])))
        (is (= "=> 3" (:text r)) "Emacs still shows the result alone")))))

(deftest hidden-values-reach-history-but-not-the-device
  (let [root (str (tmp-root))]
    (start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (let [delivered (atom 0)]
      (with-redefs [transport/push-asset! (fn [& _] (swap! delivered inc))]
        (let [r (session/submit "^:kind/hidden (+ 1 2)" "user")]
          (is (:ok? r))
          (is (= :skipped (:delivery r)))
          (Thread/sleep 100)
          (is (= 0 @delivered) "nothing to push"))))
    (let [e (last (:entries (session/history)))]
      (is (= :hidden (get-in e [:presentation :kind])))
      (is (= :skipped (:delivery e))))))

(deftest image-results-reach-history-with-their-file
  (let [root (str (tmp-root))]
    (start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (with-redefs [transport/push-asset! (fn [& _])]
      (let [r (session/submit "(java.awt.image.BufferedImage. 2000 100 java.awt.image.BufferedImage/TYPE_INT_RGB)" "user")]
        (is (:ok? r) (pr-str (dissoc r :record)))))
    (let [e (last (:entries (session/history)))]
      (is (= :image (get-in e [:presentation :kind])))
      (is (.exists (io/file (:image e))))
      (is (.isAbsolute (io/file (:image e))))
      (is (= 1284 (:page-width e)) "as wide as the page allows, not the stored 1404"))))

(deftest settings-reach-presentation-and-placement
  (let [root (str (tmp-root))
        seen (atom [])
        settings {:style {:font-size 32}
                  :limits {:print-length 5}
                  :layout {:strict-height? false :left-margin 100}}]
    (start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (with-redefs [transport/push-asset! (fn [& _])
                  clerkenwell.core/submit! (let [orig clerkenwell.core/submit!]
                                             (fn [args]
                                               (swap! seen conj (select-keys args [:style :limits :config]))
                                               (orig args)))]
      (session/submit "1" "user" {:settings settings})
      (session/submit "2" "user"))
    (is (= [{:style {:font-size 32} :limits {:print-length 5}
             :config {:strict-height? false :left-margin 100}}
            {:style nil :limits nil :config nil}]
           @seen)
        "passed on when given; otherwise the defaults stand")
    (testing "and they are applied"
      (let [r (session/submit "(range 100)" "user" {:settings settings})]
        (is (= "=> (0 1 2 3 4 ...)" (:text r)))
        (is (= 100 (get-in r [:placement :dx])))))))

(deftest history-interleaves-ink-and-computation
  (let [root (str (tmp-root))
        nb (store/notebook-dir root doc)
        bs (Files/readAllBytes (.toPath (io/file "test/resources/sample-page.rm")))]
    (start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    ;; Ink observed first, then a computation.
    (ink/observe! nb {:page-id page :rm-bytes bs
                      :strokes (:strokes (lines/parse bs))})
    (session/submit "(+ 1 2)" "user")
    (let [h (session/history)
          kinds (map :kind (:entries h))]
      (is (:ok? h))
      (testing "both kinds appear, in host observation order"
        (is (= [:handwriting :computation] kinds))
        (is (apply < (map :sequence (:entries h)))))
      (testing "the handwriting entry is sized in page units"
        (let [bb (lines/bbox (filter lines/ink? (:strokes (lines/parse bs))))]
          (is (< (Math/abs (- (:width (ink/extent bb)) (:page-width (first (:entries h)))))
                 0.01)
              "within float precision: the bbox has been through EDN")))
      (testing "the handwriting entry points at a real rendered asset"
        (let [asset (:asset (first (:entries h)))]
          (is (.isAbsolute (io/file asset))
              "relative paths resolve against Emacs's directory, not the JVM's")
          (is (.exists (io/file asset)))))
      (testing "the computation entry carries what the buffer shows"
        (let [c (second (:entries h))]
          (is (= "(+ 1 2)" (:source c)))
          (is (= "=> 3" (:text c)))
          (is (= "user" (:namespace c))))))))

(deftest an-extended-entry-appears-once-in-history
  (let [root (str (tmp-root))
        nb (store/notebook-dir root doc)
        bs (Files/readAllBytes (.toPath (io/file "test/resources/sample-page.rm")))
        line (vec (take 22 (filter lines/ink? (:strokes (lines/parse bs)))))]
    (start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (ink/observe! nb {:page-id page :rm-bytes bs :strokes (subvec line 0 11)})
    (session/submit "(+ 1 2)" "user")
    (let [ext (ink/observe! nb {:page-id page
                                :rm-bytes (byte-array (concat (seq bs) [0]))
                                :strokes line})
          h (:entries (session/history))]
      (testing "in its original place, ahead of the computation"
        (is (= [:handwriting :computation] (map :kind h))))
      (testing "counting every stroke, and showing the merged image"
        (is (= 22 (:strokes (first h))))
        (is (= (.getAbsolutePath (io/file (get-in ext [:asset :path]))) (:asset (first h))))))))

(defn- legacy-observe!
  "Record `strokes` as a separate entry, as observation did before overlapping
   ink extended entries."
  [nb bs strokes]
  (let [snapshot-id (str (random-uuid))
        entry-id (str (random-uuid))]
    (store/publish-bytes! (io/file (store/page-dir nb page) (str snapshot-id ".rm")) bs)
    (ink/render-strokes! strokes (store/handwriting-file nb entry-id))
    (store/append-event! nb {:event :ink-observed :page-id page
                             :entry-id entry-id :snapshot-id snapshot-id
                             :stroke-ids (mapv :item-id strokes)
                             :bbox (lines/bbox strokes)
                             :asset {:path (str "handwriting/" entry-id ".png")}})))

(deftest regroup-merges-split-entries-in-history
  (let [root (str (tmp-root))
        nb (store/notebook-dir root doc)
        bs (Files/readAllBytes (.toPath (io/file "test/resources/sample-page.rm")))
        line (vec (take 22 (filter lines/ink? (:strokes (lines/parse bs)))))]
    (start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (legacy-observe! nb bs (subvec line 0 11))
    (session/submit "(+ 1 2)" "user")
    (legacy-observe! nb bs (subvec line 11))
    (is (= [:handwriting :computation :handwriting]
           (map :kind (:entries (session/history)))))
    (let [r (session/regroup)]
      (is (:ok? r))
      (is (= 1 (:merged r))))
    (let [h (:entries (session/history))]
      (testing "the later entry joins the earlier, which keeps its place"
        (is (= [:handwriting :computation] (map :kind h))))
      (is (= 22 (:strokes (first h))))
      (is (.exists (io/file (:asset (first h))))))))

(deftest archive-sets-history-aside-without-destroying-it
  (let [root (str (tmp-root))
        device (str (tmp-root))
        nb (store/notebook-dir root doc)
        bs (Files/readAllBytes (.toPath (io/file "test/resources/sample-page.rm")))]
    (start! {:state-root root :ssh-host "no-host" :port port
                     :device {:local-root device}})
    (report-identity!)
    (ink/observe! nb {:page-id page :rm-bytes bs :strokes (:strokes (lines/parse bs))})
    (session/submit "(+ 1 2)" "user")
    (is (seq (:entries (session/history))))
    (is (eventually #(.exists (io/file device doc "events"))))
    (let [r (session/archive-notebook!)]
      (is (:ok? r))
      (testing "the old history still exists on disk"
        (is (.isDirectory (io/file (:archived r))))
        (is (.exists (io/file (:archived r) "manifest.edn"))))
      (testing "and the live notebook directory is gone"
        (is (not (.exists nb))))
      (testing "on the device too, where it is set aside the same way"
        (is (not (.exists (io/file device doc))))
        (is (= 1 (count (filter #(.startsWith (.getName %) (str doc ".archived-"))
                                (.listFiles (io/file device))))))))))

(deftest archive-is-refused-when-the-device-copy-cannot-be-moved
  (testing "or attaching again would fetch the whole history back"
    (let [root (str (tmp-root))
          device (str (tmp-root))
          nb (store/notebook-dir root doc)]
      (start! {:state-root root :ssh-host "no-host" :port port :device {:local-root device}})
      (report-identity!)
      (session/submit "(+ 1 2)" "user")
      ;; The device's copy cannot be moved once the notebook was taken.
      (.setWritable (io/file device) false)
      (try
        (let [r (session/archive-notebook!)]
          (is (false? (:ok? r)))
          (is (re-find #"device" (:message r)))
          (is (.exists nb) "the local history is untouched"))
        (finally (.setWritable (io/file device) true))))))

(deftest another-computer-continues-the-session
  (let [device (str (tmp-root))
        here (str (tmp-root))
        there (str (tmp-root))]
    (start! {:state-root here :ssh-host "no-host" :port port
                     :device {:local-root device}})
    (report-identity!)
    (is (:ok? (session/submit "(+ 1 2)" "user")))
    (testing "disconnecting sends what the device does not have yet"
      (session/stop!)
      (is (.exists (io/file device doc "events"))))
    (start! {:state-root there :ssh-host "no-host" :port (next-port)
                     :device {:local-root device}})
    (report-identity!)
    (is (:ok? (session/submit "(* 6 7)" "user")))
    (testing "the other computer's history is there, then this one's"
      (is (= ["(+ 1 2)" "(* 6 7)"]
             (map :source (filter #(= :computation (:kind %))
                                  (:entries (session/history)))))))
    (testing "and the device has both"
      (session/stop!)
      (is (= 2 (count (filter #(= :result-created (:event %))
                              (store/read-events (io/file device doc)))))))))

(deftest archive-without-a-notebook-is-not-an-error
  (let [root (str (tmp-root))]
    (start! {:state-root root :ssh-host "no-host" :port port})
    (let [r (session/archive-notebook!)]
      (is (false? (:ok? r)))
      (is (= "no notebook linked" (:message r))))))

(deftest status-reports-what-the-buffer-needs
  (let [root (str (tmp-root))]
    (is (false? (:running? (session/status))))
    (start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (session/submit "(+ 1 2)" "user")
    (let [s (session/status)]
      (is (:running? s))
      (is (= doc (:notebook-id s)))
      (is (= page (:page (:active s))))
      (is (= {:queued 1} (:deliveries s)))
      (is (empty? (:uncertain s))))))

;; -------- a notebook's identity --------

(def other-doc "6a1f0c3e-7d2b-4c59-9e8a-0b4d2c7f1e93")

(deftest a-notebook-remembers-its-namespace
  (let [root (str (tmp-root))]
    (create-ns 'ckw.elsewhere)
    (start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (testing "a new one takes the namespace it is given"
      (is (= "=> \"user\"" (:text (session/submit "(clojure.core/str clojure.core/*ns*)" "user")))))
    (testing "after which that is the one it is evaluated in"
      (is (= "=> \"user\"" (:text (session/submit "(clojure.core/str clojure.core/*ns*)" "ckw.elsewhere"))))
      (is (= "user" (:namespace (session/history-version)))))
    (testing "until it is changed"
      (is (:ok? (session/set-namespace! "ckw.elsewhere" doc)))
      (is (= "=> \"ckw.elsewhere\"" (:text (session/submit "(clojure.core/str clojure.core/*ns*)" "user"))))
      (is (= "ckw.elsewhere" (:namespace (session/status)))))
    (testing "only on the notebook the tablet has open"
      (is (false? (:ok? (session/set-namespace! "user" other-doc)))))))

(deftest a-submission-for-another-notebook-is-refused
  (let [root (str (tmp-root))]
    (start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (intern 'user 'ckw-identity-marker (atom 0))
    (let [r (session/submit "(swap! user/ckw-identity-marker inc)" "user"
                            {:notebook-id other-doc})]
      (is (false? (:ok? r)))
      (is (= :identity (:stage r)))
      (is (zero? @@(resolve 'user/ckw-identity-marker))))))

(deftest a-notebook-the-tablet-has-left-keeps-its-history
  (let [root (str (tmp-root))]
    (start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (is (:ok? (session/submit "(+ 1 2)" "user")))
    (reset! transport/active-page {:doc other-doc :page page :instance "test"
                                   :at (System/currentTimeMillis)})
    (is (eventually #(= other-doc (:notebook-id (session/history-version)))))
    (is (empty? (:entries (session/history))))
    (is (= ["(+ 1 2)"] (map :source (:entries (session/history doc)))))
    (is (pos? (:version (session/history-version doc))))))

(deftest the-tablet-says-what-a-notebook-is-called
  (let [xochitl (tmp-root)]
    (spit (io/file xochitl (str doc ".metadata")) "{\"visibleName\": \"Fourier scratch\"}")
    (start! {:state-root (str (tmp-root)) :ssh-host "no-host" :port port
             :device {:local-root (str (tmp-root)) :xochitl-root (str xochitl)}})
    (report-identity!)
    (is (eventually #(= "Fourier scratch" (:name (session/history-version)))))
    (is (= "Fourier scratch" (:name (session/status))))))

;; -------- the lease --------

(defn- elsewhere
  "Another connection, on another computer, holding `doc`'s lease on `device`
   with a session of its own in `root`."
  [device root]
  (let [m (mirror/mirror (store/notebook-dir root doc) doc {:local-root device} "eeee5555")]
    (is (:ok? (mirror/write-lease! m :none)))
    m))

(defn- holder [device] (:token (mirror/read-lease (mirror/mirror (tmp-root) doc {:local-root device} "x"))))

(deftest the-tablet-reporting-here-takes-the-notebook-without-asking
  (let [device (str (tmp-root))
        other (elsewhere device (str (tmp-root)))]
    (start! {:state-root (str (tmp-root)) :ssh-host "no-host" :port port
             :device {:local-root device}})
    (report-identity!)
    (is (:ok? (session/submit "(+ 1 2)" "user")))
    (is (:held? (session/lease)))
    (is (not= "eeee5555" (holder device)))
    (testing "after which the old holder's pushes are refused on the device"
      (store/append-event! (:nb other) {:event :delivery :id "x" :state :queued})
      (store/append-event! (:nb other) {:event :delivery :id "y" :state :queued})
      (is (:lease-lost? (mirror/flush! other))))))

(deftest a-connection-that-lost-the-notebook-stops-writing-until-reported-to-again
  (let [device (str (tmp-root))
        root (str (tmp-root))]
    (start! {:state-root root :ssh-host "no-host" :port port
             :device {:local-root device} :identity-max-age-ms 3000})
    (report-identity!)
    (is (:ok? (session/submit "(+ 1 2)" "user")))
    (let [mine (holder device)
          other (mirror/mirror (store/notebook-dir (str (tmp-root)) doc)
                               doc {:local-root device} "eeee5555")]
      (is (:ok? (mirror/write-lease! other mine)))
      (testing "it notices on its next copy"
        (store/append-event! (store/notebook-dir root doc) {:event :delivery :id "x" :state :queued})
        (is (eventually #(false? (:held? (session/lease))))))
      (testing "and does not take it back on a report from before it lost it"
        (Thread/sleep 1500)
        (is (false? (:held? (session/lease))))
        (is (= "eeee5555" (holder device))))
      (testing "but does on reports after"
        (is (eventually #(do (report-identity!) (:held? (session/lease)))))
        (is (= mine (holder device)))))))

(deftest reports-resuming-after-a-gap-check-the-lease-again
  (testing "another computer may have had the tunnel meanwhile"
    (let [device (str (tmp-root))]
      (start! {:state-root (str (tmp-root)) :ssh-host "no-host" :port port
               :device {:local-root device} :identity-max-age-ms 500})
      (report-identity!)
      (is (:ok? (session/submit "(+ 1 2)" "user")))
      (let [mine (holder device)
            other (mirror/mirror (store/notebook-dir (str (tmp-root)) doc)
                                 doc {:local-root device} "eeee5555")]
        (reset! transport/active-page nil)
        (is (:ok? (mirror/write-lease! other mine)))
        (Thread/sleep 1000)
        (is (eventually #(do (report-identity!)
                             (and (= mine (holder device)) (:held? (session/lease))))))))))

(deftest disconnecting-gives-the-lease-up
  (let [device (str (tmp-root))]
    (start! {:state-root (str (tmp-root)) :ssh-host "no-host" :port port
             :device {:local-root device}})
    (report-identity!)
    (is (:ok? (session/submit "(+ 1 2)" "user")))
    (is (.exists (io/file device doc "lease.edn")))
    (session/stop!)
    (is (not (.exists (io/file device doc "lease.edn"))))
    (testing "having sent everything first"
      (is (seq (.list (io/file device doc "events")))))))
