; Copyright (C) David Barksdale 2012 <amatus.amongus@gmail.com>
;
; foofs is free software: you can redistribute it and/or modify it
; under the terms of the GNU General Public License as published by the
; Free Software Foundation, either version 3 of the License, or
; (at your option) any later version.
; 
; foofs is distributed in the hope that it will be useful, but
; WITHOUT ANY WARRANTY; without even the implied warranty of
; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
; See the GNU General Public License for more details.
; 
; You should have received a copy of the GNU General Public License along
; with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns foofs.fuse.protocol
  (:use (foofs.fuse bytebuffer jna parser)
        clojure.contrib.monads)
  (:import com.sun.jna.Memory))

(def fuse-version-major 7)
(def fuse-version-minor 8)
(def fuse-root-id 1)
(def setattr-valid-mode 0)
(def setattr-valid-uid 1)
(def setattr-valid-gid 2)
(def setattr-valid-size 3)
(def setattr-valid-atime 4)
(def setattr-valid-mtime 5)
(def setattr-valid-handle 6)
(def setattr-valid-atime-now 7)
(def setattr-valid-mtime-now 8)

(def op-lookup       1)
(def op-forget       2)
(def op-getattr      3)
(def op-setattr      4)
(def op-readlink     5)
(def op-symlink      6)
(def op-mknod        8)
(def op-mkdir        9)
(def op-unlink       10)
(def op-rmdir        11)
(def op-rename       12)
(def op-link         13)
(def op-open         14)
(def op-read         15)
(def op-write        16)
(def op-statfs       17)
(def op-release      18)
(def op-fsync        20)
(def op-setxattr     21)
(def op-getxattr     22)
(def op-listxattr    23)
(def op-removexattr  24)
(def op-flush        25)
(def op-init         26)
(def op-opendir      27)
(def op-readdir      28)
(def op-releasedir   29)
(def op-fsyncdir     30)
(def op-getlk        31)
(def op-setlk        32)
(def op-setlkw       33)
(def op-access       34)
(def op-create       35)
(def op-interrupt    36)
(def op-bmap         37)
(def op-destroy      38)
(def op-ioctl        39)
(def op-poll         40)
(def op-notify-reply 41)
(def op-batch-forget 42)

(defn write-out-header
  [out]
  (domonad state-m
    [_ (write-int32 (:len out))
     _ (write-int32 (:error out))
     _ (write-int64 (:unique out))]
    nil))

(def out-header-len 16)

(defn send-reply!
  [fuse request error reply]
  (let [mem (Memory. 0x21000)
        buf (.getByteBuffer mem 0 (.size mem))]
    (.clear buf)
    (.position buf out-header-len)
    (reply buf)
    (.flip buf)
    ((write-out-header {:len (.limit buf)
                        :error error
                        :unique (:unique request)})
       buf)
    (try
      (c-write (:fd fuse) mem (.limit buf))
      (catch Exception e
        (.printStackTrace e))))
  nil)

(defn reply-error!
  [fuse request error]
  (send-reply! fuse request (- error) write-nothing))

(defn reply-ok!
  [fuse request reply]
  (send-reply! fuse request 0 reply))

(def parse-in-header
  (domonad
    parser-m
    [len parse-uint32
     opcode parse-opaque32
     unique parse-opaque64
     nodeid parse-opaque64
     uid parse-opaque32
     gid parse-opaque32
     pid parse-opaque32
     _ skip-32]
    {:len len
     :opcode opcode
     :unique unique
     :nodeid nodeid
     :uid uid
     :gid gid
     :pid pid}))

(def parse-forget-in
  (domonad
    parser-m
    [nlookup parse-uint64]
    nlookup))

(def parse-setattr-in
  (domonad
    parser-m
    [valid parse-opaque32
     _ skip-32
     handle parse-opaque64
     size parse-uint64
     _ skip-64
     atime parse-opaque64
     mtime parse-opaque64
     _ skip-64
     atimensec parse-opaque32
     mtimensec parse-opaque32
     _ skip-32
     mode parse-opaque32
     _ skip-32
     uid parse-opaque32
     gid parse-opaque32
     _ skip-32]
    {:valid valid
     :handle handle
     :size size
     :atime atime
     :mtime mtime
     :atimensec atimensec
     :mtimensec mtimensec
     :mode mode
     :uid uid
     :gid gid}))

