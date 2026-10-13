(ns clerkenwell.session
  "The entry point Clerkenwell presents inside your own REPL.

   This namespace is loaded into the JVM your CIDER session is connected to, so
   `submit` evaluates in the same process as the rest of your work: your
   namespaces are loaded, your state is live, and the ACTUAL value reaches
   presentation before anything prints it. Recovering rich values from nREPL's
   printed output is exactly what the spec forbids.

   Everything Emacs needs is here. `clerkenwell.main` remains only as a
   development driver for exercising the device without a project.

   The state held here is this REPL's *connection* to the tablet: the
   transport server, the identity poller and the handwriting watcher. It lasts
   as long as the JVM. A *session* is a notebook's history, kept in the store
   and keyed by the notebook's id; the connection serves whichever session the
   tablet has open, and is the single writer of its store. See
   docs/sessions.md."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clerkenwell.core :as core]
            [clerkenwell.ink :as ink]
            [clerkenwell.mirror :as mirror]
            [clerkenwell.placement :as placement]
            [clerkenwell.store :as store]
            [clerkenwell.transport :as transport]
            [clerkenwell.watch :as watch]))

(defonce ^:private connection (atom nil))

(defn- usable-width
  "Page units between the margins, the widest a result can be."
  []
  (let [{:keys [page-width left-margin]} placement/default-config]
    (- page-width (* 2 left-margin))))

;; Rejects a second submission while one is in flight. Delivery of an earlier
;; result does NOT block a later evaluation; only evaluation is serialized.
(defonce ^:private evaluating (atom false))

(def default-options
  ;; :state-root nil means `store/default-root`. :device nil means the
  ;; device's copy is reached over SSH at :ssh-host; tests give
  ;; {:local-root dir} instead.
  {:state-root nil
   :device nil
   :port 14242
   :identity-max-age-ms 5000})

;; -------- lifecycle --------

(defn- start-watcher!
  "Watch `notebook-id` for saved handwriting. Returns a stop atom."
  [{:keys [state-root ssh-host on-ink render-opts]} notebook-id]
  (let [nb (store/notebook-dir state-root notebook-id)
        stop? (atom false)]
    (doto (Thread. (fn []
                     (watch/watch! {:nb nb :ssh-host ssh-host
                                    :notebook-id notebook-id
                                    :render-opts render-opts
                                    :stop? stop?
                                    :on-entry (or on-ink (fn [_]))})))
      (.setDaemon true)
      (.setName (str "clerkenwell-watch-" notebook-id))
      (.start))
    stop?))

(declare ensure-watching! active)

(defn- start-identity-poller!
  "Watch for the device reporting a notebook, and attach the handwriting watcher
   as soon as it does.

   This used to happen only inside `submit`, which meant handwriting was never
   observed until a submission had succeeded -- the buffer would show the page
   but never any ink."
  [stop?]
  (doto (Thread.
         (fn []
           (while (not @stop?)
             (try
               (when-let [live (active)]
                 (ensure-watching! live))
               (catch Exception _ nil))
             (Thread/sleep 1000))))
    (.setDaemon true)
    (.setName "clerkenwell-identity")
    (.start)))

(defn- deliver-async!
  "Push a result's asset to the device off the submission path.

   The transfer is SSH over USB and can be slow or stall; doing it inline made
   the editor wait on it. Delivery state is mutable and shown separately from
   the committed result, so the buffer can report progress without blocking."
  [nb result-id ssh-host]
  (doto (Thread.
         (fn []
           (try
             (transport/push-asset! nb result-id {:ssh-host ssh-host})
             (catch Exception e
               (store/set-delivery! nb result-id :queued
                                    (str "transfer failed: " (.getMessage e)))))))
    (.setDaemon true)
    (.setName (str "clerkenwell-deliver-" result-id))
    (.start)))

(defn- device
  "Where the device's copy of sessions is reached."
  [s]
  (or (:device s) {:ssh-host (:ssh-host s)}))

