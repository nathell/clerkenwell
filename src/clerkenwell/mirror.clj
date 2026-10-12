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
   arguments are a validated device path, notebook id and lease token, and file
   names travel inside the tar or as a list on stdin, never through a shell.

   One connection writes a session at a time: the one holding its lease, a
   file in the session's directory naming the holder's token. A push is
   refused on the device unless it carries that token."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
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

;; Changes to the lease, and pushes, which must check it, happen under a lock
;; on the device: a directory, since mkdir either creates it or fails, whoever
;; else is trying. A lock left by a script that died is broken after ten
;; seconds; nothing holds it for more than a moment.
(def ^:private locked
  (str "L=$d/.lock; n=0; until mkdir \"$L\" 2>/dev/null; do n=$((n+1)); "
       "if [ $n -ge 10 ]; then rmdir \"$L\" 2>/dev/null || true; n=0; fi; sleep 1; done; "
       "trap \"rmdir $L\" EXIT; "))

;; The lease's first line is a comment holding the token, so that sh can read
;; it with `read`: `;; <token>`.
(def ^:private holder
  "cur=none; if [ -f \"$d/lease.edn\" ]; then read -r _ cur < \"$d/lease.edn\" || true; fi; ")

(def ^:private scripts
  {:list
   "d=$1/$2; [ -d \"$d\" ] || exit 0; cd \"$d\" && find events results pages -type f 2>/dev/null; true"

   ;; $3 is the pushing connection's token. The batch is unpacked aside
   ;; first; only then, under the lock, is the lease checked and the batch
   ;; moved into place.
   :push
   (str "set -e; d=$1/$2; s=$d/.incoming.$$; mkdir -p \"$s\"; tar x -f - -C \"$s\"; "
        locked holder
        "if [ \"$cur\" != \"$3\" ]; then rm -rf \"$s\"; echo \"lease not held\" >&2; exit 3; fi; "
        "cd \"$s\"; "
        "find . -type f ! -path \"./events/*\" | while read -r f; do "
        "mkdir -p \"$d/${f%/*}\"; mv \"$f\" \"$d/$f\"; done; "
        "if [ -d events ]; then mkdir -p \"$d/events\"; "
        "ls events | sort -n | while read -r f; do mv \"events/$f\" \"$d/events/$f\"; done; fi; "
        "cd \"$d\"; rm -rf \"$s\"")

   :pull
   "cd \"$1/$2\" && tar c -f - -T -"

   ;; The device's clock, then the lease's last renewal by that clock, and
   ;; the lease itself; just the clock when there is none.
   :lease-read
   "d=$1/$2; date +%s; [ -f \"$d/lease.edn\" ] || exit 0; date -r \"$d/lease.edn\" +%s; cat \"$d/lease.edn\""

   ;; Write the lease from stdin if it is still held by $4 ("none" when
   ;; free): taking it, taking it over, and renewing it are all this. Prints
   ;; ok, or the token that holds it instead.
   :lease-write
   (str "d=$1/$2; mkdir -p \"$d\"; " locked holder
        "if [ \"$cur\" = \"$4\" ]; then cat > \"$d/.lease.tmp\" && mv \"$d/.lease.tmp\" \"$d/lease.edn\" && echo ok; "
        "else cat > /dev/null; echo \"$cur\"; fi")

   :lease-release
   (str "d=$1/$2; [ -d \"$d\" ] || exit 0; " locked holder
        "if [ \"$cur\" = \"$3\" ]; then rm -f \"$d/lease.edn\"; fi")

   :archive
   "d=$1/$2; [ ! -d \"$d\" ] || mv \"$d\" \"$d.archived-$3\""})

(def ^:private arg-re
  ;; A device path, a notebook id, a token or an archive stamp. Nothing here
  ;; needs quoting, so nothing can escape the script it is handed to.
  #"[A-Za-z0-9/][A-Za-z0-9._/-]*")

(defn- command
  "The shell command running `script` with `args`, refusing any argument that
   is not plainly a path, an id, a token or a stamp."
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
      (throw (ex-info (if (= 3 exit)
                        "this connection does not hold the notebook's lease"
                        (str (name script) " on the device failed: "
                             (let [e (str/trim err)] (if (str/blank? e) (str "exit " exit) e))))
                      {:exit exit :lease-lost? (= 3 exit)})))
    (:out r)))

