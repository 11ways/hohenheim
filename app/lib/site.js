/**
 * The Site class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {Develry.SiteDispatcher}   siteDispatcher
 * @param    {Object}                           record
 */
var Site = Function.inherits('Informer', 'Develry', function Site(siteDispatcher, record) {

	// The parent site dispatcher
	this.parent = siteDispatcher;

	// The id in the database
	this.id = record._id;

	// The running processes
	this.processes = {};

	// The amount of running processes
	this.running = 0;

	// The incoming bytes
	this.incoming = 0;

	// The outgoing bytes
	this.outgoing = 0;

	// Counters per path
	this.pathCounters = {};

	// The redirecthost
	this.redirectHost = siteDispatcher.redirectHost;

	// The request log model
	this.Log = Model.get('Request');

	// The ProcLog
	this.Proclog = Model.get('Proclog');

	this.update(record);
});

/**
 * Start a new process
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {Function}   callback
 */
Site.setMethod(function start(callback) {

	var that = this,
	    processStats,
	    process,
	    port;

	// Get an open port number
	port = this.parent.getPort(this);

	// Start the server
	process = child.fork(this.script, ['--port=' + port, 'hohenchild'], {cwd: this.cwd, silent: true});

	process.proclog_id = null;
	process.procarray = [];

	// Get the child process' output
	process.stdout.on('data', function onData(data) {

		Function.series(function getId(next) {
			if (process.proclog_id) {
				return next();
			}

			that.Proclog.save({
				site_id: that.id,
				log: []
			}, function saved(err, data) {

				if (err) {
					return next(err);
				}

				process.proclog_id = data[0].item._id;
				next();
			});
		}, function done(err) {

			var str;

			if (err) {
				log.error('Error saving proclog', {err: err});
				return;
			}

			str = data.toString();
			process.procarray.push({time: Date.now(), html: ansiHTML(str)});

			that.Proclog.save({
				_id: process.proclog_id,
				log: process.procarray
			});
		});
	});

	// Store the port it should be running on
	process.port = port;

	// Store the time this was started
	process.startTime = Date.now();

	this.processes[process.pid] = process;

	this.running++;

	// Handle cpu & memory information from the process
	processStats = function processStats(stats) {
		that.processStats(process, stats.cpu, stats.mem);
	};

	// Attach process monitor
	process.monitor = procmon.monitor({
		pid: process.pid,
		interval: 6000,
		technique: 'proc'
	}).start();

	// Listen for process information
	process.monitor.on('stats', processStats);

	// Listen for exit events
	process.on('exit', function(code, signal) {

		// Clean up the process
		that.processExit(process, code, signal);

		// Stop the process monitor
		process.monitor.stop();

		// Delete the monitor from the process
		delete process.monitor;
	});

	// Listen for the message that tells us the server is ready
	process.on('message', function listenForReady(message) {

		if (typeof message !== 'object') {
			return;
		}

		if (message.alchemy && message.alchemy.ready) {

			// Add this to the process object
			process.ready = true;

			// Execute the callback
			if (callback) callback();

			// Remove the event listener
			process.removeListener('message', listenForReady);
		}
	});
});

/**
 * Handle child process cpu & memory information
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {ChildProcess}   process
 * @param    {Number}         cpu       Cpu usage in percentage
 * @param    {Number}         mem       Memory usage in kilobytes
 */
Site.setMethod(function processStats(process, cpu, mem) {

	process.cpu = ~~cpu;
	process.mem = ~~(mem/1024);

	if (cpu > 50) {
		pr('Site "' + this.name.bold + '" process id ' + process.pid + ' is using ' + process.cpu + '% cpu and ' + process.mem + ' MiB memory');
	}
});

/**
 * Handle child process exits
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {ChildProcess}   process
 * @param    {Number}         code
 * @param    {String}         signal
 */
Site.setMethod(function processExit(process, code, signal) {

	// Tell the parent this port is free again
	this.parent.freePort(process.port);

	// Decrease the running counter
	this.running--;

	// Remove the process from the processes object
	delete this.processes[process.pid];

	log.warn('Process ' + String(process.pid).bold + ' for site ' + this.name.bold + ' has exited with code ' + String(code).bold + ' and signal ' + String(signal).bold);
});

