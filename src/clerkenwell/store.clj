(ns clerkenwell.store
  "Manifest, event log, assets and recovery.

   Layout, per clerkenwell.md and docs/sessions.md:

     <state-root>/<notebook-uuid>/
       events/<sequence>.edn               canonical
       results/<result-uuid>/record.edn    canonical
       results/<result-uuid>/result.png    canonical
       pages/<page-uuid>/<snapshot-id>.rm  canonical
       manifest.edn                        derived: replayed from events
       handwriting/<entry-uuid>.png        derived: drawn from the snapshots

   Canonical parts are the session itself. Derived parts are this computer's
   cache of them, and can be deleted at any time: the manifest is replayed on
   read, and handwriting is redrawn, in this computer's colours, by
   `clerkenwell.ink/restore!`.

   Two invariants do the real work:

   1. Everything is published by writing a temp file in the same directory and
      renaming it, so a reader never sees a partially written record or asset.
   2. The event log is the source of truth and the manifest is a derived cache.
      Recovery replays events, so a crash between the event write and the
      manifest write loses nothing.

   Immutable content (source, presentation, asset) is kept separate from mutable
   delivery state, so a delivery retry can never rewrite what was computed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file AtomicMoveNotSupportedException Files StandardCopyOption]
           [java.security MessageDigest]))

(def schema-version 1)

;; Delivery states, per clerkenwell.md. `uncertain` means insertion may have
;; happened and automatic replay is prohibited. `skipped` means there was
;; nothing to put on the page: a hidden value, submitted without its source.
(def delivery-states
  #{:queued :transferred :waiting-for-page :prepared
    :insertion-started :inserted :failed :uncertain :skipped})

;; -------- atomic publication --------

(defn- move! [from to]
  (try
    (Files/move (.toPath from) (.toPath to)
                (into-array StandardCopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    (catch AtomicMoveNotSupportedException _
      (Files/move (.toPath from) (.toPath to)
                  (into-array StandardCopyOption
                              [StandardCopyOption/REPLACE_EXISTING])))))

(defn publish!
  "Write `content` to `f` via a same-directory temp file plus rename. Readers
   either see the previous version or the new one, never a partial write."
  [f content]
  (let [f (io/file f)]
    (io/make-parents f)
    (let [tmp (io/file (.getParentFile f) (str "." (.getName f) ".tmp"))]
      (spit tmp content)
      (move! tmp f)
      f)))

(defn publish-bytes!
  "As `publish!`, for binary assets."
  [f ^bytes bs]
  (let [f (io/file f)]
    (io/make-parents f)
    (let [tmp (io/file (.getParentFile f) (str "." (.getName f) ".tmp"))]
      (with-open [o (io/output-stream tmp)] (.write o bs))
      (move! tmp f)
      f)))

(defn sha256 [f]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (Files/readAllBytes (.toPath (io/file f)))]
    (.update md bs)
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

;; -------- paths --------

(defn default-root
  "Where sessions are kept unless told otherwise: a cache outside any project,
   since a session belongs to a document on the tablet, not to the code."
  []
  (io/file (or (System/getenv "XDG_CACHE_HOME")
               (io/file (System/getProperty "user.home") ".cache"))
           "clerkenwell"))

(defn notebook-dir [state-root notebook-id]
  (io/file state-root (str notebook-id)))

(defn- manifest-file [nb] (io/file nb "manifest.edn"))
(defn- events-dir [nb] (io/file nb "events"))
(defn result-dir [nb result-id] (io/file nb "results" (str result-id)))
(defn page-dir [nb page-id] (io/file nb "pages" (str page-id)))
(defn handwriting-file [nb entry-id] (io/file nb "handwriting" (str entry-id ".png")))

;; -------- events --------

(defn- event-seq [f]
  (parse-long (str/replace (.getName f) ".edn" "")))

(defn read-events
  "All events in sequence order. Files that fail to parse are skipped rather
   than aborting recovery -- a torn final write must not make the whole history
   unreadable. (Publication is atomic, so this should not occur; tolerating it
   is belt and braces.)"
  [nb]
  (let [d (events-dir nb)]
    (if-not (.isDirectory d)
      []
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName %) ".edn"))
           (sort-by event-seq)
           (keep (fn [f]
                   (try (edn/read-string (slurp f))
                        (catch Exception _ nil))))
           vec))))

(defn next-sequence
  "Monotonic host observation order. This is the order we observed things, not
   exact physical writing order: a delayed save can batch strokes."
  [nb]
  (let [d (events-dir nb)]
    (if-not (.isDirectory d)
      1
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName %) ".edn"))
           (map event-seq)
           (reduce max 0)
           inc))))

(def ^:private append-lock
  "Serialises appends within this JVM. Choosing the next sequence and
   publishing it is a read-then-write, and the watcher, evaluation and delivery
   all append from their own threads: two of them racing would take the same
   number, and the later rename would silently replace the earlier event."
  (Object.))

