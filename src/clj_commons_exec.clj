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

(defn- command-line [cmd-and-args]
  "create a CommandLine object given a list/vector that has the base command and all the CLI arguments/options"
  (let [base-command (first cmd-and-args)
        cl (CommandLine. base-command)
        args-opts (rest cmd-and-args)]
    (doseq [ao args-opts]
      (doto cl
        (.addArgument ao)))
    cl))

(defn- run-pipe-2 [cmd-and-args1 cmd-and-args2]
  "works for two commands, one piped to the second. each command a vector of strings. return a future that contains a map similar to the one contained in the sh function's return promise"
  (let [result (promise)]
    ;; putting result promise in outer let in case
    ;; the inner let bindings can be GC'ed. this is premature optimization(?)
    (let [cl1 (command-line cmd-and-args1)
          cl2 (command-line cmd-and-args2)
          pos (java.io.PipedOutputStream.)
          pis (java.io.PipedInputStream. pos)
          output (java.io.ByteArrayOutputStream.)
          error (java.io.ByteArrayOutputStream.)          
          exec1 (doto (DefaultExecutor.) (.setStreamHandler (PumpStreamHandler. pos nil nil)))
          exec2 (doto (DefaultExecutor.) (.setStreamHandler (PumpStreamHandler. output error pis)))
          t1-fn (fn [] (.execute exec1 cl1))
          t2-fn (fn [] (deliver result
                               (future (do
                                         (let [exit-code (.execute exec2 cl2)]
                                           {:exit exit-code
                                            :out (.toString output)
                                            :err (.toString error)})))))
          t1 (Thread. t1-fn)
          t2 (Thread. t2-fn)]
      (.start t1)
      (.start t2))
    @result))

(defn- run-in-str-1 [cmd-and-args in-str]
  "feed the input string into the command. command specified as a vector of strings. return a future that contains a map similar to the one contained in the sh function's return promise"
  (let [cl (command-line cmd-and-args)
        output (java.io.ByteArrayOutputStream.)
        error (java.io.ByteArrayOutputStream.)
        input (string->input-stream (str in-str \newline))
        exec (doto (DefaultExecutor.) (.setStreamHandler (PumpStreamHandler. output error input)))]
    (future (do
              (let [exit-code (.execute exec cl)]
                {:exit exit-code
                 :out (.toString output)
                 :err (.toString error)})))))

(defn run [cmd-and-args-list & [input]]
  "run one or more commands, each command piped to the next if more than one. each command in the list specified as a vector of strings, the/all command string vector(s) enclosed inside one outer vector. return a future that contains a map similar to the one contained in the sh function's return promise"
  (let [result (promise)]
    ;; putting result promise in outer let in case
    ;; the inner let bindings can be GC'ed. this is premature optimization(?)
    (let [first-input (if (string? input) (string->input-stream (str input \newline)) input)
          last-output (java.io.ByteArrayOutputStream.)
          last-error (java.io.ByteArrayOutputStream.)
          cmds (for [cmd-and-args cmd-and-args-list]
                 (command-line cmd-and-args))
          num-cmds (count cmds)
          nil-streams (into []
                            (for [_ (range num-cmds)]
                              [nil nil nil]))
          pipe-streams (loop [streams nil-streams
                              i 0]
                         (if (>= i (dec num-cmds))
                           streams
                           (let [pos (java.io.PipedOutputStream.)
                                 pis (java.io.PipedInputStream. pos)
                                 new-streams (-> streams
                                                 (assoc-in [i 0] pos)
                                                 (assoc-in [(inc i) 2] pis))]
                             (recur new-streams (inc i)))))
          all-streams (-> pipe-streams
                          (assoc-in [0 2] first-input)
                          (assoc-in [(dec num-cmds) 0] last-output)
                          (assoc-in [(dec num-cmds) 1] last-error))
          execs (for [cmd-streams all-streams]
                  (doto (DefaultExecutor.) (.setStreamHandler (apply #(PumpStreamHandler. %1 %2 %3) cmd-streams))))
          butlast-thread-fns (map (fn [exec cmd] (fn [] (.execute exec cmd))) (butlast execs) (butlast cmds))
          last-thread-fn (fn [] (deliver result
                                        (future (do
                                                  (let [exit-code (.execute (last execs) (last cmds))]
                                                    {:exit exit-code
                                                     :out (.toString last-output)
                                                     :err (.toString last-error)})))))
          all-thread-fns (concat butlast-thread-fns [last-thread-fn])
          threads (for [tfn all-thread-fns]
                    (Thread. tfn))
          ]
      (doseq [t threads]
        (.start t)))
    @result))

(defn sh-pipe [& args-and-opts]
  (let [[cmds-list [opts]] (parse-args-pipe args-and-opts)
        in  (when-let [i (:in opts)]
              (if (string? i) (string->input-stream i (:encode opts)) i))
        out (or (:out opts) (ByteArrayOutputStream.))
        err (or (:err opts) (ByteArrayOutputStream.))
        num-cmds (count cmds-list)
        nil-streams (into []
                          (for [_ (range num-cmds)]
                            [nil nil nil]))
        pipe-streams (loop [streams nil-streams
                            i 0]
                       (if (>= i (dec num-cmds))
                         streams
                         (let [pos (java.io.PipedOutputStream.)
                               pis (java.io.PipedInputStream. pos)
                               new-streams (-> streams
                                               (assoc-in [i 0] pos)
                                               (assoc-in [(inc i) 2] pis))]
                           (recur new-streams (inc i)))))
        all-streams (-> pipe-streams
                        (assoc-in [0 2] in)
                        (assoc-in [(dec num-cmds) 0] out)
                        (assoc-in [(dec num-cmds) 1] err))
        exec-fn (fn [cmd-and-args [cmd-out cmd-err cmd-in]]
                  (let [new-opts (-> opts
                                     (assoc :out cmd-out)
                                     (assoc :err cmd-err)
                                     (assoc :in cmd-in))
                        sh-fn-args (concat cmd-and-args [new-opts])]
                    (apply sh sh-fn-args)))
        ]
    (last (doall
           (map exec-fn cmds-list all-streams)))
    ))