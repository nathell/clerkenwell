(ns clerkenwell.lines
  "A reMarkable .lines v6 parser, enough of one to extract stroke geometry.

   A JVM port of `edsger.lines`, which is itself based on rmscene
   (https://github.com/ricklupton/rmscene, MIT, (c) 2023 Rick Lupton).
   Parsing happens on the host so the device runs only the QML adapter.

   The JVM makes several of Edsger's workarounds unnecessary: ByteBuffer gives
   little-endian reads directly, and Float/intBitsToFloat replaces the hand
   written IEEE-754 decoder let-go forced.

   Format, from rmscene's tagged_block_common.py / scene_stream.py:

     - All multi-byte ints/floats little-endian.
     - 43-byte header: \"reMarkable .lines file, version=6\" + 10 spaces.
     - Block frame (8 bytes): uint32 length, uint8 0, uint8 min_ver,
       uint8 cur_ver, uint8 block_type, then `length` payload bytes.
     - Tag byte (varuint): (field_index << 4) | type. Type nibbles:
       0x1=Byte1, 0x4=Byte4, 0x8=Byte8, 0xC=Length4 subblock,
       0xF=ID (CrdtId = uint8 + varuint).
     - SceneLineItemBlock = block type 0x05, whose field 6 subblock holds
       uint8 item_type (0x03 = Line) then the Line payload.
     - Point v2 (14 bytes): x f32, y f32, speed u16, width u16,
       direction u8, pressure u8. The block's current_version selects v1/v2."
  (:require [clojure.java.io :as io])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files]))

(def header-v6 (str "reMarkable .lines file, version=6" "          "))

(def point-size-v2 14)
(def point-size-v1 24)
(def block-type-line 0x05)
(def item-type-line 0x03)

;; -------- primitive reads --------

(defn- u8 [^ByteBuffer b] (bit-and (.get b) 0xFF))
(defn- u16 [^ByteBuffer b] (bit-and (.getShort b) 0xFFFF))
(defn- u32 [^ByteBuffer b] (bit-and (.getInt b) 0xFFFFFFFF))
(defn- f32 [^ByteBuffer b] (.getFloat b))

(defn- varuint
  "LEB128 unsigned."
  [^ByteBuffer b]
  (loop [shift 0 acc 0]
    (let [byte (u8 b)
          acc (bit-or acc (bit-shift-left (bit-and byte 0x7F) shift))]
      (if (zero? (bit-and byte 0x80))
        acc
        (recur (+ shift 7) acc)))))

(defn- tag
  "Returns [field-index type-nibble]."
  [^ByteBuffer b]
  (let [x (varuint b)]
    [(bit-shift-right x 4) (bit-and x 0xF)]))

(defn- crdt-id
  "CrdtId = uint8 + varuint, as a [part1 part2] vector so it is a value and can
   be used as a map key for stroke identity."
  [^ByteBuffer b]
  [(u8 b) (varuint b)])

(defn- skip-value
  "Skip a value of `type-nibble`; the cursor is already past the tag."
  [^ByteBuffer b type-nibble]
  (case (long type-nibble)
    0x1 (.position b (+ (.position b) 1))
    0x4 (.position b (+ (.position b) 4))
    0x8 (.position b (+ (.position b) 8))
    0xF (crdt-id b)
    0xC (let [len (u32 b)] (.position b (+ (.position b) (int len))))
    (throw (ex-info "unknown tag type" {:type type-nibble
                                        :position (dec (.position b))})))
  nil)

;; -------- points --------

(defn- read-points [^ByteBuffer b n point-size]
  (when-not (= point-size point-size-v2)
    (throw (ex-info "v1 points not implemented" {:point-size point-size})))
  (loop [i 0 acc (transient [])]
    (if (= i n)
      (persistent! acc)
      (let [x (f32 b) y (f32 b)
            speed (u16 b) width (u16 b)
            direction (u8 b) pressure (u8 b)]
        (recur (inc i)
               (conj! acc {:x x :y y :speed speed :width width
                           :direction direction :pressure pressure}))))))

;; -------- line payload --------

(defn- parse-line-payload [^ByteBuffer b end point-version]
  (let [point-size (if (= point-version 1) point-size-v1 point-size-v2)]
    (loop [line {:points [] :point-version point-version}]
      (if (>= (.position b) end)
        line
        (let [[idx t] (tag b)]
          (cond
            (and (= idx 1) (= t 0x4)) (recur (assoc line :tool (u32 b)))
            (and (= idx 2) (= t 0x4)) (recur (assoc line :color (u32 b)))
            (and (= idx 4) (= t 0x4)) (recur (assoc line :starting-length (f32 b)))

            (and (= idx 5) (= t 0xC))
            (let [len (u32 b)
                  after (+ (.position b) (int len))
                  pts (read-points b (quot len point-size) point-size)]
              (.position b (int after))
              (recur (assoc line :points pts)))

            (and (= idx 6) (= t 0xF)) (recur (assoc line :timestamp (crdt-id b)))
            :else (do (skip-value b t) (recur line))))))))

