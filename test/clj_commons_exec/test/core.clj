(ns clj-commons-exec.test.core
  (:require [clj-commons-exec :as exec])
  (:use [clojure.test]))

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

(deftest test-sh-pipe
  (is (= (map deref (exec/sh-pipe ["echo" "hello world"] ["cat"]))
         [{:exit 0, :out nil, :err nil}
          {:exit 0, :out "hello world\n", :err nil}])))