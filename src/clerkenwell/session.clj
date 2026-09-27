(ns clerkenwell.session
  "The entry point Clerkenwell presents inside your own REPL.

   This namespace is loaded into the JVM your CIDER session is connected to, so
   `submit` evaluates in the same process as the rest of your work: your
   namespaces are loaded, your state is live, and the ACTUAL value reaches
   presentation before anything prints it. Recovering rich values from nREPL's
   printed output is exactly what the spec forbids.

   Everything Emacs needs is here. `clerkenwell.main` remains only as a
   development driver for exercising the device without a project.

   One session owns one notebook and is the single writer of its store."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clerkenwell.core :as core]
            [clerkenwell.ink :as ink]
            [clerkenwell.store :as store]
            [clerkenwell.transport :as transport]
            [clerkenwell.watch :as watch]))

(defonce ^:private session (atom nil))

;; Rejects a second submission while one is in flight. Delivery of an earlier
;; result does NOT block a later evaluation; only evaluation is serialized.
(defonce ^:private evaluating (atom false))

(def default-options
  {:state-root "clerkenwell-state"
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

(declare ensure-watching!)

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
               (when-let [live (transport/fresh-identity)]
                 (ensure-watching! (:doc live)))
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

(defn start!
  "Start the transport server and, once the device reports which notebook is
   open, the handwriting watcher.

   `:ssh-host` is a normal SSH host alias. `:on-ink` is called with each new
   handwriting observation, which is how the Emacs buffer learns about it."
  [opts]
  (if-let [s @session]
    ;; Idempotent rather than throwing: re-opening the Emacs buffer against a
    ;; live session is ordinary, and a thrown exception here is indistinguishable
    ;; from a real failure once it has crossed nREPL as an error.
    {:ok? true :already-running? true :port (:port s) :state-root (:state-root s)}
    (let [{:keys [state-root port] :as opts} (merge default-options opts)
          server (transport/start! state-root {:port port})
          stop? (atom false)]
      (reset! session (assoc opts :server server :watcher nil :notebook-id nil
                             :identity-stop stop?))
      (start-identity-poller! stop?)
      {:ok? true :already-running? false :port port :state-root state-root})))

(defn stop!
  "Stop the server and watcher. History and pending deliveries are untouched."
  []
  (when-let [s @session]
    (when-let [stop? (:identity-stop s)] (reset! stop? true))
    (when-let [stop? (:watcher s)] (reset! stop? true))
    (transport/stop! (:server s))
    (reset! session nil)
    {:ok? true}))

(defn- ensure-watching!
  "Start or move the watcher when the device reports a notebook."
  [notebook-id]
  (let [s @session]
    (when (and s (not= notebook-id (:notebook-id s)))
      (when-let [stop? (:watcher s)] (reset! stop? true))
      (swap! session assoc
             :watcher (start-watcher! s notebook-id)
             :notebook-id notebook-id))))

;; -------- what the device says is open --------

(defn active
  "The notebook and page the adapter last reported, or nil when that report is
   too old to believe. A submission aimed at a guess is worse than no
   submission, so this is deliberately strict."
  []
  (when-let [s @session]
    (transport/fresh-identity (:identity-max-age-ms s))))

(defn check-device
  "Verify the two independent paths to the device, which fail separately.

   The tunnel carries the adapter's own outbound requests and needs no ssh from
   this JVM, so a page can be reported while every ssh here is failing. The
   watcher and asset pushes shell out to ssh and need working credentials."
  []
  (if-let [s @session]
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
       :ssh (assoc ssh :host (:ssh-host s))})
    {:running? false}))

