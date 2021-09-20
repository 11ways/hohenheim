var sitesById = alchemy.shared('Sites.byId'),
    child     = require('child_process'),
    libpath   = require('path'),
    procmon   = require('process-monitor'),
    ansiHTML  = require('ansi-html'),
    fs        = require('fs'),
    versions  = {};

/**
 * The Node Site class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.3.2
 *
 * @param    {Develry.SiteDispatcher}   siteDispatcher
 * @param    {Object}                   record
 */
var Site = Function.inherits('Develry.Site', function NodeSite(siteDispatcher, record) {

	// The running processes
	this.processes = {};
	this.process_list = [];

	// The last exits
	this.exit_log = [];

	// The amount of processes that will start
	this.requested = 0;

	// The amount of running processes
	this.running = 0;

	// The amount of processes ready
	this.ready = 0;

	// Call the parent constructor
	NodeSite.super.call(this, siteDispatcher, record);
});

/**
 * Add the site type fields
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.1.0
 * @version  0.4.0
 */
Site.constitute(function addFields() {

	// The script to run
	this.schema.addField('script', 'String');

	// The user to run the script as
	this.schema.addField('user', 'Enum', {values: alchemy.shared('local_users')});

	// The node version to use
	this.schema.addField('node', 'Enum', {values: versions});

	// Wait for the child to tell us it's ready?
	this.schema.addField('wait_for_ready', 'Boolean');

	// Minimum amount of processes?
	this.schema.addField('minimum_processes', 'Number');

	// Maximum amount of processes?
	this.schema.addField('maximum_processes', 'Number');

	// API keys for Hohenheim actions
	this.schema.addField('api_keys', 'String', {array: true});

	// Create new subschema for environment variables
	let env_schema = new Classes.Alchemy.Schema();

	// Set the env name
	env_schema.addField('name', 'String');

	// And the env value
	env_schema.addField('value', 'String');

	// Set process environment variables
	this.schema.addField('environment_variables', 'Schema', {schema: env_schema, array: true});
});

/**
 * Get available node versions
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.4.0
 */
Site.setStatic(function updateVersions(callback) {

	if (!callback) {
		callback = Function.thrower;
	}

	Function.parallel(function getOldVersions(next) {

		var conditions = {
			site_type : 'node_site'
		};

		Model.get('Site').find('all', {conditions}, function gotSites(err, records) {

			if (err) {
				return next();
			}

			records.forEach(function eachRecord(record) {

				if (!record.settings || !record.settings.node) {
					return;
				}

				if (!versions[record.settings.node]) {
					versions[record.settings.node] = {
						title   : 'removed [' + record.settings.node + ']',
						version : record.settings.node
					};
				}
			});

			next();
		});
	}, function getNVersions(next) {
		Site.loadInstalledVersions(next);
	}, function getSystemVersion(next) {

		child.exec('which node', function gotMainNode(err, stdout, stderr) {

			// No system node found
			if (err) {
				return next();
			}

			let bin_path = stdout.trim();

			if (!bin_path) {
				return next();
			}

			child.exec(bin_path + ' --version', function gotVersion(err, stdout) {

				if (err) {
					return next();
				}

				let version = stdout.trim();

				if (version[0] == 'v') {
					version = version.slice(1);
				}

				versions.system = {
					title   : 'system [currently v' + version + ']',
					version : version,
					bin     : bin_path
				};

				next();
			});
		});
	}, function getMainBin(next) {

		let bin_path = '/usr/bin/node';

		child.exec(bin_path + ' --version', function gotVersion(err, stdout) {

			// No main bin version
			if (err) {
				return next();
			}

			let version = stdout.trim();

			if (version[0] == 'v') {
				version = version.slice(1);
			}

			versions.main_bin = {
				title   : bin_path + ' [currently v' + version + ']',
				version : version,
				bin     : bin_path
			};

			next();
		});
	}, function getLocalBin(next) {

		let bin_path = '/usr/local/bin/node';

		child.exec(bin_path + ' --version', function gotVersion(err, stdout) {

			// No main bin version
			if (err) {
				return next();
			}

			let version = stdout.trim();

			if (version[0] == 'v') {
				version = version.slice(1);
			}

			versions.local_bin = {
				title   : bin_path + ' [currently v' + version + ']',
				version : version,
				bin     : bin_path
			};

			next();
		});
	}, function getHohenheimNode(next) {

		let bin_path = process.execPath;

		child.exec(bin_path + ' --version', function gotVersion(err, stdout) {

			// No main bin version
			if (err) {
				return next();
			}

			let version = stdout.trim();

			if (version[0] == 'v') {
				version = version.slice(1);
			}

			versions.hohenheim_bin = {
				title   : bin_path + ' - Hohenheim [currently v' + version + ']',
				version : version,
				bin     : bin_path
			};

			next();
		});
	}, function done(err) {

		if (err) {
			return callback(err);
		}

		callback(null, versions);
	});
});

