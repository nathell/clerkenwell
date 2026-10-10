(ns clerkenwell.session-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clerkenwell.ink :as ink]
            [clerkenwell.lines :as lines]
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

(defn- report-identity!
  "Stand in for the adapter's poll, which is what tells the host what is open."
  []
  (reset! transport/active-page
          {:doc doc :page page :instance "test" :at (System/currentTimeMillis)}))

(use-fixtures :each (fn [f]
                      (session/stop!)
                      (reset! transport/active-page nil)
                      (binding [port (next-port)] (f))
                      (session/stop!)))

(deftest start-is-idempotent
  (testing "re-opening the buffer against a live session is not an error"
    (let [root (str (tmp-root))
          a (session/start! {:state-root root :ssh-host "no-host" :port port})
          b (session/start! {:state-root root :ssh-host "no-host" :port port})]
      (is (:ok? a))
      (is (false? (:already-running? a)))
      (is (:ok? b))
      (is (true? (:already-running? b)))
      (testing "and the second call did not replace the running server"
        (is (= (:port a) (:port b)))))))

(deftest submit-requires-a-session
  (let [r (session/submit "(+ 1 2)" "user")]
    (is (false? (:ok? r)))
    (is (= :session (:stage r)))))

(deftest submit-refuses-a-stale-identity
  (testing "aiming at a guessed page is worse than not submitting"
    (session/start! {:state-root (str (tmp-root)) :ssh-host "no-host" :port port})
    (reset! transport/active-page
            {:doc doc :page page :at (- (System/currentTimeMillis) 60000)})
    (let [r (session/submit "(+ 1 2)" "user")]
      (is (false? (:ok? r)))
      (is (= :identity (:stage r))))))

(deftest submit-refuses-an-unloaded-namespace
  (session/start! {:state-root (str (tmp-root)) :ssh-host "no-host" :port port})
  (report-identity!)
  (let [r (session/submit "(+ 1 2)" "no.such.namespace")]
    (is (false? (:ok? r)))
    (is (= :namespace (:stage r)))))

(deftest evaluates-in-this-jvm
  (testing "which is the whole point of running inside the user's REPL"
    (let [root (str (tmp-root))]
      (session/start! {:state-root root :ssh-host "no-host" :port port})
      (report-identity!)
      ;; A var that only exists in THIS process.
      (intern 'user 'ckw-session-marker 4242)
      (let [r (session/submit "user/ckw-session-marker" "user")]
        (is (:ok? r))
        (is (= "=> 4242" (:text r)))))))

(deftest submit-does-not-wait-for-delivery
  (testing "the transfer is SSH and can stall; it must not block the editor"
    (let [root (str (tmp-root))]
      (session/start! {:state-root root :ssh-host "definitely-no-such-host" :port port})
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
      (session/start! {:state-root root :ssh-host "no-host" :port port})
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
      (session/start! {:state-root root :ssh-host "no-host" :port port})
      (report-identity!)
      (intern 'user 'ckw-gate (promise))
      (let [other (future (session/submit "(deref user/ckw-gate 2000 :timeout)" "user"))]
        (Thread/sleep 300)
        (let [blocked (session/submit "(+ 1 2)" "user")]
          (is (false? (:ok? blocked)))
          (is (= :session (:stage blocked))))
        (deliver @(resolve 'user/ckw-gate) :done)
        @other))))

(deftest submit-controls-what-the-page-receives
  (let [root (str (tmp-root))
        nb (store/notebook-dir root doc)]
    (session/start! {:state-root root :ssh-host "no-host" :port port})
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
    (session/start! {:state-root root :ssh-host "no-host" :port port})
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
    (session/start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (with-redefs [transport/push-asset! (fn [& _])]
      (let [r (session/submit "(java.awt.image.BufferedImage. 2000 100 java.awt.image.BufferedImage/TYPE_INT_RGB)" "user")]
        (is (:ok? r) (pr-str (dissoc r :record)))))
    (let [e (last (:entries (session/history)))]
      (is (= :image (get-in e [:presentation :kind])))
      (is (.exists (io/file (:image e))))
      (is (.isAbsolute (io/file (:image e))))
      (is (= 1284 (:page-width e)) "as wide as the page allows, not the stored 1404"))))

(deftest strict-height-reaches-placement
  (let [root (str (tmp-root))
        seen (atom [])]
    (session/start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (with-redefs [transport/push-asset! (fn [& _])
                  clerkenwell.core/submit! (let [orig clerkenwell.core/submit!]
                                             (fn [args] (swap! seen conj (:config args)) (orig args)))]
      (session/submit "1" "user" {:strict-height? false})
      (session/submit "2" "user" {:strict-height? true})
      (session/submit "3" "user"))
    (is (= [{:strict-height? false} {:strict-height? true} nil] @seen)
        "passed on when given; otherwise placement's default stands")))

(deftest history-interleaves-ink-and-computation
  (let [root (str (tmp-root))
        nb (store/notebook-dir root doc)
        bs (Files/readAllBytes (.toPath (io/file "test/resources/sample-page.rm")))]
    (session/start! {:state-root root :ssh-host "no-host" :port port})
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
    (session/start! {:state-root root :ssh-host "no-host" :port port})
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
    (session/start! {:state-root root :ssh-host "no-host" :port port})
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
        nb (store/notebook-dir root doc)
        bs (Files/readAllBytes (.toPath (io/file "test/resources/sample-page.rm")))]
    (session/start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (ink/observe! nb {:page-id page :rm-bytes bs :strokes (:strokes (lines/parse bs))})
    (session/submit "(+ 1 2)" "user")
    (is (seq (:entries (session/history))))
    (let [r (session/archive-notebook!)]
      (is (:ok? r))
      (testing "the old history still exists on disk"
        (is (.isDirectory (io/file (:archived r))))
        (is (.exists (io/file (:archived r) "manifest.edn"))))
      (testing "and the live notebook directory is gone"
        (is (not (.exists nb)))))))

(deftest archive-without-a-notebook-is-not-an-error
  (let [root (str (tmp-root))]
    (session/start! {:state-root root :ssh-host "no-host" :port port})
    (let [r (session/archive-notebook!)]
      (is (false? (:ok? r)))
      (is (= "no notebook linked" (:message r))))))

(deftest status-reports-what-the-buffer-needs
  (let [root (str (tmp-root))]
    (is (false? (:running? (session/status))))
    (session/start! {:state-root root :ssh-host "no-host" :port port})
    (report-identity!)
    (session/submit "(+ 1 2)" "user")
    (let [s (session/status)]
      (is (:running? s))
      (is (= doc (:notebook-id s)))
      (is (= page (:page (:active s))))
      (is (= {:queued 1} (:deliveries s)))
      (is (empty? (:uncertain s))))))
