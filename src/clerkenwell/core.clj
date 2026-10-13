(ns clerkenwell.core
  "Submission capture and the user-facing entry point.

   The contract that matters: a submission gets its id BEFORE evaluation, and
   causes at most one evaluation attempt. Nothing in this namespace retries an
   evaluation -- not a timeout, not a rendering failure, not a transfer retry.
   Resubmission is an explicit user act that creates a new computation id."
  (:require [clojure.java.io :as io]
            [clerkenwell.html :as html]
            [clerkenwell.placement :as placement]
            [clerkenwell.render :as render]
            [clerkenwell.store :as store])
  (:import [java.io PushbackReader StringReader]))

;; -------- reading exactly one top-level form --------

(defn read-single-form
  "Read `source` as exactly one top-level form, in `ns-sym` so that the
   namespace's aliases resolve, with reader evaluation disabled.

   `read-string` alone does not prove all input was consumed, so this reads a
   second time and requires EOF. Returns {:ok form} or {:error kw :message s}."
  [source ns-sym]
  (let [ns-obj (or (find-ns ns-sym)
                   (throw (ex-info "unknown namespace" {:ns ns-sym})))]
    (if (clojure.string/blank? source)
      {:error :empty :message "nothing to evaluate"}
      (let [rdr (PushbackReader. (StringReader. source))]
        (binding [*read-eval* false
                  *ns* ns-obj]
          (let [first-form (try
                             (read {:eof ::eof :read-cond :allow} rdr)
                             (catch Exception e
                               {::read-error (.getMessage e)}))]
            (cond
              (and (map? first-form) (::read-error first-form))
              {:error :unreadable :message (::read-error first-form)}

              (= first-form ::eof)
              {:error :empty :message "nothing to evaluate"}

              :else
              (let [next-form (try
                                (read {:eof ::eof :read-cond :allow} rdr)
                                (catch Exception e
                                  {::read-error (.getMessage e)}))]
                (cond
                  (and (map? next-form) (::read-error next-form))
                  {:error :unreadable :message (::read-error next-form)}

                  (not= next-form ::eof)
                  {:error :extra-forms
                   :message "expected exactly one top-level form"}

                  :else {:ok first-form})))))))))

;; -------- evaluating exactly once --------

(defn evaluate-once
  "Evaluate `form` in `ns-sym` and capture the actual JVM value.

   Returns {:outcome :value :value v}, {:outcome :error :message s} or
   {:outcome :unknown :message s}. `:unknown` means the form may already have
   had side effects and the caller must report that rather than retry."
  [form ns-sym]
  (try
    (binding [*ns* (the-ns ns-sym)]
      {:outcome :value :value (eval form)})
    (catch ThreadDeath e (throw e))
    (catch Throwable e
      {:outcome :error
       :message (str (.getSimpleName (class e))
                     (when-let [m (.getMessage e)] (str ": " m)))})))

;; -------- submission --------

(defn- persist-image!
  "An image presentation carries its bytes only as far as here: they are
   written as the result's image before the record that points at them, so a
   record never names a file that is not there."
  [nb result-id presentation]
  (if-let [bs (:png-bytes presentation)]
    (let [rel (str "results/" result-id "/image.png")]
      (store/publish-bytes! (io/file nb rel) bs)
      (-> presentation (dissoc :png-bytes) (assoc :image rel)))
    presentation))