/**
 * Look for available node.js versions
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.2
 * @version  0.3.2
 */
Site.setStatic(function loadInstalledVersions(callback) {

	var versions_path = '/usr/local/n/versions/node';

	fs.readdir(versions_path, function gotNVersions(err, contents) {

		// N is probably not used here
		if (err) {
			return callback();
		}

		let tasks = [];

		for (let i = 0; i < contents.length; i++) {
			let bin_path,
			    version;

			version = contents[i];
			bin_path = libpath.resolve(versions_path, version, 'bin/node');

			tasks.push(function checkFile(next) {

				fs.stat(bin_path, function gotStat(err, stats) {

					if (err) {
						return next();
					}

					versions[version] = {
						title   : 'v' + version,
						version : version,
						bin     : bin_path
					};

					next();
				});
			});
		}

		Function.parallel(tasks, function gotVersions(err) {
			callback();
		});
	});
});

/**
 * The number of running + requested sites
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.0
 * @version  0.3.0
 *
 * @type     {Number}
 */
Site.setProperty(function total_proc_count() {
	return this.requested + this.running;
});

/**
 * The number of active processes
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @type     {Number}
 */
Site.setProperty(function active_process_count() {

	var result = 0,
	    proc,
	    i;

	for (i = 0; i < this.process_list.length; i++) {
		proc = this.process_list[i];

		if (proc.isolated) {
			continue;
		}

		result++;
	}

	return result;
});

/**
 * The number of inactive (isolated) processes
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @type     {Number}
 */
Site.setProperty(function inactive_process_count() {

	var result = 0,
	    proc,
	    i;

	for (i = 0; i < this.process_list.length; i++) {
		proc = this.process_list[i];

		if (proc.isolated) {
			result++;
		}
	}

	return result;
});

/**
 * Update this site,
 * recreate the entries in the parent dispatcher
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.0
 * @version  0.3.0
 *
 * @param    {Object}   record
 */
Site.setMethod(function update(record) {

	// Call the parent method
	update.super.call(this, record);

	// Check if we need to start a server already
	this.startMinimumServers();
});

/**
 * Start a new process
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.4.0
 *
 * @param    {Function}   callback
 */
Site.setMethod(function start(callback) {

	if (!callback) {
		callback = Function.thrower;
	}

	if (this.use_ports) {
		this.startWithPorts(callback);
	} else {
		this.startWithSocket(callback);
	}
});

/**
 * Start on a port
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @param    {Function}   callback
 */
Site.setMethod(function startWithSocket(callback) {

	const that = this;

	// Increase the requested count
	// (Because the `running` count won't be incremented until `startOnPort`)
	this.requested++;

	// Get the port
	this.dispatcher.getSocketfile(this, function gotFile(err, path_to_socket) {

		// Decrease the requested count again
		that.requested--;

		if (err) {
			return callback(err);
		}

		that.startOnSocket(path_to_socket, callback);
	});
});

/**
 * Start on a port
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @param    {Function}   callback
 */
Site.setMethod(function startWithPorts(callback) {

	const that = this;

	// Increase the requested count
	// (Because the `running` count won't be incremented until `startOnPort`)
	this.requested++;

	// Get the port
	this.dispatcher.getPort(this, function gotPort(err, port) {

		// Decrease the requested count again
		that.requested--;

		if (err) {
			return callback(err);
		}

		that.startOnPort(port, callback);
	});
});