;; -------- the attached notebook, and its lease --------
;;
;; An attachment is the notebook the tablet has open, as this connection sees
;; it: its device copy, and whether this connection holds its lease. Holding
;; it, the connection watches for handwriting, records results and copies its
;; history to the device.
;;
;; Which connection holds it is decided by the tunnel, which only one computer
;; can have: the tablet reports what it has open only to that one, and a
;; report for a notebook this connection does not hold makes it take the
;; lease, without asking. The lease is a fencing token: a connection that has
;; lost its tunnel without noticing -- a laptop that went to sleep, say -- has
;; its pushes refused on the device, and stops writing.

(defn- held? [att] (boolean (some-> att :state deref :held?)))

(defn- gain!
  "Start writing `att`: fetch everything first, since the log must continue
   the device's, then -- when the lease was not already this connection's --
   mark insertions another left unacknowledged as uncertain, then watch."
  [s att recover?]
  (locking (:lock att)
    (when-not (held? att)
      (let [{:keys [nb mirror notebook-id state]} att]
        (mirror/pull! mirror)
        (ink/restore! nb (:render-opts s))
        (when recover?
          (doseq [id (store/recover! nb)]
            (println (format "!! %s was interrupted mid-insertion -> :uncertain" id))
            (println "   It may or may not be on the page. Inspect, then reconcile.")))
        (swap! state assoc :held? true
               :watcher (start-watcher! s notebook-id))))))

(defn- lose!
  "Stop writing `att`, which another connection has taken."
  [att]
  (locking (:lock att)
    (when-let [w (:watcher @(:state att))] (reset! w true))
    (swap! (:state att) assoc :held? false :watcher nil
           :lost-at (System/currentTimeMillis))
    (println "clerkenwell: another computer has taken this notebook over; nothing more is written from here")))

(defn- take!
  "Make `att`'s lease this connection's, from whoever holds it, and start
   writing. Already holding it, nothing changes. Returns whether it is held;
   throws when the device cannot be asked."
  [s att]
  (let [m (:mirror att)
        current (:token (mirror/read-lease m))
        mine? (= (:lease-token s) current)]
    (cond
      (and mine? (held? att)) true
      ;; Taken while this connection was not looking: whatever it wrote
      ;; since is set aside by the pull, where it differs.
      (held? att) (do (lose! att) (take! s att))
      (:ok? (mirror/write-lease! m (if mine? (:lease-token s) (or current :none))))
      (do (gain! s att (not mine?)) true)
      :else false)))

(defn- try-take!
  "`take!`, recording a failure, so it is not retried for a few seconds."
  [s att]
  (or (try (take! s att) (catch Exception _ false))
      (do (swap! (:state att) assoc :retry-at (+ (System/currentTimeMillis) 5000))
          false)))

(defn- note-name!
  "Ask the tablet what it calls `att`'s notebook, which can change any time."
  [s att]
  (try
    (when-let [n (mirror/notebook-name (device s) (:notebook-id att))]
      (swap! (:state att) assoc :name n))
    (catch Exception _ nil)))

(defn- start-copier!
  "Send what is new in `att` to the device every two seconds, while this
   connection holds its lease, and look for the notebook being renamed every
   thirty."
  [s att]
  (doto (Thread.
         (fn []
           (loop [tick 1]
             (when-not @(:stop att)
               (try
                 (when (and (held? att) (:lease-lost? (mirror/flush! (:mirror att))))
                   (lose! att))
                 (when (zero? (mod tick 15)) (note-name! s att))
                 (catch Exception _ nil))
               (Thread/sleep 2000)
               (recur (inc tick))))))
    (.setDaemon true)
    (.setName (str "clerkenwell-copy-" (:notebook-id att)))
    (.start)))

(defn- release!
  "Let go of `att`: stop copying and watching, send what the device does not
   have yet and give the lease up."
  [att]
  (reset! (:stop att) true)
  (locking (:lock att)
    (when-let [w (:watcher @(:state att))] (reset! w true))
    (when (held? att)
      (mirror/flush! (:mirror att))
      (mirror/release-lease! (:mirror att)))
    (swap! (:state att) assoc :held? false :watcher nil))
  (mirror/close! (:mirror att)))

(defn- record-on-device!
  "Bring the device's copy of the session in `nb` up to date, now: called as
   an insertion is about to be authorized. Refused on the device unless this
   connection holds the lease."
  [nb]
  (let [s @connection
        m (some-> s :attachment :mirror)
        m (if (and m (= (.getCanonicalFile (io/file nb)) (.getCanonicalFile (:nb m))))
            m
            (mirror/mirror nb (.getName (io/file nb)) (device s) (:lease-token s)))]
    (mirror/flush! m)))

