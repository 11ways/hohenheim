var child     = require('child_process'),
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
 * @version  0.2.1
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

	// Create new subschema for environment variables
	let env_schema = new Classes.Alchemy.Schema(this);

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
 * @version  0.2.0
 */
Site.setStatic(function updateVersions(callback) {

	if (!callback) {
		callback = Function.thrower;
	}

	log.info('Updating node.js version info');

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

		var versions_path = '/usr/local/n/versions/node';

		fs.readdir(versions_path, function gotNVersions(err, contents) {

			// N is probably not used here
			if (err) {
				return next();
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
				next();
			});
		});
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
	}, function done(err) {

		if (err) {
			return callback(err);
		}

		callback(null, versions);
	});
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
 * @version  0.2.1
 *
 * @param    {Function}   callback
 */
Site.setMethod(function startOnPort(port, callback) {

	var that = this,
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

	log.info('Starting node script', this.settings.script, 'on port', port);

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

	env.PORT = port;

	args = [
		this.settings.script,
		'--port=' + port,
		'hohenchild'
	];

	config = {
		cwd    : this.cwd,
		stdio  : ['pipe', 'pipe', 'pipe', 'ipc'],
		env    : env
	};

	if (this.settings.user) {
		log.info(' - Starting as uid', this.settings.user);

		config.uid = Number(this.settings.user);
		config.gid = Number(this.settings.user);
	}

	// Start the server
	child_proc = child.spawn(node_config.bin, args, config)

	child_proc.proclog_id = null;
	child_proc.procarray = [];

	// Get the child process' output
	child_proc.stdout.on('data', function onData(data) {

		if (alchemy.settings.debug) {
			console.log('[SITE ' + that._record.name + '] ' + data);
		}

		Function.series(function getId(next) {
			if (child_proc.proclog_id) {
				return next();
			}

			that.Proclog.save({
				site_id: that.id,
				log: []
			}, {document: false}, function saved(err, data) {

				if (err) {
					return next(err);
				}

				child_proc.proclog_id = data[0]._id;
				next();
			});
		}, function done(err) {

			var str;

			if (err) {
				log.error('Error saving proclog', {err: err});
				return;
			}

			str = data.toString();
			child_proc.procarray.push({time: Date.now(), html: ansiHTML(str)});

			that.Proclog.save({
				_id: child_proc.proclog_id,
				log: child_proc.procarray
			});
		});
	});

	// Store the port it should be running on
	child_proc.port = port;

	// Store the time this was started
	child_proc.startTime = Date.now();

	// When overload started
	child_proc.startOverload = 0;

	this.processes[child_proc.pid] = child_proc;

	this.running++;

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
	child_proc.on('exit', function(code, signal) {

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

		return;
	}

	// Listen for the message that tells us the server is ready
	child_proc.on('message', function listenForReady(message) {

		var data;

		if (typeof message !== 'object') {
			return;
		}

		data = message.hohenheim || message.alchemy;

		if (!data) {
			return;
		}

		if (data.ready) {

			// Add this to the process object
			child_proc.ready = true;

			// Up the ready counter
			that.ready++;

			that.process_list.push(child_proc);

			// Execute the callback
			if (callback) callback();

			// Remove the event listener
			child_proc.removeListener('message', listenForReady);
		} else if (!child_proc.ready && data.error && data.error.code == 'EADDRINUSE') {
			// Try again if the port is already in use
			that.start(callback);
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
 * @version  0.2.0
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
 * @param    {Number}     attempt
 */
Site.setMethod(function getAddress(callback, attempt) {

	var that = this,
	    fnc;

	fnc = function addressCreator() {

		var site_process,
		    url,
		    i;

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

/**
 * Before starting the actual server, we need the node versions
 *
 * @author        Jelle De Loecker   <jelle@develry.be>
 * @since         0.2.0
 * @version       0.2.0
 */
alchemy.sputnik.beforeSerial('startServer', function getNodeVersions(done) {

	// We have to wait for blast & classes to have loaded
	Blast.loaded(function hasLoaded() {
		// Update all the node versions
		Site.updateVersions(function gotVersions(err, versions) {

			if (err) {
				log.error('Error getting node versions:', err);
				return done();
			}

			let entry,
			    key;

			log.info('Got', Object.size(versions), 'node versions');

			for (key in versions) {
				entry = versions[key];

				log.info(' -', entry.version, '(' + entry.title + ')', '@', entry.bin);
			}

			done();
		});
	});
});