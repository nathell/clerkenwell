(ns clerkenwell.core
  "Submission capture and the user-facing entry point.

   The contract that matters: a submission gets its id BEFORE evaluation, and
   causes at most one evaluation attempt. Nothing in this namespace retries an
   evaluation -- not a timeout, not a rendering failure, not a transfer retry.
   Resubmission is an explicit user act that creates a new computation id."
  (:require [clojure.java.io :as io]
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

(defn submit!
  "Capture, evaluate once, present, persist and queue for delivery.

   Order is deliberate. The id exists before evaluation, so an unknown outcome
   is still attributable. The bounded text is persisted before rasterization, so
   a rasterization failure can be retried from stored text without re-evaluating
   anything."
  [{:keys [state-root notebook-id page-id source namespace
           ink-bottom outputs-bottom limits config include-source?]}]
  (let [ns-sym (symbol (str namespace))
        nb (store/notebook-dir state-root notebook-id)
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
          (let [presentation (render/present
                              (:value ev)
                              (cond-> (merge render/default-limits limits)
                                include-source? (assoc :source source)))]
            (if (:failed? presentation)
              ;; Retain source and failure status; do not claim a committed
              ;; result and do not re-evaluate.
              (do (store/create-result!
                   nb {:result-id result-id :notebook-id notebook-id :page-id page-id
                       :source source :namespace (str namespace)
                       :presentation presentation :asset nil})
                  (store/set-delivery! nb result-id :failed (name (:reason presentation)))
                  {:ok? false :stage :present :result-id result-id
                   :reason (:reason presentation)})
              ;; Persist the bounded text BEFORE rasterizing.
              (let [cfg (merge placement/default-config config)
                    record (store/create-result!
                            nb {:result-id result-id :notebook-id notebook-id
                                :page-id page-id :source source
                                :namespace (str namespace)
                                :presentation presentation :asset nil})
                    tmp (io/file (System/getProperty "java.io.tmpdir")
                                 (str "ckw-" result-id ".png"))
                    usable-w (- (:page-width cfg) (* 2 (:left-margin cfg)))
                    top (placement/output-top ink-bottom outputs-bottom cfg)
                    raster (render/rasterize presentation tmp
                                             {:max-width usable-w
                                              :max-height (- (:page-height cfg)
                                                             top (:bottom-margin cfg))})]
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
                           :presentation presentation}))))))))))))
