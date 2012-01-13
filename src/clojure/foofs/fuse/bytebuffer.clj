(ns foofs.fuse.bytebuffer
  (:use foofs.fuse.parser))

(defn parse-int32
  [buffer]
  (when (<= 4 (.remaining buffer))
    (let [buffer2 (.duplicate buffer)]
      (.order buffer2 (.order buffer)) ;; this is a damn shame right here
      [(.getInt buffer2) buffer2])))

(def parse-opaque32 parse-int32)

(defn parse-uint32
  [buffer]
  (when (<= 4 (.remaining buffer))
    (let [buffer2 (.duplicate buffer)]
      (.order buffer2 (.order buffer))
      [(bit-and 0xffffffff (.getInt buffer2)) buffer2])))

(defn parse-int64
  [buffer]
  (when (<= 8 (.remaining buffer))
    (let [buffer2 (.duplicate buffer)]
      (.order buffer2 (.order buffer))
      [(.getLong buffer2) buffer2])))

(def parse-opaque64 parse-int64)

(defn parse-uint64
  [buffer]
  (when (<= 8 (.remaining buffer))
    (let [buffer2 (.duplicate buffer)]
      (.order buffer2 (.order buffer))
      [(.and (biginteger 0xffffffffffffffff) (biginteger (.getLong buffer2)))
       buffer2])))

(defn skip
  [x]
  (fn parse-skip
    [buffer]
    (let [position (.position buffer)
          buffer2 (.duplicate buffer)]
      (.order buffer2 (.order buffer))
      [nil (.position buffer2 (+ x position))])))

(def skip-32
  (skip 4))

(def skip-64
  (skip 8))

(defn write-int16
  [x]
  (fn write-int16!
    [buffer]
    [nil (.putShort buffer x)]))

(defn write-int32
  [x]
  (fn write-int32!
    [buffer]
    [nil (.putInt buffer x)]))

(defn write-int64
  [x]
  (fn write-int64!
    [buffer]
    [nil (.putLong buffer x)]))

(defn pad
  [x]
  (fn pad!
    [buffer]
    (let [position (.position buffer)]
      [nil (.position buffer (+ x position))])))
