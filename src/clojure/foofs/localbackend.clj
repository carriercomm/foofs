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

(ns foofs.localbackend
  (:use [foofs.filesystembackend :only [FilesystemBackend]]
        [foofs.storage.scheduler :only [Scheduler]]
        [foofs.fuse bytebuffer jna]
        [foofs crypto util])
  (:import [java.io File FileInputStream FileOutputStream]
           java.nio.ByteBuffer
           [java.util.concurrent ArrayBlockingQueue Executor
            LinkedBlockingQueue]))

(def empty-inode
  {:size 0
   :blocks 0
   :atime 0
   :mtime 0
   :ctime 0
   :atimensec 0
   :mtimensec 0
   :ctimensec 0
   :mode 0
   :nlink 0
   :uid 0
   :gid 0
   :rdev 0})

(def empty-file
  {:block-list []
   :block-size 65536
   :n 0
   :k 0})

;; this is obviously a state monadic function
(defn make-inode
  [state mode]
  (let [child-nodeid (next-key (:inode-table state)
                               (:next-nodeid state)
                               Long/MIN_VALUE Long/MAX_VALUE)
        inode (assoc empty-inode :mode mode)
        state (assoc-in state [:inode-table child-nodeid] inode)
        state (assoc state :next-nodeid (inc child-nodeid))]
    [state child-nodeid]))

(defn inode-modifier!
  [state-agent nodeid f continuation!]
  (send
    state-agent
    (fn [state]
      (let [inode-table (:inode-table state)
            inode (get inode-table nodeid)]
        (if (nil? inode)
          (do (continuation! errno-noent) state)
          (let [new-inode (f inode)]
            (agent-do state-agent (continuation! new-inode))
            (assoc-in state [:inode-table nodeid] new-inode)))))))

(defn attribute-modifier!
  [state-agent nodeid f attribute continuation!]
  (inode-modifier! state-agent nodeid
                   (fn [inode]
                     (assoc inode attribute (f (get inode attribute))))
                   continuation!))

(defn local-read-block
  "Callback for BasicScheduler to read an e-block from the local filesystem."
  [e-hash block-size _ _]
  (let [hash-chars (base32-encode e-hash)
        dir-name (String. (into-array Character/TYPE (take 2 hash-chars)))
        file-name (String. (into-array Character/TYPE (drop 2 hash-chars)))
        path (str "/tmp/foofs/" dir-name "/" file-name)]
    (try
      (let [file-in (FileInputStream. path)
            block-bytes (byte-array block-size)
            size (.read file-in block-bytes)]
        (if (= size block-size)
          block-bytes
          nil))
      (catch Exception _))))

(defn local-write-block
  "Callback for BasicScheduler to write an e-block from the local filesystem."
  [e-hash e-block _ _]
  (let [hash-chars (base32-encode e-hash)
        dir-name (String. (into-array Character/TYPE (take 2 hash-chars)))
        file-name (String. (into-array Character/TYPE (drop 2 hash-chars)))
        dir-path (str "/tmp/foofs/" dir-name)
        path (str dir-path "/" file-name)
        file (File. path)]
    (try
      (if (.isFile file)
        true
        (let [dir-file (doto (File. dir-path) (.mkdirs))
              temp-file (File/createTempFile "foofs" nil dir-file)]
          (.write (FileOutputStream. temp-file) (to-byte-array e-block))
          (.renameTo temp-file file)))
      (catch Exception _
        false))))

