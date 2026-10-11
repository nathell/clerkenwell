(ns clerkenwell.ink-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clerkenwell.ink :as ink]
            [clerkenwell.lines :as lines]
            [clerkenwell.store :as store])
  (:import [java.nio.file Files]))

(def fixture "test/resources/sample-page.rm")

(defn- tmp-nb []
  (let [root (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "ckw-ink-" (random-uuid)))
               (.mkdirs))]
    (store/notebook-dir root "nb-1")))

(defn- fixture-bytes [] (Files/readAllBytes (.toPath (io/file fixture))))

(defn- strokes [] (:strokes (lines/parse-file fixture)))

(deftest renders-strokes-cropped-to-their-bounds
  (let [out (io/file (System/getProperty "java.io.tmpdir") (str "ink-" (random-uuid) ".png"))
        r (ink/render-strokes! (strokes) out)]
    (testing "the image is the bounding box plus a margin on each side"
      (let [bb (lines/bbox (filter lines/ink? (strokes)))
            margin (:margin ink/default-opts)]
        (is (= (int (+ (- (:max-x bb) (:min-x bb)) (* 2 margin))) (:width r)))
        (is (= (int (+ (- (:max-y bb) (:min-y bb)) (* 2 margin))) (:height r)))))
    (testing "and it is a real image on disk"
      (is (.exists (io/file out)))
      (is (pos? (.length (io/file out)))))
    (.delete (io/file out))))

(deftest resolution-scales-the-image-not-its-extent
  (let [out (io/file (System/getProperty "java.io.tmpdir") (str "ink-" (random-uuid) ".png"))
        bb (lines/bbox (filter lines/ink? (strokes)))
        {:keys [width height]} (ink/extent bb)
        r (ink/render-strokes! (strokes) out {:resolution 2})]
    (is (= (int (* 2 width)) (:width r)))
    (is (= (int (* 2 height)) (:height r)))
    (testing "the file really is that size"
      (let [img (javax.imageio.ImageIO/read (io/file out))]
        (is (= (:width r) (.getWidth img)))))
    (.delete (io/file out))))

(deftest strokes-are-drawn-in-the-requested-colours
  (let [out (io/file (System/getProperty "java.io.tmpdir") (str "ink-" (random-uuid) ".png"))
        _ (ink/render-strokes! (strokes) out {:stroke-color "#ffffff" :background "#002451"})
        img (javax.imageio.ImageIO/read (io/file out))
        rgbs (frequencies (for [x (range (.getWidth img)) y (range (.getHeight img))]
                            (bit-and 0xffffff (.getRGB img x y))))]
    (is (= 0x002451 (key (apply max-key val rgbs))) "the background dominates")
    (is (contains? rgbs 0xffffff) "and the ink is there")
    (.delete (io/file out))))

(deftest a-lone-point-is-a-dot
  (let [out (io/file (System/getProperty "java.io.tmpdir") (str "ink-" (random-uuid) ".png"))
        _ (ink/render-strokes! [{:points [{:x 0 :y 0}]}] out)
        img (javax.imageio.ImageIO/read (io/file out))
        c (:margin ink/default-opts)]
    (is (= 0 (bit-and 0xffffff (.getRGB img c c))))
    (.delete (io/file out))))

(deftest nothing-to-draw-is-not-an-error
  (is (nil? (ink/render-strokes! [] "/tmp/never-written.png")))
  (is (nil? (ink/render-strokes! [{:points []}] "/tmp/never-written.png"))))

(deftest first-observation-records-everything
  (let [nb (tmp-nb)
        r (ink/observe! nb {:page-id "page-1" :rm-bytes (fixture-bytes)
                            :strokes (strokes)})]
    (is (:new? r))
    (testing "only strokes that render are recorded"
      (is (= 54 (count (:stroke-ids r)))))
    (testing "the raw snapshot is retained for later reinterpretation"
      (is (.exists (io/file (store/page-dir nb "page-1") (str (:snapshot-id r) ".rm")))))
    (testing "the rendered entry exists"
      (is (.exists (store/handwriting-file nb (:entry-id r)))))
    (testing "ink-bottom is available to placement"
      (is (< 319.53 (ink/ink-bottom nb "page-1") 319.54)))))

