;;; clerkenwell.el --- Handwritten Clojure notebooks on a reMarkable  -*- lexical-binding: t; -*-

;; Copyright (c) 2026 Daniel Janus
;; SPDX-License-Identifier: MIT

;;; Commentary:

;; A minor mode over `clojure-mode' providing one interaction buffer per
;; notebook on the tablet: a read-only ordered history, then an editable tail
;; holding the expression you are working on.  The buffers follow the tablet:
;; opening another notebook there brings up its buffer here.
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

(defcustom clerkenwell-state-root nil
  "Directory holding notebook history, or nil for the default.

The default is a cache outside any project, $XDG_CACHE_HOME/clerkenwell
or ~/.cache/clerkenwell, since a notebook's history belongs to the
document on the tablet rather than to the code.  A relative name is
relative to the REPL's working directory."
  :type '(choice (const :tag "Default" nil) directory))

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

(defcustom clerkenwell-strict-height t
  "Whether a result must fit above the bottom of the screen.
reMarkable pages can be scrolled to be taller than the screen.  When
nil, a result that does not fit in what is left of the screen goes
below it instead of being refused, as far as `clerkenwell-scroll-limit'
screens down, and images are no longer shrunk to fit the room left."
  :type 'boolean)

;; How results look and where they go on the page.  All of these are sent
;; with every submission, so a change takes effect with the next one.
;; Sizes are in page pixels: the page is 1404 wide and 1872 high.

