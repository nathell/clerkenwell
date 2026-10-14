;;; clerkenwell-test.el --- Tests for clerkenwell.el  -*- lexical-binding: t; -*-

;;; Commentary:
;; Covers the buffer mechanics, which is where the risk is: the history must
;; stay protected, and asynchronous updates must never disturb the draft.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'clerkenwell)

(defun clerkenwell-test--parse (edn)
  "Parse EDN exactly as a response from the REPL is parsed.

Fixtures go through `parseedn' rather than being hand-built: an earlier
hand-built fixture used plain symbols where the real parser yields Emacs
keywords, so the rendering tests passed while nothing rendered at all."
  (parseedn-read-str edn))

(defun clerkenwell-test--entries (edn)
  "Parse EDN as a history response and return its entries as a list."
  (append (gethash :entries (clerkenwell-test--parse edn)) nil))

(defmacro clerkenwell-test--with-buffer (&rest body)
  "Run BODY in a fresh Clerkenwell buffer."
  `(with-temp-buffer
     (clojure-mode)
     (clerkenwell-mode 1)
     (setq clerkenwell--namespace "user")
     ,@body))

(defconst clerkenwell-test--history-edn
  (concat "{:ok? true :entries ["
          "{:kind :handwriting :sequence 1 :id \"e1\" :page-id \"p1\""
          " :strokes 12 :asset \"/nonexistent.png\"}"
          "{:kind :computation :sequence 2 :id \"r1\" :source \"(+ 1 2)\""
          " :text \"=> 3\" :namespace \"user\" :delivery :inserted}]}")
  "A history response in the shape `clerkenwell.session/history' returns.")

(defconst clerkenwell-test--entries
  (clerkenwell-test--entries clerkenwell-test--history-edn))

(ert-deftest clerkenwell-test-renders-real-parsed-entries ()
  "Both entry kinds must render as themselves, not as a fallback dump."
  (clerkenwell-test--with-buffer
   (clerkenwell--rebuild-history clerkenwell-test--entries)
   (let ((text (buffer-substring-no-properties (point-min) (point-max))))
     (should (string-match-p "handwriting" text))
     (should (string-match-p (regexp-quote "12 strokes") text))
     (should (string-match-p (regexp-quote "(+ 1 2)") text))
     (should (string-match-p (regexp-quote "=> 3") text))
     (should-not (string-match-p "unrecognised" text))
     (should-not (string-match-p "hash-table" text)))))

