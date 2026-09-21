(ns clerkenwell.watch
  "Watches a notebook's pages on the device and feeds new handwriting to the
   ink store.

   Xochitl saves lazily -- roughly twelve seconds after you stop writing -- and
   a poll can easily catch a half-written file. The spec's heuristic is
   implemented here:

     - require two identical observations at least 500ms apart
     - copy the file, then observe again and require it unchanged across the copy
     - parse the copy, and treat a parse failure as another reason to retry,
       even when the file looks unchanged

   Observations are content digests rather than mtimes, so a save that rewrites
   a file to the same bytes is correctly seen as no change.

   This heuristic cannot prove that all visible ink has been saved. The user
   should pause writing while a result is being placed. That is an accepted MVP
   approximation, not a correctness proof.

   Every changed page is processed on each pass. (Edsger deliberately handled
   only one changed page per tick; copying that would silently drop ink.)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clerkenwell.ink :as ink]
            [clerkenwell.lines :as lines])
  (:import [java.nio.file Files]))

(def xochitl-root "/home/root/.local/share/remarkable/xochitl")

(def default-config
  {:poll-ms 1000
   :settle-ms 500
   :max-attempts 5})

;; See clerkenwell.transport/ssh-batch-opts: a blocking ssh prompt would stall
;; the watcher thread indefinitely.
(def ^:private transport-opts
  ["-o" "BatchMode=yes" "-o" "ConnectTimeout=10" "-o" "StrictHostKeyChecking=accept-new"])