(deftest re-observing-the-same-snapshot-adds-nothing
  (let [nb (tmp-nb)
        bs (fixture-bytes)]
    (ink/observe! nb {:page-id "page-1" :rm-bytes bs :strokes (strokes)})
    (let [again (ink/observe! nb {:page-id "page-1" :rm-bytes bs :strokes (strokes)})]
      (testing "deduplicated by content hash, not timestamp"
        (is (false? (:new? again)))
        (is (= :snapshot-already-seen (:reason again))))
      (testing "and the history did not grow"
        (is (= 1 (count (filter #(= :ink-observed (:event %))
                                (store/read-events nb)))))))))

(deftest a-save-with-no-new-ink-creates-no-entry
  (testing "this is what an inserted result image produces: scene items are not strokes"
    (let [nb (tmp-nb)
          bs (fixture-bytes)]
      (ink/observe! nb {:page-id "page-1" :rm-bytes bs :strokes (strokes)})
      ;; Same strokes, different file content -- as if the page were re-saved
      ;; after an image insertion.
      (let [r (ink/observe! nb {:page-id "page-1"
                                :rm-bytes (byte-array (concat (seq bs) [0]))
                                :strokes (strokes)})]
        (is (false? (:new? r)))
        (is (= :no-new-strokes (:reason r)))))))

(deftest only-new-strokes-become-the-next-entry
  (let [nb (tmp-nb)
        all (strokes)
        inked (filter lines/ink? all)
        ;; First save: everything but the last stroke.
        earlier (butlast inked)
        bs (fixture-bytes)]
    (ink/observe! nb {:page-id "page-1" :rm-bytes bs :strokes earlier})
    (let [r (ink/observe! nb {:page-id "page-1"
                              :rm-bytes (byte-array (concat (seq bs) [0]))
                              :strokes inked})]
      (is (:new? r))
      (testing "exactly the one stroke that appeared"
        (is (= 1 (count (:stroke-ids r))))
        (is (= (:item-id (last inked)) (first (:stroke-ids r)))))
      (testing "the log keeps both observations"
        (is (= 2 (count (filter #(= :ink-observed (:event %))
                                (store/read-events nb)))))))))

(defn- inked [] (vec (filter lines/ink? (strokes))))

(defn- observe-in-two-saves!
  "Observe `first-save`, then `second-save`, as two saves of the page."
  [nb first-save second-save]
  (let [bs (fixture-bytes)
        a (ink/observe! nb {:page-id "page-1" :rm-bytes bs :strokes first-save})
        b (ink/observe! nb {:page-id "page-1"
                            :rm-bytes (byte-array (concat (seq bs) [0]))
                            :strokes second-save})]
    [a b]))

(deftest ink-overlapping-an-entry-extends-it
  (testing "pausing mid-line and carrying on does not split the line"
    (let [nb (tmp-nb)
          line (subvec (inked) 0 22)
          [a b] (observe-in-two-saves! nb (subvec line 0 11) line)]
      (is (:new? b))
      (is (:extended? b))
      (is (= (:entry-id a) (:entry-id b)) "the same entry, not a new one")
      (is (= 11 (count (:stroke-ids b))) "the event records only what is new")
      (testing "the image is redrawn from both saves' strokes"
        (let [bb (lines/bbox line)
              margin (:margin ink/default-opts)]
          (is (= (int (+ (- (:max-y bb) (:min-y bb)) (* 2 margin)))
                 (get-in b [:asset :height])))))
      (testing "into a new file, since Emacs caches images by path"
        (is (not= (get-in a [:asset :path]) (get-in b [:asset :path])))
        (is (.exists (io/file (get-in b [:asset :path])))))
      (testing "the earlier asset is left as it was"
        (is (.exists (io/file (get-in a [:asset :path]))))))))

(deftest ink-clear-of-every-entry-starts-a-new-one
  (let [nb (tmp-nb)
        ;; Strokes 0-5 span y 171-227; strokes 22-49 start at 247.
        top (subvec (inked) 0 6)
        below (subvec (inked) 22 50)
        [a b] (observe-in-two-saves! nb top (into top below))]
    (is (:new? b))
    (is (not (:extended? b)))
    (is (not= (:entry-id a) (:entry-id b)))))

