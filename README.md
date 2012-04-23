# clj-commons-exec

[Apache Commons Exec](http://commons.apache.org/exec/) wrapper for Clojure

## Usage
```clojure
(require '[clj-commons-exec :as exec])

(exec/sh "echo" "hello")   ; A promise is returned immediately.
;=> #<core$promise$reify__5727@12fa6824: :pending>

@(exec/sh "echo" "hello")  ; To get a result, deref it.
;=> {:exit 0, :out "hello\n", :err nil}

(exec/sh "ls" "-l" {:dir "/"}) ; Last argument can be recognized as an option map.
```

options

* **:dir** *(String or java.io.File)* override the process dir.
* **:in** *(String or InputStream)* is fed to the sub-process's stdin.
* **:out** *(OutputStream)* is used as the sub-process's stdout.
* **:flush-input?** *(boolean)* flush or not input stream.
* **:err** *(OutputStream)* is used as the sub-process's stderror.
* **:watchdog** *(int)* set watchdog timer in ms.
* **:env** *(Map)* The environment for the new process. If null, the environment of the current process is used.
* **:add-env** *(Map)* The added environment for the new process.
* **:shutdown** *(boolean)* destroys sub-processes when the VM exits.
* **:as-success** *(int)* is regarded as sucess exit value.
* **:as-successes** *(sequence)* are regarded as sucess exit values.
* **:result-handler-fn** *(function)* A function, which will be called with promiss, in, out, err and option map, returns an instance which implements org.apache.commons.exec.ExecuteResultHandler. You have to close in, out, and err stream when sub-process is finished.

If you want to have multiple processes piped to each other and/or custom input (stream or a string), you can try using **run** :

```clojure
(exec/run [["ls" "-lart" "/etc"]])
;=> #<core$future_call$reify__5684@b47a87e: :pending>
(def result (exec/run [["ls" "-lart" "/usr"]]))
(future-done? result)
;=> true
@result
{:exit 0, :out "total 244\ndrwxr-xr-x  10 root root  4096 2011-10-12 07:26 local\ndrwxr-xr-x  11 root root  4096 2011-10-28 17:52 .\ndrwxr-xr-x   2 root root  4096 2012-02-16 11:44 games\ndrwxr-xr-x  41 root root 20480 2012-04-07 19:23 include\ndrwxr-xr-x 379 root root 12288 2012-04-07 19:25 share\ndrwxr-xr-x  11 root root  4096 2012-04-12 02:53 src\ndrwxr-xr-x  26 root root  4096 2012-04-12 02:53 ..\ndrwxr-xr-x  37 root root 36864 2012-04-12 04:06 lib32\ndrwxr-xr-x   2 root root 12288 2012-04-16 09:01 sbin\ndrwxr-xr-x 267 root root 65536 2012-04-18 20:59 lib\ndrwxr-xr-x   2 root root 69632 2012-04-18 21:01 bin\n", :err ""}
@(exec/run [["ls" "-lart" "/usr"] ["wc"]])
;=> {:exit 0, :out "     12      90     592\n", :err ""}
@(exec/run [["ls" "-lart" "/usr"] ["wc"] ["wc"]])
;=> {:exit 0, :out "      1       3      24\n", :err ""}
(def s "the quick brown fox\njumps\nover\nthe lazy dog")
@(exec/run [["wc"]] s)
;=> {:exit 0, :out "      4       9      44\n", :err ""}

```
Note: currently, when using **run**, you will need to handler your own watchdog timeout functionality and catch any Exceptions generated from any problems in your commands or execution. Support for an execution options map like in **sh** is not currently implemented.
## Installation
Leiningen [org.clojars.hozumi/clj-commons-exec "1.0.0-SNAPSHOT"]

## License

Copyright (C) 2011 FIXME

Distributed under the Eclipse Public License, the same as Clojure.