/**
 * Process stdout data
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.0
 * @version  0.3.1
 *
 * @param    {ChildProcess}   proc
 * @param    {Buffer}         data
 */
Site.setMethod(function onStdout(proc, data) {

	var that = this;

	if (alchemy.settings.debug) {
		console.log('[SITE ' + that._record.name + '] ' + data);
	}

	Function.series(function getId(next) {
		if (proc.proclog_id) {
			return next();
		}

		that.Proclog.save({
			site_id: that.id,
			log: []
		}, {document: false}, function saved(err, doc) {

			if (err) {
				return next(err);
			}

			proc.proclog_id = doc[0]._id;
			next();
		});
	}, function done(err) {

		var str;

		if (err) {
			log.error('Error saving proclog', {err: err});
			return;
		}

		// Limit the log to 500 lines
		if (proc.procarray.length > 500) {
			proc.procarray.shift();
		}

		str = ansiHTML(data.toString());

		proc.procarray.push({time: Date.now(), html: str});

		that.saveProclog(proc);
	});
});

/**
 * Bounced save of proclog record:
 * Only save once per 30 seconds
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.1
 * @version  0.3.1
 *
 * @param    {ChildProcess}   proc
 */
Site.setMethod(function saveProclog(proc) {

	// Dirty little hack: create a throttle function
	// the first time this method is called on this instance
	if (this.saveProclog === saveProclog) {
		this.saveProclog = Function.throttle(saveProclog, 1000 * 30);
		this.saveProclog(proc);
		return;
	}

	this.Proclog.save({
		_id: proc.proclog_id,
		log: proc.procarray
	});
});


/**
 * Start a new process on the specified socket file
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @param    {String}     path_to_socket
 * @param    {Function}   callback
 */
Site.setMethod(function startOnSocket(path_to_socket, callback) {
	this._startOnType('socket', path_to_socket, callback);
});

/**
 * Start a new process on the specified port
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.3.0
 *
 * @param    {Number}     port
 * @param    {Function}   callback
 */
Site.setMethod(function startOnPort(port, callback) {
	this._startOnType('port', port, callback);
});

/**
 * Start a new process on the specified port
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.3.0
 *
 * @param    {String}            type
 * @param    {Number|String}     value
 * @param    {Function}          callback
 */