(defn- exec-text! ^String [device script args in]
  (String. ^bytes (exec-ok! device script args in) "UTF-8"))

;; -------- a session's copy --------

(defn mirror
  "A handle on the device copy of the session in `nb`, for the connection
   whose lease token is `token`. `device` is {:ssh-host alias} or, for tests,
   {:local-root dir}."
  [nb notebook-id device token]
  {:nb (io/file nb)
   :notebook-id notebook-id
   :device device
   :token token
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
  (into #{} (filter canonical?) (str/split-lines (exec-text! device :list [notebook-id] nil))))

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
           (note! ~m (cond-> {:ok? false :message (.getMessage e#)}
                       (:lease-lost? (ex-data e#)) (assoc :lease-lost? true))))))))

;; -------- which events are known to match the device --------

;; Event files are named by sequence number, so the same name on both sides
;; need not mean the same event: a computer that lost the lease before its
;; last events reached the device may have numbers another has since used.
;; Events up to this mark have been compared, or sent; above it, an event the
;; device also has is compared before it is trusted.

(defn- confirmed-file [nb] (io/file nb "copied.edn"))

(defn- confirmed-through [nb]
  (or (try (:events-through (edn/read-string (slurp (confirmed-file nb))))
           (catch Exception _ nil))
      0))

(defn- confirm! [nb n]
  (when (> n (confirmed-through nb))
    (store/publish! (confirmed-file nb) (pr-str {:events-through n}))))

(defn- stamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
           (java.time.LocalDateTime/now)))

(defn- set-aside!
  "Move this computer's events numbered `from` and up out of the log, into
   stranded/<stamp>/, where they are kept but no longer replayed. Returns the
   directory and how many."
  [nb from]
  (let [dir (io/file nb "stranded" (stamp) "events")
        moving (filter #(>= (event-number %) from)
                       (filter event-number (local-files nb)))]
    (.mkdirs dir)
    (doseq [p moving]
      (java.nio.file.Files/move (.toPath (io/file nb p))
                                (.toPath (io/file dir (subs p (count "events/"))))
                                (make-array java.nio.file.CopyOption 0)))
    [(.getParentFile dir) (count moving)]))

(defn pull!
  "Fetch whatever the device has that this computer does not. Returns
   {:ok? true :pulled n}, or {:ok? false :message ...}.

   Where this computer's events disagree with the device's, the device's win:
   this computer's, from the first that differs, are set aside under
   stranded/ and reported as :stranded n and :stranded-to dir."
  [{:keys [nb device notebook-id] :as m}]
  (exchange m nil
    (let [remote (reset! (:on-device m) (remote-files m))
          local (local-files nb)
          mark (confirmed-through nb)
          unconfirmed (filter #(and (> (or (event-number %) 0) mark) (remote %)) local)
          wanted (ordered (concat (remove local remote) unconfirmed))
          entries (if (seq wanted)
                    (into {} (tar/read-entries
                              (exec-ok! device :pull [notebook-id]
                                        (.getBytes (str (str/join "\n" wanted) "\n") "UTF-8"))))
                    {})
          differs (->> unconfirmed
                       (remove #(when-let [theirs (get entries %)]
                                  (java.util.Arrays/equals
                                   ^bytes theirs
                                   (Files/readAllBytes (.toPath (io/file nb %))))))
                       (map event-number))
          [aside n-aside] (when (seq differs) (set-aside! nb (apply min differs)))]
      (when aside
        (println "clerkenwell copy:" n-aside "events recorded here differ from the tablet's;"
                 "the tablet's are kept, and these are set aside in" (str aside)))
      ;; In the same order as asked: events last.
      (let [written (doall
                     (for [p wanted
                           :let [data (get entries p)
                                 f (io/file nb p)]
                           :when (and data (canonical? p) (not (.exists f)))]
                       (do (store/publish-bytes! f data) p)))]
        (confirm! nb (reduce max mark (keep event-number remote)))
        (cond-> {:ok? true :pulled (count written)}
          aside (assoc :stranded n-aside :stranded-to (str aside)))))))

(defn flush!
  "Send the device whatever it does not have yet. Returns {:ok? true :pushed n},
   or {:ok? false :message ...}, with :lease-lost? true when the device refused
   it because this connection does not hold the lease. Nothing is lost by a
   failure, since the next flush compares again."
  [{:keys [nb device notebook-id token] :as m}]
  ;; After a failure the device may have taken some of it: list again.
  (exchange m (reset! (:on-device m) nil)
    (let [on-device (listed! m)
          local (local-files nb)
          todo (ordered (remove on-device local))]
      (when (seq todo)
        (exec-ok! device :push [notebook-id token]
                  (tar/write (for [p todo]
                               [p (Files/readAllBytes (.toPath (io/file nb p)))])))
        (swap! (:on-device m) into todo)
        ;; Confirmed as far as every event is either confirmed or just sent.
        (let [sent (set (keep event-number todo))
              mark (confirmed-through nb)]
          (confirm! nb (->> (sort (keep event-number local))
                            (drop-while #(<= % mark))
                            (take-while sent)
                            (reduce max mark)))))
      {:ok? true :pushed (count todo)})))

(defn status
  "How the copy stands: {:ok? :pending n :message}."
  [{:keys [nb] :as m}]
  (let [known @(:on-device m)]
    (assoc @(:status m)
           :pending (when known (count (remove known (local-files nb)))))))

;; -------- the lease --------

(defn read-lease
  "Who holds the session's lease: {:token :host :pid :acquired-at :age-s},
   where :age-s is the seconds since it was taken, by the device's
   clock. Nil when nobody does. Throws when the device cannot be asked."
  [{:keys [device notebook-id]}]
  (let [[now at & more] (str/split-lines (exec-text! device :lease-read [notebook-id] nil))]
    (when at
      (let [lease (edn/read-string (str/join "\n" more))]
        (assoc lease :age-s (- (parse-long now) (parse-long at)))))))

(def ^:private host-name
  ;; What the lease calls this computer. Asked of `hostname` rather than of
  ;; the JVM, whose lookup can stall on a misconfigured resolver.
  (delay (try (let [p (.start (ProcessBuilder. ["hostname" "-s"]))
                    out (str/trim (slurp (.getInputStream p)))]
                (.waitFor p)
                (if (str/blank? out) "unknown" out))
              (catch Exception _ "unknown"))))

(defn- lease-text [token]
  (str ";; " token "\n"
       (pr-str {:token token
                :host @host-name
                :pid (.pid (java.lang.ProcessHandle/current))
                :acquired-at (str (java.time.Instant/now))})
       "\n"))

(defn write-lease!
  "Take the lease for this connection if it is still held by `expected`, a
   token, or :none when free. Returns
   {:ok? true}, {:ok? false :held-by token}, or {:ok? false :message ...} when
   the device could not be asked."
  [{:keys [device notebook-id token]} expected]
  (try
    (let [out (str/trim (exec-text! device :lease-write
                                  [notebook-id token (if (= :none expected) "none" expected)]
                                  (.getBytes ^String (lease-text token) "UTF-8")))]
      (if (= "ok" out)
        {:ok? true}
        {:ok? false :held-by out}))
    (catch Exception e
      {:ok? false :message (.getMessage e)})))

(defn release-lease!
  "Give the lease up, if this connection holds it."
  [{:keys [device notebook-id token]}]
  (try
    (exec-ok! device :lease-release [notebook-id token] nil)
    {:ok? true}
    (catch Exception e
      {:ok? false :message (.getMessage e)})))

;; -------- setting a session aside --------

(defn archive!
  "Set the device copy aside as `<id>.archived-<stamp>`, as the local history
   is. Returns {:ok? ...}; a missing copy is not an error."
  [notebook-id device stamp]
  (try
    (exec-ok! device :archive [notebook-id stamp] nil)
    {:ok? true}
    (catch Exception e
      {:ok? false :message (.getMessage e)})))
