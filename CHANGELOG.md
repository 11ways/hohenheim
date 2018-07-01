## 0.3.0 (WIP)

* Also set the node.js port to use on the child process PORT env variable
* Regular `Node` sites don't need to send a "ready" message anymore
* Added `Alchemy` site, which inherits from `Node`
* Log access to file (instead of database) at `/var/log/hohenheim/access.log`
* Log the URL when giving up on a request
* Node sites now have a `minimum_processes` and `maximum_processes` field
* Access Alchemy sites' Janeway interface thanks to xterm.js

## 0.2.0 (2017-09-01)

* Add Letsencrypt support using the `greenlock` module
* Added websocket proxy support
* Ports are tested before a node process starts
* Should a port still be in use when a new process starts hohenheim will try a new one
* Add `posix` module, so we can increase the filelimit
* Enable keep-alive
* Each configured site domain now **requires** you to assign an available ip address
* You can now define the uid a node site runs as
* Fix `Site#matches` so it also searches through defined `hostname`s
* `Site#getAddress` will callback with an error when it fails to start
* The callback provided to `Site#getAddress` will now accept error objects
* Fix `NodeSite` cwd not being set properly
* Fix `NodeSite#ready` going under 0, which made it stop creating a new process
* You can now set which node version a `NodeSite` uses (defaults to `which node`, no more forking)

## 0.1.0

* Convert old codebase to new alchemy 0.3.x
