;;; clerkenwell.el --- Handwritten Clojure notebooks on a reMarkable  -*- lexical-binding: t; -*-

;; Copyright (c) 2026 Daniel Janus
;; SPDX-License-Identifier: MIT

;;; Commentary:

;; A minor mode over `clojure-mode' providing one interaction buffer per linked
;; notebook: a read-only ordered history, then an editable tail holding the
;; expression you are working on.
;;
;; Evaluation happens in your own CIDER session, through `clerkenwell.session',
;; which must be on that JVM's classpath.  Ordinary `cider-eval-*' commands are
;; untouched and never send anything to the tablet; only
;; `clerkenwell-evaluate-and-send' commits a result.
;;
;; The history is not a faithful replica of the tablet page.  It is an ordered
;; record of what the host observed: handwriting as rendered images, and
;; computations as source plus result.

;;; Code:

(require 'cl-lib)
(require 'cider)
(require 'clojure-mode)
(require 'parseedn)

(defgroup clerkenwell nil
  "Handwritten Clojure notebooks on a reMarkable."
  :group 'clojure
  :prefix "clerkenwell-")

(defcustom clerkenwell-state-root "clerkenwell-state"
  "Directory holding notebook history, relative to the REPL's working directory."
  :type 'string)

(defcustom clerkenwell-ssh-host "rm"
  "SSH host for the reMarkable: an alias from your SSH config, or a hostname."
  :type 'string)

(defcustom clerkenwell-ssh-user nil
  "SSH user for the reMarkable, or nil to let SSH decide.

Leave this nil when the user is already settled by your SSH config, which
is the tidier arrangement.  Set it to the string \"root\" if you would
otherwise type \"ssh root@rm\": without it SSH defaults to your local
username, which the device rejects."
  :type '(choice (const :tag "From SSH config" nil) string))

(defun clerkenwell--ssh-destination ()
  "The SSH destination, as USER@HOST or just HOST."
  (if (and clerkenwell-ssh-user (not (string-empty-p clerkenwell-ssh-user)))
      (concat clerkenwell-ssh-user "@" clerkenwell-ssh-host)
    clerkenwell-ssh-host))

(defcustom clerkenwell-port 14242
  "Port the device reaches the host on, through the reverse tunnel."
  :type 'integer)

(defcustom clerkenwell-quiet t
  "Whether to omit commentary the tablet page does not have.

When non-nil the buffer shows handwriting images and computations much as
the page does, without per-entry headers.  Delivery state is still shown
whenever it is anything other than delivered: a result still in flight,
failed, or uncertain must not look the same as one on the page.  Problems
rendering an entry are always shown."
  :type 'boolean)

(defcustom clerkenwell-send-source t
  "Whether the tablet receives the expression above its result.
Emacs records both either way; this only controls what is written on the
page.  `clerkenwell-evaluate-and-send' inverts this for a single
submission when given a prefix argument."
  :type 'boolean)

(defcustom clerkenwell-ink-colors 'theme
  "Colours handwriting images are drawn in.

These images are shown in Emacs and never sent to the device, so they
follow the editor's theme rather than the page's.  Either the symbol
`theme' to track the current Emacs faces, or a cons cell of
\(FOREGROUND . BACKGROUND) naming colours ImageMagick understands."
  :type '(choice (const :tag "Follow the Emacs theme" theme)
                 (cons :tag "Explicit" string string)))

(defun clerkenwell--ink-colors ()
  "The stroke and background colours to draw handwriting in."
  (if (consp clerkenwell-ink-colors)
      clerkenwell-ink-colors
    (let ((fg (face-attribute 'default :foreground nil t))
          (bg (face-attribute 'default :background nil t)))
      ;; A terminal frame can report `unspecified-*', which ImageMagick would
      ;; reject; fall back to the ink-on-paper default in that case.
      (cons (if (and (stringp fg) (string-prefix-p "#" fg)) fg "black")
            (if (and (stringp bg) (string-prefix-p "#" bg)) bg "white")))))