(defn- writable?
  "Whether this connection may write the notebook `doc`: it is attached and
   holds its lease."
  [doc]
  (let [att (:attachment @connection)]
    (and (= doc (:notebook-id att)) (held? att))))

(defn- notebook-dir-for
  "The store of `notebook-id`, or of the attached notebook when nil."
  [s notebook-id]
  (when-let [id (or notebook-id (:notebook-id s))]
    (store/notebook-dir (:state-root s) id)))

(defn- lease-token []
  (let [bs (byte-array 16)]
    (.nextBytes (java.security.SecureRandom.) bs)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bs))))

(defn start!
  "Start the transport server and, once the device reports which notebook is
   open, attach to it.

   `:ssh-host` is a normal SSH host alias. `:on-ink` is called with each new
   handwriting observation, which is how the Emacs buffer learns about it."
  [opts]
  (if-let [s @connection]
    ;; Idempotent rather than throwing: re-opening the Emacs buffer against a
    ;; live connection is ordinary, and a thrown exception here is indistinguishable
    ;; from a real failure once it has crossed nREPL as an error.
    {:ok? true :already-running? true :port (:port s) :state-root (:state-root s)}
    (let [{:keys [port] :as opts} (merge default-options opts)
          state-root (.getAbsolutePath (io/file (or (:state-root opts)
                                                    (store/default-root))))
          server (transport/start! state-root {:port port
                                               ;; Done on attach, holding the lease.
                                               :recover? false
                                               :writable? #(writable? %)
                                               :on-attempt #(record-on-device! %)})
          stop? (atom false)]
      (reset! connection (assoc opts :state-root state-root
                                ;; Identifies this connection, so a thread
                                ;; of an earlier one cannot write into it.
                                :token (Object.)
                                ;; Names it in the leases it holds.
                                :lease-token (lease-token)
                                :server server :attachment nil :notebook-id nil
                                :identity-stop stop?))
      (start-identity-poller! stop?)
      {:ok? true :already-running? false :port port :state-root state-root})))

(defn- detach!
  "Let go of the notebook connection `s` is attached to. Its history is
   untouched. A connection that is no longer the current one is left as it
   is."
  [s]
  (when-let [att (:attachment s)] (release! att))
  (swap! connection #(if (and % (identical? (:token %) (:token s)))
                       (assoc % :attachment nil :notebook-id nil)
                       %)))

(defn stop!
  "Stop the server and let go of the notebook: what the device does not have
   yet is sent, and the lease given up, so another computer can continue from
   here. History and pending deliveries are untouched."
  []
  (when-let [s @connection]
    (when-let [stop? (:identity-stop s)] (reset! stop? true))
    (detach! s)
    (transport/stop! (:server s))
    (reset! connection nil)
    {:ok? true}))

(def ^:private attach-lock
  "Serialises attaching to a notebook with setting its history aside, so the
   identity poller cannot re-attach -- and copy everything back to the device
   -- between the two halves of an archive."
  (Object.))

(defn- ensure-watching!
  "Attach to the notebook the tablet reports, in `live`, and take its lease:
   the tablet reporting to this connection means this is the computer in use.
   Taking it fetches what the device's copy has that this computer does not,
   and the two are kept in step from then on.

   A notebook already attached is taken again when it was lost, on a report
   that came after losing it, and checked again when reports resume after a
   gap -- the tunnel was down, and another computer may have had it meanwhile.

   Handwriting images are derived, so any that are missing -- the cache was
   cleared, or the history was recorded elsewhere -- are redrawn first, before
   the notebook becomes the one history is read from."
  [{notebook-id :doc at :at}]
  (locking attach-lock
    (let [s @connection]
      (when s
        (if (= notebook-id (:notebook-id s))
          (let [att (:attachment s)
                {:keys [held? lost-at retry-at seen-at]} @(:state att)
                gap? (> (- at (or seen-at 0)) (:identity-max-age-ms s))]
            (swap! (:state att) assoc :seen-at at)
            (when (and (or (not held?) gap?)
                       (> at (or lost-at 0))
                       (> (System/currentTimeMillis) (or retry-at 0)))
              (try-take! s att)))
          (do
            (detach! s)
            (let [nb (store/notebook-dir (:state-root s) notebook-id)
                  att {:notebook-id notebook-id
                       :nb nb
                       :mirror (mirror/mirror nb notebook-id (device s) (:lease-token s))
                       :stop (atom false)
                       :lock (Object.)
                       :state (atom {:held? false :seen-at at})}]
              ;; An unreachable device leaves the notebook unwritable here, and
              ;; the next report tries again; without SSH nothing could be
              ;; written to the page anyway.
              (note-name! s att)
              (try-take! s att)
              (start-copier! s att)
              ;; The connection may have been stopped, and another started,
              ;; while this one was fetching. Attach only to the one asked.
              (let [same? #(identical? (:token %) (:token s))
                    after (swap! connection #(if (same? %)
                                               (assoc % :attachment att :notebook-id notebook-id)
                                               %))]
                (when-not (same? after)
                  (release! att))))))))))

