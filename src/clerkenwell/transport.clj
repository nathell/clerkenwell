(ns clerkenwell.transport
  "SSH transfer to the device, and the insertion handshake, backed by the store.

   The device adapter (xovi/clerkenwell.qmd) reaches this over an SSH reverse
   tunnel bound to the device's loopback, so nothing is exposed on a network:

     GET /command  -> the pending command for THIS doc+page, or {}
     GET /attempt  -> 200 only once the attempt is durable in the event log
     GET /ack      -> report inserted | aborted | failed | rejected

   Ordering is the contract. `/attempt` writes :insertion-started to the event
   log BEFORE replying 200, and the adapter refuses to insert without that 200.
   A crash in between therefore leaves :insertion-started, which `store/recover!`
   turns into :uncertain -- never replayed automatically.

   Delivery state lives in the store, not here; this namespace is stateless
   apart from the running server. Retries resend the same bytes and never
   re-evaluate anything."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clerkenwell.placement :as placement]
            [clerkenwell.store :as store])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress URLDecoder]))

(def protocol-version 1)

(def default-config
  {:port 14242
   :device-asset-dir "/home/root/clerkenwell/assets"})

;; -------- minimal JSON writer --------
;; Only the fixed command shape below is ever emitted, so this stays tiny and
;; the project keeps a single dependency.

(defn- json-escape [s]
  (str/escape (str s) {\" "\\\"" \\ "\\\\" \newline "\\n" \return "\\r" \tab "\\t"}))

(defn- json-value [v]
  (cond
    (nil? v) "null"
    (number? v) (str v)
    (boolean? v) (str v)
    :else (str "\"" (json-escape v) "\"")))

(defn- json-object [m]
  (str "{" (str/join "," (for [[k v] m]
                           (str "\"" (name k) "\":" (json-value v))))
       "}"))

;; -------- shell-free SSH --------

;; ssh must never block waiting for input. A JVM started from Emacs often has no
;; ssh-agent and no tty, so without BatchMode a host-key or passphrase prompt
;; hangs the call forever -- and with the push on the submission path, that hangs
;; the editor. Fail fast and report instead.
(def ssh-batch-opts
  ["-o" "BatchMode=yes" "-o" "ConnectTimeout=10" "-o" "StrictHostKeyChecking=accept-new"])

(defn- run
  "Run a command as an argument vector. Never builds a shell string, so notebook
   names and paths cannot be interpolated into shell code."
  [& args]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec args))
             (.redirectErrorStream true))
        proc (.start pb)
        out (slurp (.getInputStream proc))
        exit (.waitFor proc)]
    {:exit exit :out out}))

(defn run-ssh
  "Run a command on the device. Public so a session can test reachability."
  [ssh-host & args]
  (apply run (concat ["ssh"] ssh-batch-opts [ssh-host] args)))

(defn push-asset!
  "Copy a result's rendered asset to the device over SSH. `ssh-host` is a normal
   SSH host alias, so keys, addresses and usernames stay in SSH configuration.

   On success the result moves to :transferred, which is the state /command
   serves from -- a result whose bytes are not on the device is never offered
   for insertion."
  [nb result-id {:keys [ssh-host device-asset-dir] :as config}]
  (let [{:keys [device-asset-dir]} (merge default-config config)
        record (get (store/read-manifest nb) result-id)
        asset (:asset record)]
    (when-not record
      (throw (ex-info "no such result" {:result-id result-id})))
    (when-not (:path asset)
      (throw (ex-info "result has no rendered asset to push"
                      {:result-id result-id})))
    (let [local (io/file nb (:path asset))
          remote (str device-asset-dir "/" result-id ".png")]
      (when-not (.exists local)
        (throw (ex-info "asset missing on host"
                        {:result-id result-id :path (str local)})))
      (let [mk (apply run (concat ["ssh"] ssh-batch-opts
                                  [ssh-host "mkdir" "-p" device-asset-dir]))]
        (when-not (zero? (:exit mk))
          (throw (ex-info "could not create device asset dir" mk))))
      (let [cp (apply run (concat ["scp"] ssh-batch-opts
                                  [(str local) (str ssh-host ":" remote)]))]
        (when-not (zero? (:exit cp))
          (throw (ex-info "scp failed" cp))))
      (store/record-push! nb result-id remote)
      (store/set-delivery! nb result-id :transferred)
      {:result-id result-id :remote remote :sha256 (:sha256 asset)})))