(defn status
  "Everything the Emacs buffer needs to render its status line."
  []
  (if-let [s @session]
    (let [live (active)
          nb (when (:notebook-id s)
               (store/notebook-dir (:state-root s) (:notebook-id s)))
          m (when nb (store/read-manifest nb))]
      {:running? true
       :state-root (:state-root s)
       :port (:port s)
       :notebook-id (:notebook-id s)
       :active live
       :evaluating? @evaluating
       :deliveries (frequencies (keep #(get-in % [:delivery :state]) (vals m)))
       :uncertain (vec (for [[id r] m
                             :when (= :uncertain (get-in r [:delivery :state]))]
                         id))})
    {:running? false}))

;; -------- submission --------

(defn submit
  "Evaluate `source` once in `ns-name` and commit the result to the page the
   device currently shows.

   Runs in THIS JVM, so `source` sees everything your REPL sees. Returns a map
   the Emacs buffer renders; `:ok? false` carries `:stage` and `:message`
   describing where it stopped.

   Never resubmits anything. A failure here leaves your draft alone.

   `opts` may carry `:include-source? true` to write the expression above its
   result on the tablet. Emacs records both regardless; this only controls what
   the page receives."
  ([source ns-name] (submit source ns-name nil))
  ([source ns-name opts]
    (let [s @session]
     (cond
       (nil? s)
       {:ok? false :stage :session :message "no Clerkenwell session; call start! first"}

       (not (compare-and-set! evaluating false true))
       {:ok? false :stage :session :message "an evaluation is already in flight"}

       :else
       (try
         (let [live (active)]
           (cond
             (nil? live)
             {:ok? false :stage :identity
              :message "the device has not reported a page recently; is the tunnel up?"}

             (nil? (find-ns (symbol ns-name)))
             {:ok? false :stage :namespace
              :message (str "namespace not loaded in this REPL: " ns-name)}

             :else
             (let [{:keys [doc page]} live
                   _ (ensure-watching! doc)
                   nb (store/notebook-dir (:state-root s) doc)
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
                                    :include-source? (boolean (:include-source? opts))})]
               (if-not (:ok? r)
                 r
                 ;; Evaluation is done and captured. Delivery happens in the
                 ;; background: a transfer failure is never a reason to evaluate
                 ;; again, and waiting on SSH would block the editor.
                 (do
                   (deliver-async! nb (:result-id r) (:ssh-host s))
                   (assoc r
                          :text (get-in r [:presentation :text])
                          :page-id page
                          :notebook-id doc
                          :delivery :queued))))))
         (finally (reset! evaluating false)))))))

;; -------- delivery, without evaluating again --------

(defn- with-notebook [f]
  (if-let [s @session]
    (if-let [id (:notebook-id s)]
      (f (store/notebook-dir (:state-root s) id))
      {:ok? false :message "no notebook linked yet"})
    {:ok? false :message "no Clerkenwell session"}))