(defn lease
  "Whether this connection holds the attached notebook's lease: {:held?}."
  []
  (when-let [att (:attachment @connection)]
    {:held? (held? att)}))

(defn- notebook-name
  "What the tablet calls the attached notebook, if it has said."
  [s]
  (some-> s :attachment :state deref :name))

(defn- unwritable-message
  "Why the attached notebook cannot be written here, in words."
  []
  (if (:lost-at (some-> @connection :attachment :state deref))
    "another computer has taken this notebook over; it is taken back when the tablet reports here again"
    "this notebook's lease could not be taken on the tablet; is ssh to it working?"))

;; -------- what the device says is open --------

(defn active
  "The notebook and page the adapter last reported, or nil when that report is
   too old to believe. A submission aimed at a guess is worse than no
   submission, so this is deliberately strict."
  []
  (when-let [s @connection]
    (transport/fresh-identity (:identity-max-age-ms s))))

(defn check-device
  "Verify the two independent paths to the device, which fail separately.

   The tunnel carries the adapter's own outbound requests and needs no ssh from
   this JVM, so a page can be reported while every ssh here is failing. The
   watcher and asset pushes shell out to ssh and need working credentials."
  []
  (if-let [s @connection]
    (let [reported (active)
          ssh (try
                (let [{:keys [exit out err]}
                      (transport/run-ssh (:ssh-host s) "echo" "clerkenwell-ok")]
                  (if (zero? exit)
                    {:ok? true}
                    {:ok? false :message (str/trim (str err out))}))
                (catch Exception e {:ok? false :message (.getMessage e)}))]
      {:tunnel {:ok? (some? reported)
                :message (if reported
                           (str "device reports page " (:page reported))
                           "no recent report from the adapter; check the tunnel and xovi")}
       :ssh (assoc ssh :host (:ssh-host s))
       :copy (if-let [m (some-> s :attachment :mirror)]
               (mirror/status m)
               {:ok? true :pending nil :message "no notebook attached"})})
    {:running? false}))