(defcustom clerkenwell-font nil
  "Font results are written on the page in, and code within Markdown.
An installed font family, or the name of a font file.  It should be
monospace: values are pretty-printed to fit the page in columns, and
monospace output is easy to tell from your handwriting.  When nil, the
font of the default face is used, or, where Emacs has no font of its
own, as on a terminal, Java's Monospaced, which every JVM has."
  :type '(choice (const :tag "Emacs's own font" nil) string))

(defun clerkenwell--font ()
  "The font results are written in: see `clerkenwell-font'."
  (or clerkenwell-font
      (let ((font (face-attribute 'default :font)))
        (and (fontp font)
             (let ((family (font-get font :family)))
               (and family (format "%s" family)))))
      "Monospaced"))

(defcustom clerkenwell-prose-font "Serif"
  "Font Markdown and tables are typeset in on the page.
An installed font family, or the name of a font file.  The default,
Serif, is Java's own serif face, which every JVM has.  A family that is
not installed falls back to it silently, rather than being refused."
  :type 'string)

(defcustom clerkenwell-font-size 40
  "Size of text on the page, in page pixels.
A table too wide for the page is set smaller, down to seven tenths of
this, before its cells are wrapped."
  :type 'integer)

(defcustom clerkenwell-side-margin 60
  "Space kept free at the left and right edges of the page, in page pixels.
Results start at the left margin and are no wider than the page between
the margins."
  :type 'integer)

(defcustom clerkenwell-top-margin 60
  "Space kept free at the top of the page, in page pixels."
  :type 'integer)

(defcustom clerkenwell-bottom-margin 20
  "Space kept free at the bottom of the screen, in page pixels."
  :type 'integer)

(defcustom clerkenwell-gap 20
  "Space between a result and the handwriting or result above it.
In page pixels."
  :type 'integer)

(defcustom clerkenwell-scroll-limit 10
  "How many screens down a result may reach, when not strict about height.
See `clerkenwell-strict-height'.  This only keeps a single result from
becoming an absurdly tall image."
  :type 'integer)

(defcustom clerkenwell-print-level 10
  "How deeply nested a value is printed before elision, as `*print-level*'."
  :type 'integer)

(defcustom clerkenwell-print-length 100
  "How many items of a collection are printed, as `*print-length*'.
This is what lets an infinite sequence such as (range) be printed."
  :type 'integer)

(defcustom clerkenwell-max-chars 10000
  "The most characters of printed output kept for a result."
  :type 'integer)

(defcustom clerkenwell-print-timeout 2
  "Seconds a value may take to print, or an image to be prepared.
Past this the result is recorded as failed to present.  Evaluation
itself is not limited."
  :type 'number)

(defcustom clerkenwell-chart-timeout 20
  "Seconds a chart may take to draw.
Longer than `clerkenwell-print-timeout': drawing is slow, and the first
chart also waits for the JIT."
  :type 'number)

(defcustom clerkenwell-table-max-rows 30
  "The most rows of a table shown."
  :type 'integer)

(defcustom clerkenwell-table-max-columns 12
  "The most columns of a table shown."
  :type 'integer)

(defcustom clerkenwell-table-max-cell-chars 40
  "The most characters of a table cell shown."
  :type 'integer)

(defun clerkenwell--settings ()
  "How results are presented and placed, as a Clojure map literal."
  (format (concat "{:style {:font %s :prose-font %s :font-size %d}"
                  " :limits {:print-level %d :print-length %d :max-chars %d"
                  " :timeout-ms %d :chart-timeout-ms %d"
                  " :max-rows %d :max-columns %d :max-cell-chars %d}"
                  " :layout {:left-margin %d :top-margin %d :bottom-margin %d :gap %d"
                  " :strict-height? %s :scroll-limit %d}}")
          (clerkenwell--clj-string (clerkenwell--font))
          (clerkenwell--clj-string clerkenwell-prose-font)
          clerkenwell-font-size
          clerkenwell-print-level clerkenwell-print-length clerkenwell-max-chars
          (round (* 1000 clerkenwell-print-timeout))
          (round (* 1000 clerkenwell-chart-timeout))
          clerkenwell-table-max-rows clerkenwell-table-max-columns
          clerkenwell-table-max-cell-chars
          clerkenwell-side-margin clerkenwell-top-margin clerkenwell-bottom-margin
          clerkenwell-gap
          (if clerkenwell-strict-height "true" "false")
          clerkenwell-scroll-limit))

(defcustom clerkenwell-ink-colors 'theme
  "Colours handwriting images are drawn in.

These images are shown in Emacs and never sent to the device, so they
follow the editor's theme rather than the page's.  Either the symbol
`theme' to track the current Emacs faces, or a cons cell of
\(FOREGROUND . BACKGROUND) as hex colours (#rrggbb or #rgb) or black or white."
  :type '(choice (const :tag "Follow the Emacs theme" theme)
                 (cons :tag "Explicit" string string)))

(defun clerkenwell--ink-colors ()
  "The stroke and background colours to draw handwriting in."
  (if (consp clerkenwell-ink-colors)
      clerkenwell-ink-colors
    (let ((fg (face-attribute 'default :foreground nil t))
          (bg (face-attribute 'default :background nil t)))
      ;; A terminal frame can report `unspecified-*', which the renderer
      ;; would reject; fall back to the ink-on-paper default in that case.
      (cons (if (and (stringp fg) (string-prefix-p "#" fg)) fg "black")
            (if (and (stringp bg) (string-prefix-p "#" bg)) bg "white")))))

(defcustom clerkenwell-image-scale 0.8
  "Size of handwriting in the buffer, in screen pixels per page unit.
The page is 1404 units wide, so at 0.8 a page-wide scribble is about
1120 pixels across.  An image wider than the window is shrunk to fit it,
whatever this says.  Takes effect on the next refresh."
  :type 'number)

(defcustom clerkenwell-ink-stroke-width 3
  "Width of handwriting strokes in the buffer's images, in page units.
Run `clerkenwell-redraw' after changing it, so existing entries are
redrawn too."
  :type 'number)

(defcustom clerkenwell-ink-resolution 2
  "Image pixels per page unit that handwriting is drawn at.
Higher is sharper when `clerkenwell-image-scale' enlarges the images, or
on a high-density display, at the cost of larger files.  The size an
image is shown at does not depend on this.  Run `clerkenwell-redraw'
after changing it, so existing entries are redrawn too."
  :type 'number)

(defun clerkenwell--render-opts ()
  "The handwriting rendering options, as a Clojure map literal."
  (let ((colors (clerkenwell--ink-colors)))
    (format "{:stroke-color %s :background %s :stroke-width %s :resolution %s}"
            (clerkenwell--clj-string (car colors))
            (clerkenwell--clj-string (cdr colors))
            (number-to-string clerkenwell-ink-stroke-width)
            (number-to-string clerkenwell-ink-resolution))))

(defcustom clerkenwell-show-images t
  "Whether to render handwriting entries as images.
When nil, they appear as a one-line summary instead, which is useful
over a slow connection or in a terminal frame."
  :type 'boolean)

(defvar-local clerkenwell--history-end nil
  "Marker at the end of the protected history region.")

(defvar-local clerkenwell--notebook-id nil
  "The notebook this buffer is for; nil until the tablet has reported one.")

(defvar-local clerkenwell--notebook-name nil
  "What the tablet calls this buffer's notebook, when it has said.")

(defvar-local clerkenwell--namespace nil
  "Namespace this notebook's submissions are evaluated in, as last reported.")

(defvar-local clerkenwell--connection nil
  "The CIDER connection buffer this buffer submits through.")

(defvar clerkenwell--repl nil
  "The CIDER connection the tablet is connected through.")

(defvar clerkenwell--default-namespace nil
  "Namespace for a notebook that has none yet.
The one current where `clerkenwell-open' was run.")

(defvar clerkenwell--attached nil
  "The notebook the tablet had open at the last poll.")

(defvar-local clerkenwell--draft-revision 0
  "Incremented on every draft edit, to detect edits made during evaluation.")

(defvar-local clerkenwell--in-flight nil
  "Non-nil while an evaluation is outstanding.")

(defvar-local clerkenwell--status ""
  "Last status string, shown in the mode line.")

(defvar clerkenwell--poll-timer nil
  "Timer polling for new history entries and for the tablet changing notebook.")

(defvar-local clerkenwell--history-version nil
  "Last history version seen, so unchanged history is not refetched.")

(defvar-local clerkenwell--held 'unknown
  "Whether this REPL held the notebook's lease at the last poll.")

(defvar-local clerkenwell--entries nil
  "The history entries last rendered, so a resize can redraw without refetching.")

(defvar-local clerkenwell--rendered-width nil
  "The width images were last fitted to, in pixels.")

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

(defun clerkenwell--clj-id ()
  "This buffer's notebook id as a Clojure literal: nil when it has none."
  (if clerkenwell--notebook-id (clerkenwell--clj-string clerkenwell--notebook-id) "nil"))

(defun clerkenwell--connection-buffer ()
  "The CIDER connection to use, erroring if there is none."
  (or clerkenwell--connection
      clerkenwell--repl
      (cider-current-repl nil 'ensure)))

(defun clerkenwell--send-eval-request (code handler connection)
  "Send CODE for evaluation on CONNECTION, passing each response to HANDLER.
CIDER 2.0 renamed the function that does this; the older name is kept
for the CIDER versions before it."
  (if (fboundp 'cider-nrepl-send-eval-request)
      (cider-nrepl-send-eval-request code handler :connection connection)
    (with-no-warnings
      (cider-nrepl-request:eval code handler nil nil nil nil connection))))

(defun clerkenwell--eval-async (code callback)
  "Evaluate CODE in the linked REPL, calling CALLBACK with parsed EDN or nil.

An nREPL evaluation arrives as several messages: the value comes in one and
the final status in another, so the value must be accumulated rather than
read off the message carrying \='done\='.  CALLBACK runs exactly once."
  (let ((buf (current-buffer))
        (result nil)
        (problem nil)
        (fired nil))
    (clerkenwell--send-eval-request
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
     (clerkenwell--connection-buffer))))

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

(defun clerkenwell--available-width ()
  "Pixels an image may occupy: the narrowest window showing this buffer.
A little is kept back so an image never quite fills the line, which would
make Emacs wrap it."
  (let ((ws (get-buffer-window-list (current-buffer) nil t)))
    (- (if ws
           (apply #'min (mapcar (lambda (w) (window-body-width w t)) ws))
         (frame-text-width))
       (frame-char-width))))

(defun clerkenwell--image-width (page-width available)
  "Pixels to show an image PAGE-WIDTH page units wide at, within AVAILABLE."
  (max 1 (min (round (* page-width clerkenwell-image-scale)) available)))

(defun clerkenwell--file-image-problem (file)
  "Why image FILE cannot be shown, or nil if it can.
Also nil when images are turned off, which is a choice, not a problem."
  (cond
   ((not clerkenwell-show-images) nil)
   ((not (display-graphic-p)) "no graphics in this frame")
   ((not (display-images-p)) "images unsupported")
   ((null file) "no asset recorded")
   ((not (file-readable-p file))
    (format "unreadable: %s" file))))

(defun clerkenwell--image-problem (entry)
  "Why handwriting ENTRY cannot be shown as an image, or nil if it can."
  (clerkenwell--file-image-problem (clerkenwell--get entry :asset)))

(defun clerkenwell--insert-sized-image (file page-width)
  "Insert image FILE, PAGE-WIDTH page units wide, scaled for this buffer."
  (insert-image
   (create-image file nil nil
                 :width (clerkenwell--image-width
                         page-width (or clerkenwell--rendered-width
                                        (clerkenwell--available-width))))))

(defun clerkenwell--shows-image-p (entry)
  "Whether ENTRY is rendered as an image."
  (and entry
       clerkenwell-show-images
       (equal "handwriting" (clerkenwell--name (clerkenwell--get entry :kind)))
       (not (clerkenwell--image-problem entry))))

;; History text is styled with `font-lock-face', never `face': the buffer is
;; in clojure-mode with font-lock on, and font-lock strips `face' from any
;; text it refontifies, so a warning would quietly lose its colour.

(defun clerkenwell--render-entry (entry &optional next)
  "Insert one history ENTRY at point.
NEXT is the entry that follows it, if any: consecutive images are not
separated by a blank line, so handwriting reads as continuous, as on
the page."
  (let ((kind (clerkenwell--get entry :kind)))
    (pcase (clerkenwell--name kind)
      ("handwriting"
       (let* ((asset (clerkenwell--get entry :asset))
              (n (clerkenwell--get entry :strokes))
              ;; Say why an image is absent rather than quietly showing only
              ;; the header, which is indistinguishable from working.
              (problem (clerkenwell--image-problem entry)))
         ;; The header is commentary the page does not have, but a problem is
         ;; not: without it a missing image is indistinguishable from a blank
         ;; one.
         (when (or problem (not clerkenwell-quiet))
           (insert (propertize
                    (format ";; handwriting — %s stroke%s%s\n"
                            n (if (equal n 1) "" "s")
                            (if problem (format " [%s]" problem) ""))
                    'font-lock-face (if problem 'font-lock-warning-face
                            'font-lock-comment-face))))
         (if (or problem (not clerkenwell-show-images))
             (insert "\n")
           (let ((pw (clerkenwell--get entry :page-width)))
             (if pw
                 (clerkenwell--insert-sized-image asset pw)
               ;; A clerkenwell.session older than this buffer, which does not report
               ;; sizes: assume the image was drawn at page resolution.
               (insert-image (create-image asset nil nil
                                           :scale clerkenwell-image-scale))))
           (insert (if (clerkenwell--shows-image-p next) "\n" "\n\n")))))
      ("computation"
       (let ((src (clerkenwell--get entry :source))
             (delivery (clerkenwell--get entry :delivery)))
         ;; Text sent as it is has no source.
         (when src (insert src "\n"))
         (clerkenwell--render-presentation entry)
         ;; Delivery is mutable and shown separately from the immutable
         ;; source/result above it. Quiet mode omits it only when there is
         ;; nothing to say: anything still in flight, failed or uncertain is
         ;; always shown, since it must not look like a result on the page.
         ;; `skipped' needs no note: the presentation already said why.
         (let ((state (clerkenwell--name delivery)))
           (if (or (equal state "skipped")
                   (and clerkenwell-quiet (equal state "inserted")))
               (insert "\n\n")
             (insert (propertize
                      (format "   ; %s\n\n" state)
                      'font-lock-face (if (member state '("failed" "uncertain"))
                                'font-lock-warning-face
                              'font-lock-comment-face)))))))
      (other
       ;; Never silently swallow an entry: a shape we do not know about is a
       ;; bug, and should look like one.
       (insert (propertize (format ";; unrecognised history entry (kind %s)\n"
                                   (if (string-empty-p other) "missing" other))
                           'font-lock-face 'font-lock-warning-face))))))

(defun clerkenwell--fontify (text mode)
  "TEXT as MODE would colour it, or plain TEXT if MODE is not available.
The faces are carried as `font-lock-face', so the buffer's own font-lock
keeps them; nothing else of MODE's (hidden markup, say) comes along."
  (if (not (fboundp mode))
      text
    (with-temp-buffer
      (insert text)
      (delay-mode-hooks (funcall mode))
      (font-lock-ensure)
      (let ((out (buffer-substring-no-properties (point-min) (point-max)))
            (pos (point-min)))
        (while (< pos (point-max))
          (let ((next (next-single-property-change pos 'face nil (point-max)))
                (face (get-text-property pos 'face)))
            (when face
              (put-text-property (1- pos) (1- next) 'font-lock-face face out))
            (setq pos next)))
        out))))

(defun clerkenwell--render-presentation (entry)
  "Insert the result of computation ENTRY, drawn according to its kind.
No trailing newline; the delivery note follows on the same line.
A kind this buffer does not know is shown as its text, which every
presentation carries as a fallback."
  (let* ((pres (clerkenwell--get entry :presentation))
         ;; Sessions older than presentations report only :text.
         (text (or (clerkenwell--get pres :text) (clerkenwell--get entry :text))))
    (pcase (clerkenwell--name (clerkenwell--get pres :kind))
      ((guard (clerkenwell--get pres :verbatim?))
       ;; Sent as it is: plain text, as on the page.
       (insert (format "%s" text)))
      ("hidden"
       (insert (propertize ";; value hidden" 'font-lock-face 'font-lock-comment-face)))
      ("md"
       (insert (clerkenwell--fontify (format "%s" text) 'markdown-mode)))
      ("image"
       (let* ((file (clerkenwell--get entry :image))
              (problem (or (clerkenwell--file-image-problem file)
                           (and (not (clerkenwell--get entry :page-width))
                                "no size recorded"))))
         (if (and clerkenwell-show-images (not problem))
             (clerkenwell--insert-sized-image
              file (clerkenwell--get entry :page-width))
           ;; As text, saying why when it is not by choice.
           (insert (propertize (format "%s" text) 'font-lock-face 'font-lock-string-face))
           (when (and clerkenwell-show-images problem)
             (insert (propertize (format " [%s]" problem)
                                 'font-lock-face 'font-lock-warning-face))))))
      (_
       (insert (propertize (format "%s" text) 'font-lock-face 'font-lock-string-face))))))

(defun clerkenwell--render-entry-safely (entry next)
  "Render ENTRY, or a visible warning in its place if rendering fails.
One bad entry must not cost the rest of the history."
  (condition-case err
      (clerkenwell--render-entry entry next)
    (error
     (insert (propertize (format ";; could not render entry %s: %s\n\n"
                                 (clerkenwell--get entry :id)
                                 (error-message-string err))
                         'font-lock-face 'font-lock-warning-face)))))

(defun clerkenwell--rebuild-history (entries)
  "Replace the history region with ENTRIES, preserving the draft and point.
Asynchronous updates must not disturb what you are typing, so the draft
text, point and mark are restored around the rewrite."
  (setq clerkenwell--entries entries
        clerkenwell--rendered-width (clerkenwell--available-width))
  (let* ((inhibit-read-only t)
         (draft (or (clerkenwell-draft) ""))
         (point-in-draft (when (and clerkenwell--history-end
                                    (>= (point) (clerkenwell--draft-start)))
                           (- (point) (clerkenwell--draft-start))))
         ;; Reading back through the history: stay there. Rewriting keeps
         ;; earlier text where it was, so the position still means the same.
         (point-in-history (unless point-in-draft (point)))
         (starts (mapcar (lambda (w) (cons w (window-start w)))
                         (get-buffer-window-list (current-buffer) nil t))))
    (save-restriction
      (widen)
      (erase-buffer)
      ;; Erasing moves the history/draft marker to the start of the buffer.
      ;; If rendering then failed before the marker was reset, the next
      ;; rebuild would read the whole buffer back as "draft" and append it
      ;; below the separator. So the separator, the marker and the draft are
      ;; restored however the rendering ends.
      (unwind-protect
          (progn
            (insert (propertize ";; Clerkenwell\n\n" 'font-lock-face 'font-lock-comment-face))
            (let ((es entries))
              (while es
                (clerkenwell--render-entry-safely (car es) (cadr es))
                (setq es (cdr es)))))
        (goto-char (point-max))
        ;; The separator stays even when quiet: it is structure, not
        ;; commentary, and without it there is no way to see where the
        ;; editable region starts.
        (insert clerkenwell--separator)
        (clerkenwell--protect (point-min) (point))
        (setq clerkenwell--history-end (copy-marker (point) nil))
        (insert draft)))
    (goto-char (cond (point-in-draft
                      (min (point-max) (+ (clerkenwell--draft-start) point-in-draft)))
                     (point-in-history (min point-in-history (clerkenwell--draft-start)))
                     (t (point-max))))
    (dolist (ws starts)
      (set-window-start (car ws) (min (cdr ws) (point-max)) t))))

(defun clerkenwell--on-resize (_frame-or-window)
  "Refit images when the space available to them has changed."
  (let ((buf (current-buffer)))
    ;; Deferred: this runs during redisplay, which is no place to rewrite
    ;; the buffer; the timer also coalesces a burst of resize events.
    (run-at-time 0 nil
                 (lambda ()
                   (when (buffer-live-p buf)
                     (with-current-buffer buf
                       (when (and clerkenwell--entries
                                  (not (eql (clerkenwell--available-width)
                                            clerkenwell--rendered-width)))
                         (clerkenwell--rebuild-history clerkenwell--entries))))))))

;;; ------------------------------------------------------------------
;;; Commands

;;; ------------------------------------------------------------------
;;; One buffer per notebook

(defun clerkenwell--buffers ()
  "The live Clerkenwell buffers."
  (seq-filter (lambda (b) (buffer-local-value 'clerkenwell-mode b)) (buffer-list)))

(defun clerkenwell--buffer-for (notebook-id)
  "The buffer for NOTEBOOK-ID, if there is one."
  (seq-find (lambda (b) (equal notebook-id
                               (buffer-local-value 'clerkenwell--notebook-id b)))
            (clerkenwell--buffers)))

(defun clerkenwell--buffer-name (notebook-id name)
  "What to call the buffer for NOTEBOOK-ID, which the tablet calls NAME."
  (if notebook-id
      (format "*clerkenwell: %s*" (or name (substring notebook-id 0 8)))
    "*clerkenwell*"))

(defun clerkenwell--setup (buf)
  "Make BUF a Clerkenwell buffer, submitting through `clerkenwell--repl'."
  (with-current-buffer buf
    (unless (derived-mode-p 'clojure-mode) (clojure-mode))
    (setq clerkenwell--connection clerkenwell--repl)
    (clerkenwell-mode 1))
  buf)

(defun clerkenwell--notebook-buffer (notebook-id name)
  "The buffer for NOTEBOOK-ID, which the tablet calls NAME.
A buffer still waiting for its first notebook takes it; otherwise one is
made.  Named after the notebook, and renamed when the notebook is."
  (let ((buf (or (clerkenwell--buffer-for notebook-id)
                 (clerkenwell--buffer-for nil)
                 (clerkenwell--setup
                  (generate-new-buffer (clerkenwell--buffer-name notebook-id name))))))
    (with-current-buffer buf
      (unless (and (equal clerkenwell--notebook-id notebook-id)
                   (equal clerkenwell--notebook-name name))
        (setq clerkenwell--notebook-id notebook-id
              clerkenwell--notebook-name name
              clerkenwell--history-version nil)
        (rename-buffer (clerkenwell--buffer-name notebook-id name) t)))
    buf))

(defun clerkenwell--follow (buf)
  "Show BUF in the windows showing another notebook's buffer.
Called when the tablet opens another notebook, so the buffer in view is
the one whose page is in front of you."
  (dolist (b (clerkenwell--buffers))
    (unless (eq b buf)
      (dolist (w (get-buffer-window-list b nil t))
        (set-window-buffer w buf)))))

;;; ------------------------------------------------------------------
;;; Commands

;;;###autoload
(defun clerkenwell-open ()
  "Connect the current REPL to the tablet, and show the open notebook's buffer.

Each notebook has its own buffer, and remembers the namespace its
submissions are evaluated in.  A notebook that has none yet takes the
namespace current here; `clerkenwell-set-namespace' changes it."
  (interactive)
  (let ((conn (cider-current-repl nil 'ensure))
        (ns (cider-current-ns)))
    (setq clerkenwell--repl conn
          clerkenwell--default-namespace ns)
    (let ((started (clerkenwell--eval-sync
                    (format "(do (require 'clerkenwell.session) (clerkenwell.session/start! {:state-root %s :ssh-host %s :port %d :render-opts %s}))"
                            (if clerkenwell-state-root
                                (clerkenwell--clj-string clerkenwell-state-root)
                              "nil")
                            (clerkenwell--clj-string (clerkenwell--ssh-destination))
                            clerkenwell-port
                            (clerkenwell--render-opts)))))
      ;; A nil result means the eval itself failed -- most often because
      ;; clerkenwell.session is not on this REPL's classpath. Saying
      ;; "already running" there would hide a real problem.
      (unless started
        (user-error
         "Clerkenwell: could not connect. Is clerkenwell.session on the classpath?"))
      (let* ((v (clerkenwell--eval-sync "(clerkenwell.session/history-version)"))
             (id (and v (clerkenwell--get v :notebook-id)))
             (buf (if id
                      (clerkenwell--notebook-buffer id (clerkenwell--get v :name))
                    (or (clerkenwell--buffer-for nil)
                        (clerkenwell--setup (get-buffer-create "*clerkenwell*"))))))
        (setq clerkenwell--attached id)
        (dolist (b (clerkenwell--buffers))
          (with-current-buffer b (setq clerkenwell--connection conn)))
        (with-current-buffer buf
          (clerkenwell-refresh)
          (unless id
            (setq clerkenwell--status
                  (if (clerkenwell--get started :already-running?)
                      "reusing connection; waiting for the tablet"
                    "waiting for the tablet")))
          (clerkenwell--start-polling)
          (clerkenwell-check-device))
        (pop-to-buffer buf)))))

(defun clerkenwell--copy-description (copy)
  "How the tablet's copy of the session stands, from COPY, in words."
  (let ((pending (clerkenwell--get copy :pending)))
    (cond ((null copy) "copy unknown")
          ((not (clerkenwell--get copy :ok?))
           (format "copy on tablet FAILING (%s)" (clerkenwell--get copy :message)))
          ((and (numberp pending) (> pending 0))
           (format "copy on tablet %d file%s behind" pending (if (= pending 1) "" "s")))
          (t "copy on tablet ok"))))

(defun clerkenwell-check-device ()
  "Report on both paths to the device, which fail independently.

The tunnel carries the adapter's own outbound requests and needs no SSH
from the REPL's JVM, so a page can be reported while every SSH from that
JVM is failing -- which is how handwriting goes missing while the status
line looks healthy."
  (interactive)
  (let* ((r (clerkenwell--eval-sync "(clerkenwell.session/check-device)"))
         (tunnel (and r (clerkenwell--get r :tunnel)))
         (ssh (and r (clerkenwell--get r :ssh)))
         (copy (and r (clerkenwell--get r :copy))))
    (if (null r)
        (message "Clerkenwell: not connected")
      (let ((tunnel-ok (clerkenwell--get tunnel :ok?))
            (ssh-ok (clerkenwell--get ssh :ok?)))
        (message
         "Clerkenwell: tunnel %s, ssh(%s) %s, %s%s"
         (if tunnel-ok "ok" "DOWN")
         (clerkenwell--get ssh :host)
         (if ssh-ok "ok" "FAILING")
         (clerkenwell--copy-description copy)
         (cond
          ((and tunnel-ok (not ssh-ok))
           (format " — results will insert but handwriting will not arrive: %s"
                   (clerkenwell--get ssh :message)))
          ((not tunnel-ok) (format " — %s" (clerkenwell--get tunnel :message)))
          (t "")))))))

(defun clerkenwell-refresh ()
  "Fetch history and device state without evaluating anything."
  (interactive)
  (let* ((h (and clerkenwell--notebook-id
                 (clerkenwell--eval-sync
                  (format "(clerkenwell.session/history %s)"
                          (clerkenwell--clj-string clerkenwell--notebook-id)))))
         (entries (and h (clerkenwell--get h :entries))))
    (clerkenwell--rebuild-history (append entries nil))
    (clerkenwell--update-status)))

(defun clerkenwell--update-status ()
  "Refresh the mode-line status from the session."
  (let ((s (clerkenwell--eval-sync "(clerkenwell.session/status)")))
    (when (and s (equal clerkenwell--notebook-id (clerkenwell--get s :notebook-id)))
      (clerkenwell--note-namespace (clerkenwell--get s :namespace)))
    (setq clerkenwell--status
          (cond
           ((not (and s (clerkenwell--get s :running?))) "not running")
           ((null clerkenwell--notebook-id) "waiting for the tablet")
           ((not (equal clerkenwell--notebook-id (clerkenwell--get s :notebook-id)))
            "not open on the tablet")
           (t
            (let* ((active (clerkenwell--get s :active))
                   (page (and active (clerkenwell--name
                                      (clerkenwell--get active :page))))
                   (uncertain (clerkenwell--get s :uncertain))
                   (copy (clerkenwell--get s :copy))
                   (lease (clerkenwell--get s :lease)))
              (concat (when (and lease (not (clerkenwell--get lease :held?)))
                        "read-only ")
                      (when clerkenwell--namespace
                        (format "%s, " clerkenwell--namespace))
                      (if (and page (not (string-empty-p page)))
                          (format "page %s" (substring page 0 (min 8 (length page))))
                        "no page")
                      (when (and uncertain (> (length uncertain) 0))
                        (format " ⚠%d uncertain" (length uncertain)))
                      ;; History is kept on the tablet too; say when it
                      ;; is not reaching it.
                      (when (and copy (not (clerkenwell--get copy :ok?)))
                        " ⚠not copied to tablet"))))))
    (force-mode-line-update)))

(defun clerkenwell--note-namespace (ns)
  "Note that this notebook is evaluated in NS, when it says.
Ordinary CIDER evaluation in the buffer uses it too."
  (when ns
    (setq clerkenwell--namespace ns
          cider-buffer-ns ns)))

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
   ((null clerkenwell--notebook-id)
    (message "Clerkenwell: no notebook open on the tablet yet"))
   (t
    (let* ((source (clerkenwell-draft))
           (revision clerkenwell--draft-revision)
           (with-source (if invert (not clerkenwell-send-source)
                          clerkenwell-send-source)))
      (setq clerkenwell--in-flight t
            clerkenwell--status "evaluating…")
      (force-mode-line-update)
      ;; Nothing clears the flag but the callback, so a request that
      ;; cannot even be sent must clear it here.
      (condition-case err
          (clerkenwell--eval-async
           (format "(clerkenwell.session/submit %s %s {:notebook-id %s :include-source? %s :settings %s})"
                   (clerkenwell--clj-string source)
                   ;; Only for a notebook that has no namespace yet.
                   (clerkenwell--clj-string (or clerkenwell--namespace
                                                clerkenwell--default-namespace
                                                "user"))
                   (clerkenwell--clj-string clerkenwell--notebook-id)
                   (if with-source "true" "false")
                   (clerkenwell--settings))
           (lambda (result err)
             (setq clerkenwell--in-flight nil)
             (cond
              (err (setq clerkenwell--status "error")
                   (message "Clerkenwell: %s" err))
              ((null result)
               (setq clerkenwell--status "error")
               (message "Clerkenwell: no result (is clerkenwell.session on the classpath?)"))
              ((equal "lease" (clerkenwell--name (clerkenwell--get result :stage)))
               ;; Nothing was evaluated, so submitting again is safe.
               (setq clerkenwell--status "read-only")
               (message "Clerkenwell: %s" (clerkenwell--get result :message)))
              ((clerkenwell--get result :ok?)
               ;; Only clear the draft if it is still the text we submitted.
               (when (= revision clerkenwell--draft-revision)
                 (let ((inhibit-read-only t))
                   (delete-region (clerkenwell--draft-start) (point-max))))
               (message "Clerkenwell: %s" (or (clerkenwell--get result :text)
                                              "value hidden"))
               (clerkenwell-refresh))
              (t
               (setq clerkenwell--status "rejected")
               ;; A failure leaves the draft alone so it can be corrected.
               (message "Clerkenwell [%s]: %s"
                        (clerkenwell--get result :stage)
                        (or (clerkenwell--get result :message)
                            (clerkenwell--get result :reason)))))))
        (error
         (setq clerkenwell--in-flight nil
               clerkenwell--status "error")
         (force-mode-line-update)
         (message "Clerkenwell: %s" (error-message-string err))))))))

(defun clerkenwell--dedent (text &optional column)
  "TEXT with the indentation its lines share removed, and tabs expanded.
COLUMN is where TEXT's first line starts, as when it is cut from the
middle of a line: the lines after it are indented relative to that."
  (let ((width tab-width))
    (with-temp-buffer
      (setq tab-width width)
      (insert (make-string (or column 0) ?\s) text)
      (untabify (point-min) (point-max))
      (let ((indent nil))
        (goto-char (point-min))
        (while (not (eobp))
          (unless (looking-at-p " *$")
            (setq indent (min (or indent most-positive-fixnum)
                              (current-indentation))))
          (forward-line 1))
        (goto-char (point-min))
        (while (not (eobp))
          (delete-region (point) (progn (skip-chars-forward " " (+ (point) (or indent 0)))
                                        (point)))
          (forward-line 1)))
      (buffer-string))))

;;;###autoload
(defun clerkenwell-send-text (text)
  "Put TEXT on the tablet's page as it is, evaluating nothing.
Interactively, the region if it is active, or else text read from the
minibuffer.  Indentation the lines share is removed, counting the
region's first line from the column it starts in, so a form selected
from within a line keeps its own indentation.  Works from any buffer
while the tablet is connected; TEXT goes to whichever notebook the
tablet has open, and is recorded in its history like a result."
  (interactive
   (list (clerkenwell--dedent
          (if (use-region-p)
              (buffer-substring-no-properties (region-beginning) (region-end))
            (read-string "Send to the tablet: "))
          (when (use-region-p)
            (save-excursion (goto-char (region-beginning)) (current-column))))))
  (unless clerkenwell--repl
    (user-error "Clerkenwell: not connected; run `clerkenwell-open' first"))
  (when (use-region-p) (deactivate-mark))
  (if (string-blank-p text)
      (message "Clerkenwell: nothing to send")
    (clerkenwell--eval-async
     (format "(clerkenwell.session/send-text %s {:settings %s})"
             (clerkenwell--clj-string text)
             (clerkenwell--settings))
     (lambda (result err)
       (cond
        (err (message "Clerkenwell: %s" err))
        ((null result)
         (message "Clerkenwell: no result (is clerkenwell.session on the classpath?)"))
        ((clerkenwell--get result :ok?)
         (message "Clerkenwell: sent")
         (when-let ((buf (clerkenwell--buffer-for
                          (clerkenwell--get result :notebook-id))))
           (with-current-buffer buf (clerkenwell-refresh))))
        (t
         (message "Clerkenwell [%s]: %s"
                  (clerkenwell--name (clerkenwell--get result :stage))
                  (or (clerkenwell--get result :message)
                      (clerkenwell--get result :reason)))))))))

(defun clerkenwell-set-namespace (ns)
  "Evaluate this notebook's submissions in NS from now on."
  (interactive (list (cider-read-from-minibuffer
                      "Evaluate this notebook in namespace: "
                      (or clerkenwell--namespace (cider-current-ns)))))
  (let ((r (clerkenwell--eval-sync
            (format "(clerkenwell.session/set-namespace! %s %s)"
                    (clerkenwell--clj-string ns)
                    (clerkenwell--clj-id)))))
    (if (and r (clerkenwell--get r :ok?))
        (progn (clerkenwell--note-namespace ns)
               (clerkenwell--update-status)
               (message "Clerkenwell: this notebook is evaluated in %s" ns))
      (message "Clerkenwell: %s"
               (or (and r (clerkenwell--get r :message)) "could not set the namespace")))))

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

(defun clerkenwell-regroup ()
  "Merge handwriting entries that overlap vertically into one.
New ink that overlaps an entry already extends it; this applies the same
rule to history recorded before it did.  Nothing is rewritten, and running
it again does nothing."
  (interactive)
  (let ((r (clerkenwell--eval-sync
            (format "(clerkenwell.session/regroup %s)"
                    (clerkenwell--clj-id)))))
    (if (and r (clerkenwell--get r :ok?))
        (let ((n (clerkenwell--get r :merged 0)))
          (setq clerkenwell--history-version nil)
          (clerkenwell-refresh)
          (message "Clerkenwell: %s" (if (zerop n)
                                         "nothing to regroup"
                                       (format "%d entr%s merged into others"
                                               n (if (= n 1) "y" "ies")))))
      (message "Clerkenwell: %s"
               (or (and r (clerkenwell--get r :message)) "could not regroup")))))

(defun clerkenwell-redraw ()
  "Redraw handwriting images with the current colours and resolution.
Colours follow `clerkenwell-ink-colors', resolution follows
`clerkenwell-ink-resolution'.  Existing entries are redrawn from their
retained page snapshots, so the history does not end up half in one
scheme and half in another.  Run this after changing theme or resolution."
  (interactive)
  ;; Asynchronous: every entry is redrawn, which at a high resolution easily
  ;; outlasts a synchronous request's timeout.
  (let ((colors (clerkenwell--ink-colors))
        (resolution clerkenwell-ink-resolution))
    (message "Clerkenwell: redrawing handwriting...")
    (clerkenwell--eval-async
     (format "(clerkenwell.session/set-render-opts! %s)" (clerkenwell--render-opts))
     (lambda (r problem)
       (if (and r (clerkenwell--get r :ok?))
           (progn
             (clear-image-cache)
             (setq clerkenwell--history-version nil)
             (clerkenwell-refresh)
             (message "Clerkenwell: %s on %s at %sx, %s entries redrawn"
                      (car colors) (cdr colors) resolution
                      (clerkenwell--get r :rerendered 0)))
         (message "Clerkenwell: %s"
                  (or problem
                      (and r (clerkenwell--get r :message))
                      "could not redraw")))))))

(define-obsolete-function-alias 'clerkenwell-sync-colors #'clerkenwell-redraw "0.2")

(defun clerkenwell-reset-history ()
  "Archive this notebook's history so the buffer starts fresh.

The history is moved aside, not deleted.  Note that this clears only what
the host remembers: ink already on the page is observed again as a fresh
initial entry, which is also what keeps results placed below it.  For a
genuinely blank slate, start on a blank page."
  (interactive)
  (when (yes-or-no-p "Archive this notebook's Clerkenwell history? ")
    (let ((r (clerkenwell--eval-sync
              (format "(clerkenwell.session/archive-notebook! %s)"
                      (clerkenwell--clj-id)))))
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
  (setq clerkenwell--attached nil)
  (dolist (b (clerkenwell--buffers))
    (with-current-buffer b
      (setq clerkenwell--status "stopped")
      (force-mode-line-update)))
  (message "Clerkenwell: stopped"))

;;; ------------------------------------------------------------------
;;; Mode

(defun clerkenwell--poll ()
  "Follow the tablet, and refresh the open notebook's buffer if its history
has grown.  A notebook the tablet opens gets a buffer, shown where another
notebook's was.  Skipped while an evaluation is outstanding, so a background
refresh cannot interleave with a submission."
  (let ((bufs (clerkenwell--buffers)))
    (if (null bufs)
        (clerkenwell--stop-polling)
      (unless (seq-some (lambda (b) (buffer-local-value 'clerkenwell--in-flight b)) bufs)
        (condition-case nil
            (let* ((v (clerkenwell--eval-sync "(clerkenwell.session/history-version)"))
                   (id (and v (clerkenwell--get v :notebook-id))))
              (when (and v (clerkenwell--get v :ok?))
                (let ((was clerkenwell--attached))
                  (setq clerkenwell--attached id)
                  (when id
                    (let ((buf (clerkenwell--notebook-buffer id (clerkenwell--get v :name))))
                      (unless (equal id was) (clerkenwell--follow buf))
                      (with-current-buffer buf
                        (clerkenwell--poll-buffer v))))
                  ;; The others' history cannot grow, but the mode line says
                  ;; that their notebook is no longer the one on the tablet.
                  (unless (equal id was)
                    (dolist (b (clerkenwell--buffers))
                      (unless (equal id (buffer-local-value 'clerkenwell--notebook-id b))
                        (with-current-buffer b (clerkenwell--update-status))))))))
          ;; A dead REPL or a stopped session must not spam errors from a timer.
          (error nil))))))

(defun clerkenwell--poll-buffer (v)
  "Bring this buffer up to date with V, the open notebook's history version."
  (let ((version (clerkenwell--get v :version))
        (held (and (clerkenwell--get v :held?) t)))
    (cond
     ((not (equal version clerkenwell--history-version))
      (setq clerkenwell--history-version version
            clerkenwell--held held)
      (clerkenwell-refresh))
     ;; Taking or losing the notebook changes no history, but the mode line
     ;; says which it is.
     ((not (eq held clerkenwell--held))
      (setq clerkenwell--held held)
      (clerkenwell--update-status)))))

(defun clerkenwell--start-polling ()
  (clerkenwell--stop-polling)
  (setq clerkenwell--poll-timer
        (run-with-timer clerkenwell-poll-interval clerkenwell-poll-interval
                        #'clerkenwell--poll)))

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
    (define-key m (kbd "C-c C-n") #'clerkenwell-set-namespace)
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
        (add-hook 'window-size-change-functions #'clerkenwell--on-resize nil t)
        (unless clerkenwell--history-end
          (setq clerkenwell--history-end (copy-marker (point-max) nil))))
    (remove-hook 'after-change-functions #'clerkenwell--after-change t)
    (remove-hook 'window-size-change-functions #'clerkenwell--on-resize t)))

(provide 'clerkenwell)
;;; clerkenwell.el ends here
