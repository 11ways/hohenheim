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
	this.process_list = []

	// The amount of running processes
	this.running = 0;

	// The amount of processes ready
	this.ready = 0;

	NodeSite.super.call(this, siteDispatcher, record);
});

/**
 * Add the site type fields
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.1.0
 * @version  0.2.0
 */
Site.constitute(function addFields() {

	// The script to run
	this.schema.addField('script', 'String');

	// The user to run the script as
	this.schema.addField('user', 'Enum', {values: alchemy.shared('local_users')});
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

	if (!callback) {
		callback = Function.thrower;
	}

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
	    config,
	    args,
	    port;

	log.info('Starting node script', this.settings.script, 'on port', port);

	if (!this.cwd) {
		return callback(new Error('Working directory is not set, can not start script!'));
	}

	args = [
		'--port=' + port,
		'hohenchild'
	];

	config = {
		cwd    : this.cwd,
		silent : true
	};

	if (this.settings.user) {
		log.info(' - Starting as uid', this.settings.user);

		config.uid = this.settings.user;
		config.gid = this.settings.user;
	}

	// Start the server
	process = child.fork(this.settings.script, args, config);

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

	// When overload started
	process.startOverload = 0;

	this.processes[process.pid] = process;

	this.running++;

	// Handle cpu & memory information from the process
	processStats = function processStats(stats) {
		that.processStats(process, stats.cpu, stats.mem);
	};

	// Attach process monitor
	process.monitor = procmon.monitor({
		pid: process.pid,
		interval: 4000,
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

				// Up the ready counter
				that.ready++;

				that.process_list.push(process);

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
		let now = Date.now();
		process.startIdle = 0;

		if (!process.startOverload) {
			process.startOverload = now;
		} else if (now - process.startOverload > 15000) {
			// The process is in overload for over 15 seconds, start a new one?
			if (this.running < 5) {
				log.warn('Starting new', this.name, 'process because others are too busy');
				this.start();

				// Reset the overload timer, so we don't start another one on the next stat
				process.startOverload = 0;
			}
		}
	} else {
		process.startOverload = 0;

		if (cpu == 0) {
			let now = Date.now();

			if (!process.startIdle) {
				process.startIdle = now;
			} else if (now - process.startIdle > 180000 && this.process_list.length > 1) {
				process.kill();
			}
		} else {
			process.startIdle = 0;
		}
	}

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
	this.ready--;

	if (this.ready == 0) {
		this.initial_hinder = null;
	}

	// Remove the process from the processes object
	delete this.processes[process.pid];

	let index = this.process_list.indexOf(process);

	if (index > -1) {
		this.process_list.splice(index, 1);
	}

	log.warn('Process', process.pid, 'for site', this.name, 'has exited with code', code, 'and signal', signal);
});

/**
 * Get an adress to proxy to
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.2.0
 *
 * @param    {Function}   callback
 */
Site.setMethod(function getAddress(callback) {

	var that = this,
	    fnc;

	fnc = function addressCreator() {

		var site_process,
		    url,
		    i;

		if (!that.process_list.length) {
			return callback(new Error('Failed to start node site process'));
		}

		// Shuffle the process list
		if (that.process_list.length > 1) {
			that.process_list.shuffle();

			for (i = 0; i < that.process_list.length; i++) {
				site_process = that.process_list[i];

				if (site_process.cpu > 95) {
					continue;
				} else {
					break;
				}
			}
		} else {
			site_process = that.process_list[0];
		}

		url = 'http://' + that.redirectHost + ':' + site_process.port;
		return callback(null, url);
	};

	if (!this.ready) {
		if (this.initial_hinder) {
			this.initial_hinder.push(fnc);
		} else {
			this.initial_hinder = Function.hinder(function startFirstProcess(done) {
				that.start(done);
			});

			this.initial_hinder.push(fnc);
		}
	} else {
		fnc();
	}
});