(defn- parse-line-item-block [^ByteBuffer b end point-version]
  (loop [out {}]
    (if (>= (.position b) end)
      out
      (let [[idx t] (tag b)]
        (cond
          (and (= idx 1) (= t 0xF)) (recur (assoc out :parent-id (crdt-id b)))
          (and (= idx 2) (= t 0xF)) (recur (assoc out :item-id (crdt-id b)))
          (and (= idx 3) (= t 0xF)) (recur (assoc out :left-id (crdt-id b)))
          (and (= idx 4) (= t 0xF)) (recur (assoc out :right-id (crdt-id b)))
          (and (= idx 5) (= t 0x4)) (recur (assoc out :deleted-length (u32 b)))

          (and (= idx 6) (= t 0xC))
          (let [sub-len (u32 b)
                sub-end (+ (.position b) (int sub-len))
                item-type (u8 b)
                out (if (= item-type item-type-line)
                      (merge out (parse-line-payload b sub-end point-version))
                      out)]
            (.position b (int sub-end))
            (recur out))

          :else (do (skip-value b t) (recur out)))))))

;; -------- blocks --------

(defn- parse-blocks [^ByteBuffer b]
  (loop [acc []]
    (if (>= (.position b) (.limit b))
      acc
      (let [block-len (u32 b)
            _ (u8 b)                        ; reserved 0
            _min-ver (u8 b)
            cur-ver (u8 b)
            blk-type (u8 b)
            payload-start (.position b)
            payload-end (+ payload-start (int block-len))
            block (if (= blk-type block-type-line)
                    {:type blk-type
                     :line (parse-line-item-block b payload-end cur-ver)}
                    {:type blk-type :start payload-start :end payload-end})]
        (.position b (int payload-end))
        (recur (conj acc block))))))

(defn parse
  "Parse .lines v6 bytes. Returns {:strokes [...] :other [...]}."
  [^bytes bs]
  (let [b (doto (ByteBuffer/wrap bs) (.order ByteOrder/LITTLE_ENDIAN))
        hdr-len (count header-v6)]
    (when (< (alength bs) hdr-len)
      (throw (ex-info "file too short for a lines-v6 header" {:length (alength bs)})))
    (dotimes [i hdr-len]
      (when-not (= (int (.charAt header-v6 i)) (bit-and (aget bs i) 0xFF))
        (throw (ex-info "bad lines-v6 header" {:position i}))))
    (.position b hdr-len)
    (let [blocks (parse-blocks b)]
      {:strokes (->> blocks (filter #(= block-type-line (:type %))) (mapv :line))
       :other (->> blocks (remove #(= block-type-line (:type %))) vec)})))

(defn parse-file [path]
  (parse (Files/readAllBytes (.toPath (io/file path)))))

;; -------- snapshots and diffing --------

(defn snapshot
  "{item-id -> stroke} for strokes that have an identity. Strokes without an
   item-id cannot be tracked across saves and are dropped."
  [strokes]
  (into {} (for [s strokes :when (:item-id s)] [(:item-id s) s])))

(defn ink?
  "Whether a stroke contributes visible ink. A fully erased stroke survives in
   the file as a line block with a deleted-length and no points; treating one as
   new ink would produce a history entry that renders to nothing."
  [s]
  (boolean (seq (:points s))))

(defn new-strokes
  "Strokes in `strokes` whose item-id is not in the `known` set. Erased strokes
   are excluded -- see `ink?`."
  [known strokes]
  (filterv (fn [s] (and (:item-id s)
                        (ink? s)
                        (not (contains? known (:item-id s)))))
           strokes))

(defn bbox
  "Min/max x/y across all points of all strokes, or nil when there are none."
  [strokes]
  (let [pts (mapcat :points strokes)]
    (when (seq pts)
      (reduce (fn [acc {:keys [x y]}]
                (-> acc
                    (update :min-x min x) (update :min-y min y)
                    (update :max-x max x) (update :max-y max y)))
              (let [{:keys [x y]} (first pts)]
                {:min-x x :min-y y :max-x x :max-y y})
              (rest pts)))))
