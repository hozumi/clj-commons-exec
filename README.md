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
* **:out** *(OutputStream)* is used as the sub-process's stdout.
* **:err** *(OutputStream)* is used as the sub-process's stderror.
* **:in** *(String or InputStream)* is fed to the sub-process's stdin.
* **:close-out?** *(boolean)* close or not a stream of stdout when sub-process complete. Default is false.
* **:close-err?** *(boolean)*
* **:close-in?** *(boolean)*
* **:flush-input?** *(boolean)* flush or not input stream.
* **:handle-quoting?** *(boolean)* Add the argument with/without handling quoting.
* **:watchdog** (*int* or instance of *ExecuteWatchdog*) set watchdog timer in ms.
* **:env** *(Map)* The environment for the new process. If null, the environment of the current process is used.
* **:add-env** *(Map)* The added environment for the new process.
* **:shutdown** *(boolean)* destroys sub-processes when the VM exits.
* **:as-success** *(int)* is regarded as sucess exit value.
* **:as-successes** *(sequence)* are regarded as sucess exit values.
* **:result-handler-fn** *(function)* A function, which will be called with promise, in, out, err stream and option map, returns an instance which implements org.apache.commons.exec.ExecuteResultHandler.

If you want to have multiple processes piped to each other, you can use **sh-pipe**. Syntax is like sh :
```clojure
(exec/sh-pipe ["cat"] ["wc"] {:in "hello world"})
;=> (#<Promise@11ba3c1f: {:exit 0, :out nil, :err nil}>
;    #<Promise@59c12050: {:exit 0, :out "       0       2      11\n", :err nil}>)
```

When piping commands using sh-pipe, the first command that does not exit successfully will additionally have the :exception key (and corresponding Exception) delivered in its result map:
```clojure
(exec/sh-pipe ["cat" "abc.txt"] ["wc"])
;=> (#<Promise@563da1dc:
;     {:exit 1, :out nil,
;      :err "cat: abc.txt: No such file or directory\n",
;      :exception #<ExecuteException org.apache.commons.exec.ExecuteException:
;                   Process exited with an error: 1 (Exit value: 1)>}>
;    #<Promise@7bff88c3:
;     {:exit 0, :out "       0       0       0\n", :err nil}>)
```
Note: Commands that are waiting for piped input downstream of any such error might receive an empty stream.  Most likely, they will finish execution, but their output will be logically incorrect.

## Installation
Leiningen [org.clojars.hozumi/clj-commons-exec "1.2.0"]

## License

Copyright (C) 2011 FIXME

Distributed under the Eclipse Public License, the same as Clojure.
