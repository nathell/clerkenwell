(ns clerkenwell.table
  "Tables: the shapes Kindly's `kind/table` accepts, reduced to bounded rows of
   strings, shown in Emacs as aligned text and on the page typeset in Times
   New Roman. See docs/rich-output.md."
  (:require [clojure.string :as str]
            [clerkenwell.html :as html]
            [clerkenwell.image :as image])
  (:import (java.awt.image BufferedImage)))

(def default-limits
  {:max-rows 30
   :max-columns 12
   ;; A long cell would take most of a page-wide table to itself.
   :max-cell-chars 40})

;; -------- reading the shapes --------

(defn- cap [s n]
  (let [s (str/replace s #"\s*\n\s*" " ")]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn- cell
  "A value as table text: a string as itself, nothing for nil, anything else
   as Clojure prints it."
  [v n]
  (cap (cond (nil? v) "" (string? v) v :else (pr-str v)) n))

(defn- header
  "A column name: a keyword's name without its colon reads better as a
   heading; anything else as a cell would show it."
  [k n]
  (cap (if (and (keyword? k) (nil? (namespace k))) (name k) (cell k Integer/MAX_VALUE)) n))

(defn- known-count
  "How many items `xs` has, if that can be known without walking it."
  [xs]
  (when (or (counted? xs)
            ;; A dataset's column, say. A lazy seq is a Collection too, and
            ;; counting one could take forever.
            (and (instance? java.util.Collection xs)
                 (not (instance? clojure.lang.Sequential xs))))
    (count xs)))

(defn- bounded
  "The first `n` of `xs`, and how many more there are: a number, :some when
   there are more but counting them could take forever, or 0."
  [xs n]
  (let [shown (vec (take n xs))
        total (known-count xs)]
    [shown (cond total (max 0 (- total n))
                 (seq (drop n xs)) :some
                 :else 0)]))

(defn- seq-of? [pred xs]
  (and (sequential? xs) (seq xs) (every? pred (take 100 xs))))

(defn- keys-in-order
  "Every key of `row-maps`, in the order they first appear."
  [row-maps]
  (into [] (distinct) (mapcat keys row-maps)))

(defn- shape
  "`value` as {:columns names-or-nil :rows [[raw ...]] :more-rows n-or-:some},
   following Clay: a sequence of maps or of sequences; a map with :row-vectors
   or :row-maps (and optionally :column-names); or a map of columns, which is
   also what a tech.ml.dataset dataset is. Nil for anything else."
  [value max-rows]
  (cond
    (and (map? value) (contains? value :row-vectors))
    (let [[rows more] (bounded (:row-vectors value) max-rows)]
      {:columns (:column-names value) :rows (mapv vec rows) :more-rows more})

    (and (map? value) (contains? value :row-maps))
    (let [[rows more] (bounded (:row-maps value) max-rows)
          cols (or (:column-names value) (keys-in-order rows))]
      {:columns cols :rows (mapv (fn [r] (mapv #(get r %) cols)) rows) :more-rows more})

    (map? value)
    (let [cols (keys value)
          columns (vals value)]
      (when (and (seq cols) (every? #(or (sequential? %) (instance? java.util.List %)) columns))
        (let [[first-col more] (bounded (first columns) max-rows)
              n (count first-col)]
          {:columns (vec cols)
           :rows (apply mapv vector (map #(vec (take n %)) columns))
           :more-rows more})))

    (seq-of? map? value)
    (let [[rows more] (bounded value max-rows)
          cols (keys-in-order rows)]
      {:columns cols :rows (mapv (fn [r] (mapv #(get r %) cols)) rows) :more-rows more})

    (seq-of? sequential? value)
    (let [[rows more] (bounded value max-rows)]
      {:columns nil :rows (mapv vec rows) :more-rows more})))

(defn table-data
  "`value` as a bounded table, or nil if it is no shape of table:

     {:columns [\"name\" \"age\"]   ; nil when there is no header
      :rows [[\"Alice\" \"42\"]]     ; every cell already text
      :align [:left :right]        ; a column of numbers aligns right
      :omitted {:rows 120 :columns 0}}

   :omitted :rows is :some when more rows exist but were not counted."
  ([value] (table-data value default-limits))
  ([value limits]
   (let [{:keys [max-rows max-columns max-cell-chars]} (merge default-limits limits)]
     (when-let [{:keys [columns rows more-rows]} (shape value max-rows)]
       (let [width (if columns (count columns) (reduce max 0 (map count rows)))
             keep-n (min width max-columns)
             rows (mapv #(vec (take keep-n (concat % (repeat nil)))) rows)
             numeric? (fn [i] (let [vs (keep #(nth % i) rows)]
                                (and (seq vs) (every? number? vs))))]
         {:columns (when columns (mapv #(header % max-cell-chars) (take keep-n columns)))
          :rows (mapv (fn [r] (mapv #(cell % max-cell-chars) r)) rows)
          :align (mapv #(if (numeric? %) :right :left) (range keep-n))
          :omitted {:rows more-rows :columns (- width keep-n)}})))))

(defn- notes
  "What was left out, said in words."
  [{{:keys [rows columns]} :omitted}]
  (cond-> []
    (= :some rows) (conj "… more rows")
    (and (number? rows) (pos? rows)) (conj (str "… " rows " more row" (when (> rows 1) "s")))
    (pos? columns) (conj (str "… " columns " more column" (when (> columns 1) "s")))))

;; -------- Emacs: aligned text --------

(defn text
  "The table as monospace text, in the style of an Org table, which Emacs
   shows as it is."
  [{:keys [columns rows align] :as t}]
  (let [all (cond->> rows columns (cons columns))
        widths (apply mapv (fn [& cells] (reduce max 1 (map count cells))) all)
        pad (fn [s w a] (let [fill (apply str (repeat (- w (count s)) \space))]
                          (if (= a :right) (str fill s) (str s fill))))
        line (fn [r] (str "| " (str/join " | " (map pad r widths align)) " |"))
        rule (str "|" (str/join "+" (map #(apply str (repeat (+ 2 %) \-)) widths)) "|")]
    (str/join "\n" (concat (when columns [(line columns) rule])
                           (map line rows)
                           (notes t)))))

;; -------- the page: typeset --------

(def font-family "Times New Roman")
(def font-size 40)
(def min-font-size
  "A table too wide for the page is set smaller, down to this; it is still
   comfortably legible on e-ink."
  28)

(defn- html-table [{:keys [columns rows align] :as t}]
  (let [cell (fn [tag s a] (str "<" tag (when (= a :right) " class=\"r\"") ">"
                                (html/escape s) "</" tag ">"))]
    (str "<table>"
         (when columns
           (str "<tr>" (apply str (map #(cell "th" %1 %2) columns align)) "</tr>"))
         (apply str (for [r rows]
                      (str "<tr>" (apply str (map #(cell "td" %1 %2) r align)) "</tr>")))
         "</table>"
         (apply str (map #(str "<p>" (html/escape %) "</p>") (notes t))))))

(defn- css [size {:keys [nowrap? width]}]
  (str "body { font-family: '" font-family "', serif; font-size: " size "px; }"
       "table { border-collapse: collapse;"
       (when width (str " width: " width "px;")) " }"
       "th, td { padding: 0.1em 0.5em; text-align: left; vertical-align: top;"
       (when nowrap? " white-space: nowrap;") " }"
       "th:first-child, td:first-child { padding-left: 0; }"
       "th { border-bottom: 2px solid black; }"
       ".r { text-align: right; }"
       "p { font-style: italic; margin: 0.3em 0 0 0; }"))

(def ^:private canvas-width
  "Laid out on a page wider than any table, so nothing is clipped at its edge
   and the drawn width is the table's own."
  8000)

(defn- draw [t size opts]
  (let [img (html/render (html/document canvas-width (css size opts) (html-table t)))
        gray (image/scale img (.getWidth img) (.getHeight img))]
    (image/trim gray)))

(defn image
  "The table typeset for the page, no wider than `max-width`. A table that is
   too wide is set in a smaller size, down to `min-font-size`; one still too
   wide then wraps its cells between words, and only if even that cannot fit
   is the drawing scaled down."
  ^BufferedImage [t max-width]
  (let [fits? (fn [^BufferedImage img] (<= (.getWidth img) max-width))
        natural (draw t font-size {:nowrap? true})]
    (if (fits? natural)
      natural
      ;; Width is not quite proportional to size (rounding, rules), so start
      ;; from the proportional estimate and step down until it fits.
      (let [estimate (int (* font-size (/ (double max-width) (.getWidth natural))))
            sizes (range (max min-font-size (min estimate (dec font-size)))
                         (dec min-font-size) -1)
            smaller (some #(let [img (draw t % {:nowrap? true})] (when (fits? img) img))
                          sizes)]
        (or smaller
            (let [wrapped (draw t min-font-size {:width max-width})]
              (if (fits? wrapped)
                wrapped
                (let [[w h] (image/fit (.getWidth wrapped) (.getHeight wrapped)
                                       max-width Long/MAX_VALUE)]
                  (image/scale wrapped w h)))))))))
