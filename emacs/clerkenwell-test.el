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

(provide 'clerkenwell-test)
;;; clerkenwell-test.el ends here
