(ns clerkenwell.ink
  "Handwriting history: rendering observed strokes, and recording them durably.

   This is an ordered historical record, not a mirror of the page. The renderer
   draws fixed-width polylines, so erasures, transformations, layers, brush
   appearance and existing images are approximate or absent.

   New strokes that overlap an existing entry vertically extend that entry
   rather than starting a new one, so pausing mid-paragraph does not split it.
   Extension is itself an appended event sharing the entry's id: the log is
   never rewritten, and an entry is the group of events that carry its id.

   Two rules keep the history honest:

   - Stroke ids are committed only after the entry and its assets are durable.
     A failed pull can then only cost a repeat observation, never a permanent
     hole in the history.
   - Snapshots and observations are deduplicated by identity -- the stroke ids
     themselves and a content hash of the page file -- never by timestamp."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clerkenwell.lines :as lines]
            [clerkenwell.store :as store]))

(def default-opts
  {:stroke-color "black"
   :background "white"
   :stroke-width 3
   :margin 20})

;; -------- rendering --------

(defn- polyline-arg
  "One stroke as a magick `polyline` primitive, translated from scene
   coordinates (origin top-centre, x may be negative) into image coordinates
   (origin top-left)."
  [stroke tx ty]
  (when-let [pts (seq (:points stroke))]
    (str "polyline "
         (str/join " " (map (fn [{:keys [x y]}]
                              (str (int (- x tx)) "," (int (- y ty))))
                            pts)))))

