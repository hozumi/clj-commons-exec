(ns clj-commons-exec.test.core
  (:require [clj-commons-exec :as exec])
  (:use [clojure.test])
  (:import [org.apache.commons.exec ExecuteWatchdog]))

(deftest test-sh
  (is (= @(exec/sh ["cat"] {:in "hello world"})
         {:exit 0, :out "hello world", :err nil}))
  (is (= @(exec/sh ["echo" "hello world"])
         {:exit 0, :out "hello world\n", :err nil}))
  (is (= @(exec/sh ["echo" "hello world"] {:handle-quoting? true})
         {:exit 0, :out "\"hello world\"\n", :err nil}))
  (let [{:keys [exit exception]} @(exec/sh ["sleep" "1"] {:watchdog 100})]
    (is (= 143 exit))
    (is exception)))

(deftest test-sh-with-explicit-watchdog-and-termination
  (let [watchdog (ExecuteWatchdog. ExecuteWatchdog/INFINITE_TIMEOUT)
        promise-exec (exec/sh ["sleep" "2"] {:watchdog watchdog})] 
    (is (not (realized? promise-exec)))
    (is (.isWatching watchdog))
    (.destroyProcess watchdog)
    (let [{:keys [exit exception]} @promise-exec]
      (is (= 143 exit))
      (is exception))))

(deftest test-sh-pipe
  (is (= (map deref (exec/sh-pipe ["echo" "hello world"] ["cat"]))
         [{:exit 0, :out nil, :err nil}
          {:exit 0, :out "hello world\n", :err nil}])))
