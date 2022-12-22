/**
 * The Static Controller class
 *
 * @constructor
 * @extends       Alchemy.AppController
 *
 * @author        Jelle De Loecker   <jelle@develry.be>
 * @since         0.1.0
 * @version       0.1.0
 */
var Static = Function.inherits('Alchemy.Controller.App', function Static(conduit, options) {
	Static.super.call(this, conduit, options);
});

/**
 * The home action
 *
 * @author        Jelle De Loecker   <jelle@develry.be>
 * @since         0.1.0
 * @version       0.3.0
 *
 * @param   {Conduit}   conduit
 */
Static.setAction(function home(conduit) {
	// Render a specific view
	this.render('static/home');
});

/**
 * Make basic field information about a model available
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.5.0
 */
Static.setAction(function sitestat(conduit) {

	var user = conduit.session('UserData');

	if (!user) {
		return conduit.notAuthorized();
	}

	let data   = conduit.param(),
	    site_id = alchemy.castObjectId(data.site_id);

	if (!site_id) {
		return conduit.error(new Error('No site_id given'));
	}

	if (!alchemy.dispatcher) {
		return conduit.end({});
	}

	let site = alchemy.dispatcher.ids[site_id];

	if (!site) {
		return conduit.error(new Error('Site "' + site_id + '" does not exist'));
	}

	let result = {},
	    proc,
	    pid;

	// Get the amount of processes running
	result.running = site.running;

	result.processes = {};

	// Get the pids
	for (pid in site.processes) {

		proc = site.processes[pid];

		// Prune the fingerprints cache
		if (proc.fingerprints) {
			proc.fingerprints.prune();
		}

		let status;

		if (proc.isolated) {
			status = 'isolated';
		} else if (!proc.ready) {
			status = 'starting';
		} else {
			status = 'ready';
		}

		result.processes[pid] = {
			id           : pid,
			start_time   : proc.startTime,
			port         : proc.port,
			cpu          : proc.cpu || 0,
			memory       : proc.mem || 0,
			isolated     : proc.isolated,
			fingerprints : proc.fingerprints.length,
			status,
		};
	}

	result.incoming = site.incoming;
	result.outgoing = site.outgoing;

	conduit.end(result);
});

/**
 * Kill the requested pid
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.5.0
 */
Static.setAction(function sitestatKill(conduit) {

	var user = conduit.session('UserData');

	if (!user) {
		return conduit.notAuthorized();
	}

	let data   = conduit.param(),
	    siteId = alchemy.castObjectId(data.id);

	if (!siteId) {
		return conduit.error(new Error('No site_id given'));
	}

	if (!alchemy.dispatcher) {
		return conduit.end({success: false});
	}

	let site = alchemy.dispatcher.ids[siteId];

	if (!site) {
		return conduit.error(new Error('Site "' + siteId + '" does not exist'));
	}

	log.info('Manual sitekill requested for pid', data.pid);

	let proc = site.processes[data.pid];

	if (!proc) {
		return conduit.error(new Error('pid does not exist'));
	}

	proc.kill();

	this.renderer.history = false;
	this.set('message', 'Killed process ' + data.pid);
	this.render('chimera/hohenheim_message');
});


/**
 * Isolate the requested pid
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.4.0
 * @version  0.5.0
 */
Static.setAction(function sitestatIsolate(conduit) {

	var user = conduit.session('UserData');

	if (!user) {
		return conduit.notAuthorized();
	}

	let data   = conduit.param(),
	    siteId = alchemy.castObjectId(data.id);

	if (!siteId) {
		return conduit.error(new Error('No site_id given'));
	}

	if (!alchemy.dispatcher) {
		return conduit.end({success: false});
	}

	let site = alchemy.dispatcher.ids[siteId];

	if (!site) {
		return conduit.error(new Error('Site "' + siteId + '" does not exist'));
	}

	log.info('Manual isolation requested for pid', data.pid);

	let proc = site.processes[data.pid];

	if (!proc) {
		return conduit.error(new Error('pid does not exist'));
	}

	proc.isolated = true;

	this.renderer.history = false;
	this.set('message', 'Isolated process ' + data.pid);
	this.render('chimera/hohenheim_message');
});

/**
 * Start a new process
 *
 * @author   Jelle De Loecker   <jelle@kipdola.be>
 * @since    0.0.1
 * @version  0.3.2
 */
