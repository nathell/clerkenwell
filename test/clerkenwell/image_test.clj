(ns clerkenwell.image-test
  (:require [clojure.test :refer [deftest is testing]]
            [clerkenwell.image :as image])
  (:import (java.awt Color)))

(deftest colours
  (testing "the forms Emacs and the defaults produce"
    (is (= (Color. 0x00 0x24 0x51) (image/parse-color "#002451")))
    (is (= (Color. 0xff 0xaa 0x00) (image/parse-color "#fa0")))
    (is (= (Color. 0x12 0x56 0x9a) (image/parse-color "#123456789abc")))
    (is (= Color/black (image/parse-color "black")))
    (is (= Color/white (image/parse-color " White "))))
  (testing "anything else is refused rather than guessed at"
    (is (thrown? clojure.lang.ExceptionInfo (image/parse-color "unspecified-fg")))
    (is (thrown? clojure.lang.ExceptionInfo (image/parse-color "#12345")))))