(defn render-strokes!
  "Render `strokes` to a PNG at `out-path`, cropped to their bounding box plus a
   margin. Returns {:path :width :height :bbox} or nil when there is nothing to
   draw."
  ([strokes out-path] (render-strokes! strokes out-path nil))
  ([strokes out-path opts]
   (let [{:keys [stroke-color background stroke-width margin]} (merge default-opts opts)
         bb (lines/bbox (filter lines/ink? strokes))]
     (when bb
       (let [w (int (+ (- (:max-x bb) (:min-x bb)) (* 2 margin)))
             h (int (+ (- (:max-y bb) (:min-y bb)) (* 2 margin)))
             tx (- (:min-x bb) margin)
             ty (- (:min-y bb) margin)
             draws (mapcat (fn [pl] ["-draw" pl])
                           (keep #(polyline-arg % tx ty) strokes))
             out (io/file out-path)]
         (io/make-parents out)
         (let [{:keys [exit err]}
               (apply sh/sh (concat ["magick" "-size" (str w "x" h)
                                     (str "xc:" background)
                                     "-fill" "none"
                                     "-stroke" stroke-color
                                     "-strokewidth" (str stroke-width)
                                     "-antialias"]
                                    draws
                                    [(str out)]))]
           (when-not (zero? exit)
             (throw (ex-info "ImageMagick failed rendering strokes"
                             {:exit exit :err err})))
           {:path (str out) :width w :height h :bbox bb}))))))

;; -------- observed state --------

(defn observed-ids
  "Stroke ids already recorded for `page-id`, replayed from the event log."
  [nb page-id]
  (into #{}
        (comp (filter #(and (= :ink-observed (:event %)) (= page-id (:page-id %))))
              (mapcat :stroke-ids))
        (store/read-events nb)))

(defn observed-snapshots
  "Content hashes of page files already processed for `page-id`. A save that
   changes nothing we care about still produces a new mtime, so snapshots are
   identified by content."
  [nb page-id]
  (into #{}
        (comp (filter #(and (= :ink-observed (:event %)) (= page-id (:page-id %))))
              (map :snapshot-id))
        (store/read-events nb)))

(defn ink-bottom
  "Bottom-most y of all handwriting observed on `page-id`, or nil if none.

   Uses every observation, not just the latest, so a result placed after a save
   that added nothing still clears the ink above it. The high-water mark is
   deliberately retained after erasure: native movement is not tracked, and the
   spec forbids pretending otherwise."
  [nb page-id]
  (let [bottoms (->> (store/read-events nb)
                     (filter #(and (= :ink-observed (:event %)) (= page-id (:page-id %))))
                     (keep #(get-in % [:bbox :max-y])))]
    (when (seq bottoms) (reduce max bottoms))))

;; -------- recording --------

(defn- sha256-bytes [^bytes bs]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (.update md bs)
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- ink-events
  "Observations and joins for `page-id` (or all pages), in sequence order."
  [nb page-id]
  (filter #(and (#{:ink-observed :ink-joined} (:event %))
                (or (nil? page-id) (= page-id (:page-id %))))
          (store/read-events nb)))

(defn- root-of
  "A function from an entry id to the entry it now belongs to, following joins
   transitively."
  [events]
  (let [joins (into {} (for [e events :when (= :ink-joined (:event e))]
                         [(:entry-id e) (:into e)]))]
    (fn [id] (loop [id id] (if-let [t (get joins id)] (recur t) id)))))

(defn- bbox-union [bbs]
  (when-let [bbs (seq (remove nil? bbs))]
    {:min-x (apply min (map :min-x bbs)) :min-y (apply min (map :min-y bbs))
     :max-x (apply max (map :max-x bbs)) :max-y (apply max (map :max-y bbs))}))

(defn entry-groups
  "`events` (observations and joins) grouped into entries, in order of each
   entry's first observation. Each group is {:entry-id :events :bbox :asset},
   where :events are its observations and :asset the image most recently drawn
   for it, by either an observation or a join."
  [events]
  (let [root (root-of events)
        owner (comp root :entry-id)
        obs (filter #(= :ink-observed (:event %)) events)
        by-root (group-by owner obs)
        asset (reduce (fn [m e] (if-let [a (:asset e)] (assoc m (owner e) a) m))
                      {} events)]
    (for [id (distinct (map owner obs))
          :let [evs (by-root id)]]
      {:entry-id id :events evs :bbox (bbox-union (map :bbox evs)) :asset (asset id)})))

(defn- y-overlap? [a b]
  (and (<= (:min-y a) (:max-y b)) (<= (:min-y b) (:max-y a))))

(defn- most-recent-overlapping
  "Of `clusters` (collections of observations), the one `bb` overlaps
   vertically, or nil. When several do, the one added to most recently wins --
   a simplification: the others are left separate rather than merged together.

   This is THE merging rule. Live observation and `regroup!` both use it, which
   is what makes regrouping a notebook recorded under it a no-op."
  [bb clusters]
  (->> clusters
       (filter #(y-overlap? bb (bbox-union (map :bbox %))))
       (sort-by #(apply max (map :sequence %)))
       last))

(defn- event-strokes
  "The strokes one event contributed, read back from its retained snapshot, or
   nil when the snapshot is gone."
  [nb ev]
  (let [snap (io/file (store/page-dir nb (:page-id ev)) (str (:snapshot-id ev) ".rm"))]
    (when (.exists snap)
      (let [parsed (try (lines/parse (java.nio.file.Files/readAllBytes (.toPath snap)))
                        (catch Exception _ nil))
            wanted (set (:stroke-ids ev))]
        (seq (filter #(contains? wanted (:item-id %)) (:strokes parsed)))))))

(defn- extendable-entry
  "The entry group on `page-id` that `bb` should extend, if any."
  [nb page-id bb]
  (let [groups (entry-groups (ink-events nb page-id))
        hit (most-recent-overlapping bb (map :events groups))]
    (when hit (first (filter #(= hit (:events %)) groups)))))

(defn rerender!
  "Redraw every handwriting asset for `page-id` (or all pages) with `opts`.

   This is what the retained raw snapshots are for. Each observation records the
   snapshot it came from and the stroke ids it contributed, so an entry's image
   can be reproduced exactly from its observations -- which is how a
   colour-scheme change reaches entries recorded before it. Only an entry's
   current asset is redrawn."
  ([nb] (rerender! nb nil nil))
  ([nb page-id opts]
   (reduce
    (fn [acc {:keys [entry-id events asset]}]
      (let [strokes (mapcat #(event-strokes nb %) events)]
        (if (empty? strokes)
          (update acc :skipped conj entry-id)
          (do (render-strokes! strokes (io/file nb (:path asset)) opts)
              (update acc :rendered conj entry-id)))))
    {:rendered [] :skipped []}
    (entry-groups (ink-events nb page-id)))))

(defn- replay
  "Replay observations in order under the merging rule, ignoring how they were
   grouped when recorded. Returns clusters of observations."
  [obs]
  (reduce (fn [clusters ev]
            (if-let [hit (most-recent-overlapping (:bbox ev) clusters)]
              (mapv #(if (identical? % hit) (conj % ev) %) clusters)
              (conj clusters [ev])))
          [] obs))

(defn regroup!
  "Apply the merging rule retroactively to `page-id` (or every page).

   Handwriting recorded before overlapping ink extended entries was split into
   one entry per save. This replays the observations, and wherever entries now
   belong together, redraws the earliest of them from all their strokes and
   appends a join for each of the others. Nothing recorded is rewritten; the
   joined entries' events and images remain.

   Idempotent: once applied, replay agrees with the recorded grouping. Returns
   {:joined [{:into id :entries [id ...]}] :skipped [ids]}."
  ([nb] (regroup! nb nil nil))
  ([nb page-id opts]
   (let [events (ink-events nb page-id)
         root (root-of events)
         groups (entry-groups events)
         position (zipmap (map :entry-id groups) (range))
         pages (distinct (map :page-id (filter #(= :ink-observed (:event %)) events)))]
     (reduce
      (fn [acc cluster]
        (let [ids (sort-by position (distinct (map (comp root :entry-id) cluster)))]
          (if-not (next ids)
            acc
            (let [[target & joined] ids
                  members (set ids)
                  evs (mapcat :events (filter #(members (:entry-id %)) groups))
                  strokes (mapcat #(event-strokes nb %) evs)
                  asset-name (str target "-" (subs (str (random-uuid)) 0 8))
                  png (store/handwriting-file nb asset-name)]
              (if (empty? strokes)
                (update acc :skipped conj target)
                ;; The merged image first, then the joins that point at it.
                (let [r (render-strokes! strokes png opts)]
                  (doseq [id joined]
                    (store/append-event!
                     nb {:event :ink-joined
                         :page-id (:page-id (first cluster))
                         :entry-id id
                         :into target
                         :asset {:path (str "handwriting/" asset-name ".png")
                                 :width (:width r) :height (:height r)}}))
                  (update acc :joined conj {:into target :entries (vec joined)})))))))
      {:joined [] :skipped []}
      (mapcat (fn [p] (replay (filter #(and (= :ink-observed (:event %)) (= p (:page-id %)))
                                      events)))
              pages)))))

(defn observe!
  "Record whatever is new in a stable page snapshot.

   `rm-bytes` is the page file exactly as it was parsed. Returns
   {:new? false :reason ...} when there is nothing to add, or
   {:new? true :entry-id ... :stroke-ids ... :bbox ...}.

   Ordering is the durability rule: the raw snapshot and the rendered PNG are
   published first, and only then does the event commit those stroke ids as
   observed. A crash in between costs a repeated observation, which is
   idempotent, rather than losing the ink from history."
  [nb {:keys [page-id ^bytes rm-bytes strokes render-opts]}]
  (let [snapshot-id (sha256-bytes rm-bytes)]
    (if (contains? (observed-snapshots nb page-id) snapshot-id)
      {:new? false :reason :snapshot-already-seen :snapshot-id snapshot-id}
      (let [known (observed-ids nb page-id)
            fresh (lines/new-strokes known strokes)]
        (if (empty? fresh)
          ;; A save with no new ink -- which is exactly what an inserted result
          ;; image produces, since scene items are not strokes. No entry.
          {:new? false :reason :no-new-strokes :snapshot-id snapshot-id}
          (let [bb (lines/bbox fresh)
                target (extendable-entry nb page-id bb)
                entry-id (if target (:entry-id target) (str (random-uuid)))
                ;; An extended entry gets a new file: Emacs caches images by
                ;; path, so redrawing in place would keep showing the old one.
                asset-name (if target
                             (str entry-id "-" (subs snapshot-id 0 12))
                             entry-id)
                strokes (concat (mapcat #(event-strokes nb %) (:events target)) fresh)
                snap-file (io/file (store/page-dir nb page-id) (str snapshot-id ".rm"))
                png-file (store/handwriting-file nb asset-name)]
            ;; Assets first.
            (store/publish-bytes! snap-file rm-bytes)
            (let [r (render-strokes! strokes png-file render-opts)]
              ;; Then commit the ids.
              (store/append-event!
               nb (cond-> {:event :ink-observed
                           :page-id page-id
                           :entry-id entry-id
                           :snapshot-id snapshot-id
                           :stroke-ids (mapv :item-id fresh)
                           :bbox bb
                           :asset {:path (str "handwriting/" asset-name ".png")
                                   :width (:width r) :height (:height r)}}
                    target (assoc :extends? true)))
              {:new? true :entry-id entry-id :snapshot-id snapshot-id
               :extended? (boolean target)
               :stroke-ids (mapv :item-id fresh) :bbox bb :asset r})))))))
