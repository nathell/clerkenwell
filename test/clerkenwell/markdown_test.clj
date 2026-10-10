(ns clerkenwell.markdown-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clerkenwell.html :as html]
            [clerkenwell.markdown :as markdown]
            [clerkenwell.render :as render]
            [scicloj.kindly.v4.kind :as kind]))

(deftest hiccup-to-xhtml
  (testing "tags, shorthand, attributes and escaped text"
    (is (= "<p class=\"a b\" id=\"x\">1 &lt; 2 &amp; <em>so</em></p>"
           (html/xhtml [:p#x.a {:class "b"} "1 < 2 & " [:em "so"]]))))
  (testing "empty elements close themselves; whitespace is content"
    (is (= "<br />" (html/xhtml [:br])))
    (is (= "<span> </span>" (html/xhtml [:span " "]))))
  (testing "seqs splice, nil and false attributes vanish, style maps become CSS"
    (is (= "<td style=\"text-align: right; \">a</td>"
           (html/xhtml [:td {:style {:text-align "right"} :hidden nil :checked false} (list "a" nil)])))))

(deftest markdown-to-xhtml
  (let [x (markdown/->xhtml "# Hi *there*\n\n- [x] done\n\n![pic](https://example.com/p.png)\n\n<script>alert(1)</script>\n\n$x^2$ and #tag")]
    (testing "ordinary Markdown"
      (is (str/includes? x "<h1 id=\"hi-there\">Hi <em>there</em></h1>")))
    (testing "a task's box is text"
      (is (str/includes? x "[x] done")))
    (testing "an image is never fetched: only its alt text is shown"
      (is (not (str/includes? x "<img")))
      (is (str/includes? x "[pic]")))
    (testing "raw HTML is shown as written, not run or rendered"
      (is (str/includes? x "&lt;script&gt;alert(1)&lt;/script&gt;")))
    (testing "formulas and tags as their text"
      (is (str/includes? x "$x^2$"))
      (is (str/includes? x "#tag"))
      (is (not (str/includes? x "/tags/"))))))

(deftest typeset-for-the-page
  (let [img (markdown/image "A paragraph long enough to wrap, and then some more words to make sure of it, at a narrow width." 400)]
    (is (<= (.getWidth img) 400))
    (is (> (.getHeight img) 100) "wrapped onto several lines")))

(deftest markdown-through-present-and-rasterize
  (testing "kind/md is Markdown; Emacs gets it as written"
    (let [p (render/present (kind/md "# Hello\n\nworld"))]
      (is (= :md (:kind p)))
      (is (= "# Hello\n\nworld" (:text p) (:markdown p)))))
  (testing "a sequence of strings is lines"
    (is (= "a\nb" (:markdown (render/present (kind/md ["a" "b"]))))))
  (testing "kind/md on something else prints it"
    (is (= "=> 42" (:text (render/present (kind/md 42))))))
  (testing "long Markdown is capped like text"
    (let [p (render/present (kind/md (apply str (repeat 20000 "x"))))]
      (is (:truncated? p))
      (is (< (count (:markdown p)) 10100))))
  (let [out (io/file (System/getProperty "java.io.tmpdir") (str "ckw-md-" (random-uuid) ".png"))]
    (testing "drawn beneath its source"
      (let [r (render/rasterize (render/present (kind/md "# Hello")
                                                (assoc render/default-limits :source "(kind/md \"# Hello\")"))
                                out {:max-width 1284 :max-height 1800})]
        (is (:ok? r))
        (is (> (:height r) 80))))
    (testing "too tall for the room left is refused, like text"
      (let [r (render/rasterize (render/present (kind/md (str/join "\n\n" (repeat 40 "A paragraph."))))
                                out {:max-width 1284 :max-height 300})]
        (is (false? (:ok? r)))
        (is (= :insufficient-space (:reason r)))))
    (.delete out)))