(def parse-symlink-in
  (domonad
    parser-m
    [filename parse-utf8
     link-target parse-bytes-until-zero]
    {:filename filename
     :link-target link-target}))

(def parse-mknod-in
  (domonad
    parser-m
    [mode parse-opaque32
     rdev parse-opaque32
     filename parse-utf8]
    {:mode mode
     :rdev rdev
     :filename filename}))

(def parse-mkdir-in
  (domonad
    parser-m
    [mode parse-opaque32
     _ skip-32
     filename parse-utf8]
    {:mode mode
     :filename filename}))

(def parse-rename-in
  (domonad
    parser-m
    [target-nodeid parse-opaque64
     filename parse-utf8
     target-filename parse-utf8]
    {:target-nodeid target-nodeid
     :filename filename
     :target-filename target-filename}))

(def parse-link-in
  (domonad
    parser-m
    [target-nodeid parse-opaque64
     filename parse-utf8]
    {:target-nodeid target-nodeid
     :filename filename}))

(def parse-open-in
  (domonad parser-m
    [flags parse-opaque32
     _ skip-32]
    flags))

(def parse-read-in
  (domonad
    parser-m
    [handle parse-opaque64
     offset parse-uint64
     size parse-uint32
     read-flags parse-opaque32]
    {:handle handle
     :offset offset
     :size size
     :read-flags read-flags}))

(def parse-write-in
  (domonad
    parser-m
    [handle parse-opaque64
     offset parse-uint64
     size parse-uint32
     write-flags parse-opaque32
     data (parse-bytes size)]
    {:handle handle
     :offset offset
     :size size
     :write-flags write-flags
     :data data}))

(def parse-release-in
  (domonad
    parser-m
    [handle parse-opaque64
     flags parse-opaque32
     release-flags parse-opaque32]
    {:handle handle
     :flags flags
     :release-flags release-flags}))

(def parse-init-in
  (domonad
    parser-m
    [major parse-uint32
     minor parse-uint32
     max-readahead parse-uint32
     flags parse-opaque32]
    {:major major
     :minor minor
     :max-readahead max-readahead
     :flags flags}))

(def parse-create-in
  (domonad
    parser-m
    [flags parse-opaque32
     mode parse-opaque32
     filename parse-utf8]
    {:flags flags
     :mode mode
     :filename filename}))

(defn write-skip
  [_]
  write-nothing)

(defn write-fuse-attr
  [attr]
  (domonad
    state-m
    [_ (write-int64 (:nodeid attr))
     _ (write-int64 (:size attr))
     _ (write-int64 (:blocks attr))
     _ (write-int64 (:atime attr))
     _ (write-int64 (:mtime attr))
     _ (write-int64 (:ctime attr))
     _ (write-int32 (:atimensec attr))
     _ (write-int32 (:mtimensec attr))
     _ (write-int32 (:ctimensec attr))
     _ (write-int32 (:mode attr))
     _ (write-int32 (:nlink attr))
     _ (write-int32 (:uid attr))
     _ (write-int32 (:gid attr))
     _ (write-int32 (:rdev attr))]
    nil))

(defn write-entry-out
  [entry-out]
  (domonad
    state-m
    [_ (write-int64 (:nodeid entry-out))
     _ (write-int64 (:generation entry-out))
     _ (write-int64 (:entry-valid entry-out))
     _ (write-int64 (:attr-valid entry-out))
     _ (write-int32 (:entry-valid-nsec entry-out))
     _ (write-int32 (:attr-valid-nsec entry-out))
     _ (write-fuse-attr (:attr entry-out))]
    nil))

(defn write-attr-out
  [attr-out]
  (domonad
    state-m
    [_ (write-int64 (:valid attr-out))
     _ (write-int32 (:valid-nsec attr-out))
     _ (pad 4)
     _ (write-fuse-attr attr-out)]
    nil))