(defn read-file
  "Execute a synchronous read of a file."
  [scheduler salt {:keys [block-list block-size n k]} offset size]
  (let [start-blockid (quot offset block-size)
        end-blockid (inc (quot (+ size offset -1) block-size)) ;; one past
        block-count (- end-blockid start-blockid)
        blocks (take block-count (drop start-blockid block-list))
        blocking-queue (LinkedBlockingQueue.)]
    (doseq [block blocks]
      (.fetch-block scheduler salt block block-size n k
                    #(.put blocking-queue [block %])))
    (loop [fetched-blocks {}]
      (if (= block-count (count fetched-blocks))
        (let [buffer (ByteBuffer/allocate (* block-size block-count))]
          (doseq [block blocks]
            (.put buffer (get fetched-blocks block)))
          (.position buffer (rem offset block-size))
          (.limit buffer (+ size (.position buffer)))
          buffer)
        (let [[block block-bytes] (.take blocking-queue)]
          (if (nil? block-bytes)
            errno-io
            (recur (assoc fetched-blocks block block-bytes))))))))

(defn read-block
  [scheduler salt block block-size n k]
  (let [blocking-queue (ArrayBlockingQueue. 1)]
    (.fetch-block scheduler salt block block-size n k
                  ;; wrapped in a vec because the queue doesn't like nil
                  #(.put blocking-queue [%]))
    (first (.take blocking-queue))))

(defn write-block!
  [scheduler salt n k block-bytes]
  (let [blocking-queue (ArrayBlockingQueue. 1)]
    (.store-block scheduler salt block-bytes n k
                  ;; wrapped in a vec because the queue doesn't like nil
                  #(.put blocking-queue [%]))
    (first (.take blocking-queue))))

(defn get-nth-or-zero!
  [scheduler salt {:keys [block-list block-size n k]} index]
  (if (< index (count block-list))
    (nth block-list index)
    (let [block-bytes (byte-array block-size)]
      (write-block! scheduler salt n k block-bytes))))

(defn write-file!
  [scheduler salt {:keys [block-list block-size n k] :as file} offset size data]
  (let [first-blockid (quot offset block-size)
        last-blockid (quot (+ size offset) block-size)]
    (doall
      (for [index (range (max (inc last-blockid) (count block-list)))]
        (cond
          (and (> index first-blockid) (< index last-blockid))
          (let [partial-data (.duplicate data)
                data-offset (- (* block-size index) offset)]
            (.position partial-data data-offset)
            (.limit partial-data (+ block-size data-offset))
            (write-block! scheduler salt n k partial-data))
          (or (= index first-blockid) (= index last-blockid))
          (let [block (get-nth-or-zero! scheduler salt file index)
                f-block (read-block scheduler salt block block-size n k)
                partial-data (.duplicate data)
                data-offset (- (* block-size index) offset)]
            (if (pos? data-offset)
              (do
                (.position partial-data data-offset)
                (.put f-block partial-data))
              (do
                (.position f-block (- data-offset))
                (.limit partial-data
                        (min (.remaining partial-data) (.remaining f-block)))
                (.put f-block partial-data)))
            (.rewind f-block)
            (write-block! scheduler salt n k f-block))
          true
          (get-nth-or-zero! scheduler salt file index))))))

;; TODO: Reed-Solomon coding.
(defrecord LocalBackend
  [^clojure.lang.Agent state-agent
   ^foofs.storage.scheduler.Scheduler scheduler
   ^java.util.concurrent.Executor executor]
  FilesystemBackend
  (lookup [_ nodeid child continuation!]
    (let [lookup-table (:lookup-table (deref state-agent))]
      (if (= "" child)
        (continuation! nodeid)
        (continuation! (get-in lookup-table [nodeid child])))))
  (getattr [_ nodeid continuation!]
    (let [inode (get-in (deref state-agent) [:inode-table nodeid])]
      (if (nil? inode)
        (continuation! nil)
        (continuation! (assoc inode :nodeid nodeid)))))
  (reference [_ nodeid continuation!]
    (attribute-modifier! state-agent nodeid inc :nlink continuation!))
  (dereference [_ nodeid continuation!]
    (attribute-modifier! state-agent nodeid dec :nlink continuation!))
  (clonedir [_ nodeid continuation!]
    (let [state (deref state-agent)
          inode-table (:inode-table state)]
      (continuation!
        (map
          (fn [[filename child-nodeid]]
            {:name filename
             :nodeid child-nodeid
             :type (get-in inode-table [child-nodeid :mode])})
          (get-in state [:lookup-table nodeid])))))
  (readfile [_ nodeid offset size continuation!]
    (let [state (deref state-agent)
          file (get-in state [:file-table nodeid] empty-file)
          file-size (get-in state [:inode-table nodeid :size])
          salt (get state :salt)]
      (if (nil? file-size)
        (continuation! errno-noent)
        (let [offset (min file-size offset)
              size (- (min file-size (+ offset size)) offset)]
          (.execute
            executor
            #(continuation! (read-file scheduler salt file offset size)))))))
  (writefile [_ nodeid offset size data continuation!]
    ;; TODO: Write out all the complete blocks before bothering the state agent
    (send-off
      state-agent
      (fn [state]
        (let [inode (get-in state [:inode-table nodeid])]
          (if (nil? inode)
            (do (continuation! errno-noent) state)
            (let [file (get-in state [:file-table nodeid] empty-file)
                  salt (get state :salt)
                  block-list (write-file! scheduler salt file offset size data)
                  inode (assoc inode :size (max (:size inode)
                                                (+ offset size)))
                  file (assoc file :block-list block-list)
                  state (assoc-in state [:inode-table nodeid] inode)
                  state (assoc-in state [:file-table nodeid] file)]
              (agent-do state-agent (continuation! {:size size}))
              state))))))
  (mknod [_ nodeid filename mode continuation!]
    (send
      state-agent
      (fn [state]
        (if (contains? (get-in state [:lookup-table nodeid]) filename)
          (do (continuation! errno-exist) state)
          (let [[state child-nodeid] (make-inode state mode)
                state (assoc-in state [:inode-table child-nodeid :nlink] 1)
                state (assoc-in state [:lookup-table nodeid filename]
                                child-nodeid)
                state (assoc state :next-nodeid (inc child-nodeid))
                inode (get-in state [:inode-table child-nodeid])]
            (agent-do state-agent
                      (continuation! (assoc inode :nodeid child-nodeid)))
            state)))))
  (mkdir [_ nodeid filename mode continuation!]
    (send
      state-agent
      (fn [state]
        (if (contains? (get-in state [:lookup-table nodeid]) filename)
          (do (continuation! errno-exist) state)
          (let [[state child-nodeid] (make-inode
                                       state (bit-or stat-type-directory mode))
                state (assoc-in state [:inode-table child-nodeid :nlink] 2)
                state (assoc-in state [:lookup-table nodeid filename]
                                child-nodeid)
                state (assoc-in state [:lookup-table child-nodeid]
                                {"." child-nodeid ".." nodeid})
                nlink (get-in state [:inode-table nodeid :nlink])
                state (assoc-in state [:inode-table nodeid :nlink]
                                (inc nlink))
                state (assoc state :next-nodeid (inc child-nodeid))
                inode (get-in state [:inode-table child-nodeid])]
            (agent-do state-agent
                      (continuation! (assoc inode :nodeid child-nodeid)))
            state)))))
  (link [_ nodeid filename target-nodeid continuation!]
    (send
      state-agent
      (fn [state]
        (let [children (get-in state [:lookup-table nodeid])
              inode (get-in state [:inode-table nodeid])
              target-inode (get-in state [:inode-table target-nodeid])]
          (if (contains? children filename)
            (do (continuation! errno-exist) state)
            (if (not (= stat-type-directory
                        (bit-and stat-type-mask (:mode inode))))
              (do (continuation! errno-notdir) state)
              (if (nil? target-inode)
                (do (continuation! errno-noent) state)
                (let [state (assoc-in state [:inode-table target-nodeid :nlink]
                                      (inc (:nlink target-inode)))
                      state (assoc-in state [:lookup-table nodeid filename]
                                      target-nodeid)]
                  (agent-do state-agent
                            (continuation! (assoc target-inode
                                                  :nodeid target-nodeid)))
                  state))))))))
  (unlink [_ nodeid filename continuation!]
    (send
      state-agent
      (fn [state]
        (let [inode-table (:inode-table state)
              inode (get inode-table nodeid)
              children (get-in state [:lookup-table nodeid])
              child-nodeid (get children filename)
              child-inode (get inode-table child-nodeid)]
          (cond
            (nil? inode)
            (do (continuation! errno-noent) state)
            (not (= stat-type-directory
                    (bit-and stat-type-mask (:mode inode))))
            (do (continuation! errno-notdir) state)
            (nil? child-inode)
            (do (continuation! errno-noent) state)
            (= stat-type-directory
               (bit-and stat-type-mask (:mode child-inode)))
            (do (continuation! errno-isdir) state)
            true
            (let [nlink (dec (:nlink child-inode))
                  state (assoc-in state [:lookup-table nodeid]
                                  (dissoc children filename))
                  inode-table (if (zero? nlink)
                                (dissoc inode-table child-nodeid)
                                (assoc-in inode-table [child-nodeid :nlink]
                                          nlink))
                  state (assoc state :inode-table inode-table)]
              ;; agent-do?
              (continuation! 0)
              state))))))
  (rmdir [_ nodeid filename continuation!]
    (send
      state-agent
      (fn [state]
        (let [inode-table (:inode-table state)
              inode (get inode-table nodeid)
              lookup-table (:lookup-table state)
              children (get lookup-table nodeid)
              child-nodeid (get children filename)
              child-inode (get inode-table child-nodeid)
              child-children (get lookup-table child-nodeid)]
          (cond
            (nil? inode)
            (do (continuation! errno-noent) state)
            (not (= stat-type-directory
                    (bit-and stat-type-mask (:mode inode))))
            (do (continuation! errno-notdir) state)
            (nil? child-nodeid)
            (do (continuation! errno-noent) state)
            (not (= stat-type-directory
                    (bit-and stat-type-mask (:mode child-inode))))
            (do (continuation! errno-notdir) state)
            (not (empty? (dissoc child-children "." "..")))
            (do (continuation! errno-notempty) state)
            true
            (let [nlink (- (:nlink child-inode) 2)
                  lookup-table (assoc lookup-table nodeid
                                      (dissoc children filename))
                  lookup-table (dissoc lookup-table child-nodeid)
                  state (assoc state :lookup-table lookup-table)
                  inode-table (if (zero? nlink)
                                (dissoc inode-table child-nodeid)
                                (assoc-in inode-table [child-nodeid :nlink]
                                          nlink))
                  inode-table (assoc-in inode-table [nodeid :nlink]
                                        (dec (get-in inode-table
                                                     [nodeid :nlink])))
                  state (assoc state :inode-table inode-table)]
              (agent-do state-agent (continuation! 0))
              state))))))
  (chmod [_ nodeid mode continuation!]
    (attribute-modifier! state-agent nodeid
                         #(bit-or (bit-and stat-type-mask %)
                                  (bit-and stat-mode-mask mode))
                         :mode continuation!))
  (setuid [_ nodeid uid continuation!]
    (attribute-modifier! state-agent nodeid
                         (fn [_] uid)
                         :uid continuation!))
  (setgid [_ nodeid gid continuation!]
    (attribute-modifier! state-agent nodeid
                         (fn [_] gid)
                         :gid continuation!))
  (truncate [_ nodeid size continuation!]
    (send
      state-agent
      (fn [state]
        (if (nil? (get-in state [:inode-table nodeid]))
          (do (continuation! errno-noent) state)
          (let [state (assoc-in state [:inode-table nodeid :size] size)]
            ;; TODO truncate block-list
            (agent-do state-agent (continuation! nil))
            state)))))
  (setatime [_ nodeid seconds nseconds continuation!]
    (inode-modifier! state-agent nodeid
                     #(assoc % :atime seconds :atimensec nseconds)
                     continuation!))
  (setmtime [_ nodeid seconds nseconds continuation!]
    (inode-modifier! state-agent nodeid
                     #(assoc % :mtime seconds :mtimensec nseconds)
                     continuation!))
  (rename [_ nodeid target-nodeid filename target-filename continuation!]
    (send
      state-agent
      (fn [state]
        (let [inode-table (:inode-table state)
              lookup-table (:lookup-table state)
              dir-inode (get inode-table nodeid)
              target-dir-inode (get inode-table target-nodeid)
              children (get lookup-table nodeid)
              file-nodeid (get children filename)
              file-inode (get inode-table file-nodeid)]
          (cond
            (not (= stat-type-directory
                    (bit-and stat-type-mask (:mode dir-inode))))
            (do (continuation! errno-notdir) state)
            (not (= stat-type-directory
                    (bit-and stat-type-mask (:mode target-dir-inode))))
            (do (continuation! errno-notdir) state)
            (nil? file-inode)
            (do (continuation! errno-noent) state)
            true
            (let [lookup-table (assoc lookup-table nodeid
                                      (dissoc children filename))
                  lookup-table (assoc-in lookup-table [target-nodeid
                                                       target-filename]
                                         file-nodeid)
                  ;; If we are renaming a directory fixup ".." link
                  file-isdir (= stat-type-directory
                                (bit-and stat-type-mask (:mode file-inode)))
                  lookup-table (if file-isdir
                                 (assoc-in lookup-table [file-nodeid
                                                         ".."]
                                           target-nodeid)
                                 lookup-table)
                  inode-table (if file-isdir
                                (assoc-in inode-table [nodeid :nlink]
                                          (dec (:nlink dir-inode)))
                                inode-table)
                  inode-table (if file-isdir
                                (assoc-in inode-table [target-nodeid :nlink]
                                          (inc (:nlink target-dir-inode)))
                                inode-table)
                  state (assoc state :inode-table inode-table
                               :lookup-table lookup-table)]
              (agent-do state-agent (continuation! nil))
              state))))))
  (symlink [_ nodeid filename link-target continuation!]
    (send
      state-agent
      (fn [state]
        (if (contains? (get-in state [:lookup-table nodeid]) filename)
          (do (continuation! errno-exist) state)
          (let [[state child-nodeid] (make-inode
                                       state
                                       (bit-or stat-type-link 0777))
                state (assoc-in state [:inode-table child-nodeid :nlink] 1)
                state (assoc-in state [:lookup-table nodeid filename]
                                child-nodeid)
                state (assoc state :next-nodeid (inc child-nodeid))
                state (assoc-in state [:file-table child-nodeid] link-target)
                inode (get-in state [:inode-table child-nodeid])]
            (agent-do state-agent
                      (continuation! (assoc inode :nodeid child-nodeid)))
            state)))))
  (readlink [_ nodeid continuation!]
    (let [file (get-in (deref state-agent) [:file-table nodeid])]
      (if (nil? file)
        (continuation! errno-noent)
        (continuation! file)))))
