(ns clj-commons-exec
  (:require [clojure.java.io :as javaio :only [file]])
  (:import [java.io
            ByteArrayOutputStream
            ByteArrayInputStream
            InputStream
            OutputStream
            IOException
            PipedInputStream
            PipedOutputStream]
           [org.apache.commons.exec
            CommandLine
            DefaultExecutor
            DefaultExecuteResultHandler
            ExecuteResultHandler
            ExecuteWatchdog
            ExecuteStreamHandler
            StreamPumper
            ShutdownHookProcessDestroyer
            Watchdog
            PumpStreamHandler]
           [org.apache.commons.exec.environment
            EnvironmentUtils]))

(defn convert-baos-into-x [st ^String enc]
  (when (instance? ByteArrayOutputStream st)
    (let [b (.toByteArray ^ByteArrayOutputStream st)]
      (cond (nil? (seq b)) nil
            (= enc :byte)  b
            (string? enc)  (String. b enc)
            :else (String. b (System/getProperty "file.encoding"))))))

(defrecord DefaultResultHandler [result in out err opts]
  ExecuteResultHandler
  (onProcessComplete
   [_ exit-value]
   (when (and in (:close-in? opts)) (.close ^InputStream in))
   (when (and out (:close-out? opts)) (.close ^OutputStream out))
   (when (and err (:close-err? opts)) (.close ^OutputStream err))
   (deliver result
            {:exit exit-value
             :out (convert-baos-into-x out (:encode opts))
             :err (convert-baos-into-x err (:encode opts))}))
  (onProcessFailed
   [_ e]
   (when (and in (:close-in? opts)) (.close ^InputStream in))
   (when (and out (:close-out? opts)) (.close ^OutputStream out))
   (when (and err (:close-err? opts)) (.close ^OutputStream err))
   (deliver result
            {:exit (.getExitValue e)
             :out (convert-baos-into-x out (:encode opts))
             :err (convert-baos-into-x err (:encode opts))
             :exception e})))

(defrecord FlushStreamPumper [^InputStream is ^OutputStream os]
  Runnable
  (run [_]
    (loop []
      (let [b (.read is)]
        (if (< 0 b)
          (do (.write os b)
              (.flush os)
              (recur))
          (try (.close os)
               (catch IOException _)))))))

;; PumpStreamHandler flushes input stream only when input stream is System/in.
;; http://stackoverflow.com/questions/7113007/trouble-providing-multiple-input-to-a-command-using-apache-commons-exec-and-extr
;; ported from http://svn.apache.org/viewvc/commons/proper/exec/tags/EXEC_1_1/src/main/java/org/apache/commons/exec/PumpStreamHandler.java?view=markup
;; and add flush-input? option.
(defn pump-stream-handler
  [^InputStream in ^OutputStream out ^OutputStream err opts]
  (let [threads (atom [])]
    (reify
      ExecuteStreamHandler
      (setProcessOutputStream
        [_ is] ;;InputStream
        (when out
          (let [t (Thread. (StreamPumper. is out (boolean (:close-out? opts))))]
            (swap! threads conj t)
            (.setDaemon t true))))
      (setProcessErrorStream
        [_ is] ;;InputStream
        (when err
          (let [t (Thread. (StreamPumper. is err (boolean (:close-err? opts))))]
            (swap! threads conj t)
            (.setDaemon t true))))
      (setProcessInputStream
        [_ os] ;;OutputStream
        (if in
          (let [pumper (if (:flush-input? opts)
                         (FlushStreamPumper. in os)
                         (StreamPumper. in os true))
                t (Thread. ^Runnable pumper)]
            (swap! threads conj t)
            (.setDaemon t true))
          (try (.close os)
               (catch IOException e))))
      (start [_]
        (doseq [^Thread t @threads] (.start t)))
      (stop [_]
        (doseq [^Thread t @threads]
          (try (.join t)
               (catch InterruptedException _)))
        (when err (try (.flush err) (catch IOException _)))
        (when out (try (.flush out) (catch IOException _)))))))

(defn string->input-stream [^String s & [^String encode]]
  (ByteArrayInputStream. (.getBytes s (or encode (System/getProperty "file.encoding")))))

(defn sh [[^String comm & args] & [opts]]
  (let [command (CommandLine. comm)
        handle-quoting? (-> opts :handle-quoting? boolean)
        in  (when-let [in (:in opts)]
              (if (string? in) (string->input-stream in (:encode opts)) in))
        out (or (:out opts) (ByteArrayOutputStream.))
        err (or (:err opts) (ByteArrayOutputStream.))
        opts (let [{:keys [close-in? close-out? close-err?]} opts]
               (assoc opts :close-in? (if (:in opts) close-in? false)
                      :close-out? (if (:out opts) close-out? true)
                      :close-err? (if (:err opts) close-err? true)))
        result (promise)

        ^ExecuteResultHandler result-handler
        ((or (:result-handler-fn opts) ->DefaultResultHandler) result in out err opts)

        stream-handler (pump-stream-handler in out err opts)
        executor (DefaultExecutor.)]
    (doseq [arg args]
      (.addArgument command arg handle-quoting?))
    (when-let [dir (:dir opts)]
      (.setWorkingDirectory executor (javaio/file dir)))
    (when-let [success (:as-success opts)]
      (.setExitValue executor success))
    (when-let [successes (:as-successes opts)]
      (.setExitValues executor (into-array Integer/TYPE successes)))
    (when-let [watchdog-value (:watchdog opts)]
      (.setWatchdog executor 
                    (cond (instance? ExecuteWatchdog watchdog-value) watchdog-value
                          :else (ExecuteWatchdog. watchdog-value))))
    (when (:shutdown opts)
      (.setProcessDestroyer executor (ShutdownHookProcessDestroyer.)))
    (.setStreamHandler executor stream-handler)
    (if-let [env (:env opts)]
      (.execute executor command env result-handler)
      (if-let [add-env (:add-env opts)]
        (let [env (EnvironmentUtils/getProcEnvironment)]
          (doseq [[k v] add-env]
            (.put env k v))
          (.execute executor command env result-handler))
        (.execute executor command result-handler)))
    result))

(defn parse-args-pipe [args]
  (split-with sequential? args))

(defn sh-pipe [& args-and-opts]
  (let [[cmds-list [opts]] (parse-args-pipe args-and-opts)
        num-cmds-1 (-> cmds-list count dec)
        pouts (repeatedly num-cmds-1 #(PipedOutputStream.))
        pins (map (fn [^PipedOutputStream pos] (PipedInputStream. pos)) pouts)
        outs (concat pouts [(:out opts)])
        errs (concat (repeat num-cmds-1 nil) [(:err opts)])
        ins (cons (:in opts) pins)
        close-out?s (concat (repeat num-cmds-1 true) [(:close-out? opts)])
        close-err?s (concat (repeat num-cmds-1 true) [(:close-err? opts)])
        close-in?s (cons (:close-in? opts) (repeat num-cmds-1 true))
        opts-list (map (fn [in out err close-in? close-out? close-err?]
                         (assoc opts :in in :out out :err err :close-in? close-in?
                                :close-out? close-out? :close-err? close-err?))
                       ins outs errs close-in?s close-out?s close-err?s)]
    (doall
     (map sh cmds-list opts-list))))