(def ^:private uuid-re #"^[0-9a-fA-F-]{36}$")

(defn- check-uuid!
  "Identities come from the device, so they are validated before being built
   into a remote command rather than trusted."
  [id what]
  (when-not (and (string? id) (re-matches uuid-re id))
    (throw (ex-info (str "refusing to use a malformed " what) {:value id})))
  id)

(defn- ssh [ssh-host & args]
  (let [pb (doto (ProcessBuilder. ^java.util.List
                  (into (into ["ssh"] transport-opts) (cons ssh-host args)))
             (.redirectErrorStream false))
        proc (.start pb)
        out (slurp (.getInputStream proc))
        err (slurp (.getErrorStream proc))
        exit (.waitFor proc)]
    {:exit exit :out out :err err}))

;; -------- observing the remote pages --------

(defn remote-digests
  "Content digests of every page file of `notebook-id`, as {page-id digest}.

   An empty map means the notebook directory holds no page files, which is a
   legitimate state for a blank notebook -- distinct from an SSH or filesystem
   failure, which throws."
  [ssh-host notebook-id]
  (check-uuid! notebook-id "notebook id")
  (let [dir (str xochitl-root "/" notebook-id)
        {:keys [exit out err]} (ssh ssh-host
                                    (str "cd " dir " 2>/dev/null && md5sum *.rm 2>/dev/null; "
                                         "test -d " dir " || echo NODIR"))]
    (cond
      (str/includes? out "NODIR")
      (throw (ex-info "notebook directory not present on device"
                      {:notebook-id notebook-id :dir dir}))

      (and (not (zero? exit)) (str/blank? out))
      (throw (ex-info (str "could not list pages on device (ssh " ssh-host "): "
                          (str/trim (str err)))
                      {:exit exit :err err :ssh-host ssh-host}))

      :else
      (into {}
            (for [line (str/split-lines out)
                  :when (seq (str/trim line))
                  :let [[digest file] (str/split (str/trim line) #"\s+" 2)]
                  :when (and digest file (str/ends-with? file ".rm"))]
              [(str/replace file #"\.rm$" "") digest])))))

(defn- pull-page!
  "Copy one page file to `dest`. Returns its bytes, or nil if it vanished."
  [ssh-host notebook-id page-id dest]
  (check-uuid! notebook-id "notebook id")
  (check-uuid! page-id "page id")
  (let [remote (str ssh-host ":" xochitl-root "/" notebook-id "/" page-id ".rm")
        pb (ProcessBuilder. ^java.util.List
            (into (into ["scp" "-q"] transport-opts) [remote (str dest)]))
        proc (.start pb)
        err (slurp (.getErrorStream proc))]
    (if (zero? (.waitFor proc))
      (Files/readAllBytes (.toPath (io/file dest)))
      (if (str/includes? err "No such file")
        nil
        (throw (ex-info "scp failed" {:page-id page-id :err err}))))))

(defn- digest-of [^bytes bs]
  (let [md (java.security.MessageDigest/getInstance "MD5")]
    (.update md bs)
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn stable-pull!
  "Pull `page-id` only once it looks settled, and prove it did not move under
   us. Returns {:ok? true :bytes ... :parsed ...}, or {:ok? false :reason ...}.

   `:reason` distinguishes a page that is simply blank (no file) from one that
   would not hold still or would not parse -- the caller must not treat those
   alike."
  [ssh-host notebook-id page-id {:keys [settle-ms max-attempts] :as config}]
  (let [{:keys [settle-ms max-attempts]} (merge default-config config)
        tmp (io/file (System/getProperty "java.io.tmpdir")
                     (str "ckw-pull-" (random-uuid) ".rm"))]
    (try
      (loop [attempt 1]
        (if (> attempt max-attempts)
          {:ok? false :reason :would-not-settle}
          (let [before (get (remote-digests ssh-host notebook-id) page-id)]
            (if (nil? before)
              {:ok? false :reason :no-page-file}
              (do
                (Thread/sleep settle-ms)
                (let [second-look (get (remote-digests ssh-host notebook-id) page-id)]
                  (if (not= before second-look)
                    ;; Still being written; start over.
                    (recur (inc attempt))
                    (let [bs (pull-page! ssh-host notebook-id page-id tmp)]
                      (cond
                        (nil? bs)
                        {:ok? false :reason :no-page-file}

                        ;; Compare the file before and after copying.
                        (not= before (digest-of bs))
                        (recur (inc attempt))

                        (not= before (get (remote-digests ssh-host notebook-id) page-id))
                        (recur (inc attempt))

                        :else
                        (let [parsed (try (lines/parse bs)
                                          (catch Exception e
                                            {::parse-error (.getMessage e)}))]
                          ;; A parse failure is a reason to retry even though
                          ;; the digest did not change: we may have caught a
                          ;; consistent-looking but incomplete write.
                          (if (::parse-error parsed)
                            (recur (inc attempt))
                            {:ok? true :bytes bs :parsed parsed
                             :digest before})))))))))))
      (finally (.delete tmp)))))

;; -------- the pass --------

(defn poll-once!
  "One pass over the notebook. Processes EVERY page whose content changed since
   the last pass, and returns what it recorded.

   `seen` maps page-id to the digest last processed; the returned :seen carries
   it forward. A page is only marked seen once its observation is durable, so a
   failure costs a repeat rather than lost ink."
  [{:keys [nb ssh-host notebook-id seen config render-opts]}]
  (let [digests (remote-digests ssh-host notebook-id)
        changed (for [[page-id digest] digests
                      :when (not= digest (get seen page-id))]
                  page-id)]
    (reduce
     (fn [acc page-id]
       (let [pull (stable-pull! ssh-host notebook-id page-id config)]
         (if-not (:ok? pull)
           (update acc :skipped conj {:page-id page-id :reason (:reason pull)})
           (let [obs (ink/observe! nb {:page-id page-id
                                       :rm-bytes (:bytes pull)
                                       :strokes (:strokes (:parsed pull))
                                       :render-opts render-opts})]
             (-> acc
                 (assoc-in [:seen page-id] (:digest pull))
                 (update (if (:new? obs) :recorded :unchanged)
                         conj (assoc obs :page-id page-id)))))))
     {:seen seen :recorded [] :unchanged [] :skipped []}
     changed)))

(defn watch!
  "Poll until `stop?` derefs truthy. Calls `on-entry` with each new observation."
  [{:keys [nb ssh-host notebook-id config stop? on-entry render-opts]}]
  (let [{:keys [poll-ms]} (merge default-config config)
        last-error (atom nil)]
    (loop [seen {}]
      (if (and stop? @stop?)
        seen
        (let [r (try
                  (poll-once! {:nb nb :ssh-host ssh-host :notebook-id notebook-id
                               :seen seen :config config :render-opts render-opts})
                  (catch Exception e
                    ;; Poll failures repeat every tick, so report a given
                    ;; message once rather than flooding the REPL.
                    (let [msg (.getMessage e)]
                      (when (not= msg @last-error)
                        (reset! last-error msg)
                        (println "clerkenwell watch:" msg)))
                    {:seen seen :recorded []}))]
          (when (seq (:recorded r)) (reset! last-error nil))
          (doseq [entry (:recorded r)]
            (when on-entry (on-entry entry)))
          (Thread/sleep poll-ms)
          (recur (:seen r)))))))