Site.setMethod(function _startOnType(type, value, callback) {

	var that = this,
	    path_to_socket,
	    processStats,
	    node_config,
	    child_proc,
	    bin_path,
	    config,
	    args,
	    port,
	    key,
	    env;

	if (!this.settings.script) {
		return callback(new Error('No script has been set'));
	}

	if (type == 'port') {
		port = value;
		log.info('Starting node script', this.settings.script, 'on port', port);
	} else {
		path_to_socket = value;
		log.info('Starting node script', this.settings.script, 'on socket', path_to_socket);
	}

	if (!this.cwd) {
		return callback(new Error('Working directory is not set, can not start script!'));
	}

	if (this.settings.node) {
		node_config = versions[this.settings.node];

		if (!node_config || !node_config.bin) {

			if (node_config) {
				log.info(' -', 'Version', this.settings.node, 'is no longer available');
			}

			node_config = null;
		}
	}

	// If no node configuration was found,
	// then we use the system-wide node version
	if (!node_config) {
		node_config = versions.system;

		if (!node_config) {
			return callback(new Error('Not a single node version was found'));
		}

		log.info(' -', 'Falling back to using system node instance v' + node_config.version);
	}

	env = {};

	for (key in process.env) {
		env[key] = process.env[key];
	}

	if (this.settings.environment_variables && this.settings.environment_variables.length) {
		let entry,
		    i;

		for (i = 0; i < this.settings.environment_variables.length; i++) {
			entry = this.settings.environment_variables[i];
			env[entry.name] = entry.value;
		}
	}

	if (path_to_socket) {

		env.PORT = path_to_socket;
		env.PATH_TO_SOCKET = path_to_socket;

		args = [
			this.settings.script,
			'--port=' + path_to_socket,
			'hohenchild'
		];
	} else {
		env.PORT = port;

		args = [
			this.settings.script,
			'--port=' + port,
			'hohenchild'
		];
	}

	if (this.default_args) {
		let i;

		for (i = 0; i < this.default_args.length; i++) {
			args.push(this.default_args[i]);
		}
	}

	config = {
		cwd    : this.cwd,
		stdio  : ['pipe', 'pipe', 'pipe', 'ipc', 'pipe'],
		env    : env
	};

	if (this.settings.user) {
		log.info(' - Starting as uid', this.settings.user);

		config.uid = Number(this.settings.user);
		config.gid = Number(this.settings.user);

		if (config.uid) {
			// Unset HOME, or else os.homedir() will use the wrong path
			env.HOME = undefined;
		}
	}

	// Start the server
	child_proc = child.spawn(node_config.bin, args, config);

	child_proc.proclog_id = null;
	child_proc.procarray = [];

	// Get the child process' output
	child_proc.stdout.on('data', function onData(data) {
		that.onStdout(child_proc, data);
	});

	if (path_to_socket) {
		child_proc.path_to_socket = path_to_socket;
	} else {
		// Store the port it should be running on
		child_proc.port = port;
	}

	// Store the time this was started
	child_proc.startTime = Date.now();

	// When overload started
	child_proc.startOverload = 0;

	// Processes can be "isolated", meaning they no longer get new clients
	child_proc.isolated = false;

	// Add a cache instance for remembering fingerprints
	child_proc.fingerprints = new Blast.Classes.Develry.Cache({
		max_idle: '1 hour'
	});

	this.processes[child_proc.pid] = child_proc;

	this.running++;

	// Emit this new process
	this.emit('child', child_proc);

	// Handle cpu & memory information from the process
	processStats = function processStats(stats) {
		that.processStats(child_proc, stats.cpu, stats.mem);
	};

	// Attach process monitor
	child_proc.monitor = procmon.monitor({
		pid       : child_proc.pid,
		interval  : 4000,
		technique : 'proc'
	}).start();

	// Listen for process information
	child_proc.monitor.on('stats', processStats);

	// Listen for exit events
	child_proc.on('exit', function onChildExit(code, signal) {

		// Clean up the process
		that.processExit(child_proc, code, signal);

		// Stop the process monitor
		child_proc.monitor.stop();

		// Delete the monitor from the process
		delete child_proc.monitor;
	});

	// Only wait for the ready message when it has been enabled
	if (!this.settings.wait_for_ready) {

		child_proc.ready = true;
		that.ready++;
		that.process_list.push(child_proc);

		if (callback) {
			callback();
		}
	}

	let reciprocal = new Classes.Alchemy.Reciprocal(child_proc, 'hohenheim');

	child_proc.reciprocal = reciprocal;

	// Listen for messages from the child process
	reciprocal.on('message', function onMessage(message) {

		var data;

		if (typeof message !== 'object') {
			return;
		}

		data = message.hohenheim || message.alchemy;

		if (!data) {
			return;
		}

		if (data.ready && that.settings.wait_for_ready) {

			// If it's already ready, just ignore it
			if (child_proc.ready) {
				return;
			}

			// Add this to the process object
			child_proc.ready = true;

			// Up the ready counter
			that.ready++;

			that.process_list.push(child_proc);

			// Execute the callback
			if (callback) callback();

			// Remove the event listener
			child_proc.removeListener('message', onMessage);
		} else if (!child_proc.ready && data.error && data.error.code == 'EADDRINUSE') {
			// Try again if the port is already in use
			that.start(callback);
		}
	});

	// Listen for remcache requests
	reciprocal.on('remcache_set', function onRemcacheSet(data) {
		that.remcache.set(data.key, data.value, data.max_age);
	});

	reciprocal.on('remcache_get', function onRemcacheGet(data, callback) {
		let value = that.remcache.get(data.key);
		callback(null, value);
	});

	reciprocal.on('remcache_peek', function onRemcachePeek(data, callback) {
		let value = that.remcache.peek(data.key);
		callback(null, value);
	});

	reciprocal.on('remcache_remove', function onRemcacheRemove(data) {
		that.remcache.remove(data.key);
	});
});

/**
 * Handle child process cpu & memory information
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.4.0
 *
 * @param    {ChildProcess}   process
 * @param    {Number}         cpu       Cpu usage in percentage
 * @param    {Number}         mem       Memory usage in kilobytes
 */