Static.setAction(function sitestatStart(conduit) {

	var user = conduit.session('UserData');

	if (!user) {
		return conduit.notAuthorized();
	}

	let data   = conduit.param(),
	    siteId = alchemy.castObjectId(data.id);

	if (!siteId) {
		return conduit.error(new Error('No site_id given'));
	}

	let site = alchemy.dispatcher.ids[siteId];

	if (!site) {
		return conduit.error(new Error('Site "' + siteId + '" does not exist'));
	}

	log.info('Starting site:', site);

	site.start();

	conduit.end({success: 'process started'});
});

/**
 * Get available logs
 *
 * @author   Jelle De Loecker   <jelle@kipdola.be>
 * @since    0.0.2
 * @version  0.3.2
 */
Static.setAction(function sitestatLogs(conduit) {

	var user = conduit.session('UserData');

	if (!user) {
		return conduit.notAuthorized();
	}

	let data   = conduit.param(),
	    siteId = alchemy.castObjectId(data.id);

	if (!siteId) {
		return conduit.error(new Error('No site_id given'));
	}

	if (!alchemy.dispatcher) {
		return conduit.end([]);
	}

	let site = alchemy.dispatcher.ids[siteId];

	if (!site) {
		return conduit.error(new Error('Site "' + siteId + '" does not exist'));
	}

	let Proclog = this.getModel('Proclog');
	let options = {
		document   : false,
		conditions : {
			site_id: siteId
		},
		fields     : ['_id', 'created', 'updated'],
		limit      : 5,
		sort       : {_id: -1}
	};

	Proclog.find('all', options, function gotProclogs(err, data) {

		if (err) {
			return conduit.error(err);
		}

		data = Object.extract(data, '$..Proclog');
		data = Array.cast(data);
		conduit.end(data);
	});
});

/**
 * Get log
 *
 * @author   Jelle De Loecker   <jelle@kipdola.be>
 * @since    0.0.2
 * @version  0.3.0
 */
Static.setAction(function sitestatLog(conduit) {

	var data     = conduit.param(),
	    logId    = alchemy.castObjectId(data.logid),
	    Proclog  = Model.get('Proclog'),
	    user     = conduit.session('UserData');

	if (!user) {
		return conduit.notAuthorized();
	}

	if (!logId) {
		return conduit.error(new Error('No site_id given'));
	}

	let options = {
		document   : false,
		conditions : {_id: logId}
	};

	Proclog.find('all', options, function gotProcLog(err, data) {
		data = Object.extract(data, '$..Proclog');
		conduit.end(data);
	});
});

/**
 * Show the terminal
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.0
 * @version  0.3.0
 */
Static.setAction(function terminal(conduit, linkup, config) {

	var that = this,
	    site = alchemy.dispatcher.ids[config.site_id],
	    user = conduit.session('UserData');

	if (!user) {
		return conduit.notAuthorized();
	}

	if (!site) {
		return conduit.error(new Error('Site does not exist'));
	}

	let proc = site.processes[config.pid];

	if (!proc) {
		return conduit.error(new Error('pid does not exist'));
	}

	// Create the output stream
	let output_stream = conduit.createStream();

	linkup.submit('output_stream', {}, output_stream);

	linkup.on('propose_geometry', function onPropose(data) {

		proc.send({
			type : 'janeway_propose_geometry',
			data : data
		});

		setTimeout(function requestRedraw() {
			proc.send('janeway_redraw');
		}, 50);
	});

	linkup.on('resize', function onResized() {
		proc.send('janeway_redraw');
	});

	linkup.on('input_stream', function gotInput(data, stream) {

		stream.columns = config.width;
		stream.rows = config.height;

		output_stream.columns = config.width;
		output_stream.rows = config.height;

		stream.on('data', function onData(d) {
			proc.stdin.write(d);
		});

		if (proc._janeway_redraw) {
			proc.send('janeway_redraw');
		} else {
			proc._janeway_redraw = true;
		}

		proc.stdio[4].on('data', onData);

		// Remove the data listener on disconnect
		conduit.on('disconnect', function onDisconnect() {
			proc.stdio[4].removeListener('data', onData);
			output_stream = null;
			stream = null;
		});

		function onData(d) {
			output_stream.write(d);
		}
	});
});