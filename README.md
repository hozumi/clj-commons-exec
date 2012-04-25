# clj-commons-exec

[Apache Commons Exec](http://commons.apache.org/exec/) wrapper for Clojure

## Usage

```clojure
(require '[clj-commons-exec :as exec])

(exec/sh ["echo" "hello"])   ; A promise is returned immediately.
;=> #<core$promise$reify__5727@12fa6824: :pending>

@(exec/sh ["echo" "hello"])  ; To get a result, deref it.
;=> {:exit 0, :out "hello\n", :err nil}

(exec/sh ["ls" "-l"] {:dir "/"}) ; Second argument is option map.
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
* **:result-handler-fn** *(function)* A function, which will be called with promise, in, out, err stream and option map, returns an instance which implements org.apache.commons.exec.ExecuteResultHandler. You have to close in, out, and err stream when sub-process is finished.

If you want to have multiple processes piped to each other, you can use **sh-pipe**. Syntax is like sh :

```clojure
(-> (exec/sh-pipe ["ls" "-lart" "/usr"]) last deref :out println)
; total 244
; drwxr-xr-x  10 root root  4096 2011-10-12 07:26 local
; drwxr-xr-x  11 root root  4096 2011-10-28 17:52 .
; drwxr-xr-x   2 root root  4096 2012-02-16 11:44 games
; drwxr-xr-x  41 root root 20480 2012-04-07 19:23 include
; drwxr-xr-x 379 root root 12288 2012-04-07 19:25 share
; drwxr-xr-x  11 root root  4096 2012-04-12 02:53 src
; drwxr-xr-x  26 root root  4096 2012-04-12 02:53 ..
; drwxr-xr-x  37 root root 36864 2012-04-12 04:06 lib32
; drwxr-xr-x   2 root root 12288 2012-04-16 09:01 sbin
; drwxr-xr-x 267 root root 65536 2012-04-18 20:59 lib
; drwxr-xr-x   2 root root 69632 2012-04-18 21:01 bin
; 
;=> nil
@(last (exec/sh-pipe ["ls" "-lart" "/usr"] ["wc"]))
;=> {:exit 0, :out "     12      90     592\n", :err ""}
@(last (exec/sh-pipe ["ls" "-lart" "/usr"] ["wc"] ["wc"]))
;=> {:exit 0, :out "      1       3      24\n", :err ""}
(def s "the quick brown fox\njumps\nover\nthe lazy dog")
@(last (exec/sh-pipe ["wc"] {:in s}))
;=> {:exit 0, :out "      4       9      44\n", :err ""}
```

When piping commands using sh-pipe, the first command that does not exit successfully will additionally have the :exception key (and corresponding Exception) delivered in its result map:
```clojure
@(first (exec/sh-pipe ["sleep" "6"] {:watchdog 4000}))
;=> {:exit 143, :out nil, :err nil, :exception #<ExecuteException org.apache.commons.exec.ExecuteException: Process exited with an error: 143 (Exit value: 143)>}
@(first (exec/sh-pipe ["ls" "-blerg"] ["wc"] ["wc"]))
;=> {:exit 2, :out nil, :err "ls: invalid option -- 'e'\nTry `ls --help' for more information.\n", :exception #<ExecuteException org.apache.commons.exec.ExecuteException: Process exited with an error: 2 (Exit value: 2)>}
```
Note: Commands that are waiting for piped input downstream of any such error might receive an empty stream.  Most likely, they will finish execution, but their output will be logically incorrect.
```clojure
@(nth (exec/sh-pipe ["ls" "-blerg"] ["wc"] ["wc"]) 1)
;=> {:exit 0, :out nil, :err nil}
@(last (exec/sh-pipe ["ls" "-blerg"] ["wc"] ["wc"]))
;=> {:exit 0, :out "      1       3      24\n", :err nil}
```

## Installation
Leiningen [org.clojars.hozumi/clj-commons-exec "1.0.4"]

## License

Copyright (C) 2011 FIXME

Distributed under the Eclipse Public License, the same as Clojure.