(defn- commit!
  "Persist `presentation` as result `result-id`, then draw it, place it and
   queue it for delivery. The bounded text is persisted before rasterization,
   so a rasterization failure can be retried from stored text."
  [{:keys [state-root notebook-id page-id source namespace
           ink-bottom outputs-bottom config style]}
   result-id presentation]
  (let [nb (store/notebook-dir state-root notebook-id)
        cfg (merge placement/default-config config)
        style (merge html/default-style style)
        usable-w (- (:page-width cfg) (* 2 (:left-margin cfg)))
        record! (fn [presentation]
                  (store/create-result!
                   nb {:result-id result-id :notebook-id notebook-id :page-id page-id
                       :source source :namespace (some-> namespace str)
                       :presentation presentation :asset nil}))]
    (cond
      (:failed? presentation)
      ;; Retain source and failure status; do not claim a committed
      ;; result and do not re-evaluate.
      (do (record! presentation)
          (store/set-delivery! nb result-id :failed (name (:reason presentation)))
          {:ok? false :stage :present :result-id result-id
           :reason (:reason presentation)})

      ;; A hidden value submitted without its source: nothing goes on
      ;; the page, but the computation is still recorded.
      (and (= :hidden (:kind presentation))
           (nil? (:tablet-text presentation)))
      (let [record (record! presentation)]
        (store/set-delivery! nb result-id :skipped "hidden")
        {:ok? true :result-id result-id :record record
         :presentation presentation :delivery :skipped})

      :else
      ;; Persist the bounded text BEFORE rasterizing.
      (let [presentation (persist-image! nb result-id presentation)
            record (record! presentation)
            tmp (io/file (System/getProperty "java.io.tmpdir")
                         (str "ckw-" result-id ".png"))
            top (placement/output-top ink-bottom outputs-bottom cfg)
            raster (render/rasterize presentation tmp
                                     {:base-dir nb
                                      :font (:font style)
                                      :font-size (:font-size style)
                                      :prose-font (:prose-font style)
                                      :max-width usable-w
                                      :max-height (- (placement/max-bottom cfg)
                                                     top)})]
        (if-not (:ok? raster)
          (do (store/set-delivery! nb result-id :failed "insufficient-space")
              {:ok? false :stage :render :result-id result-id
               :reason (:reason raster) :record record})
          (let [asset (store/attach-asset! nb result-id raster)
                spot (placement/place {:ink-bottom ink-bottom
                                       :outputs-bottom outputs-bottom
                                       :width (:width raster)
                                       :height (:height raster)
                                       :config cfg})]
            (.delete tmp)
            (if-not (:ok? spot)
              (do (store/set-delivery! nb result-id :failed "insufficient-space")
                  {:ok? false :stage :place :result-id result-id
                   :reason (:reason spot) :record record})
              (do (store/record-placement! nb result-id spot)
                  (store/set-delivery! nb result-id :queued)
                  {:ok? true :result-id result-id :record record
                   :asset asset :placement spot
                   :presentation presentation}))))))))

(defn submit!
  "Capture, evaluate once, present, persist and queue for delivery.

   Order is deliberate. The id exists before evaluation, so an unknown outcome
   is still attributable. The bounded text is persisted before rasterization, so
   a rasterization failure can be retried from stored text without re-evaluating
   anything."
  [{:keys [source namespace limits config style include-source?] :as opts}]
  (let [ns-sym (symbol (str namespace))
        parsed (read-single-form source ns-sym)]
    (if (:error parsed)
      {:ok? false :stage :read :reason (:error parsed) :message (:message parsed)}
      ;; Id assigned BEFORE evaluation.
      (let [result-id (str (random-uuid))
            ev (evaluate-once (:ok parsed) ns-sym)]
        (case (:outcome ev)
          :error
          {:ok? false :stage :eval :result-id result-id :message (:message ev)}

          :unknown
          {:ok? false :stage :eval :result-id result-id :unknown? true
           :message (:message ev)}

          :value
          (let [cfg (merge placement/default-config config)
                style (merge html/default-style style)
                usable-w (- (:page-width cfg) (* 2 (:left-margin cfg)))]
            (commit! opts result-id
                     (render/present
                      (:value ev)
                      (cond-> (merge render/default-limits
                                     {:form (:ok parsed)
                                      :chart-width usable-w
                                      :right-margin (render/columns
                                                     usable-w
                                                     (:font style)
                                                     (:font-size style))}
                                     limits)
                        include-source? (assoc :source source))))))))))

(defn send-text!
  "Put `text` on the page as it is, evaluating nothing: persisted, drawn,
   placed and queued for delivery exactly as a result is. The record has no
   source or namespace, since nothing was read."
  [{:keys [text limits] :as opts}]
  (if (clojure.string/blank? text)
    {:ok? false :stage :read :reason :empty :message "nothing to send"}
    (commit! (dissoc opts :source :namespace) (str (random-uuid))
             (render/verbatim text (:max-chars (merge render/default-limits limits))))))