/**
 * Get an adress to proxy to
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {Function}   callback
 */
Site.setMethod(function getAddress(callback) {

	var that = this,
	    fnc;

	if (this.url) {
		return callback(this.url);
	}

	fnc = function addressCreator() {
		var pid;

		// @todo: do some load balancing
		for (pid in that.processes) {
			return callback('http://' + that.redirectHost + ':' + that.processes[pid].port);
		}
	};

	if (!this.running) {
		this.start(fnc);
	} else {
		fnc();
	}
});

/**
 * Remove this site completely
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 */
Site.setMethod(function remove() {
	this.cleanParent();
});

/**
 * Remove this site from the parent entries
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 */
Site.setMethod(function cleanParent() {

	var domain,
	    name;

	delete this.parent.ids[this.id];

	// Remove this instance from the parent's domains
	for (domain in this.parent.domains) {
		if (this.parent.domains[domain] == this) {
			delete this.parent.domains[domain];
		}
	}

	// Remove this instance from the parent's names
	for (name in this.parent.names) {
		if (this.parent.names[name] == this) {
			delete this.parent.names[name];
		}
	}
});

/**
 * Update this site,
 * recreate the entries in the parent dispatcher
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.0.1
 *
 * @param    {Object}   record
 */
Site.setMethod(function update(record) {

	var that = this;

	// The db record itself
	this._record = record;

	this.name = record.name;
	this.domains = record.domain || [];
	this.script = record.script;

	if (this.script) {
		this.cwd = path.dirname(this.script);
	}

	// We can also proxy to an existing url (apache sites)
	this.url = record.url;

	// Remove this instance from the parent
	this.remove();

	// Add by id
	this.parent.ids[this.id] = this;

	// Add by domains
	this.domains.filter(function(domain) {
		that.parent.domains[domain] = that;
	});

	// Re-add the instance by name
	this.parent.names[this.name] = this;
});

/**
 * This site has been hit,
 * register some metrics
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 * 
 * @param    {IncommingMessage}   req
 * @param    {ServerResponse}     res
 * @param    {Function}           callback
 */
Site.setMethod(function registerHit(req, res, callback) {

	var that = this,
	    bytesPrevRead,
	    remoteAddress,
	    bytesRead,
	    fullPath,
	    path,
	    read;

	fullPath = req.url;

	// Get the wanted path
	path = fullPath.split('?')[0];

	// Get the previous amount of bytes read on this socket
	bytesPrevRead = req.socket.prevRead || 0;
	bytesRead = req.socket.bytesRead;

	// The total amount of bytes read for this request
	read = bytesRead - bytesPrevRead;

	// Set the new previous read amount of bytes
	req.socket.prevRead = req.socket.bytesRead;

	// Get the remote address
	remoteAddress = req.socket.remoteAddress;

	res.on('finish', function finalizeHitRegister() {

		var bytesPrevWritten = req.socket.prevWritten || 0,
		    bytesWritten = req.socket.bytesWritten,
		    sent = bytesWritten - bytesPrevWritten;

		that.incoming += read;
		that.outgoing += sent;

		if (typeof that.pathCounters[path] === 'undefined') {
			that.pathCounters[path] = {
				incoming: 0,
				outgoing: 0
			};
		}

		that.pathCounters[path].incoming += read;
		that.pathCounters[path].outgoing += sent;

		// Set the new written amount
		req.socket.prevWritten = bytesWritten;

		that.Log.registerHit({
			site_id: that.id,
			host: req.headers.host,
			path: fullPath,
			status: res.statusCode,
			request_size: read,
			response_size: sent,
			referer: req.headers.referer,
			user_agent: req.headers['user-agent'],
			remote_address: remoteAddress,
			duration: Date.now() - req.startTime
		});

		pr(that.name.bold + ' has now received ' + ~~(that.incoming/1024) + ' KiBs and submitted ' + ~~(that.outgoing/1024) + ' KiBs');
	});
});