(defn status
  "Everything the Emacs buffer needs to render its status line."
  []
  (if-let [s @connection]
    (let [live (active)
          nb (when (:notebook-id s)
               (store/notebook-dir (:state-root s) (:notebook-id s)))
          m (when nb (store/read-manifest nb))]
      {:running? true
       :state-root (:state-root s)
       :port (:port s)
       :notebook-id (:notebook-id s)
       :name (notebook-name s)
       :namespace (some-> nb store/session-namespace)
       :active live
       :evaluating? @evaluating
       ;; The device's copy of the session: {:ok? :pending :message}.
       :copy (some-> s :attachment :mirror mirror/status)
       ;; Whether this connection may write the notebook: {:held?}.
       :lease (lease)
       :deliveries (frequencies (keep #(get-in % [:delivery :state]) (vals m)))
       :uncertain (vec (for [[id r] m
                             :when (= :uncertain (get-in r [:delivery :state]))]
                         id))})
    {:running? false}))

;; -------- submission --------

(defn- namespace-for
  "The namespace `notebook-id`'s submissions are evaluated in: the one it
   remembers, or `default` for a notebook that has none yet."
  [s notebook-id default]
  (or (store/session-namespace (store/notebook-dir (:state-root s) notebook-id))
      default))

(defn submit
  "Evaluate `source` once in the notebook's namespace and commit the result to
   the page the device currently shows. A notebook remembers the namespace its
   results were evaluated in; `ns-name` is used for one that has none yet.

   Runs in THIS JVM, so `source` sees everything your REPL sees. Returns a map
   the Emacs buffer renders; `:ok? false` carries `:stage` and `:message`
   describing where it stopped.

   Never resubmits anything. A failure here leaves your draft alone.

   `opts` may carry `:include-source? true` to write the expression above its
   result on the tablet. Emacs records both regardless; this only controls what
   the page receives. `:notebook-id` says which notebook the submission is
   meant for, and is refused unless the tablet has that one open.

   `:settings` is how the result is presented and placed, each part merged
   over the defaults: `:style` as `html/default-style`, `:limits` as
   `render/default-limits` and `:layout` as `placement/default-config`.
   Emacs sends its customisations with every submission, so a change takes
   effect with the next."
  ([source ns-name] (submit source ns-name nil))
  ([source ns-name opts]
    (let [s @connection]
     (cond
       (nil? s)
       {:ok? false :stage :connection :message "not connected; call start! first"}

       (not (compare-and-set! evaluating false true))
       {:ok? false :stage :connection :message "an evaluation is already in flight"}

       :else
       (try
         (let [live (active)]
           (cond
             (nil? live)
             {:ok? false :stage :identity
              :message "the device has not reported a page recently; is the tunnel up?"}

             (and (:notebook-id opts) (not= (:notebook-id opts) (:doc live)))
             {:ok? false :stage :identity
              :message "this notebook is not the one open on the tablet"}

             (do (ensure-watching! live)
                 (not (writable? (:doc live))))
             ;; Checked before evaluating: nothing has happened yet, so
             ;; submitting again is safe.
             {:ok? false :stage :lease :message (unwritable-message)}

             (nil? (find-ns (symbol (namespace-for s (:doc live) ns-name))))
             {:ok? false :stage :namespace
              :message (str "namespace not loaded in this REPL: "
                            (namespace-for s (:doc live) ns-name))}

             :else
             (let [{:keys [doc page]} live
                   nb (store/notebook-dir (:state-root s) doc)
                   ns-name (namespace-for s doc ns-name)
                   prev-bottom (->> (vals (store/read-manifest nb))
                                    (filter #(= page (:page-id %)))
                                    (keep #(get-in % [:placement :bottom]))
                                    (reduce max 0))
                   r (core/submit! {:state-root (:state-root s)
                                    :notebook-id doc
                                    :page-id page
                                    :source source
                                    :namespace ns-name
                                    :ink-bottom (ink/ink-bottom nb page)
                                    :outputs-bottom (when (pos? prev-bottom) prev-bottom)
                                    :include-source? (boolean (:include-source? opts))
                                    :style (get-in opts [:settings :style])
                                    :limits (get-in opts [:settings :limits])
                                    :config (get-in opts [:settings :layout])})]
               (if-not (:ok? r)
                 r
                 ;; Evaluation is done and captured. Delivery happens in the
                 ;; background: a transfer failure is never a reason to evaluate
                 ;; again, and waiting on SSH would block the editor.
                 (do
                   ;; A hidden value without its source has nothing to send.
                   (when-not (= :skipped (:delivery r))
                     (deliver-async! nb (:result-id r) (:ssh-host s)))
                   (assoc r
                          :text (get-in r [:presentation :text])
                          :page-id page
                          :notebook-id doc
                          :delivery (or (:delivery r) :queued)))))))
         (finally (reset! evaluating false)))))))

;; -------- delivery, without evaluating again --------

(defn- with-notebook
  "Call `f` with the store of `notebook-id`, or of the attached notebook when
   nil."
  ([f] (with-notebook nil f))
  ([notebook-id f]
   (if-let [s @connection]
     (if-let [nb (notebook-dir-for s notebook-id)]
       (f nb)
       {:ok? false :message "no notebook linked yet"})
     {:ok? false :message "not connected"})))

(defn- with-held-notebook
  "As `with-notebook`, for changes to its history, which only the holder of
   its lease may make: the notebook must be the one the tablet has open."
  ([f] (with-held-notebook nil f))
  ([notebook-id f]
   (with-notebook notebook-id
     (fn [nb]
       (cond
         (and notebook-id (not= notebook-id (:notebook-id @connection)))
         {:ok? false :message "this notebook is not the one open on the tablet"}
         (writable? (.getName (io/file nb))) (f nb)
         :else {:ok? false :message (unwritable-message)})))))

(defn set-namespace!
  "Evaluate the attached notebook's submissions in `ns-name` from now on.
   When `notebook-id` is given, it must be the attached one."
  ([ns-name] (set-namespace! ns-name nil))
  ([ns-name notebook-id]
   (with-held-notebook notebook-id
     (fn [nb]
       (store/append-event! nb {:event :namespace-set :namespace ns-name})
       {:ok? true :namespace ns-name}))))

(defn retry-delivery
  "Resume a definitely-uninserted result with its existing id and asset."
  [result-id]
  (with-held-notebook #(transport/retry-delivery! % result-id)))

(defn reconcile
  "Resolve an :uncertain result after looking at the page."
  [result-id present?]
  (with-held-notebook #(transport/reconcile! % result-id present?)))

(defn abandon
  "Give up on a known-uninserted delivery."
  [result-id]
  (with-held-notebook #(transport/abandon! % result-id)))

(defn archive-notebook!
  "Set the linked notebook's history aside, so the next observation starts fresh.

   Renames rather than deletes: the record is immutable by design, and a command
   that quietly destroyed it would be at odds with the whole point. The archived
   directory keeps its assets and event log and can be moved back.

   The device's copy is set aside first, the same way; if that cannot be
   done, nothing is archived, since the next attach would fetch it all back.

   Note this only clears what the HOST remembers. Ink already on the page will
   be observed again as a fresh initial entry, because that is what a first
   observation means -- and it is what keeps placement below it correct. For a
   genuinely blank slate, start on a blank page too.

   When `notebook-id` is given, it must be the attached one."
  [& [notebook-id]]
  (locking attach-lock
    (let [s @connection
          id (:notebook-id s)]
      (cond
        (nil? s) {:ok? false :message "not connected"}
        (nil? id) {:ok? false :message "no notebook linked"}
        (and notebook-id (not= notebook-id id))
        {:ok? false :message "this notebook is not the one open on the tablet"}
        (not (writable? id))
        {:ok? false :message (unwritable-message)}
        :else
        (let [nb (store/notebook-dir (:state-root s) id)
              stamp (.format (java.time.format.DateTimeFormatter/ofPattern
                              "yyyyMMdd-HHmmss")
                             (java.time.LocalDateTime/now))
              dest (io/file (:state-root s) (str id ".archived-" stamp))]
          ;; Let go first: the watcher holds the digests it has already seen,
          ;; so leaving it running would skip re-observing the page, and a
          ;; flush could recreate what is about to be moved.
          (detach! s)
          (let [device-copy (mirror/archive! id (device s) stamp)]
            (cond
              (not (:ok? device-copy))
              {:ok? false :message (str "could not set aside the device's copy: "
                                        (:message device-copy))}
              (not (.exists nb)) {:ok? true :archived nil :message "no history to archive"}
              (.renameTo nb dest) {:ok? true :archived (.getAbsolutePath dest)}
              :else {:ok? false :message (str "could not rename " nb)})))))))

(defn set-render-opts!
  "Change how handwriting is drawn -- colours, resolution -- and redraw what
   already exists.

   The images are shown in Emacs, never sent to the device, so they follow the
   editor's theme rather than the page's. Existing entries are redrawn from
   their retained snapshots, so the history does not end up half in one scheme
   and half in another. Options not given keep their current values."
  [opts]
  (if-let [s @connection]
    (let [merged (merge (:render-opts s) opts)]
      (swap! connection assoc :render-opts merged)
      ;; The running watcher captured the old options; restart it.
      (when (:notebook-id s) (detach! s))
      (let [redrawn (when-let [id (:notebook-id s)]
                      (ink/rerender! (store/notebook-dir (:state-root s) id) nil merged))]
        {:ok? true :render-opts merged :rerendered (count (:rendered redrawn))}))
    {:ok? false :message "not connected"}))

(defn history-version
  "A monotonic marker that changes whenever the history of `notebook-id`
   grows -- the attached notebook's, when not given -- and what Emacs needs
   to know which notebook its buffers are for.

   Cheap enough to poll: it reads the event directory listing, not the events.
   The Emacs buffer uses it to avoid refetching history that has not changed.
   :notebook-id is the attached notebook, :name what the tablet calls it.
   :held? says whether this connection may write it, which changes without
   history changing when the lease is taken or lost."
  ([] (history-version nil))
  ([notebook-id]
   (if-let [s @connection]
     (let [attached (:notebook-id s)
           nb (notebook-dir-for s notebook-id)]
       (cond-> {:ok? true
                :version (if nb (store/next-sequence nb) 0)
                :notebook-id attached}
         attached (assoc :held? (writable? attached)
                         :name (notebook-name s))
         nb (assoc :namespace (store/session-namespace nb))))
     {:ok? false :version 0})))

(defn history
  "The ordered history for the linked notebook: computations and handwriting,
   in host observation order. This is what the Emacs buffer restores from.

   Observation order is not exact physical writing order -- a delayed save can
   batch strokes. An entry stays where its first strokes were observed; later
   ink that overlaps it vertically joins it there.

   The history of `notebook-id`, when given, whether or not the tablet has it
   open: it is read from this computer's copy."
  [& [notebook-id]]
  (with-notebook notebook-id
    (fn [nb]
      (let [m (store/read-manifest nb)
            events (store/read-events nb)
            ;; An entry is shown once, where its first observation was, with the
            ;; image most recently drawn for it -- whether by an extension or by
            ;; a retroactive join.
            groups (ink/entry-groups
                    (filter #(#{:ink-observed :ink-joined} (:event %)) events))
            first-seen (into {} (map (fn [g] [(:sequence (first (:events g))) g]) groups))]
        {:ok? true
         :entries
         (vec
          (for [ev events
                :let [g (get first-seen (:sequence ev))]
                :when (or (= :result-created (:event ev))
                          (and (= :ink-observed (:event ev)) g))]
            (case (:event ev)
              :result-created
              (let [r (:record ev)
                    cur (get m (:id ev))]
                {:kind :computation
                 :sequence (:sequence ev)
                 :id (:id ev)
                 :source (:source r)
                 ;; The whole presentation, which the buffer draws by kind.
                 ;; :text stays for buffers older than that.
                 :presentation (:presentation r)
                 ;; An image result, as the page shows it: the buffer sizes
                 ;; it from its width on the page, in page units.
                 :image (some->> (get-in r [:presentation :image])
                                 (io/file nb) (.getAbsolutePath))
                 :page-width (when (get-in r [:presentation :image])
                               (min (get-in r [:presentation :width])
                                    (usable-width)))
                 :text (get-in r [:presentation :text])
                 :namespace (:namespace r)
                 :page-id (:page-id r)
                 :at (:submitted-at r)
                 :delivery (get-in cur [:delivery :state])})

              :ink-observed
              {:kind :handwriting
               :sequence (:sequence ev)
               :id (:entry-id g)
               :page-id (:page-id ev)
               :at (:at ev)
               :strokes (reduce + (map (comp count :stroke-ids) (:events g)))
               ;; In page units, whatever resolution the image was drawn at:
               ;; the buffer sizes images from this, not from their pixels.
               :page-width (:width (ink/extent (:bbox g)))
               ;; Absolute: the state root may be relative to the JVM's
               ;; working directory, which is not where Emacs resolves
               ;; relative names from.
               :asset (.getAbsolutePath (io/file nb (get-in g [:asset :path])))})))}))))

(defn regroup
  "Retroactively merge handwriting entries that overlap vertically, as live
   observation now does. Safe to repeat: a second run finds nothing to do.
   When `notebook-id` is given, it must be the attached one."
  [& [notebook-id]]
  (with-held-notebook notebook-id
    (fn [nb]
      (let [r (ink/regroup! nb nil (:render-opts @connection))]
        (assoc r :ok? true :merged (reduce + (map (comp count :entries) (:joined r))))))))
