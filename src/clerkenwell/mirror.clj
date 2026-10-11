(ns clerkenwell.mirror
  "A session's canonical files, copied to the device. See docs/sessions.md.

   The device keeps each session under /home/root/clerkenwell/<notebook-uuid>/,
   in the same layout as the store, but only its canonical parts: events,
   results and page snapshots. Derived parts stay on each computer.

   There is no outbox to keep. Canonical files are immutable and named by what
   they are, so what remains to send is simply the local files not yet on the
   device, and a crash loses nothing that the next comparison will not find.

   Files travel in batches, as tar on stdin or stdout of a single SSH call. On
   the device a batch is unpacked aside and then moved into place a file at a
   time, events last and in order, so a reader there never sees an event
   before the files it names. The remote commands are fixed scripts; their only
   arguments are a validated device path and notebook id, and file names travel
   inside the tar or as a list on stdin, never through a shell."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clerkenwell.store :as store]
            [clerkenwell.tar :as tar]
            [clerkenwell.transport :as transport])
  (:import (java.nio.file Files)))

(def device-root "/home/root/clerkenwell")

;; -------- what is copied --------

(def ^:private canonical-re
  ;; Every segment starts with a letter or digit: no dotfiles (temp files are
  ;; dotted), no `..`, and nothing a shell would treat specially.
  #"(events|results|pages)(/[A-Za-z0-9][A-Za-z0-9._-]*)+")

(defn canonical?
  "Whether `path`, relative to a session, is one of its canonical files."
  [path]
  (boolean (re-matches canonical-re path)))

(defn- event-number [path]
  (when-let [[_ n] (re-matches #"events/(\d+)\.edn" path)]
    (parse-long n)))

(defn- ordered
  "Events last, in sequence order: a file an event names arrives before it."
  [paths]
  (sort-by (fn [p] (if-let [n (event-number p)] [1 n ""] [0 0 p])) paths))

(defn local-files
  "The canonical files of session directory `nb`, as relative paths.

   Events are listed first. A record or asset is always published before the
   event that names it, so listing events first means every file an included
   event names is included too, however writes race the listing."
  [nb]
  (let [root (.toPath (io/file nb))]
    (into #{}
          (comp (map #(io/file nb %))
                (filter #(.isDirectory ^java.io.File %))
                (mapcat file-seq)
                (filter #(.isFile ^java.io.File %))
                (map #(str (.relativize root (.toPath ^java.io.File %))))
                (filter canonical?))
          ["events" "results" "pages"])))

;; -------- running the fixed scripts --------

(def ^:private scripts
  {:list
   "d=$1/$2; [ -d \"$d\" ] || exit 0; cd \"$d\" && find events results pages -type f 2>/dev/null; true"

   :push
   (str "set -e; d=$1/$2; s=$d/.incoming.$$; mkdir -p \"$s\"; tar x -f - -C \"$s\"; cd \"$s\"; "
        "find . -type f ! -path \"./events/*\" | while read -r f; do "
        "mkdir -p \"$d/${f%/*}\"; mv \"$f\" \"$d/$f\"; done; "
        "if [ -d events ]; then mkdir -p \"$d/events\"; "
        "ls events | sort -n | while read -r f; do mv \"events/$f\" \"$d/events/$f\"; done; fi; "
        "cd \"$d\"; rm -rf \"$s\"")

   :pull
   "cd \"$1/$2\" && tar c -f - -T -"

   :archive
   "d=$1/$2; [ ! -d \"$d\" ] || mv \"$d\" \"$d.archived-$3\""})

(def ^:private arg-re
  ;; A device path, a notebook id or an archive stamp. Nothing here needs
  ;; quoting, so nothing can escape the script it is handed to.
  #"[A-Za-z0-9/][A-Za-z0-9._/-]*")

(defn- command
  "The shell command running `script` with `args`, refusing any argument that
   is not plainly a path, an id or a stamp."
  [script args]
  (doseq [a args]
    (when-not (and (string? a) (re-matches arg-re a) (not (str/includes? a "..")))
      (throw (ex-info "refusing an unexpected argument for the device" {:arg a}))))
  (str "sh -c '" (scripts script) "' ckw " (str/join " " args)))

(defn- exec!
  "Run `script` on the device -- over SSH, or for `:local-root`, with this
   machine's sh, which is how the scripts are tested -- with `in` on stdin.
   Returns {:exit :out bytes :err string}."
  [{:keys [ssh-host local-root]} script args ^bytes in]
  (let [args (cons (or local-root device-root) args)
        cmd (command script args)
        argv (if local-root
               (concat ["sh" "-c" (scripts script) "ckw"] args)
               (concat ["ssh"] transport/ssh-batch-opts
                       ;; A device that sleeps mid-transfer must not hang it.
                       ["-o" "ServerAliveInterval=5" "-o" "ServerAliveCountMax=3"
                        ssh-host cmd]))
        p (.start (ProcessBuilder. ^java.util.List (vec argv)))
        writer (future (try (with-open [o (.getOutputStream p)]
                              (when in (.write o in)))
                            ;; The process may exit without reading; its exit
                            ;; status says what happened.
                            (catch java.io.IOException _ nil)))
        err (future (slurp (.getErrorStream p)))
        out (with-open [i (.getInputStream p)] (.readAllBytes i))
        exit (.waitFor p)]
    @writer
    {:exit exit :out out :err @err}))

(defn- exec-ok!
  [device script args in]
  (let [{:keys [exit err] :as r} (exec! device script args in)]
    (when-not (zero? exit)
      (throw (ex-info (str (name script) " on the device failed: "
                           (let [e (str/trim err)] (if (str/blank? e) (str "exit " exit) e)))
                      {:exit exit})))
    (:out r)))

;; -------- a session's copy --------

(defn mirror
  "A handle on the device copy of the session in `nb`. `device` is
   {:ssh-host alias} or, for tests, {:local-root dir}."
  [nb notebook-id device]
  {:nb (io/file nb)
   :notebook-id notebook-id
   :device device
   ;; What is known to be on the device; nil until listed.
   :on-device (atom nil)
   :status (atom {:ok? true})
   :closed? (atom false)
   :lock (Object.)})

(defn close!
  "Stop this handle copying anything, waiting for an exchange in progress to
   finish. Needed before the device's copy is moved: a flush already under way
   would otherwise recreate it."
  [m]
  (locking (:lock m) (reset! (:closed? m) true)))

(defn- note!
  "Record how the last exchange went, saying so when that changes."
  [m status]
  (let [before @(:status m)]
    (reset! (:status m) status)
    (when (and (not (:ok? status)) (not= (:message before) (:message status)))
      (println "clerkenwell copy:" (:message status)))
    status))

(defn remote-files
  "The canonical files of this session on the device."
  [{:keys [device notebook-id]}]
  (into #{}
        (filter canonical?)
        (str/split-lines (String. ^bytes (exec-ok! device :list [notebook-id] nil) "UTF-8"))))

(defn- listed!
  [m]
  (or @(:on-device m) (reset! (:on-device m) (remote-files m))))

(defmacro ^:private exchange
  "Run `body` holding `m`'s lock, unless `m` is closed. An exception is noted
   in its status and returned as {:ok? false :message ...}, after `on-error`."
  [m on-error & body]
  `(locking (:lock ~m)
     (if @(:closed? ~m)
       {:ok? false :message "closed"}
       (try
         (let [r# (do ~@body)]
           (note! ~m {:ok? true})
           r#)
         (catch Exception e#
           ~on-error
           (note! ~m {:ok? false :message (.getMessage e#)}))))))

(defn pull!
  "Fetch whatever the device has that this computer does not. Returns
   {:ok? true :pulled n}, or {:ok? false :message ...}."
  [{:keys [nb device notebook-id] :as m}]
  (exchange m nil
    (let [remote (reset! (:on-device m) (remote-files m))
          wanted (ordered (remove (local-files nb) remote))]
      (when (seq wanted)
        (let [bs (exec-ok! device :pull [notebook-id]
                           (.getBytes (str (str/join "\n" wanted) "\n") "UTF-8"))
              entries (into {} (tar/read-entries bs))]
          ;; In the same order as asked: events last.
          (doseq [p wanted
                  :let [data (get entries p)]
                  :when (and data (canonical? p))]
            (store/publish-bytes! (io/file nb p) data))))
      {:ok? true :pulled (count wanted)})))

(defn flush!
  "Send the device whatever it does not have yet. Returns {:ok? true :pushed n},
   or {:ok? false :message ...}; nothing is lost by a failure, since the next
   flush compares again."
  [{:keys [nb device notebook-id] :as m}]
  ;; After a failure the device may have taken some of it: list again.
  (exchange m (reset! (:on-device m) nil)
    (let [on-device (listed! m)
          todo (ordered (remove on-device (local-files nb)))]
      (when (seq todo)
        (exec-ok! device :push [notebook-id]
                  (tar/write (for [p todo]
                               [p (Files/readAllBytes (.toPath (io/file nb p)))])))
        (swap! (:on-device m) into todo))
      {:ok? true :pushed (count todo)})))

(defn status
  "How the copy stands: {:ok? :pending n :message}."
  [{:keys [nb] :as m}]
  (let [known @(:on-device m)]
    (assoc @(:status m)
           :pending (when known (count (remove known (local-files nb)))))))

(defn start!
  "Keep flushing in the background, every `interval-ms`. Returns a stop atom."
  ([m] (start! m 2000))
  ([m interval-ms]
   (let [stop? (atom false)]
     (doto (Thread. (fn []
                      (while (not @stop?)
                        (flush! m)
                        (Thread/sleep (long interval-ms)))))
       (.setDaemon true)
       (.setName (str "clerkenwell-copy-" (:notebook-id m)))
       (.start))
     stop?)))

(defn archive!
  "Set the device copy aside as `<id>.archived-<stamp>`, as the local history
   is. Returns {:ok? ...}; a missing copy is not an error."
  [notebook-id device stamp]
  (try
    (exec-ok! device :archive [notebook-id stamp] nil)
    {:ok? true}
    (catch Exception e
      {:ok? false :message (.getMessage e)})))