Site.setMethod(function processStats(process, cpu, mem) {

	process.cpu = ~~cpu;
	process.mem = ~~(mem/1024);

	if (process.isolated) {

		if (process.fingerprints && process.fingerprints.length == 0) {
			process.kill();
		}

		return;
	}

	if (cpu > 50) {
		let now = Date.now();
		process.startIdle = 0;

		if (!process.startOverload) {
			process.startOverload = now;
		} else if (now - process.startOverload > 15000) {
			// The process is in overload for over 15 seconds, start a new one?
			if (this.running < 5) {

				if (this.settings.maximum_processes && this.settings.maximum_processes >= this.running) {
					// Do nothing, maximum number of processes reached
				} else {
					log.warn('Starting new', this.name, 'process because others are too busy');
					this.start();
				}

				// Reset the overload timer, so we don't start another one on the next stat
				process.startOverload = 0;
			}
		}
	} else {
		process.startOverload = 0;

		if (cpu == 0) {
			let now = Date.now();
			let min_proc = this.settings.minimum_processes || 1;

			if (!process.startIdle) {
				process.startIdle = now;
			} else if (now - process.startIdle > 180000 && this.active_process_count > min_proc) {
				// Kill this process, because we have at least 1
				// (or more then the minimum_processes) process running
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
 * @version  0.4.0
 *
 * @param    {ChildProcess}   process
 * @param    {Number}         code
 * @param    {String}         signal
 */
Site.setMethod(function processExit(process, code, signal) {

	var now = Date.now();

	// Remove items from the exit log
	if (this.exit_log.length > 20) {
		this.exit_log.shift();
	}

	// Add the current timestamp to the exit log
	this.exit_log.push(now);

	// Tell the parent this port is free again
	this.dispatcher.freePort(process.port);

	// Decrease the running counter
	this.running--;

	// If the process was ready, also decrease that
	if (process.ready) {
		this.ready--;
	}

	// Just a paranoid check to make sure nothing goes under 0
	if (this.ready < 0) {
		this.ready = 0;
	}

	if (this.running < 0) {
		this.running = 0;
	}

	if (this.ready == 0) {
		this.initial_hinder = null;
	}

	// Remove socket files
	if (process.path_to_socket) {
		fs.unlink(process.path_to_socket, Function.dummy);
	}

	// Remove the process from the processes object
	delete this.processes[process.pid];

	let index = this.process_list.indexOf(process);

	if (index > -1) {
		this.process_list.splice(index, 1);
	}

	log.warn('Process', process.pid, 'for site', this.name, 'has exited with code', code, 'and signal', signal);

	if (this.exit_log.length > 5) {
		let mean = Math.floor(Math.mean(this.exit_log)),
		    diff = now - mean;

		if (diff < 2500 * this.exit_log.length) {
			let that = this;

			log.warn('Waiting 3 seconds before trying to start', that.name, 'again');

			setTimeout(function tryAgain() {
				that.startMinimumServers();
			}, 3000);

			return;
		}
	}

	// Make sure the required minimum servers are running
	this.startMinimumServers();
});

/**
 * Get an adress to proxy to
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.4.0
 *
 * @param    {IncomingMessage}  req
 * @param    {Function}         callback
 * @param    {Number}           attempt
 */
Site.setMethod(function getAddress(req, callback, attempt) {

	var that = this,
	    fingerprint,
	    fnc,
	    ip;

	if (req) {
		if (req.headers) {
			ip = req.headers['x-forwarded-for'] || req.headers['x-real-ip'];
			fingerprint = (req.headers['user-agent'] || '') + (req.headers['accept-language'] || '');
		}

		if (!ip && req.connection) {
			ip = req.connection.remoteAddress;
		}

		if (!ip) {
			ip = '';
		}

		fingerprint = ip + fingerprint;
	}

	fnc = function addressCreator() {

		var found_fingerprinted = false,
		    site_process,
		    address,
		    i;

		// Shuffle the process list
		if (that.process_list.length > 1) {

			// If a fingerprint is given, look for it first
			if (fingerprint) {
				for (i = 0; i < that.process_list.length; i++) {
					site_process = that.process_list[i];

					if (site_process.fingerprints.get(fingerprint)) {
						found_fingerprinted = true;
						break;
					}
				}
			}

			if (!found_fingerprinted) {
				that.process_list.shuffle();

				for (i = 0; i < that.process_list.length; i++) {
					site_process = that.process_list[i];

					// Isolated processes should no longer
					// serve new clients
					if (site_process.isolated) {
						continue;
					}

					if (site_process.cpu > 92) {
						continue;
					} else {
						break;
					}
				}
			}
		} else {
			site_process = that.process_list[0];
		}

		if (!site_process) {
			return callback(new Error('No running site process was found'));
		}

		// If a fingerprint was found, but no process matches,
		// set the current process
		if (fingerprint && !found_fingerprinted) {
			site_process.fingerprints.set(fingerprint, true);
		}

		if (site_process.path_to_socket) {
			address = {
				socketPath: site_process.path_to_socket
			};
		} else {
			address = 'http://' + that.redirectHost + ':' + site_process.port;
		}

		return callback(null, address);
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

/**
 * Start servers with minimum amount of processes
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.0
 * @version  0.4.0
 */
Site.setMethod(function startMinimumServers() {

	var that = this;

	this.dispatcher.queue.add(function startServersWhenReady() {
		var key;

		if (that.settings
			&& that.settings.minimum_processes
			&& that.settings.minimum_processes > that.total_proc_count
		) {
			let count = that.total_proc_count || 0;

			log.info('Site', that.name, 'requires at least', that.settings.minimum_processes, 'running processes,', that.running, 'are already running');

			for (; count < that.settings.minimum_processes; count++) {
				if (count == 0) {
					// Use `getAddress` to get the first server
					that.getAddress(null, Function.thrower);
				} else {
					that.start();
				}
			}
		}
	});
});

/**
 * Handle an incoming request
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 * 
 * @param    {IncomingMessage}    req
 * @param    {ServerResponse}     res
 */
Site.setMethod(function handleRequest(req, res) {

	if (req.headers['x-hohenheim-key'] && this.settings.api_keys && this.settings.api_keys.length) {
		let result = this.handleApiRequest(req, res);

		if (result) {
			return;
		}
	}

	return handleRequest.super.call(this, req, res);
});

/**
 * Handle a Hohenheim api request
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 * 
 * @param    {IncomingMessage}    req
 * @param    {ServerResponse}     res
 */
Site.setMethod(function handleApiRequest(req, res) {

	var actions = req.headers['x-hohenheim-action'],
	    key     = req.headers['x-hohenheim-key'];

	if (!key || !actions) {
		return false;
	}

	if (this.settings.api_keys.indexOf(key) === -1) {
		return false;
	}

	actions = actions.split(',');

	let action;

	for (action of actions) {
		if (action == 'broadcast') {
			this.handleApiBroadcast(req);
		}
	}

	res.end();

	return true;
});

/**
 * Handle a Hohenheim api broadcast request
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 * 
 * @param    {IncomingMessage}    req
 */
Site.setMethod(function handleApiBroadcast(req) {

	const that = this;

	// Make sure a server has been started
	this.startMinimumServers();

	alchemy.parseRequestBody(req, function gotBody(err, body) {

		if (err) {
			return;
		}

		let proc,
		    key;

		for (key in that.processes) {
			proc = that.processes[key];

			proc.send({
				type: 'hohenheim_broadcast',
				body: body
			});
		}
	});
});

/**
 * Before starting the actual server, we need the node versions
 *
 * @author        Jelle De Loecker   <jelle@develry.be>
 * @since         0.2.0
 * @version       0.2.0
 */
alchemy.sputnik.before('start_server', function getNodeVersions(done) {

	let pledge = new Pledge();

	// Update all the node versions
	Site.updateVersions(function gotVersions(err, versions) {

		if (err) {
			log.error('Error getting node versions:', err);
			pledge.resolve();
			return;
		}

		let entry,
		    key;

		log.info('Got', Object.size(versions), 'node versions');

		for (key in versions) {
			entry = versions[key];

			log.info(' -', entry.version, '(' + entry.title + ')', '@', entry.bin);
		}

		pledge.resolve();
	});

	return pledge;
});