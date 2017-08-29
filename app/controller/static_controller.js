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
var Static = Function.inherits('Alchemy.AppController', function StaticController(conduit, options) {
	StaticController.super.call(this, conduit, options);
});

/**
 * The home action
 *
 * @author        Jelle De Loecker   <jelle@develry.be>
 * @since         0.1.0
 * @version       0.1.0
 *
 * @param   {Conduit}   conduit
 */
Static.setMethod(function home(conduit) {

	// Set information variables
	Controller.get('AlchemyInfo').setInfoVariables.call(this);

	// Set the `message` variable to be used inside the view file
	this.set('message', 'This is a standard message set in the <b>home</b> method of the <b>Static</b> controller');

	// Render a specific view
	this.render('static/home');
});

/**
 * Make basic field information about a model available
 *
 * @author   Jelle De Loecker   <jelle@kipdola.be>
 * @since    0.0.1
 * @version  0.0.1
 */
Static.setMethod(function sitestat(conduit) {

	var data     = conduit.param(),
	    siteId   = alchemy.castObjectId(data.id),
	    result   = {},
	    process,
	    site,
	    pid;

	if (!siteId) {
		return conduit.error(new Error('No site_id given'));
	}

	site = alchemy.dispatcher.ids[siteId];

	if (!site) {
		return conduit.error(new Error('Site "' + siteId + '" does not exist'));
	}

	// Get the amount of processes running
	result.running = site.running;

	result.processes = {};

	// Get the pids
	for (pid in site.processes) {

		process = site.processes[pid];

		result.processes[pid] = {
			startTime: process.startTime,
			port: process.port,
			cpu: process.cpu,
			mem: process.mem
		};
	}

	result.incoming = site.incoming;
	result.outgoing = site.outgoing;

	conduit.end(result);
});

/**
 * Kill the requested pid
 *
 * @author   Jelle De Loecker   <jelle@kipdola.be>
 * @since    0.0.1
 * @version  0.2.0
 */
Static.setMethod(function sitestatKill(conduit) {

	var data     = conduit.param(),
	    siteId   = alchemy.castObjectId(data.id),
	    result   = {},
	    process,
	    site,
	    pid;

	log.info('Manual sitekill requested for pid', data.pid);

	if (!siteId) {
		return conduit.error(new Error('No site_id given'));
	}

	site = alchemy.dispatcher.ids[siteId];

	if (!site) {
		return conduit.error(new Error('Site does not exist'));
	}

	process = site.processes[data.pid];

	if (!process) {
		return conduit.error(new Error('pid does not exist'));
	}

	process.kill();

	conduit.end({success: 'process killed'});
});

/**
 * Start a new process
 *
 * @author   Jelle De Loecker   <jelle@kipdola.be>
 * @since    0.0.1
 * @version  0.0.1
 */
Static.setMethod(function sitestatStart(conduit) {

	var data     = conduit.param(),
	    siteId   = alchemy.castObjectId(data.id),
	    result   = {},
	    process,
	    site,
	    pid;

	if (!siteId) {
		return conduit.error(new Error('No site_id given'));
	}

	site = alchemy.dispatcher.ids[siteId];

	if (!site) {
		return conduit.error(new Error('Site does not exist'));
	}

	site.start();

	conduit.end({success: 'process started'});
});

/**
 * Get available logs
 *
 * @author   Jelle De Loecker   <jelle@kipdola.be>
 * @since    0.0.2
 * @version  0.0.2
 */
Static.setMethod(function sitestatLogs(conduit) {

	var data     = conduit.param(),
	    siteId   = alchemy.castObjectId(data.id),
	    result   = {},
	    Proclog  = Model.get('Proclog'),
	    process,
	    site,
	    pid;

	if (!siteId) {
		return conduit.error(new Error('No site_id given'));
	}

	site = alchemy.dispatcher.ids[siteId];

	if (!site) {
		return conduit.error(new Error('Site does not exist'));
	}

	Proclog.find('all', {document: false, conditions: {site_id: siteId}, fields: ['_id', 'created', 'updated']}, function(err, data) {

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
 * @version  0.0.2
 */
Static.setMethod(function sitestatLog(conduit) {

	var data     = conduit.param(),
	    logId    = alchemy.castObjectId(data.logid),
	    Proclog  = Model.get('Proclog');

	if (!logId) {
		return conduit.error(new Error('No site_id given'));
	}

	Proclog.find('all', {document: false, conditions: {_id: logId}}, function(err, data) {
		data = Object.extract(data, '$..Proclog');
		conduit.end(data);
	});
});