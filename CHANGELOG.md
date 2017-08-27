## 0.2.0 (WIP)

* Add Letsencrypt support using the `greenlock` module
* Added websocket proxy support
* Ports are tested before a node process starts
* Should a port still be in use when a new process starts hohenheim will try a new one
* Add `posix` module, so we can increase the filelimit
* Enable keep-alive
* Each configured site domain now **requires** you to assign an available ip address
* You can now define the uid a node site runs as
* Fix `Site#matches` so it also searches through defined `hostname`s

## 0.1.0

* Convert old codebase to new alchemy 0.3.x
