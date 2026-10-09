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

(deftest grayscale-keeps-midtones
  (let [img (java.awt.image.BufferedImage. 4 4 java.awt.image.BufferedImage/TYPE_INT_RGB)
        g (.createGraphics img)]
    (.setColor g (Color. 0x80 0x80 0x80))
    (.fillRect g 0 0 4 4)
    (.dispose g)
    (let [out (image/scale img 4 4)]
      (is (= java.awt.image.BufferedImage/TYPE_BYTE_GRAY (.getType out)))
      ;; The raw sample, which is what the PNG stores. getRGB would report
      ;; it lightened, through Java's linear gray colour space.
      (is (= 0x80 (.getSample (.getRaster out) 1 1 0))
          "mid-gray stays mid-gray"))))
