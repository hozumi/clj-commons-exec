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

(defn parse-args [args]
  (split-with string? args))

(defn parse-args-pipe [args]
  (split-with sequential? args))

(deftest parse-args-test
  (is (= [["ls" "-l"] {:dir "foo"}]
           (parse-args ["ls" "-l" {:dir "foo"}])))
  (is (= [["ls" "-l" "src"] {:dir "foo"}]
           (parse-args ["ls" "-l" "src" {:dir "foo"}]))))

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
(defn flush-pump-stream-handler [out err in flush-input?]
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
                       (StreamPumper. in os))
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
           (doseq [t @threads]
             (try (.join t)
                  (catch InterruptedException _)))))))

(defn string->input-stream [^String s & [^String encode]]
  (ByteArrayInputStream. (.getBytes (str s \newline) (or encode (System/getProperty "file.encoding")))))

(defn sh [& args-and-opts]
  (let [[[^String comm & args] [opts]] (parse-args args-and-opts)
        command (CommandLine. comm)
        in  (when-let [i (:in opts)]
              (if (string? i) (string->input-stream i (:encode opts)) i))
        out (or (:out opts) (ByteArrayOutputStream.))
        err (or (:err opts) (ByteArrayOutputStream.))
        result (promise)

        ^ExecuteResultHandler result-handler
        ((or (:result-handler-fn opts) ->DefaultResultHandler) result in out err opts)

        stream-handler (flush-pump-stream-handler out err in (:flush-input? opts))
        stream-handler (PumpStreamHandler. out err in)
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

(defn sh-pipe [& args-and-opts]
  (let [[cmds-list [opts]] (parse-args-pipe args-and-opts)
        in  (when-let [i (:in opts)]
              (if (string? i) (string->input-stream i (:encode opts)) i))
        out (or (:out opts) (ByteArrayOutputStream.))
        err (or (:err opts) (ByteArrayOutputStream.))
        num-cmds (count cmds-list)
        first-stream-set [nil nil in]
        middle-stream-sets (reduce concat
                                   (for [_ (range (dec num-cmds))]
                                     (let [pos (java.io.PipedOutputStream.)
                                           pis (java.io.PipedInputStream. pos)]
                                       [[pos nil nil] [nil nil pis]])))
        last-stream-set [out err nil]
        all-stream-sets (concat [first-stream-set] middle-stream-sets [last-stream-set])
        all-streams (map (fn [[set1 set2]] (map (fn [stream1 stream2] (or stream1 stream2)) set1 set2)) (partition 2 all-stream-sets)) 
        exec-fn (fn [cmd-and-args [cmd-out cmd-err cmd-in]]
                  (let [new-opts (-> opts
                                     (assoc :out cmd-out)
                                     (assoc :err cmd-err)
                                     (assoc :in cmd-in))
                        sh-fn-args (concat cmd-and-args [new-opts])]
                    (apply sh sh-fn-args)))]
    (last (doall
           (map exec-fn cmds-list all-streams)))))