(defn retry-delivery
  "Resume a definitely-uninserted result with its existing id and asset."
  [result-id]
  (with-notebook #(transport/retry-delivery! % result-id)))

(defn reconcile
  "Resolve an :uncertain result after looking at the page."
  [result-id present?]
  (with-notebook #(transport/reconcile! % result-id present?)))

(defn abandon
  "Give up on a known-uninserted delivery."
  [result-id]
  (with-notebook #(transport/abandon! % result-id)))

(defn archive-notebook!
  "Set the linked notebook's history aside, so the next observation starts fresh.

   Renames rather than deletes: the record is immutable by design, and a command
   that quietly destroyed it would be at odds with the whole point. The archived
   directory keeps its assets and event log and can be moved back.

   Note this only clears what the HOST remembers. Ink already on the page will
   be observed again as a fresh initial entry, because that is what a first
   observation means -- and it is what keeps placement below it correct. For a
   genuinely blank slate, start on a blank page too."
  []
  (if-let [s @session]
    (if-let [id (:notebook-id s)]
      (let [nb (store/notebook-dir (:state-root s) id)
            stamp (.format (java.time.format.DateTimeFormatter/ofPattern
                            "yyyyMMdd-HHmmss")
                           (java.time.LocalDateTime/now))
            dest (io/file (:state-root s) (str id ".archived-" stamp))]
        ;; Stop the watcher first: it holds the digests it has already seen, so
        ;; leaving it running would skip re-observing the page.
        (when-let [stop? (:watcher s)] (reset! stop? true))
        (swap! session assoc :watcher nil :notebook-id nil)
        (cond
          (not (.exists nb)) {:ok? true :archived nil :message "no history to archive"}
          (.renameTo nb dest) {:ok? true :archived (.getAbsolutePath dest)}
          :else {:ok? false :message (str "could not rename " nb)}))
      {:ok? false :message "no notebook linked"})
    {:ok? false :message "no session"}))

(defn set-ink-colors!
  "Set the colours handwriting is drawn in, and redraw what already exists.

   The images are shown in Emacs, never sent to the device, so they follow the
   editor's theme rather than the page's. Existing entries are redrawn from
   their retained snapshots, so the history does not end up half in one scheme
   and half in another."
  [{:keys [stroke-color background] :as colors}]
  (if-let [s @session]
    (do
      (swap! session assoc :render-opts colors)
      ;; The running watcher captured the old options; restart it.
      (when-let [id (:notebook-id s)]
        (when-let [stop? (:watcher s)] (reset! stop? true))
        (swap! session assoc :watcher nil :notebook-id nil))
      (let [redrawn (when-let [id (:notebook-id s)]
                      (ink/rerender! (store/notebook-dir (:state-root s) id) nil colors))]
        {:ok? true :colors colors :rerendered (count (:rendered redrawn))}))
    {:ok? false :message "no session"}))

(defn history-version
  "A monotonic marker that changes whenever history grows.

   Cheap enough to poll: it reads the event directory listing, not the events.
   The Emacs buffer uses it to avoid refetching history that has not changed."
  []
  (if-let [s @session]
    (if-let [id (:notebook-id s)]
      {:ok? true :version (store/next-sequence (store/notebook-dir (:state-root s) id))}
      {:ok? true :version 0})
    {:ok? false :version 0}))

(defn history
  "The ordered history for the linked notebook: computations and handwriting,
   in host observation order. This is what the Emacs buffer restores from.

   Observation order is not exact physical writing order -- a delayed save can
   batch strokes. An entry stays where its first strokes were observed; later
   ink that overlaps it vertically joins it there."
  []
  (with-notebook
    (fn [nb]
      (let [m (store/read-manifest nb)]
        {:ok? true
         :entries
         (->> (store/read-events nb)
              (filter #(#{:result-created :ink-observed} (:event %)))
              (reduce
               (fn [{:keys [entries index] :as acc} ev]
                 (case (:event ev)
                   :result-created
                   (let [r (:record ev)
                         cur (get m (:id ev))]
                     (update acc :entries conj
                             {:kind :computation
                              :sequence (:sequence ev)
                              :id (:id ev)
                              :source (:source r)
                              :text (get-in r [:presentation :text])
                              :namespace (:namespace r)
                              :page-id (:page-id r)
                              :at (:submitted-at r)
                              :delivery (get-in cur [:delivery :state])}))

                   :ink-observed
                   ;; Absolute: the state root may be relative to the JVM's
                   ;; working directory, which is not where Emacs resolves
                   ;; relative names from.
                   (let [asset (.getAbsolutePath (io/file nb (get-in ev [:asset :path])))
                         n (count (:stroke-ids ev))]
                     (if-let [i (get index (:entry-id ev))]
                       ;; An extension: the entry keeps its place in history
                       ;; and shows its latest, merged image.
                       (update-in acc [:entries i]
                                  #(-> % (update :strokes + n) (assoc :asset asset)))
                       (-> acc
                           (assoc-in [:index (:entry-id ev)] (count entries))
                           (update :entries conj
                                   {:kind :handwriting
                                    :sequence (:sequence ev)
                                    :id (:entry-id ev)
                                    :page-id (:page-id ev)
                                    :at (:at ev)
                                    :strokes n
                                    :asset asset}))))))
               {:entries [] :index {}})
              :entries)}))))