(defn write-open-out
  [open-out]
  (domonad
    state-m
    [_ (write-int64 (:handle open-out))
     _ (write-int32 (:flags open-out))
     _ (pad 4)]
    nil))

(defn write-write-out
  [write-out]
  (domonad
    state-m
    [_ (write-int32 (:size write-out))
     _ (pad 4)]
    nil))

(defn write-statfs-out
  [statfs-out]
  (domonad
    state-m
    [_ (write-int64 (:blocks statfs-out))
     _ (write-int64 (:bfree statfs-out))
     _ (write-int64 (:bavail statfs-out))
     _ (write-int64 (:files statfs-out))
     _ (write-int64 (:ffree statfs-out))
     _ (write-int32 (:bsize statfs-out))
     _ (write-int32 (:namelen statfs-out))
     _ (write-int32 (:frsize statfs-out))
     _ (pad 28)]
    nil))

(defn write-init-out
  [init-out]
  (domonad state-m
    [_ (write-int32 (:major init-out))
     _ (write-int32 (:minor init-out))
     _ (write-int32 (:max-readahead init-out))
     _ (write-int32 (:flags init-out))
     _ (write-int16 (:max-background init-out))
     _ (write-int16 (:congestion-threshold init-out))
     _ (write-int32 (:max-write init-out))]
    nil))

(defn write-create-out
  [create-out]
  (domonad state-m
    [_ (write-entry-out create-out)
     _ (write-open-out create-out)]
    nil))

(defn process-generic!
  [filesystem-fn result-fn fuse request]
  (filesystem-fn
    (:filesystem fuse)
    request
    (fn [result]
      (if (integer? result)
        (reply-error! fuse request result)
        (reply-ok! fuse request (result-fn result))))))

(defn process-forget!
  [fuse request]
  (.forget (:filesystem fuse) request)
  (reply-ok! fuse request write-nothing))

(defn process-init!
  [fuse request]
  (domonad
    maybe-m
    [:let [init (:arg request)
           connection (:connection fuse)]
     _ (do
         (reset! (:proto-major connection) (:major init))
         (reset! (:proto-minor connection) (:minor init))
         :nop)
     _ (if (> fuse-version-major (:major init))
         ;; kernel is too old, give up
         (reply-error! fuse request errno-proto)
         :nop)
     _ (if (< fuse-version-major (:major init))
         ;; kernel is too new, tell it we want to talk at an earlier version
         (reply-ok! fuse request
           (write-init-out {:major fuse-version-major
                            :minor fuse-version-minor
                            :max-readahead 0
                            :flags 0
                            :max-background 0
                            :congestion-threshold 0
                            :max-write 0}))
         :nop)
     _ (do
         (.init (:filesystem fuse) request)
         (reply-ok! fuse request
           (write-init-out {:major fuse-version-major
                            :minor fuse-version-minor
                            :max-readahead (:max-readahead init)
                            :flags 0
                            :max-background 0
                            :congestion-threshold 0
                            :max-write 0x21000}))
         :nop)
     ] nil))

(defn process-destroy!
  [fuse request]
  (.destroy (:filesystem fuse) request)
  (c-close (:fd fuse)))