(ert-deftest clerkenwell-test-keyword-names-lose-their-colon ()
  "EDN keywords arrive as Emacs keywords, whose name keeps the colon."
  (should (equal (clerkenwell--name :handwriting) "handwriting"))
  (should (equal (clerkenwell--name 'handwriting) "handwriting"))
  (should (equal (clerkenwell--name "handwriting") "handwriting"))
  (should (equal (clerkenwell--name nil) "")))

(ert-deftest clerkenwell-test-delivery-state-is-readable ()
  "Delivery is shown without its keyword colon."
  (clerkenwell-test--with-buffer
   (let ((clerkenwell-quiet nil))
     (clerkenwell--rebuild-history clerkenwell-test--entries)
     (let ((text (buffer-substring-no-properties (point-min) (point-max))))
       (should (string-match-p "; inserted" text))
       (should-not (string-match-p "; :inserted" text))))))

(ert-deftest clerkenwell-test-unknown-entry-is-visible ()
  "An unrecognised shape must look like the bug it is."
  (clerkenwell-test--with-buffer
   (clerkenwell--rebuild-history
    (clerkenwell-test--entries "{:entries [{:kind :sideways}]}"))
   (should (string-match-p
            "unrecognised"
            (buffer-substring-no-properties (point-min) (point-max))))))

(ert-deftest clerkenwell-test-missing-image-is-explained ()
  "An absent image says why, instead of looking like a bare header."
  (clerkenwell-test--with-buffer
   (let ((clerkenwell-show-images t))
     (clerkenwell--rebuild-history
      (clerkenwell-test--entries
       "{:entries [{:kind :handwriting :strokes 9 :asset \"/no/such/file.png\"}]}"))
     (let ((text (buffer-substring-no-properties (point-min) (point-max))))
       (should (string-match-p "9 strokes" text))
       ;; In batch there is no graphic display, so the reason differs; what
       ;; matters is that some reason is given.
       (should (string-match-p "\\[" text))))))

(defun clerkenwell-test--buffer-text ()
  (buffer-substring-no-properties (point-min) (point-max)))

(ert-deftest clerkenwell-test-quiet-omits-commentary ()
  "Quiet mode drops per-entry headers the page does not have."
  (clerkenwell-test--with-buffer
   (let ((clerkenwell-quiet t) (clerkenwell-show-images nil))
     (clerkenwell--rebuild-history clerkenwell-test--entries)
     (let ((text (clerkenwell-test--buffer-text)))
       (should-not (string-match-p "handwriting —" text))
       (should-not (string-match-p "; inserted" text))
       ;; The content itself is still there.
       (should (string-match-p (regexp-quote "(+ 1 2)") text))
       (should (string-match-p (regexp-quote "=> 3") text))
       ;; And the draft separator survives: it is structure, not commentary.
       (should (string-match-p "draft" text))))))

(ert-deftest clerkenwell-test-loud-keeps-commentary ()
  (clerkenwell-test--with-buffer
   (let ((clerkenwell-quiet nil) (clerkenwell-show-images nil))
     (clerkenwell--rebuild-history clerkenwell-test--entries)
     (let ((text (clerkenwell-test--buffer-text)))
       (should (string-match-p "handwriting —" text))
       (should (string-match-p "; inserted" text))))))

(ert-deftest clerkenwell-test-quiet-still-shows-undelivered ()
  "A result not on the page must never look like one that is."
  (dolist (state '("queued" "failed" "uncertain" "transferred"))
    (clerkenwell-test--with-buffer
     (let ((clerkenwell-quiet t) (clerkenwell-show-images nil))
       (clerkenwell--rebuild-history
        (clerkenwell-test--entries
         (format "{:entries [{:kind :computation :source \"(+ 1 2)\" :text \"=> 3\" :delivery :%s}]}"
                 state)))
       (should (string-match-p (format "; %s" state)
                               (clerkenwell-test--buffer-text)))))))

(ert-deftest clerkenwell-test-quiet-still-shows-render-problems ()
  "A missing image is a problem, not commentary."
  (clerkenwell-test--with-buffer
   (let ((clerkenwell-quiet t) (clerkenwell-show-images t))
     (clerkenwell--rebuild-history
      (clerkenwell-test--entries
       "{:entries [{:kind :handwriting :strokes 9 :asset \"/no/such.png\"}]}"))
     (should (string-match-p "9 strokes" (clerkenwell-test--buffer-text))))))

(ert-deftest clerkenwell-test-clj-string-escapes ()
  "Draft text is escaped, never interpolated."
  (should (equal (clerkenwell--clj-string "abc") "\"abc\""))
  (should (equal (clerkenwell--clj-string "say \"hi\"") "\"say \\\"hi\\\"\""))
  (should (equal (clerkenwell--clj-string "a\\b") "\"a\\\\b\""))
  (should (equal (clerkenwell--clj-string "one\ntwo") "\"one\\ntwo\""))
  (should (equal (clerkenwell--clj-string "a\tb") "\"a\\tb\"")))

(ert-deftest clerkenwell-test-clj-string-survives-a-closing-quote ()
  "A draft ending in a quote must not terminate the literal early."
  (let ((s (clerkenwell--clj-string "(str \"x\")")))
    ;; Exactly two unescaped quotes: the delimiters.
    (should (equal s "\"(str \\\"x\\\")\""))))

(ert-deftest clerkenwell-test-history-is-read-only ()
  (clerkenwell-test--with-buffer
   (clerkenwell--rebuild-history clerkenwell-test--entries)
   (goto-char (point-min))
   (should-error (insert "nope") :type 'text-read-only)
   (goto-char (1- (clerkenwell--draft-start)))
   (should-error (insert "nope") :type 'text-read-only)))

(ert-deftest clerkenwell-test-draft-is-editable ()
  (clerkenwell-test--with-buffer
   (clerkenwell--rebuild-history clerkenwell-test--entries)
   (goto-char (point-max))
   (insert "(+ 1 2)")
   (should (equal (clerkenwell-draft) "(+ 1 2)"))
   ;; And editable right at the boundary, not just at the end.
   (goto-char (clerkenwell--draft-start))
   (insert "x")
   (should (equal (clerkenwell-draft) "x(+ 1 2)"))))

(ert-deftest clerkenwell-test-rebuild-preserves-draft ()
  "An async refresh must not eat what you are typing."
  (clerkenwell-test--with-buffer
   (clerkenwell--rebuild-history clerkenwell-test--entries)
   (goto-char (point-max))
   (insert "(in-progress")
   (clerkenwell--rebuild-history
    (append clerkenwell-test--entries
            (clerkenwell-test--entries
             "{:entries [{:kind :handwriting :strokes 3 :asset \"/none.png\"}]}")))
   (should (equal (clerkenwell-draft) "(in-progress"))))

(ert-deftest clerkenwell-test-rebuild-preserves-point-in-draft ()
  (clerkenwell-test--with-buffer
   (clerkenwell--rebuild-history clerkenwell-test--entries)
   (goto-char (point-max))
   (insert "(+ 1 2)")
   (goto-char (- (point-max) 3))
   (let ((offset (- (point) (clerkenwell--draft-start))))
     (clerkenwell--rebuild-history clerkenwell-test--entries)
     (should (equal (- (point) (clerkenwell--draft-start)) offset)))))

(ert-deftest clerkenwell-test-draft-revision-tracks-edits ()
  "The token is what stops a completed submission clearing newer text."
  (clerkenwell-test--with-buffer
   (clerkenwell--rebuild-history clerkenwell-test--entries)
   (goto-char (point-max))
   (let ((before clerkenwell--draft-revision))
     (insert "x")
     (should (> clerkenwell--draft-revision before)))))

(ert-deftest clerkenwell-test-history-grows-without-touching-draft ()
  "Repeated refreshes are idempotent from the draft's point of view."
  (clerkenwell-test--with-buffer
   (clerkenwell--rebuild-history clerkenwell-test--entries)
   (goto-char (point-max))
   (insert "(draft)")
   (dotimes (_ 5) (clerkenwell--rebuild-history clerkenwell-test--entries))
   (should (equal (clerkenwell-draft) "(draft)"))))

(ert-deftest clerkenwell-test-empty-history ()
  (clerkenwell-test--with-buffer
   (clerkenwell--rebuild-history nil)
   (should (equal (clerkenwell-draft) ""))
   (goto-char (point-max))
   (insert "(+ 1 2)")
   (should (equal (clerkenwell-draft) "(+ 1 2)"))))

(ert-deftest clerkenwell-test-ssh-destination ()
  "The user is composed into the destination, or left to SSH config."
  (let ((clerkenwell-ssh-host "rm"))
    (let ((clerkenwell-ssh-user nil))
      (should (equal (clerkenwell--ssh-destination) "rm")))
    (let ((clerkenwell-ssh-user ""))
      (should (equal (clerkenwell--ssh-destination) "rm")))
    (let ((clerkenwell-ssh-user "root"))
      (should (equal (clerkenwell--ssh-destination) "root@rm")))))

(ert-deftest clerkenwell-test-image-width-follows-scale ()
  "Images are sized in page units, whatever resolution they were drawn at."
  (let ((clerkenwell-image-scale 0.5))
    (should (= 300 (clerkenwell--image-width 600 10000))))
  (let ((clerkenwell-image-scale 1.0))
    (should (= 600 (clerkenwell--image-width 600 10000)))))

(ert-deftest clerkenwell-test-image-width-never-exceeds-window ()
  "An image wider than the window is shrunk to fit it."
  (let ((clerkenwell-image-scale 1.0))
    (should (= 800 (clerkenwell--image-width 1404 800)))
    ;; Never a degenerate width.
    (should (= 1 (clerkenwell--image-width 1404 0)))))

(ert-deftest clerkenwell-test-rebuild-preserves-point-in-history ()
  "Reading back through the history must survive a refresh or a resize."
  (clerkenwell-test--with-buffer
   (clerkenwell--rebuild-history clerkenwell-test--entries)
   (goto-char (point-min))
   (search-forward "(+ 1 2)")
   (let ((pos (point)))
     (clerkenwell--rebuild-history clerkenwell-test--entries)
     (should (= pos (point))))))

(defmacro clerkenwell-test--with-images (&rest body)
  "Run BODY as if in a graphical frame, with each image inserted as \"[img]\"."
  `(cl-letf (((symbol-function 'display-graphic-p) (lambda (&rest _) t))
             ((symbol-function 'display-images-p) (lambda (&rest _) t))
             ((symbol-function 'insert-image) (lambda (&rest _) (insert "[img]"))))
     ,@body))

(defun clerkenwell-test--history (&rest kinds)
  "A history of entries of KINDS, with handwriting pointing at a real file."
  (let ((asset (make-temp-file "ckw-img" nil ".png")))
    (clerkenwell-test--entries
     (concat "{:ok? true :entries ["
             (mapconcat
              (lambda (k)
                (if (eq k 'ink)
                    (format "{:kind :handwriting :id \"e\" :strokes 1 :asset %S :page-width 100}" asset)
                  "{:kind :computation :id \"r\" :source \"(+ 1 2)\" :text \"=> 3\" :delivery :inserted}"))
              kinds " ")
             "]}"))))

(ert-deftest clerkenwell-test-consecutive-images-are-not-separated ()
  "Handwriting split across entries reads as continuous, as on the page."
  (clerkenwell-test--with-images
   (clerkenwell-test--with-buffer
    (let ((clerkenwell-quiet t))
      (clerkenwell--rebuild-history (clerkenwell-test--history 'ink 'ink 'ink))
      (should (string-match-p "\\[img\\]\n\\[img\\]\n\\[img\\]\n\n" (buffer-string)))))))

(ert-deftest clerkenwell-test-image-before-computation-keeps-its-blank ()
  "Only image-to-image separation goes; a computation still stands apart."
  (clerkenwell-test--with-images
   (clerkenwell-test--with-buffer
    (let ((clerkenwell-quiet t))
      (clerkenwell--rebuild-history (clerkenwell-test--history 'ink 'code 'ink))
      (should (string-match-p "\\[img\\]\n\n(\\+ 1 2)" (buffer-string)))))))

(ert-deftest clerkenwell-test-failing-entry-is-shown-not-fatal ()
  "An entry that cannot be rendered becomes a warning; the rest still render."
  (clerkenwell-test--with-images
   (clerkenwell-test--with-buffer
    (cl-letf (((symbol-function 'clerkenwell--image-width)
               (lambda (&rest _) (error "Boom"))))
      (clerkenwell--rebuild-history (clerkenwell-test--history 'ink 'code))
      (should (string-match-p "could not render entry e: Boom" (buffer-string)))
      (should (string-match-p "(\\+ 1 2)" (buffer-string)))))))

(ert-deftest clerkenwell-test-failed-rebuild-does-not-duplicate-history ()
  "A rebuild that fails part-way must not leave the history readable as draft.
Otherwise the next rebuild appends the whole previous buffer below the
separator, header and all."
  (clerkenwell-test--with-buffer
   (clerkenwell--rebuild-history clerkenwell-test--entries)
   (goto-char (point-max))
   (insert "(my-draft)")
   ;; Fail after the erase, outside any per-entry guard.
   (cl-letf (((symbol-function 'propertize)
              (lambda (&rest _) (error "Mid-rebuild failure"))))
     (ignore-errors (clerkenwell--rebuild-history clerkenwell-test--entries)))
   (should (equal "(my-draft)" (clerkenwell-draft)))
   (clerkenwell--rebuild-history clerkenwell-test--entries)
   (should (equal "(my-draft)" (clerkenwell-draft)))
   (should (= 1 (how-many ";; Clerkenwell" (point-min) (point-max))))))

(ert-deftest clerkenwell-test-hidden-value ()
  "A hidden value says so, and needs no delivery note to explain itself."
  (clerkenwell-test--with-buffer
   (let ((clerkenwell-quiet nil) (clerkenwell-show-images nil))
     (clerkenwell--rebuild-history
      (clerkenwell-test--entries
       (concat "{:entries [{:kind :computation :source \"(load)\" :text nil"
               " :presentation {:kind :hidden :text nil} :delivery :skipped}]}")))
     (let ((text (clerkenwell-test--buffer-text)))
       (should (string-match-p "(load)\n;; value hidden\n" text))
       (should-not (string-match-p "skipped" text))))))

(ert-deftest clerkenwell-test-presentation-text-is-shown ()
  "Text comes from the presentation, falling back to :text for old sessions."
  (dolist (entry '("{:kind :computation :source \"x\" :presentation {:kind :text :text \"=> 1\"} :delivery :inserted}"
                   "{:kind :computation :source \"x\" :text \"=> 1\" :delivery :inserted}"
                   "{:kind :computation :source \"x\" :text \"=> 1\" :presentation {:kind :chart} :delivery :inserted}"))
    (clerkenwell-test--with-buffer
     (let ((clerkenwell-quiet t))
       (clerkenwell--rebuild-history
        (clerkenwell-test--entries (format "{:entries [%s]}" entry)))
       (should (string-match-p "^x\n=> 1$" (clerkenwell-test--buffer-text)))))))

(ert-deftest clerkenwell-test-image-result ()
  "An image result is shown as an image, or as its text saying why not."
  (let ((file (make-temp-file "ckw-img" nil ".png")))
    (clerkenwell-test--with-images
     (clerkenwell-test--with-buffer
      (let ((clerkenwell-quiet t) (clerkenwell-show-images t))
        (clerkenwell--rebuild-history
         (clerkenwell-test--entries
          (format "{:entries [{:kind :computation :source \"(draw)\" :text \"=> #<image 2×2>\" :presentation {:kind :image :text \"=> #<image 2×2>\"} :image %S :page-width 2 :delivery :inserted}]}" file)))
        (should (string-match-p "^(draw)\n\\[img\\]$" (clerkenwell-test--buffer-text))))))
    (clerkenwell-test--with-images
     (clerkenwell-test--with-buffer
      (let ((clerkenwell-quiet t) (clerkenwell-show-images t))
        (clerkenwell--rebuild-history
         (clerkenwell-test--entries
          "{:entries [{:kind :computation :source \"(draw)\" :text \"=> #<image 2×2>\" :presentation {:kind :image :text \"=> #<image 2×2>\"} :image \"/nonexistent.png\" :page-width 2 :delivery :inserted}]}"))
        (let ((text (clerkenwell-test--buffer-text)))
          (should (string-match-p "=> #<image 2×2> \\[unreadable: /nonexistent.png\\]" text))))))
    (delete-file file)))

(ert-deftest clerkenwell-test-table-result ()
  "A table is shown as its aligned text."
  (clerkenwell-test--with-buffer
   (let ((clerkenwell-quiet t))
     (clerkenwell--rebuild-history
      (clerkenwell-test--entries
       "{:entries [{:kind :computation :source \"(t)\" :text \"| a |\\n|---|\\n| 1 |\" :presentation {:kind :table :text \"| a |\\n|---|\\n| 1 |\"} :delivery :inserted}]}"))
     (should (string-match-p "^(t)\n| a |\n|---|\n| 1 |$" (clerkenwell-test--buffer-text))))))

(ert-deftest clerkenwell-test-verbatim-text ()
  "Text sent as it is has no source, and is shown as written."
  (clerkenwell-test--with-buffer
   (let ((clerkenwell-quiet t))
     (clerkenwell--rebuild-history
      (clerkenwell-test--entries
       "{:entries [{:kind :computation :source nil :text \"note\\n  this\" :presentation {:kind :text :verbatim? true :text \"note\\n  this\"} :delivery :inserted}]}"))
     (should (string-match-p "^note\n  this$" (clerkenwell-test--buffer-text))))))

(ert-deftest clerkenwell-test-dedent ()
  ;; A map selected from within a call, starting at column 24.
  (should (equal "{:foo foo\n :bar bar}"
                 (clerkenwell--dedent "{:foo foo\n                         :bar bar}" 24)))
  (should (equal "a\n  b\n\nc"
                 (clerkenwell--dedent "    a\n      b\n\n    c")))
  (let ((tab-width 8))
    (should (equal "a\n b" (clerkenwell--dedent "\ta\n         b"))))
  (should (equal "as is" (clerkenwell--dedent "as is"))))

(ert-deftest clerkenwell-test-eval-request-before-cider-2 ()
  "CIDER before 2.0 has only the positional `cider-nrepl-request:eval'."
  (let (sent)
    (cl-letf (((symbol-function 'fboundp)
               (let ((real (symbol-function 'fboundp)))
                 (lambda (f) (and (not (eq f 'cider-nrepl-send-eval-request))
                                  (funcall real f)))))
              ((symbol-function 'cider-nrepl-request:eval)
               (lambda (code _handler _ns _line _column _params connection)
                 (setq sent (list code connection)))))
      (clerkenwell--send-eval-request "(+ 1 2)" #'ignore 'conn))
    (should (equal '("(+ 1 2)" conn) sent))))

(ert-deftest clerkenwell-test-unsendable-request-is-not-left-in-flight ()
  (clerkenwell-test--with-buffer
   (setq clerkenwell--notebook-id "nb")
   (let ((inhibit-read-only t))
     (goto-char (point-max))
     (insert "(+ 1 2)"))
   (cl-letf (((symbol-function 'clerkenwell--eval-async)
              (lambda (&rest _) (error "No nREPL"))))
     (clerkenwell-evaluate-and-send))
   (should-not clerkenwell--in-flight)
   (should (equal "error" clerkenwell--status))))

(ert-deftest clerkenwell-test-send-text-needs-a-connection ()
  (let ((clerkenwell--repl nil))
    (should-error (clerkenwell-send-text "hello") :type 'user-error)))

(ert-deftest clerkenwell-test-faces-survive-font-lock ()
  "A warning keeps its face when font-lock refontifies the history.
Text in Clojure's comment syntax (a `;' note) still takes the comment
face from clojure-mode; this checks text that is not."
  (clerkenwell-test--with-images
   (clerkenwell-test--with-buffer
    (font-lock-mode 1)
    (let ((clerkenwell-quiet t) (clerkenwell-show-images t))
      (clerkenwell--rebuild-history
       (clerkenwell-test--entries
        "{:entries [{:kind :computation :source \"(draw)\" :text \"=> #<image 2×2>\" :presentation {:kind :image :text \"=> #<image 2×2>\"} :image \"/nonexistent.png\" :page-width 2 :delivery :inserted}]}"))
      (font-lock-ensure)
      (goto-char (point-min))
      (search-forward "unreadable")
      ;; What the display uses: our `font-lock-face', with no `face' from
      ;; font-lock laid over it.
      (should (eq 'font-lock-warning-face
                  (get-text-property (1- (point)) 'font-lock-face)))
      (should-not (get-text-property (1- (point)) 'face))))))

(ert-deftest clerkenwell-test-markdown-result ()
  "Markdown is shown as written, coloured by markdown-mode, and keeps its
colours through the buffer's own font-lock."
  (clerkenwell-test--with-buffer
   (font-lock-mode 1)
   (let ((clerkenwell-quiet t))
     (clerkenwell--rebuild-history
      (clerkenwell-test--entries
       "{:entries [{:kind :computation :source \"(m)\" :text \"# Hello\" :presentation {:kind :md :text \"# Hello\"} :delivery :inserted}]}"))
     (font-lock-ensure)
     (should (string-match-p "^(m)\n# Hello$" (clerkenwell-test--buffer-text)))
     (goto-char (point-min))
     (search-forward "Hello")
     (when (fboundp 'markdown-mode)
       (should (get-char-property (1- (point)) 'font-lock-face))))))

(ert-deftest clerkenwell-test-fontify-without-the-mode ()
  "Without the mode, the text is just the text."
  (should (equal "# Hi" (clerkenwell--fontify "# Hi" 'clerkenwell-no-such-mode))))

(ert-deftest clerkenwell-test-copy-description ()
  (should (equal "copy on tablet ok"
                 (clerkenwell--copy-description (parseedn-read-str "{:ok? true :pending 0}"))))
  (should (equal "copy on tablet 2 files behind"
                 (clerkenwell--copy-description (parseedn-read-str "{:ok? true :pending 2}"))))
  (should (string-match-p "FAILING (asleep)"
                          (clerkenwell--copy-description
                           (parseedn-read-str "{:ok? false :message \"asleep\"}")))))

;;; Settings

(ert-deftest clerkenwell-test-settings-are-sent-as-edn ()
  (let* ((clerkenwell-font "Fira \"Code\"")
         (clerkenwell-font-size 32)
         (clerkenwell-print-timeout 1.5)
         (clerkenwell-strict-height nil)
         (m (parseedn-read-str (clerkenwell--settings)))
         (style (gethash :style m))
         (limits (gethash :limits m))
         (layout (gethash :layout m)))
    (should (equal "Fira \"Code\"" (gethash :font style)))
    (should (equal 32 (gethash :font-size style)))
    (should (equal 1500 (gethash :timeout-ms limits)))
    (should (equal 30 (gethash :max-rows limits)))
    (should (equal 60 (gethash :left-margin layout)))
    ;; parseedn reads false as nil.
    (should (null (gethash :strict-height? layout)))))

(ert-deftest clerkenwell-test-font-defaults-to-emacs-font ()
  (let ((clerkenwell-font "Fira Code"))
    (should (equal "Fira Code" (clerkenwell--font))))
  (let ((clerkenwell-font nil))
    (cl-letf (((symbol-function 'face-attribute)
               (lambda (&rest _) (font-spec :family "Menlo"))))
      (should (equal "Menlo" (clerkenwell--font))))
    ;; On a terminal the default face has no font.
    (cl-letf (((symbol-function 'face-attribute)
               (lambda (&rest _) 'unspecified)))
      (should (equal "Monospaced" (clerkenwell--font))))))

;;; One buffer per notebook

(defmacro clerkenwell-test--with-clean-buffers (&rest body)
  "Run BODY, killing the Clerkenwell buffers it makes."
  `(unwind-protect (progn ,@body)
     (mapc #'kill-buffer (clerkenwell--buffers))))

(ert-deftest clerkenwell-test-each-notebook-has-its-own-buffer ()
  (clerkenwell-test--with-clean-buffers
   (let* ((waiting (clerkenwell--setup (get-buffer-create "*clerkenwell*")))
          (a (clerkenwell--notebook-buffer "aaaaaaaa-0000" "Fourier scratch")))
     (should (eq a waiting))
     (should (equal (buffer-name a) "*clerkenwell: Fourier scratch*"))
     (let ((b (clerkenwell--notebook-buffer "bbbbbbbb-0000" nil)))
       (should-not (eq a b))
       (should (equal (buffer-name b) "*clerkenwell: bbbbbbbb*"))
       (should (eq a (clerkenwell--notebook-buffer "aaaaaaaa-0000" "Fourier scratch")))
       (should (eq b (clerkenwell--buffer-for "bbbbbbbb-0000")))))))

(ert-deftest clerkenwell-test-a-renamed-notebook-renames-its-buffer ()
  (clerkenwell-test--with-clean-buffers
   (let ((a (clerkenwell--notebook-buffer "aaaaaaaa-0000" "Old")))
     (clerkenwell--notebook-buffer "aaaaaaaa-0000" "New")
     (should (equal (buffer-name a) "*clerkenwell: New*")))))

(ert-deftest clerkenwell-test-windows-follow-the-tablet ()
  (clerkenwell-test--with-clean-buffers
   (let ((a (clerkenwell--notebook-buffer "aaaaaaaa-0000" "A"))
         (b (clerkenwell--notebook-buffer "bbbbbbbb-0000" "B")))
     (set-window-buffer (selected-window) a)
     (clerkenwell--follow b)
     (should (eq b (window-buffer (selected-window)))))))

(provide 'clerkenwell-test)
;;; clerkenwell-test.el ends here