(defn append-event!
  "Append one event and return it with its assigned :sequence."
  [nb event]
  (locking append-lock
    (let [n (next-sequence nb)
          ev (assoc event :sequence n :at (str (java.time.Instant/now)))]
      (publish! (io/file (events-dir nb) (str n ".edn")) (pr-str ev))
      ev)))

(defn session-namespace
  "The namespace the session's submissions are evaluated in, or nil for one
   that has none yet: the last set with :namespace-set, or failing that the
   last a result was evaluated in -- which is how a session first gets one."
  [nb]
  (some (fn [ev]
          (case (:event ev)
            :namespace-set (:namespace ev)
            :result-created (get-in ev [:record :namespace])
            nil))
        (rseq (read-events nb))))

;; -------- manifest (derived) --------

(defn replay
  "Rebuild manifest state from the event log. Pure, so recovery and the
   in-memory cache cannot disagree."
  [events]
  (reduce (fn [m {:keys [event id] :as ev}]
            (case event
              :result-created (assoc m id (:record ev))
              :asset-attached (assoc-in m [id :asset] (:asset ev))
              ;; The device path is kept out of delivery state on purpose: an
              ;; ack overwrites :detail, and losing the remote path on an abort
              ;; would make the result unservable despite its bytes being there.
              :asset-pushed   (assoc-in m [id :remote-path] (:remote-path ev))
              :placement-decided (assoc-in m [id :placement] (:placement ev))
              :delivery       (assoc-in m [id :delivery]
                                        (select-keys ev [:state :detail :at]))
              m))
          {}
          events))

(defn read-manifest
  "The manifest cache if present, else rebuilt from events. Always reconciled
   against the log, because the manifest write may have been interrupted."
  [nb]
  (let [from-events (replay (read-events nb))
        f (manifest-file nb)]
    (if (.exists f)
      (let [cached (try (edn/read-string (slurp f)) (catch Exception _ nil))]
        (if (= cached from-events) cached from-events))
      from-events)))

(defn- save-manifest! [nb m]
  (publish! (manifest-file nb) (pr-str m)))

;; -------- results --------

(defn create-result!
  "Record an immutable computation result. `presentation` must already be the
   bounded text from `render/present`; the original JVM value is never stored.

   The record and its delivery state are separate: delivery may be updated many
   times, the record never."
  [nb {:keys [result-id notebook-id page-id source namespace presentation asset
              placement]}]
  (let [record {:schema-version schema-version
                :result-id result-id
                :notebook-id notebook-id
                :page-id page-id
                :source source
                :namespace namespace
                :submitted-at (str (java.time.Instant/now))
                :presentation presentation
                :asset asset
                :placement placement
                :delivery {:state :queued}}]
    (publish! (io/file (result-dir nb result-id) "record.edn") (pr-str record))
    (append-event! nb {:event :result-created :id result-id :record record})
    (save-manifest! nb (read-manifest nb))
    record))

(defn attach-asset!
  "Attach a rendered asset to an existing result. Assets are immutable and live
   under their result id; delivery retries reuse these exact bytes."
  [nb result-id {:keys [path width height]}]
  (let [dest (io/file (result-dir nb result-id) "result.png")]
    (publish-bytes! dest (Files/readAllBytes (.toPath (io/file path))))
    (let [asset {:path (str "results/" result-id "/result.png")
                 :sha256 (sha256 dest)
                 ;; The adapter checks byteLength against this before recording
                 ;; an attempt, so a truncated transfer cannot be inserted.
                 :bytes (.length dest)
                 :width width :height height}]
      (append-event! nb {:event :asset-attached :id result-id :asset asset})
      asset)))

(defn record-placement!
  "Record where this result is to go. Placement is decided after rasterization
   (it depends on the asset's height), so it is a separate event rather than
   part of the initial record."
  [nb result-id placement]
  (append-event! nb {:event :placement-decided :id result-id :placement placement})
  (save-manifest! nb (read-manifest nb))
  placement)

(defn record-push!
  "Note where a result's asset now lives on the device."
  [nb result-id remote-path]
  (append-event! nb {:event :asset-pushed :id result-id :remote-path remote-path})
  (save-manifest! nb (read-manifest nb))
  remote-path)

(defn set-delivery!
  "Move a result's mutable delivery state. Refuses unknown states so a typo
   cannot invent one."
  [nb result-id state & [detail]]
  (when-not (delivery-states state)
    (throw (ex-info "unknown delivery state" {:state state :known delivery-states})))
  (append-event! nb {:event :delivery :id result-id :state state :detail detail})
  (let [m (read-manifest nb)]
    (save-manifest! nb m)
    (get m result-id)))

(defn recover!
  "Any result left mid-insertion by a previous run may or may not have landed.
   Mark it :uncertain; the spec prohibits replaying it automatically. Returns
   the ids that need reconciliation."
  [nb]
  (let [m (read-manifest nb)
        stuck (for [[id r] m
                    :when (= :insertion-started (get-in r [:delivery :state]))]
                id)]
    (doseq [id stuck]
      (append-event! nb {:event :delivery :id id :state :uncertain
                         :detail "interrupted before acknowledgement"}))
    (save-manifest! nb (read-manifest nb))
    (vec stuck)))