(deftest rerender-redraws-an-extended-entry-whole
  (let [nb (tmp-nb)
        line (subvec (inked) 0 22)
        [a b] (observe-in-two-saves! nb (subvec line 0 11) line)
        current (io/file (get-in b [:asset :path]))
        before (store/sha256 current)
        out (ink/rerender! nb "page-1" {:stroke-color "#c5c8c6" :background "#002451"})]
    (is (= [(:entry-id a)] (:rendered out)) "one entry, not one per event")
    (is (not= before (store/sha256 current)) "its current asset is the one redrawn")
    (testing "at the size of the whole entry"
      (let [bb (lines/bbox line)
            margin (:margin ink/default-opts)]
        (is (= (int (+ (- (:max-y bb) (:min-y bb)) (* 2 margin)))
               (.getHeight (javax.imageio.ImageIO/read current))))))))

(defn- legacy-observe!
  "Record `save` as a separate entry, as observation did before overlapping ink
   extended entries: the history `regroup!` exists to repair."
  [nb save]
  (let [bs (fixture-bytes)
        snapshot-id (str (random-uuid))
        entry-id (str (random-uuid))
        fresh (lines/new-strokes (ink/observed-ids nb "page-1") save)]
    (store/publish-bytes! (io/file (store/page-dir nb "page-1") (str snapshot-id ".rm")) bs)
    (let [r (ink/render-strokes! fresh (store/handwriting-file nb entry-id))]
      (store/append-event! nb {:event :ink-observed :page-id "page-1"
                               :entry-id entry-id :snapshot-id snapshot-id
                               :stroke-ids (mapv :item-id fresh)
                               :bbox (lines/bbox fresh)
                               :asset {:path (str "handwriting/" entry-id ".png")
                                       :width (:width r) :height (:height r)}})
      entry-id)))

(defn- groups [nb]
  (ink/entry-groups (filter #(#{:ink-observed :ink-joined} (:event %))
                            (store/read-events nb))))

