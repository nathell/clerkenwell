(ns clerkenwell.ink
  "Handwriting history: rendering observed strokes, and recording them durably.

   This is an ordered historical record, not a mirror of the page. The renderer
   draws fixed-width polylines, so erasures, transformations, layers, brush
   appearance and existing images are approximate or absent. Later tablet edits
   never rewrite earlier entries; new annotations become new entries.

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

(defn rerender!
  "Redraw every handwriting asset for `page-id` (or all pages) with `opts`.

   This is what the retained raw snapshots are for. Each entry records the
   snapshot it came from and the stroke ids it covered, so its image can be
   reproduced exactly -- which is how a colour-scheme change reaches entries
   recorded before it."
  ([nb] (rerender! nb nil nil))
  ([nb page-id opts]
   (let [events (filter #(and (= :ink-observed (:event %))
                              (or (nil? page-id) (= page-id (:page-id %))))
                        (store/read-events nb))]
     (reduce
      (fn [acc ev]
        (let [snap (io/file (store/page-dir nb (:page-id ev))
                            (str (:snapshot-id ev) ".rm"))]
          (if-not (.exists snap)
            (update acc :skipped conj (:entry-id ev))
            (let [parsed (try (lines/parse (java.nio.file.Files/readAllBytes (.toPath snap)))
                              (catch Exception _ nil))
                  wanted (set (:stroke-ids ev))
                  strokes (filter #(contains? wanted (:item-id %)) (:strokes parsed))]
              (if (empty? strokes)
                (update acc :skipped conj (:entry-id ev))
                (do (render-strokes! strokes (store/handwriting-file nb (:entry-id ev)) opts)
                    (update acc :rendered conj (:entry-id ev))))))))
      {:rendered [] :skipped []}
      events))))

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
          (let [entry-id (str (random-uuid))
                bb (lines/bbox fresh)
                snap-file (io/file (store/page-dir nb page-id) (str snapshot-id ".rm"))
                png-file (store/handwriting-file nb entry-id)]
            ;; Assets first.
            (store/publish-bytes! snap-file rm-bytes)
            (let [r (render-strokes! fresh png-file render-opts)]
              ;; Then commit the ids.
              (store/append-event!
               nb {:event :ink-observed
                   :page-id page-id
                   :entry-id entry-id
                   :snapshot-id snapshot-id
                   :stroke-ids (mapv :item-id fresh)
                   :bbox bb
                   :asset {:path (str "handwriting/" entry-id ".png")
                           :width (:width r) :height (:height r)}})
              {:new? true :entry-id entry-id :snapshot-id snapshot-id
               :stroke-ids (mapv :item-id fresh) :bbox bb :asset r})))))))
