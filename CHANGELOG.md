## 0.4.1 (WIP)

* Change the umask to 2 to child processes will leave created files & sockets readable by others in their group
* Allow unsetting headers
* Add support for socket files to the `Proxy` site type
* Domain names can now contain named capture groups, which can be used in the socket file
* The `HOME` environment variable will be unset if a node site runs as a different UID
* Add `fallback_file` option to static site settings
* Fix issues with the Sitestat element in Chimera

## 0.4.0 (2020-07-21)

* Upgrade to Alchemy v1.1.0
* Use socketfiles for node.js sites by default
* Add sticky routing (always route same client to the same instance)
* Node sites can now be "isolated", meaning they will only serve already seen clients
* Add a `handleRequest` method to the Site class
* Allow broadcasting messages to multiple node instances
* Upgrade to the latest Greenlock version (big changes for the Letsencrypt API)
* Remove spdy in favor of internal http2 module
* Switch to http2-proxy, because http-proxy is not compatible with http2
* Upgrade to Greenlock v4

## 0.3.4 (2019-06-28)

* Add **Redirect** site class
* Allow using wildcards (* or ?) in domains

## 0.3.3 (2019-06-19)

* Greenlock requires "ursa" on node versions lower than 10.

## 0.3.2 (2019-06-19)

* Add `X-Forwarded-Host` header
* Rewrite `location` responses coming from proxied servers
* Throttle restarting node sites when they crash too often
* Add `Static` site class, which uses `ecstatic` to serve static files
* Update node versions, users & ips every hour
* Some small fixes
* Dependency (security) updates
* Attempt to work around `spdy`'s timeout issues

## 0.3.1 (2018-10-18)

* Upgrade to alchemy 1.0.5
* Dependency updates
* The `Proclog` log array is limited to 500 lines
* Throttle saving the `Proclog` record

## 0.3.0 (2018-07-07)

* Also set the node.js port to use on the child process PORT env variable
* Regular `Node` sites don't need to send a "ready" message anymore
* Added `Alchemy` site, which inherits from `Node`
* Log access to file (instead of database) at `/var/log/hohenheim/access.log`
* Log the URL when giving up on a request
* Node sites now have a `minimum_processes` and `maximum_processes` field
* Access Alchemy sites' Janeway interface thanks to xterm.js
* Upgrade alchemy to v1.0.0
* Add vhost to access log
* Add http/2 support using the `spdy` module
* Add websocket support for http-only proxies
* Add `Domain` model, needed for future wildcard support
* `Site#registerHit` will now correctly see when a response ends, even with keep-alive enabled
* Append to the `x-forwarded-for` header in case it's already set
* Allow sites to force https mode, if it's not forced for the entire server

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