(deftest regroup-joins-entries-split-before-extension-existed
  (let [nb (tmp-nb)
        line (subvec (inked) 0 22)
        a (legacy-observe! nb (subvec line 0 11))
        _ (legacy-observe! nb line)]
    (is (= 2 (count (groups nb))) "the split history")
    (let [r (ink/regroup! nb)]
      (is (= 1 (count (:joined r))))
      (is (= a (:into (first (:joined r)))) "into the earlier entry"))
    (let [[g & more] (groups nb)]
      (is (empty? more) "one entry now")
      (is (= a (:entry-id g)))
      (testing "drawn from both saves' strokes, into a new file"
        (let [bb (lines/bbox line)
              margin (:margin ink/default-opts)
              img (io/file nb (get-in g [:asset :path]))]
          (is (not= (str "handwriting/" a ".png") (get-in g [:asset :path])))
          (is (= (int (+ (- (:max-y bb) (:min-y bb)) (* 2 margin)))
                 (.getHeight (javax.imageio.ImageIO/read img)))))))
    (testing "the observations themselves are untouched"
      (is (= 2 (count (filter #(= :ink-observed (:event %)) (store/read-events nb))))))))

(deftest regroup-is-idempotent
  (let [nb (tmp-nb)
        line (subvec (inked) 0 22)]
    (legacy-observe! nb (subvec line 0 11))
    (legacy-observe! nb line)
    (ink/regroup! nb)
    (let [n (count (store/read-events nb))
          again (ink/regroup! nb)]
      (is (empty? (:joined again)))
      (is (= n (count (store/read-events nb))) "and appended nothing"))))

(deftest regroup-does-nothing-to-history-recorded-with-extension
  (let [nb (tmp-nb)
        line (subvec (inked) 0 22)]
    (observe-in-two-saves! nb (subvec line 0 11) line)
    (is (empty? (:joined (ink/regroup! nb))))))

(deftest regroup-leaves-separate-ink-separate
  (let [nb (tmp-nb)
        top (subvec (inked) 0 6)]
    (legacy-observe! nb top)
    (legacy-observe! nb (into top (subvec (inked) 22 50)))
    (is (empty? (:joined (ink/regroup! nb))))
    (is (= 2 (count (groups nb))))))

(deftest live-ink-extends-a-regrouped-entry
  (let [nb (tmp-nb)
        line (subvec (inked) 0 22)
        a (legacy-observe! nb (subvec line 0 7))]
    (legacy-observe! nb (subvec line 0 14))
    (ink/regroup! nb)
    (let [r (ink/observe! nb {:page-id "page-1"
                              :rm-bytes (byte-array (concat (seq (fixture-bytes)) [0 0 0]))
                              :strokes line})]
      (is (:extended? r))
      (is (= a (:entry-id r)) "the entry the others were joined into"))
    (let [[g & more] (groups nb)]
      (is (empty? more))
      (is (= 22 (reduce + (map (comp count :stroke-ids) (:events g))))))
    (testing "and a colour change redraws it once, whole"
      (is (= [a] (:rendered (ink/rerender! nb "page-1" {:stroke-color "white"})))))))

(deftest rerender-redraws-from-retained-snapshots
  (testing "which is what the raw snapshots are kept for"
    (let [nb (tmp-nb)
          bs (fixture-bytes)
          r (ink/observe! nb {:page-id "page-1" :rm-bytes bs :strokes (strokes)})
          asset (store/handwriting-file nb (:entry-id r))
          before (store/sha256 asset)]
      (let [out (ink/rerender! nb "page-1" {:stroke-color "#c5c8c6"
                                            :background "#002451"})]
        (is (= [(:entry-id r)] (:rendered out)))
        (is (empty? (:skipped out))))
      (testing "the asset is redrawn in place, with different bytes"
        (is (.exists asset))
        (is (not= before (store/sha256 asset))))
      (testing "and the history itself is unchanged"
        (is (= 1 (count (filter #(= :ink-observed (:event %))
                                (store/read-events nb)))))))))

(deftest rerender-without-a-snapshot-is-skipped-not-fatal
  (let [nb (tmp-nb)
        r (ink/observe! nb {:page-id "page-1" :rm-bytes (fixture-bytes)
                            :strokes (strokes)})]
    (io/delete-file (io/file (store/page-dir nb "page-1") (str (:snapshot-id r) ".rm")))
    (let [out (ink/rerender! nb "page-1" nil)]
      (is (empty? (:rendered out)))
      (is (= [(:entry-id r)] (:skipped out))))))

(deftest restore-redraws-only-missing-images
  (let [nb (tmp-nb)
        line (subvec (inked) 0 22)
        [a] (observe-in-two-saves! nb (subvec line 0 11) line)
        r (ink/observe! nb {:page-id "page-2" :rm-bytes (fixture-bytes) :strokes (strokes)})
        kept (store/handwriting-file nb (:entry-id r))
        kept-at (.lastModified kept)]
    (testing "a complete cache needs nothing"
      (is (= {:rendered [] :skipped []} (ink/restore! nb nil))))
    (testing "a cleared cache is drawn again, at the paths history names"
      (doseq [f (.listFiles (io/file nb "handwriting"))
              :when (not= f kept)]
        (io/delete-file f))
      (is (= [(:entry-id a)] (:rendered (ink/restore! nb nil))))
      (is (every? #(.exists (io/file nb (get-in % [:asset :path])))
                  (ink/entry-groups (filter #(#{:ink-observed :ink-joined} (:event %))
                                            (store/read-events nb))))))
    (testing "an image that was there is left alone"
      (is (= kept-at (.lastModified kept))))))

(deftest pages-are-independent
  (let [nb (tmp-nb)
        bs (fixture-bytes)]
    (ink/observe! nb {:page-id "page-1" :rm-bytes bs :strokes (strokes)})
    (testing "the same ink on another page is new there"
      (let [r (ink/observe! nb {:page-id "page-2" :rm-bytes bs :strokes (strokes)})]
        (is (:new? r))
        (is (= 54 (count (:stroke-ids r))))))
    (testing "and ink-bottom is per page"
      (is (nil? (ink/ink-bottom nb "page-3"))))))

(deftest ink-bottom-survives-erasure
  (testing "the high-water mark is retained: native movement is not tracked"
    (let [nb (tmp-nb)
          bs (fixture-bytes)]
      (ink/observe! nb {:page-id "page-1" :rm-bytes bs :strokes (strokes)})
      (let [before (ink/ink-bottom nb "page-1")]
        ;; A later save where the user erased everything.
        (ink/observe! nb {:page-id "page-1"
                          :rm-bytes (byte-array (concat (seq bs) [0]))
                          :strokes []})
        (is (= before (ink/ink-bottom nb "page-1")))))))
