(ns clerkenwell.main
  "A single-process driver for the first end-to-end run, before the Emacs
   integration exists.

   Everything happens in one JVM on purpose: the store's event log has one
   writer per session, so evaluation, rendering and the handshake server must
   not be split across processes. (That is also why there is no separate
   `submit` subcommand to run in another terminal.)

   Expressions are read from stdin, one per line. They are evaluated in THIS
   JVM, which is a stand-in for the user's CIDER session -- that substitution is
   the whole of what increment 3 replaces."
  (:gen-class)
  (:require [clojure.string :as str]
            [clerkenwell.core :as core]
            [clerkenwell.ink :as ink]
            [clerkenwell.watch :as watch]
            [clerkenwell.placement :as placement]
            [clerkenwell.store :as store]
            [clerkenwell.transport :as transport]))

(defn- parse-args [args]
  (into {} (for [[k v] (partition 2 args)]
             [(keyword (str/replace k #"^--" "")) v])))

(defn- report [nb id]
  (let [r (get (store/read-manifest nb) id)]
    (println (format "   %s  %s" id (name (get-in r [:delivery :state]))))))

(defn- send-one!
  "Evaluate, render, place, push, and hand off to the adapter. The target page
   is whatever the adapter last reported; if that report is stale we refuse
   rather than aim at a guess."
  [{:keys [state-root ssh-host notebook page]} source]
  (let [live (transport/fresh-identity)
        target-page (or page (:page live))
        target-doc (or notebook (:doc live))]
    (cond
      (nil? live)
      (println "!! no fresh identity from the device -- is the adapter running and the tunnel up?")

      (and notebook (not= notebook (:doc live)))
      (println (format "!! the device is on notebook %s, not %s" (:doc live) notebook))

      :else
      (let [nb (store/notebook-dir state-root target-doc)
            ;; Where the user's writing ends, from the watcher's observations.
            ;; This reflects the last SAVED state; Xochitl saves lazily, so ink
            ;; written in the last few seconds may not be accounted for yet.
            ink-bottom (ink/ink-bottom nb target-page)
            prev-bottom (->> (vals (store/read-manifest nb))
                             (filter #(= target-page (:page-id %)))
                             (keep #(get-in % [:placement :bottom]))
                             (reduce max 0))
            r (core/submit! {:state-root state-root
                             :notebook-id target-doc
                             :page-id target-page
                             :source source
                             :namespace "user"
                             :ink-bottom ink-bottom
                             :outputs-bottom (when (pos? prev-bottom) prev-bottom)})]
        (if-not (:ok? r)
          (println (format "!! %s: %s" (name (:stage r)) (or (:message r) (:reason r))))
          (do
            (println (format "   %s  at dx=%s dy=%s  (%sx%s)"
                             (get-in r [:presentation :text])
                             (get-in r [:placement :dx]) (get-in r [:placement :dy])
                             (get-in r [:asset :width]) (get-in r [:asset :height])))
            (transport/push-asset! nb (:result-id r) {:ssh-host ssh-host})
            (report nb (:result-id r))))))))

(defn -main [& args]
  (let [{:keys [state-root ssh-host notebook page port] :as opts}
        (merge {:state-root "state" :port "14242"} (parse-args args))]
    (when-not ssh-host
      (println "usage: clojure -M -m clerkenwell.main --ssh-host <alias> [--state-root DIR]")
      (println "                  [--notebook UUID] [--page UUID] [--port N]")
      (println)
      (println "With no --notebook/--page, targets whatever page the device reports.")
      (System/exit 2))
    (let [server (transport/start! state-root {:port (parse-long port)})]
      (println "waiting for the device to report its active page...")
      (loop [n 0]
        (if-let [live (transport/fresh-identity)]
          (println (format "device is on notebook %s page %s" (:doc live) (:page live)))
          (if (> n 60)
            (do (println "!! no report from the device after 30s.")
                (println "   Check that the adapter is installed, xovi is running,")
                (println "   and the tunnel is up:")
                (println (format "   ssh -R 127.0.0.1:%s:127.0.0.1:%s <alias>" port port))
                (transport/stop! server)
                (System/exit 1))
            (do (Thread/sleep 500) (recur (inc n))))))

      ;; Watch the live notebook for saved handwriting. Runs for the whole
      ;; session so ink-bottom is as current as the last completed save.
      (let [live (transport/fresh-identity)
            nb (store/notebook-dir state-root (:doc live))
            stop? (atom false)]
        (doto (Thread.
               (fn []
                 (watch/watch!
                  {:nb nb :ssh-host ssh-host :notebook-id (:doc live)
                   :stop? stop?
                   :on-entry (fn [e]
                               (println (format "\n   [ink] page %s: %d new strokes, bottom y=%.0f"
                                                (subs (:page-id e) 0 8)
                                                (count (:stroke-ids e))
                                                (double (get-in e [:bbox :max-y]))))
                               (print "clerkenwell> ") (flush))})))
          (.setDaemon true)
          (.start))
        (println)
        (println "Watching for handwriting. Xochitl saves about 12s after you stop")
        (println "writing, so pause before evaluating if you want results placed")
        (println "below ink you just wrote.")
        (println)
        (println "Type a Clojure expression and press enter. Ctrl-D to quit."))
      (loop []
        (print "clerkenwell> ") (flush)
        (when-let [line (read-line)]
          (when-not (str/blank? line)
            (try
              (send-one! (assoc opts :state-root state-root :ssh-host ssh-host
                                :notebook nil :page nil)
                         line)
              (catch Exception e
                (println "!!" (.getMessage e)))))
          (recur)))
      (transport/stop! server)
      (println "\nbye"))))
