var child     = require('child_process'),
    path      = require('path'),
    procmon   = require('process-monitor'),
    ansiHTML  = require('ansi-html');

/**
 * The Node Site class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {Develry.SiteDispatcher}   siteDispatcher
 * @param    {Object}                   record
 */
var Site = Function.inherits('Develry.Site', function NodeSite(siteDispatcher, record) {

	// The running processes
	this.processes = {};

	// The amount of running processes
	this.running = 0;

	NodeSite.super.call(this, siteDispatcher, record);
});

/**
 * Add the site type fields
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.1.0
 * @version  0.1.0
 */
Site.constitute(function addFields() {
	this.schema.addField('script', 'String');
});

/**
 * Start a new process
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.2.0
 *
 * @param    {Function}   callback
 */
Site.setMethod(function start(callback) {

	var that = this;

	// Get the port
	this.parent.getPort(this, function gotPort(err, port) {

		if (err) {
			return callback(err);
		}

		that.startOnPort(port, callback);
	});
});

/**
 * Start a new process on the specified port
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.2.0
 *
 * @param    {Function}   callback
 */
Site.setMethod(function startOnPort(port, callback) {

	var that = this,
	    processStats,
	    process,
	    port;

	log.info('Starting node script', this.settings.script, 'on port', port);

	// Start the server
	process = child.fork(this.settings.script, ['--port=' + port, 'hohenchild'], {cwd: this.cwd, silent: true});

	process.proclog_id = null;
	process.procarray = [];

	// Get the child process' output
	process.stdout.on('data', function onData(data) {

		if (alchemy.settings.debug) {
			console.log('[SITE ' + that._record.name + '] ' + data);
		}

		Function.series(function getId(next) {
			if (process.proclog_id) {
				return next();
			}

			that.Proclog.save({
				site_id: that.id,
				log: []
			}, {document: false}, function saved(err, data) {

				if (err) {
					return next(err);
				}

				process.proclog_id = data[0]._id;
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

		if (message.alchemy) {
			if (message.alchemy.ready) {

				// Add this to the process object
				process.ready = true;

				// Execute the callback
				if (callback) callback();

				// Remove the event listener
				process.removeListener('message', listenForReady);
			} else if (!process.ready && message.alchemy.error && message.alchemy.error.code == 'EADDRINUSE') {
				// Try again if the port is already in use
				that.start(callback);
			}
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
		log.warn('Site', JSON.stringify(this.name), 'process id', process.pid, 'is using', process.cpu, '% cpu and', process.mem, 'MiB memory');
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

	log.warn('Process', process.pid, 'for site', this.name, 'has exited with code', code, 'and signal', signal);
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

	fnc = function addressCreator() {

		var pid,
		    url;

		// @todo: do some load balancing
		for (pid in that.processes) {
			url = 'http://' + that.redirectHost + ':' + that.processes[pid].port;

			return callback(url);
		}
	};

	if (!this.running) {
		this.start(fnc);
	} else {
		fnc();
	}
});