;; -------- the handshake --------

(defn- params [^String qs]
  (into {} (for [pair (str/split (or qs "") #"&")
                 :when (seq pair)
                 :let [[k v] (str/split pair #"=" 2)]]
             [(keyword k) (URLDecoder/decode (or v "") "UTF-8")])))

(defn- respond! [^HttpExchange ex status ^String body content-type]
  (let [bs (.getBytes body "UTF-8")]
    (.set (.getResponseHeaders ex) "Content-Type" content-type)
    (.sendResponseHeaders ex status (alength bs))
    (with-open [os (.getResponseBody ex)] (.write os bs))))

(defn- pending-for
  "The one result ready for insertion on this page: transferred, and targeted at
   exactly this notebook and page. Results in any other state -- queued (bytes
   not yet on device), insertion-started, uncertain, inserted, failed -- are
   never served, which is what stops automatic replay."
  [manifest doc page]
  (first (for [[id r] manifest
               :when (and (= :transferred (get-in r [:delivery :state]))
                          (= doc (:notebook-id r))
                          (= page (:page-id r))
                          (:remote-path r)
                          (:placement r))]
           [id r])))

;; The adapter reports live identity on every poll, which is the freshest view
;; of the UI the host can have. Kept with a timestamp so a stale report from a
;; dead adapter is never treated as the live page.
(def active-page (atom nil))

(defn fresh-identity
  "The notebook/page the adapter last reported, if that report is recent enough
   to be believed. Returns nil otherwise -- a submission must then be rejected
   rather than aimed at a guess."
  ([] (fresh-identity 5000))
  ([max-age-ms]
   (when-let [{:keys [at] :as seen} @active-page]
     (when (< (- (System/currentTimeMillis) at) max-age-ms)
       seen))))

(defn- handle-command
  "Note what the adapter has open, and offer it the result waiting for that
   page. Nothing is offered for a notebook this host may not write, per the
   configured `:writable?`: another connection holds it, and its results are
   that connection's to deliver."
  [state-root {:keys [writable?]} {:keys [doc page instance]}]
  (reset! active-page {:doc doc :page page :instance instance
                       :at (System/currentTimeMillis)})
  (if-let [[id r] (when (or (nil? writable?) (writable? doc))
                    (pending-for (store/read-manifest
                                  (store/notebook-dir state-root doc)) doc page))]
    (let [{:keys [width height]} (:asset r)
          {:keys [dx dy]} (:placement r)
          [sx sy] (placement/display->scene dx dy width height nil)]
      (println (format "-> serving %s to %s" id instance))
      [200 (json-object {:protocol protocol-version
                         :resultId id
                         :doc (:notebook-id r)
                         :page (:page-id r)
                         :path (:remote-path r)
                         :x sx :y sy :w width :h height
                         :bytes (:bytes (:asset r))
                         :sha256 (:sha256 (:asset r))})])
    [200 "{}"]))

(defn notebook-holding
  "The notebook directory whose log contains `result-id`. Resolved by search
   rather than from ambient state: an ack carries no page identity, and using
   the last-reported page would write it to whatever notebook happened to be
   open."
  [state-root result-id]
  (first (for [d (filter #(.isDirectory %) (or (.listFiles (io/file state-root)) []))
               :when (contains? (store/read-manifest d) result-id)]
           d)))

(defn- handle-attempt
  "Record the attempt, durably, before authorizing it. `:on-attempt`, when
   configured, is called with the notebook directory once the attempt is in
   the local log, and must return {:ok? true} for the call to be authorized:
   it is how the attempt reaches the device's copy of the session first, so
   that whatever may be on the page is always in the log there."
  [state-root {:keys [on-attempt writable?]} {:keys [id doc page instance]}]
  (let [nb (notebook-holding state-root id)
        r (when nb (get (store/read-manifest nb) id))
        state (get-in r [:delivery :state])]
    (cond
      (nil? r) [409 "unknown id"]
      (and writable? (not (writable? doc))) [409 "notebook held elsewhere"]
      (not= :transferred state) [409 (str "state=" state)]
      (or (not= doc (:notebook-id r)) (not= page (:page-id r))) [409 "target mismatch"]
      :else
      (do
        ;; Durable BEFORE the 200 that authorizes the call.
        (store/set-delivery! nb id :insertion-started instance)
        (let [copied (if on-attempt (on-attempt nb) {:ok? true})]
          (if (:ok? copied)
            (do (println (format "== attempt recorded for %s (durable)" id))
                [200 "ok"])
            ;; Not authorized, so not inserted. Failed rather than pending
            ;; again, or the adapter would retry it every second.
            (do (store/set-delivery! nb id :failed
                                     (str "could not record the attempt on the device: "
                                          (:message copied)))
                (println (format "!! attempt for %s not recorded on the device" id))
                [503 "not recorded on the device"])))))))

(defn- handle-ack [state-root {:keys [id status detail instance]}]
  (let [nb (notebook-holding state-root id)
        r (when nb (get (store/read-manifest nb) id))]
    (if (nil? r)
      [409 "unknown id"]
      (let [;; `aborted` means identity was rechecked and the call was never
            ;; made, so the result is safely pending again for its ORIGINAL
            ;; page, with its bytes still on the device. Everything else is
            ;; terminal until a human decides.
            new-state (case (keyword status)
                        :inserted :inserted
                        :aborted  :transferred
                        :rejected :failed
                        :failed)]
        (store/set-delivery! nb id new-state detail)
        (println (format "<= ack %s status=%s -> %s %s"
                         id status new-state (if (seq detail) (str "(" detail ")") "")))
        [200 "ok"]))))

(defn- handler [state-root config]
  (reify HttpHandler
    (handle [_ ex]
      (let [uri (.getPath (.getRequestURI ex))
            p (params (.getQuery (.getRequestURI ex)))
            [status body] (try
                            (case uri
                              "/command" (handle-command state-root config p)
                              "/attempt" (handle-attempt state-root config p)
                              "/ack"     (handle-ack state-root p)
                              [404 "no"])
                            (catch Throwable e
                              (println "transport error:" (.getMessage e))
                              [500 "error"]))]
        (respond! ex status body
                  (if (= uri "/command") "application/json" "text/plain"))))))

(defn start!
  "Start the handshake server bound to loopback, after recovering every notebook
   under `state-root` -- unless `:recover?` is false, for a caller that
   recovers a notebook itself when it may write it. The notebook is resolved
   per request from the identity the adapter reports, so switching notebooks
   needs no restart. Returns a handle for `stop!`."
  [state-root config]
  (let [{:keys [port recover?]} (merge default-config config)
        root (io/file state-root)]
    (doseq [d (when-not (false? recover?)
                (filter #(.isDirectory %) (or (.listFiles root) [])))
            id (store/recover! d)]
      (println (format "!! %s was interrupted mid-insertion -> :uncertain" id))
      (println "   It may or may not be on the page. Inspect, then reconcile."))
    (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
      (doseq [path ["/command" "/attempt" "/ack"]]
        (.createContext server path (handler state-root config)))
      (.setExecutor server nil)
      (.start server)
      (println (format "clerkenwell transport on 127.0.0.1:%d" port))
      server)))

(defn stop! [^HttpServer server] (.stop server 0))

;; -------- operator actions --------

(defn retry-delivery!
  "Resume a definitely-uninserted result with its existing id and captured
   asset, without evaluating again. Refuses :uncertain, which must be reconciled
   by inspection first."
  [nb result-id]
  (let [r (get (store/read-manifest nb) result-id)
        state (get-in r [:delivery :state])]
    (cond
      (nil? r) {:ok? false :reason :unknown-id}
      (= :uncertain state) {:ok? false :reason :uncertain
                            :message "may already be inserted; reconcile first"}
      (= :inserted state) {:ok? false :reason :already-inserted}
      :else (do (store/set-delivery! nb result-id :transferred)
                {:ok? true :state :transferred}))))

(defn reconcile!
  "Resolve an :uncertain result after inspecting the device. `present?` true
   marks it inserted; false returns it for another attempt."
  [nb result-id present?]
  (let [state (get-in (get (store/read-manifest nb) result-id) [:delivery :state])]
    (if (not= :uncertain state)
      {:ok? false :reason :not-uncertain :state state}
      (do (store/set-delivery! nb result-id (if present? :inserted :transferred)
                               "reconciled by inspection")
          {:ok? true :state (if present? :inserted :transferred)}))))

(defn abandon!
  "Explicitly give up on a known-uninserted delivery."
  [nb result-id]
  (store/set-delivery! nb result-id :failed "abandoned by user")
  {:ok? true :state :failed})
