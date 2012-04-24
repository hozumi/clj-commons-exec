(ns clj-commons-exec
  (:require [clojure.java.io :as javaio :only [file]])
  (:use [clojure.test])
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
            InputStreamPumper
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

(defn close-all [^InputStream in
                 ^OutputStream out
                 ^OutputStream err]
  (when in
    (.close in))
  (.close out)
  (.close err))

(defrecord DefaultResultHandler [result in out err opts]
  ExecuteResultHandler
  (onProcessComplete
   [_ exit-value]
   (close-all in out err)
   (deliver result
            {:exit exit-value
             :out (convert-baos-into-x out (:encode opts))
             :err (convert-baos-into-x err (:encode opts))}))
  (onProcessFailed
   [_ e]
   (close-all in out err)
   (deliver result
            {:exit (.getExitValue e)
             :out (convert-baos-into-x out (:encode opts))
             :err (convert-baos-into-x err (:encode opts))
             :fail e})))

;; PumpStreamHandler flushes input stream only when input stream is System/in.
;; http://stackoverflow.com/questions/7113007/trouble-providing-multiple-input-to-a-command-using-apache-commons-exec-and-extr
;; ported from http://svn.apache.org/viewvc/commons/proper/exec/tags/EXEC_1_1/src/main/java/org/apache/commons/exec/PumpStreamHandler.java?view=markup
;; and add flush-input? option.
(defn flush-pump-stream-handler
  [^InputStream in ^OutputStream out ^OutputStream err flush-input?]
  (let [threads (atom [])
        isp (atom nil)]
    (reify
      ExecuteStreamHandler
      (setProcessOutputStream
        [_ is] ;;InputStream
        (when out
          (let [t (Thread. (StreamPumper. is out))]
            (swap! threads conj t)
            (.setDaemon t true))))
      (setProcessErrorStream
        [_ is] ;;InputStream
        (when err
          (let [t (Thread. (StreamPumper. is err))]
            (swap! threads conj t)
            (.setDaemon t true))))
      (setProcessInputStream
        [_ os] ;;OutputStream
        (if in
          (let [pumper (if flush-input?
                         (reset! isp (InputStreamPumper. in os))
                         (StreamPumper. in os true))
                t (Thread. pumper)]
            (swap! threads conj t)
            (.setDaemon t true))
          (try (.close os)
               (catch IOException e))))
      (start [_]
        (doseq [t @threads] (.start t)))
      (stop [_]
        (when @isp
          (.stopProcessing @isp))
        (doseq [^Thread t @threads]
          (try (.join t)
               (catch InterruptedException _)))
        (when err (try (.flush err) (catch IOException _)))
        (when out (try (.flush out) (catch IOException _)))))))

(defn string->input-stream [^String s & [^String encode]]
  (ByteArrayInputStream. (.getBytes s (or encode (System/getProperty "file.encoding")))))

(defn sh [[^String comm & args] & [opts]]
  (let [command (CommandLine. comm)
        in  (when-let [in (:in opts)]
              (if (string? in) (string->input-stream in (:encode opts)) in))
        out (or (:out opts) (ByteArrayOutputStream.))
        err (or (:err opts) (ByteArrayOutputStream.))
        result (promise)

        ^ExecuteResultHandler result-handler
        ((or (:result-handler-fn opts) ->DefaultResultHandler) result in out err opts)

        stream-handler (flush-pump-stream-handler in out err (:flush-input? opts))
        executor (DefaultExecutor.)]
    (doseq [arg args]
      (.addArgument command arg))
    (when-let [dir (:dir opts)]
      (.setWorkingDirectory executor (javaio/file dir)))
    (when-let [success (:as-success opts)]
      (.setExitValue executor success))
    (when-let [successes (:as-successes opts)]
      (.setExitValues executor (into-array Integer/TYPE successes)))
    (when-let [ms (:watchdog opts)]
      (.setWatchdog executor (ExecuteWatchdog. ms)))
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
        first-in (when-let [in (:in opts)]
                   (if (string? in) (string->input-stream in (:encode opts)) in))
        last-out (or (:out opts) (ByteArrayOutputStream.))
        last-err (or (:err opts) (ByteArrayOutputStream.))
        num-cmds (count cmds-list)
        pouts (repeatedly (dec num-cmds) #(PipedOutputStream.))
        pins (map (fn [pos] (PipedInputStream. pos)) pouts)
        outs (concat pouts [last-out])
        errs (concat (repeat (dec num-cmds) nil) [last-err])
        ins (cons first-in pins)
        opts-list (map (fn [in out err] (assoc opts :in in :out out :err err))
                       ins outs errs)]
    (doall
     (map sh cmds-list opts-list))))
