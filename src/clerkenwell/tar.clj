(ns clerkenwell.tar
  "Batches of files as tar, held in memory: how files travel to and from the
   device in one SSH call. Paths over 100 bytes use GNU long-name entries,
   which is what the device's busybox tar writes and reads."
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (org.apache.commons.compress.archivers.tar TarArchiveEntry
                                                      TarArchiveInputStream
                                                      TarArchiveOutputStream)))

(defn write
  "`entries`, a seq of [path bytes], as a tar archive."
  ^bytes [entries]
  (let [out (ByteArrayOutputStream.)]
    (with-open [tar (doto (TarArchiveOutputStream. out)
                      (.setLongFileMode TarArchiveOutputStream/LONGFILE_GNU))]
      (doseq [[^String path ^bytes data] entries]
        (.putArchiveEntry tar (doto (TarArchiveEntry. path)
                                (.setSize (alength data))))
        (.write tar data)
        (.closeArchiveEntry tar)))
    (.toByteArray out)))

(defn read-entries
  "The regular files in tar archive `bs`, as a vector of [path bytes].
   Directories and anything else are skipped."
  [^bytes bs]
  (with-open [tar (TarArchiveInputStream. (ByteArrayInputStream. bs))]
    (loop [acc []]
      (if-let [e (.getNextEntry tar)]
        (recur (if (.isFile ^TarArchiveEntry e)
                 (conj acc [(.getName ^TarArchiveEntry e) (.readAllBytes tar)])
                 acc))
        acc))))
