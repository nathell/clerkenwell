(ns clerkenwell.transport-test
  "Drives the real server over HTTP the way xovi/clerkenwell.qmd does."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clerkenwell.core :as core]
            [clerkenwell.store :as store]
            [clerkenwell.transport :as transport])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]))

(def port 14343)
(def ^:dynamic *nb* nil)

(defn- http-get
  ([path] (http-get port path))
  ([p path]
   (let [c (HttpClient/newHttpClient)
         req (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" p path)))
                 (.GET) (.build))
         resp (.send c req (HttpResponse$BodyHandlers/ofString))]
     [(.statusCode resp) (.body resp)])))

(defn- tmp-root []
  (doto (io/file (System/getProperty "java.io.tmpdir") (str "ckw-tr-" (random-uuid)))
    (.mkdirs)))

(defn- submitted
  "A result carried as far as the host can take it without a device: computed,
   rendered, placed, and with its bytes notionally on the device."
  [root doc page]
  (let [r (core/submit! {:state-root root :notebook-id doc :page-id page
                         :source "(+ 1 2)" :namespace "user"
                         :ink-bottom 300 :outputs-bottom nil})
        nb (store/notebook-dir root doc)]
    (is (:ok? r))
    ;; Stand in for push-asset! without an actual device.
    (store/record-push! nb (:result-id r) (str "/home/root/clerkenwell/assets/"
                                               (:result-id r) ".png"))
    (store/set-delivery! nb (:result-id r) :transferred)
    [nb (:result-id r)]))

(defn- state [nb id]
  (get-in (store/read-manifest nb) [id :delivery :state]))

(deftest full-handshake
  (let [root (tmp-root)
        doc "nb-1" page "page-1"
        [nb id] (submitted root doc page)
        server (transport/start! root {:port port})]
    (try
      (testing "a command is not offered for a different page"
        (let [[_ body] (http-get (str "/command?doc=" doc "&page=other&instance=i1"))]
          (is (= "{}" body))))

      (testing "the target page gets the command, with verified scene coords"
        (let [[status body] (http-get (str "/command?doc=" doc "&page=" page "&instance=i1"))]
          (is (= 200 status))
          (is (str/includes? body (str "\"resultId\":\"" id "\"")))
          (is (str/includes? body "\"protocol\":1"))
          (is (str/includes? body "\"bytes\":"))
          (is (str/includes? body "\"sha256\":"))
          ;; dx is the 60px left margin, so scene x = 60 - 702 + w/2.
          (is (re-find #"\"x\":-\d" body) "scene x is left of centre")))

      (testing "attempt is durable before it authorizes"
        (let [[status body] (http-get (str "/attempt?id=" id "&doc=" doc
                                           "&page=" page "&instance=i1"))]
          (is (= 200 status))
          (is (= "ok" body))
          (is (= :insertion-started (state nb id)))))

      (testing "a duplicate attempt is refused"
        (let [[status _] (http-get (str "/attempt?id=" id "&doc=" doc
                                        "&page=" page "&instance=i1"))]
          (is (= 409 status))))

      (testing "the command is not re-served while an attempt is outstanding"
        (let [[_ body] (http-get (str "/command?doc=" doc "&page=" page "&instance=i1"))]
          (is (= "{}" body))))

      (testing "ack inserted is terminal"
        (http-get (str "/ack?id=" id "&status=inserted&instance=i1"))
        (is (= :inserted (state nb id)))
        (let [[_ body] (http-get (str "/command?doc=" doc "&page=" page "&instance=i1"))]
          (is (= "{}" body) "no reinsertion of an inserted result")))
      (finally (transport/stop! server)))))

(deftest abort-returns-to-its-original-page
  (testing "a page turn mid-handshake leaves the result pending for its own page"
    (let [root (tmp-root)
          doc "nb-2" page "page-2"
          [nb id] (submitted root doc page)
          pt (inc port)
          server (transport/start! root {:port pt})]
      (try
        (let [[status _] (http-get pt (str "/attempt?id=" id "&doc=" doc
                                           "&page=" page "&instance=i1"))]
          (is (= 200 status))
          (is (= :insertion-started (state nb id))))

        (let [[status _] (http-get pt (str "/ack?id=" id "&status=aborted"
                                           "&detail=identity%20changed&instance=i1"))]
          (is (= 200 status)))

        (testing "it goes back to :transferred, not :queued -- bytes are still there"
          (is (= :transferred (state nb id))))

        (testing "and is re-served for the SAME page, never retargeted"
          (let [[_ body] (http-get pt (str "/command?doc=" doc "&page=" page "&instance=i2"))]
            (is (str/includes? body (str "\"resultId\":\"" id "\""))))
          (let [[_ body] (http-get pt (str "/command?doc=" doc "&page=elsewhere&instance=i2"))]
            (is (= "{}" body))))

        (testing "a rejected asset is terminal, not retried"
          (http-get pt (str "/attempt?id=" id "&doc=" doc "&page=" page "&instance=i2"))
          (http-get pt (str "/ack?id=" id "&status=rejected&detail=asset%20missing&instance=i2"))
          (is (= :failed (state nb id)))
          (let [[_ body] (http-get pt (str "/command?doc=" doc "&page=" page "&instance=i2"))]
            (is (= "{}" body))))
        (finally (transport/stop! server))))))

(deftest crash-between-attempt-and-ack-is-uncertain
  (let [root (tmp-root)
        doc "nb-3" page "page-3"
        [nb id] (submitted root doc page)
        server (transport/start! root {:port (+ port 2)})]
    (transport/stop! server)
    ;; Attempt recorded, no ack: exactly the crash window.
    (store/set-delivery! nb id :insertion-started "i1")
    (let [server2 (transport/start! root {:port (+ port 2)})]
      (try
        (testing "recovery marks it uncertain, not retryable"
          (is (= :uncertain (state nb id))))
        (testing "retry refuses an uncertain result"
          (let [r (transport/retry-delivery! nb id)]
            (is (false? (:ok? r)))
            (is (= :uncertain (:reason r)))))
        (testing "reconciling as absent makes it deliverable again"
          (let [r (transport/reconcile! nb id false)]
            (is (:ok? r))
            (is (= :transferred (state nb id)))))
        (testing "reconciling as present marks it inserted"
          (store/set-delivery! nb id :insertion-started "i1")
          (store/recover! nb)
          (is (:ok? (transport/reconcile! nb id true)))
          (is (= :inserted (state nb id))))
        (finally (transport/stop! server2))))))

(deftest queued-but-untransferred-is-never-served
  (testing "a result whose bytes are not on the device is not offered"
    (let [root (tmp-root)
          doc "nb-4" page "page-4"
          r (core/submit! {:state-root root :notebook-id doc :page-id page
                           :source "(+ 1 2)" :namespace "user"
                           :ink-bottom 300 :outputs-bottom nil})
          nb (store/notebook-dir root doc)
          server (transport/start! root {:port (+ port 3)})]
      (try
        (is (= :queued (state nb (:result-id r))))
        (let [c (HttpClient/newHttpClient)
              req (-> (HttpRequest/newBuilder
                       (URI. (str "http://127.0.0.1:" (+ port 3)
                                  "/command?doc=" doc "&page=" page)))
                      (.GET) (.build))
              body (.body (.send c req (HttpResponse$BodyHandlers/ofString)))]
          (is (= "{}" body)))
        (finally (transport/stop! server))))))