(def ops
  {op-lookup {:arg-parser parse-utf8
              :processor! (partial process-generic!
                                   #(.lookup %1 %2 %3) write-entry-out)}
   op-forget {:arg-parser parse-forget-in
              :processor! process-forget!}
   op-getattr {:arg-parser parse-nothing
               :processor! (partial process-generic!
                                    #(.getattr %1 %2 %3) write-attr-out)}
   op-setattr {:arg-parser parse-setattr-in
               :processor! (partial process-generic!
                                    #(.setattr %1 %2 %3) write-attr-out)}
   op-readlink {:arg-parser parse-nothing
                :processor! (partial process-generic!
                                     #(.readlink %1 %2 %3) write-bytes)}
   op-symlink {:arg-parser parse-symlink-in
               :processor! (partial process-generic!
                                    #(.symlink %1 %2 %3) write-entry-out)}
   op-mknod {:arg-parser parse-mknod-in
             :processor! (partial process-generic!
                                  #(.mknod %1 %2 %3) write-entry-out)}
   op-mkdir {:arg-parser parse-mkdir-in
             :processor! (partial process-generic!
                                  #(.mkdir %1 %2 %3) write-entry-out)}
   op-unlink {:arg-parser parse-utf8
              :processor! (partial process-generic!
                                   #(.unlink %1 %2 %3) write-skip)}
   op-rmdir {:arg-parser parse-utf8
             :processor! (partial process-generic!
                                  #(.rmdir %1 %2 %3) write-skip)}
   op-rename {:arg-parser parse-rename-in
              :processor! (partial process-generic!
                                   #(.rename %1 %2 %3) write-skip)}
   op-link {:arg-parser parse-link-in
            :processor! (partial process-generic!
                                 #(.link %1 %2 %3) write-entry-out)}
   op-open {:arg-parser parse-open-in
            :processor! (partial process-generic!
                                 #(.open %1 %2 %3) write-open-out)}
   op-read {:arg-parser parse-read-in
            :processor! (partial process-generic!
                                 #(.readfile %1 %2 %3) write-bytes)}
   op-write {:arg-parser parse-write-in
             :processor! (partial process-generic!
                                  #(.writefile %1 %2 %3) write-write-out)}
   op-statfs {:arg-parser parse-nothing
              :processor! (partial process-generic!
                                   #(.statfs %1 %2 %3) write-statfs-out)}
   op-release {:arg-parser parse-release-in
               :processor! (partial process-generic!
                                    #(.release %1 %2 %3) write-skip)}
   op-init {:arg-parser parse-init-in
            :processor! process-init!}
   op-opendir {:arg-parser parse-open-in
               :processor! (partial process-generic!
                                    #(.opendir %1 %2 %3) write-open-out)}
   op-readdir {:arg-parser parse-read-in
               :processor! (partial process-generic!
                                    #(.readdir %1 %2 %3) write-bytes)}
   op-releasedir {:arg-parser parse-release-in
                  :processor! (partial process-generic!
                                       #(.releasedir %1 %2 %3) write-skip)}
   op-create {:arg-parser parse-create-in
              :processor! (partial process-generic!
                                   #(.create %1 %2 %3) write-create-out)}
   op-destroy {:arg-parser parse-nothing
               :processor! process-destroy!}})

(defn process-buf!
  [fuse buf]
  (domonad maybe-m
    [[request arg-buf] (parse-in-header buf)
     ;; TODO: do something with in-header.len?
     :let [opcode (:opcode request)
           op (ops opcode)]
     _ (if (nil? op)
         (do
           (.println *err* (str "No op for " request))
           (.println *err* (hexdump arg-buf))
           (reply-error! fuse request errno-nosys)
           nil)
         :nop)
     :let [argx ((:arg-parser op) arg-buf)]
     _ (if (nil? argx)
         (do
           (.println *err* (str "Invalid arg " (hexdump arg-buf)
                                " for " opcode))
           (reply-error! fuse request errno-inval))
         :nop)
     _ ((:processor! op) fuse (assoc request :arg (first argx)))] nil))

(def name-offset 24)
(defn dirent-align
  [x]
  (* 8 (quot (+ x 7) 8)))

(defn encode-dirent
  [dirent offset]
  (let [namebytes (.getBytes (:name dirent) "UTF-8")
        namelen (count namebytes)
        entsize (dirent-align (+ name-offset namelen))]
    (take
      entsize
      (concat
        (encode-int64 (:nodeid dirent))
        (encode-int64 (+ offset entsize))
        (encode-int32 namelen)
        (encode-int32 (bit-shift-right (bit-and stat-type-mask (:type dirent))
                                       12))
        namebytes
        (repeat 0)))))

(defn encode-dirents
  [dirents]
  (first (reduce (fn
                   [state dirent]
                   (let [[encoded-dirents offset] state
                         encoded-dirent (encode-dirent dirent offset)]
                     [(concat encoded-dirents encoded-dirent)
                      (+ offset (count encoded-dirent))]))
                 [[] 0]
                 dirents)))