(defcustom clerkenwell-image-scale 0.5
  "Scale factor for handwriting images in the buffer.
Strokes are rendered at page resolution, so a page-wide scribble is
around 800 pixels across; halving it keeps the history readable."
  :type 'number)

(defcustom clerkenwell-show-images t
  "Whether to render handwriting entries as images.
When nil, they appear as a one-line summary instead, which is useful
over a slow connection or in a terminal frame."
  :type 'boolean)

(defvar-local clerkenwell--history-end nil
  "Marker at the end of the protected history region.")

(defvar-local clerkenwell--namespace nil
  "Namespace submissions are evaluated in.")

(defvar-local clerkenwell--connection nil
  "The CIDER connection buffer this buffer submits through.")

(defvar-local clerkenwell--draft-revision 0
  "Incremented on every draft edit, to detect edits made during evaluation.")

(defvar-local clerkenwell--in-flight nil
  "Non-nil while an evaluation is outstanding.")

(defvar-local clerkenwell--status ""
  "Last status string, shown in the mode line.")

(defvar-local clerkenwell--poll-timer nil
  "Timer polling for new history entries.")

(defvar-local clerkenwell--history-version nil
  "Last history version seen, so unchanged history is not refetched.")

(defcustom clerkenwell-poll-interval 3
  "Seconds between checks for new handwriting.
Handwriting arrives on its own schedule -- Xochitl saves lazily and the
watcher polls the device -- so the buffer has to look for it rather than
being told."
  :type 'number)

;;; ------------------------------------------------------------------
;;; Talking to the JVM

(defun clerkenwell--clj-string (s)
  "Render S as a Clojure string literal.
Escaping rather than interpolation: the draft is arbitrary user text."
  (concat "\""
          (replace-regexp-in-string
           "[\\\"\n\r\t]"
           (lambda (m)
             (pcase m ("\\" "\\\\") ("\"" "\\\"") ("\n" "\\n")
                    ("\r" "\\r") ("\t" "\\t")))
           s t t)
          "\""))

(defun clerkenwell--connection-buffer ()
  "The CIDER connection to use, erroring if there is none."
  (or clerkenwell--connection
      (cider-current-repl nil 'ensure)))

(defun clerkenwell--eval-async (code callback)
  "Evaluate CODE in the linked REPL, calling CALLBACK with parsed EDN or nil.

An nREPL evaluation arrives as several messages: the value comes in one and
the final status in another, so the value must be accumulated rather than
read off the message carrying \='done\='.  CALLBACK runs exactly once."
  (let ((buf (current-buffer))
        (result nil)
        (problem nil)
        (fired nil))
    (cider-nrepl-send-eval-request
     code
     (lambda (response)
       (nrepl-dbind-response response (value err ex status)
         (when value (setq result value))
         (when err (setq problem (string-trim err)))
         (when ex (setq problem (or problem ex)))
         (when (and (member "done" status) (not fired))
           (setq fired t)
           (with-current-buffer buf
             (funcall callback
                      (when (and result (not problem))
                        (condition-case nil (parseedn-read-str result)
                          (error nil)))
                      problem)))))
     :connection (clerkenwell--connection-buffer))))

(defun clerkenwell--eval-sync (code)
  "Evaluate CODE in the linked REPL and return parsed EDN."
  (let* ((resp (cider-nrepl-sync-request:eval
                code (clerkenwell--connection-buffer)))
         (value (nrepl-dict-get resp "value")))
    (when value
      (condition-case nil (parseedn-read-str value) (error nil)))))

(defun clerkenwell--get (m key &optional default)
  "Look KEY up in parsed EDN map M."
  (or (and (hash-table-p m) (gethash key m)) default))

(defun clerkenwell--name (v)
  "The bare name of V, whatever parseedn made of it.

EDN keywords arrive as Emacs keywords, whose `symbol-name' keeps the
leading colon, so comparing against a plain name silently never matches."
  (cond
   ((null v) "")
   ((keywordp v) (substring (symbol-name v) 1))
   ((symbolp v) (symbol-name v))
   (t (format "%s" v))))

;;; ------------------------------------------------------------------
;;; Buffer structure
;;
;; [ history ... ][ separator ][ editable draft ]
;;
;; The history and separator carry a `read-only' property.  The last character
;; before the draft is `rear-nonsticky', so text typed at the boundary is
;; editable rather than inheriting protection.

(defconst clerkenwell--separator
  "\n;; ─── draft ──────────────────────────────────────────────────\n")

(defun clerkenwell--protect (start end)
  "Mark START..END read-only, leaving the boundary writable."
  (let ((inhibit-read-only t))
    (put-text-property start end 'read-only t)
    (put-text-property start end 'front-sticky t)
    (when (> end start)
      (put-text-property (1- end) end 'rear-nonsticky '(read-only)))))

(defun clerkenwell-draft ()
  "The current draft text."
  (when clerkenwell--history-end
    (buffer-substring-no-properties
     (marker-position clerkenwell--history-end) (point-max))))

(defun clerkenwell--draft-start ()
  (marker-position clerkenwell--history-end))

(defun clerkenwell--render-entry (entry)
  "Insert one history ENTRY at point."
  (let ((kind (clerkenwell--get entry :kind)))
    (pcase (clerkenwell--name kind)
      ("handwriting"
       (let* ((asset (clerkenwell--get entry :asset))
              (n (clerkenwell--get entry :strokes))
              ;; Say why an image is absent rather than quietly showing only
              ;; the header, which is indistinguishable from working.
              (problem (cond
                        ((not clerkenwell-show-images) nil)
                        ((not (display-graphic-p)) "no graphics in this frame")
                        ((not (display-images-p)) "images unsupported")
                        ((null asset) "no asset recorded")
                        ((not (file-readable-p asset))
                         (format "unreadable: %s" asset)))))
         ;; The header is commentary the page does not have, but a problem is
         ;; not: without it a missing image is indistinguishable from a blank
         ;; one.
         (when (or problem (not clerkenwell-quiet))
           (insert (propertize
                    (format ";; handwriting — %s stroke%s%s\n"
                            n (if (equal n 1) "" "s")
                            (if problem (format " [%s]" problem) ""))
                    'face (if problem 'font-lock-warning-face
                            'font-lock-comment-face))))
         (if (or problem (not clerkenwell-show-images))
             (insert "\n")
           (insert-image (create-image asset nil nil
                                       :scale clerkenwell-image-scale))
           (insert "\n\n"))))
      ("computation"
       (let ((src (clerkenwell--get entry :source))
             (text (clerkenwell--get entry :text))
             (delivery (clerkenwell--get entry :delivery)))
         (insert src "\n")
         (insert (propertize (format "%s" text) 'face 'font-lock-string-face))
         ;; Delivery is mutable and shown separately from the immutable
         ;; source/result above it. Quiet mode omits it only when there is
         ;; nothing to say: anything still in flight, failed or uncertain is
         ;; always shown, since it must not look like a result on the page.
         (let ((state (clerkenwell--name delivery)))
           (if (and clerkenwell-quiet (equal state "inserted"))
               (insert "\n\n")
             (insert (propertize
                      (format "   ; %s\n\n" state)
                      'face (if (member state '("failed" "uncertain"))
                                'font-lock-warning-face
                              'font-lock-comment-face)))))))
      (other
       ;; Never silently swallow an entry: a shape we do not know about is a
       ;; bug, and should look like one.
       (insert (propertize (format ";; unrecognised history entry (kind %s)\n"
                                   (if (string-empty-p other) "missing" other))
                           'face 'font-lock-warning-face))))))

(defun clerkenwell--rebuild-history (entries)
  "Replace the history region with ENTRIES, preserving the draft and point.
Asynchronous updates must not disturb what you are typing, so the draft
text, point and mark are restored around the rewrite."
  (let* ((inhibit-read-only t)
         (draft (or (clerkenwell-draft) ""))
         (point-in-draft (when (and clerkenwell--history-end
                                    (>= (point) (clerkenwell--draft-start)))
                           (- (point) (clerkenwell--draft-start)))))
    (save-restriction
      (widen)
      (erase-buffer)
      (insert (propertize ";; Clerkenwell\n\n" 'face 'font-lock-comment-face))
      ;; The separator stays even when quiet: it is structure, not commentary,
      ;; and without it there is no way to see where the editable region starts.
      (mapc #'clerkenwell--render-entry entries)
      (insert clerkenwell--separator)
      (clerkenwell--protect (point-min) (point))
      (setq clerkenwell--history-end (copy-marker (point) nil))
      (insert draft))
    (goto-char (if point-in-draft
                   (min (point-max) (+ (clerkenwell--draft-start) point-in-draft))
                 (point-max)))))

;;; ------------------------------------------------------------------
;;; Commands

;;;###autoload
(defun clerkenwell-open (namespace)
  "Open the Clerkenwell buffer, starting a session in the current REPL.
NAMESPACE is the namespace submissions are evaluated in."
  (interactive (list (cider-read-from-minibuffer
                      "Evaluate submissions in namespace: " (cider-current-ns))))
  (let* ((conn (cider-current-repl nil 'ensure))
         (buf (get-buffer-create "*clerkenwell*")))
    (with-current-buffer buf
      (unless (derived-mode-p 'clojure-mode) (clojure-mode))
      (setq clerkenwell--connection conn
            clerkenwell--namespace namespace)
      (clerkenwell-mode 1)
      (let* ((colors (clerkenwell--ink-colors))
             (started (clerkenwell--eval-sync
                       (format "(do (require 'clerkenwell.session) (clerkenwell.session/start! {:state-root %s :ssh-host %s :port %d :render-opts {:stroke-color %s :background %s}}))"
                               (clerkenwell--clj-string clerkenwell-state-root)
                               (clerkenwell--clj-string (clerkenwell--ssh-destination))
                               clerkenwell-port
                               (clerkenwell--clj-string (car colors))
                               (clerkenwell--clj-string (cdr colors))))))
        ;; A nil result means the eval itself failed -- most often because
        ;; clerkenwell.session is not on this REPL's classpath. Saying
        ;; "already running" there would hide a real problem.
        (unless started
          (user-error
           "Clerkenwell: could not start a session. Is clerkenwell.session on the classpath?"))
        (setq clerkenwell--status
              (if (clerkenwell--get started :already-running?)
                  "reusing session" "started")))
      (clerkenwell-refresh)
      (clerkenwell--start-polling)
      (clerkenwell-check-device))
    (pop-to-buffer buf)))

(defun clerkenwell-check-device ()
  "Report on both paths to the device, which fail independently.

The tunnel carries the adapter's own outbound requests and needs no SSH
from the REPL's JVM, so a page can be reported while every SSH from that
JVM is failing -- which is how handwriting goes missing while the status
line looks healthy."
  (interactive)
  (let* ((r (clerkenwell--eval-sync "(clerkenwell.session/check-device)"))
         (tunnel (and r (clerkenwell--get r :tunnel)))
         (ssh (and r (clerkenwell--get r :ssh))))
    (if (null r)
        (message "Clerkenwell: no session")
      (let ((tunnel-ok (clerkenwell--get tunnel :ok?))
            (ssh-ok (clerkenwell--get ssh :ok?)))
        (message
         "Clerkenwell: tunnel %s, ssh(%s) %s%s"
         (if tunnel-ok "ok" "DOWN")
         (clerkenwell--get ssh :host)
         (if ssh-ok "ok" "FAILING")
         (cond
          ((and tunnel-ok (not ssh-ok))
           (format " — results will insert but handwriting will not arrive: %s"
                   (clerkenwell--get ssh :message)))
          ((not tunnel-ok) (format " — %s" (clerkenwell--get tunnel :message)))
          (t "")))))))

(defun clerkenwell-refresh ()
  "Fetch history and device state without evaluating anything."
  (interactive)
  (let* ((h (clerkenwell--eval-sync "(clerkenwell.session/history)"))
         (entries (and h (clerkenwell--get h :entries))))
    (clerkenwell--rebuild-history (append entries nil))
    (clerkenwell--update-status)))

(defun clerkenwell--update-status ()
  "Refresh the mode-line status from the session."
  (let ((s (clerkenwell--eval-sync "(clerkenwell.session/status)")))
    (setq clerkenwell--status
          (if (and s (clerkenwell--get s :running?))
              (let* ((active (clerkenwell--get s :active))
                     (page (and active (clerkenwell--name
                                        (clerkenwell--get active :page))))
                     (uncertain (clerkenwell--get s :uncertain)))
                (concat (if (and page (not (string-empty-p page)))
                            (format "page %s" (substring page 0 (min 8 (length page))))
                          "no page")
                        (when (and uncertain (> (length uncertain) 0))
                          (format " ⚠%d uncertain" (length uncertain)))))
            "not running"))
    (force-mode-line-update)))

(defun clerkenwell-evaluate-and-send (&optional invert)
  "Evaluate the draft once and commit its result to the tablet.

The draft must hold exactly one top-level form.  On success the
submission is archived into the history and the draft cleared -- unless
you have edited it in the meantime, in which case your newer text is
kept.

By default the expression is written on the page above its result, per
`clerkenwell-send-source'.  With a prefix argument INVERT, send just the
result instead (or just the source line's counterpart, if you have
customised that variable the other way)."
  (interactive "P")
  (cond
   (clerkenwell--in-flight
    (message "Clerkenwell: an evaluation is already in flight"))
   ((string-blank-p (or (clerkenwell-draft) ""))
    (message "Clerkenwell: nothing to evaluate"))
   (t
    (let* ((source (clerkenwell-draft))
           (revision clerkenwell--draft-revision)
           (with-source (if invert (not clerkenwell-send-source)
                          clerkenwell-send-source)))
      (setq clerkenwell--in-flight t
            clerkenwell--status "evaluating…")
      (force-mode-line-update)
      (clerkenwell--eval-async
       (format "(clerkenwell.session/submit %s %s {:include-source? %s})"
               (clerkenwell--clj-string source)
               (clerkenwell--clj-string clerkenwell--namespace)
               (if with-source "true" "false"))
       (lambda (result err)
         (setq clerkenwell--in-flight nil)
         (cond
          (err (setq clerkenwell--status "error")
               (message "Clerkenwell: %s" err))
          ((null result)
           (setq clerkenwell--status "error")
           (message "Clerkenwell: no result (is clerkenwell.session on the classpath?)"))
          ((clerkenwell--get result :ok?)
           ;; Only clear the draft if it is still the text we submitted.
           (when (= revision clerkenwell--draft-revision)
             (let ((inhibit-read-only t))
               (delete-region (clerkenwell--draft-start) (point-max))))
           (message "Clerkenwell: %s" (clerkenwell--get result :text))
           (clerkenwell-refresh))
          (t
           (setq clerkenwell--status "rejected")
           ;; A failure leaves the draft alone so it can be corrected.
           (message "Clerkenwell [%s]: %s"
                    (clerkenwell--get result :stage)
                    (or (clerkenwell--get result :message)
                        (clerkenwell--get result :reason)))))))))))

(defun clerkenwell-retry-delivery (result-id)
  "Resume delivery of RESULT-ID without evaluating again."
  (interactive (list (read-string "Result id: ")))
  (let ((r (clerkenwell--eval-sync
            (format "(clerkenwell.session/retry-delivery %s)"
                    (clerkenwell--clj-string result-id)))))
    (message "Clerkenwell: %s" (if (and r (clerkenwell--get r :ok?)) "requeued"
                                 (format "%s" (and r (clerkenwell--get r :reason)))))
    (clerkenwell-refresh)))

(defun clerkenwell-reconcile (result-id present)
  "Resolve an uncertain RESULT-ID after looking at the page.
PRESENT non-nil means the result is visibly on the page."
  (interactive (list (read-string "Result id: ")
                     (y-or-n-p "Is it visibly on the page? ")))
  (clerkenwell--eval-sync
   (format "(clerkenwell.session/reconcile %s %s)"
           (clerkenwell--clj-string result-id) (if present "true" "false")))
  (clerkenwell-refresh))

(defun clerkenwell-sync-colors ()
  "Redraw handwriting images in the current theme's colours.
Existing entries are redrawn from their retained page snapshots, so the
history does not end up half in one scheme and half in another.  Run this
after changing theme."
  (interactive)
  (let* ((colors (clerkenwell--ink-colors))
         (r (clerkenwell--eval-sync
             (format "(clerkenwell.session/set-ink-colors! {:stroke-color %s :background %s})"
                     (clerkenwell--clj-string (car colors))
                     (clerkenwell--clj-string (cdr colors))))))
    (if (and r (clerkenwell--get r :ok?))
        (progn
          (clear-image-cache)
          (setq clerkenwell--history-version nil)
          (clerkenwell-refresh)
          (message "Clerkenwell: %s on %s, %s entries redrawn"
                   (car colors) (cdr colors)
                   (clerkenwell--get r :rerendered 0)))
      (message "Clerkenwell: %s"
               (or (and r (clerkenwell--get r :message)) "could not set colours")))))

(defun clerkenwell-reset-history ()
  "Archive this notebook's history so the buffer starts fresh.

The history is moved aside, not deleted.  Note that this clears only what
the host remembers: ink already on the page is observed again as a fresh
initial entry, which is also what keeps results placed below it.  For a
genuinely blank slate, start on a blank page."
  (interactive)
  (when (yes-or-no-p "Archive this notebook's Clerkenwell history? ")
    (let ((r (clerkenwell--eval-sync "(clerkenwell.session/archive-notebook!)")))
      (if (and r (clerkenwell--get r :ok?))
          (progn
            (setq clerkenwell--history-version nil)
            (clerkenwell-refresh)
            (message "Clerkenwell: %s"
                     (if-let ((to (clerkenwell--get r :archived)))
                         (format "history archived to %s" to)
                       "no history to archive")))
        (message "Clerkenwell: %s"
                 (or (and r (clerkenwell--get r :message)) "reset failed"))))))

(defun clerkenwell-disconnect ()
  "Stop the transport and watcher, keeping history and pending deliveries."
  (interactive)
  (clerkenwell--stop-polling)
  (clerkenwell--eval-sync "(clerkenwell.session/stop!)")
  (setq clerkenwell--status "stopped")
  (force-mode-line-update)
  (message "Clerkenwell: stopped"))

;;; ------------------------------------------------------------------
;;; Mode

(defun clerkenwell--poll ()
  "Refresh the buffer if history has grown.
Skipped while an evaluation is outstanding, so a background refresh
cannot interleave with a submission."
  (when (and (buffer-live-p (current-buffer))
             (not clerkenwell--in-flight))
    (condition-case nil
        (let* ((v (clerkenwell--eval-sync "(clerkenwell.session/history-version)"))
               (version (and v (clerkenwell--get v :version))))
          (when (and version (not (equal version clerkenwell--history-version)))
            (setq clerkenwell--history-version version)
            (clerkenwell-refresh)))
      ;; A dead REPL or a stopped session must not spam errors from a timer.
      (error nil))))

(defun clerkenwell--start-polling ()
  (clerkenwell--stop-polling)
  (let ((buf (current-buffer)))
    (setq clerkenwell--poll-timer
          (run-with-timer clerkenwell-poll-interval clerkenwell-poll-interval
                          (lambda ()
                            (if (buffer-live-p buf)
                                (with-current-buffer buf (clerkenwell--poll))
                              (cancel-timer clerkenwell--poll-timer)))))))

(defun clerkenwell--stop-polling ()
  (when clerkenwell--poll-timer
    (cancel-timer clerkenwell--poll-timer)
    (setq clerkenwell--poll-timer nil)))

(defun clerkenwell--after-change (_beg _end _len)
  "Note that the draft changed, for the submission token check."
  (when clerkenwell--history-end
    (cl-incf clerkenwell--draft-revision)))

(defvar clerkenwell-mode-map
  (let ((m (make-sparse-keymap)))
    (define-key m (kbd "C-c C-s") #'clerkenwell-evaluate-and-send)
    (define-key m (kbd "C-c C-r") #'clerkenwell-refresh)
    (define-key m (kbd "C-c C-q") #'clerkenwell-disconnect)
    (define-key m (kbd "C-c C-d") #'clerkenwell-check-device)
    m)
  "Keymap for `clerkenwell-mode'.")

;;;###autoload
(define-minor-mode clerkenwell-mode
  "Minor mode for a Clerkenwell interaction buffer.

Only `clerkenwell-evaluate-and-send' commits anything to the tablet;
ordinary CIDER evaluation in this buffer behaves as it does anywhere
else and sends nothing."
  :lighter (:eval (concat " Clerkenwell[" clerkenwell--status "]"))
  :keymap clerkenwell-mode-map
  (if clerkenwell-mode
      (progn
        (add-hook 'after-change-functions #'clerkenwell--after-change nil t)
        (add-hook 'kill-buffer-hook #'clerkenwell--stop-polling nil t)
        (unless clerkenwell--history-end
          (setq clerkenwell--history-end (copy-marker (point-max) nil))))
    (clerkenwell--stop-polling)
    (remove-hook 'after-change-functions #'clerkenwell--after-change t)
    (remove-hook 'kill-buffer-hook #'clerkenwell--stop-polling t)))

(provide 'clerkenwell)
;;; clerkenwell.el ends here
