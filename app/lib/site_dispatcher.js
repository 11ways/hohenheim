var child     = require('child_process'),
    httpProxy = require('http-proxy'),
    http      = require('http'),
    path      = require('path'),
    procmon   = require('process-monitor'),
    ansiHTML  = require('ansi-html');

/**
 * The Site Dispathcer class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 */
var SiteDispatcher = Function.inherits('Informer', 'Develry', function SiteDispatcher(options) {

	var that = this;

	if (!options) {
		options = {};
	}

	// Get the site model
	this.Site = Model.get('Site');

	// Count the number of made hits
	this.hitCounter = 0;

	// Count the number of made connections
	this.connectionCounter = 0;

	// Store sites by id in here
	this.ids = {};

	// Store sites by domain in here
	this.domains = {};

	// Store sites by name in here
	this.names = {};

	// The ports that are in use
	this.ports = {};

	// The port the proxy runs on
	this.proxyPort = options.proxyPort || 8080;

	// Where the ports start
	this.firstPort = options.firstPort || 4701;

	// The ipv6 address
	this.ipv6Address = options.ipv6Address;

	// The host to redirect to
	this.redirectHost = options.redirectHost || 'localhost';

	// The address to fallback to when no site is found (if enabled)
	this.fallbackAddress = options.fallbackAddress || false;

	// Create the queue
	this.queue = Function.createQueue();

	// Start the queue by getting the sites first
	this.queue.start(function(done) {
		that.Site.getSites(done);
	});

	// Listen to the site updat event
	this.Site.on('siteUpdate', this.update.bind(this));

	// Create the proxy server
	this.startProxy();
});

/**
 * Start the proxy server
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 */
SiteDispatcher.setMethod(function startProxy() {

	var that = this;

	log.info('Proxy server is starting on port ' + this.proxyPort);

	// Create the proxy
	this.proxy = httpProxy.createProxyServer({});

	// Create the server
	this.server = http.createServer(this.request.bind(this));

	// Make the proxy server listen on the given port
	this.server.listen(this.proxyPort);

	// See if there is a ipv6 server defined)
	if (this.ipv6Address) {
		this.server_ipv6 = http.createServer(this.request.bind(this));
		this.server_ipv6.listen(this.proxyPort, this.ipv6Address);
	}

	// Listen for error events
	this.proxy.on('error', this.requestError.bind(this));

	// Intercept proxy responses
	//this.proxy.on('proxyRes', this.response.bind(this));
});

/**
 * Handle request errors
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 * 
 * @param    {Error}              error
 * @param    {IncommingMessage}   req
 * @param    {ServerResponse}     res
 */
SiteDispatcher.setMethod(function requestError(error, req, res) {

	if (!req.errorCount) {
		req.errorCount = 1;
	} else {
		req.errorCount++;
	}

	// Retry 4 times
	if (req.errorCount > 4) {
		log.error('Retried connection ' + req.connectionId + ' four times, giving up');
		res.writeHead(502, {'Content-Type': 'text/plain'});
		res.end('Failed to reach server!');
	} else {
		// Make the request again
		this.request(req, res);
	}
});

/**
 * Get the site object based on the headers
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.0.1
 * 
 * @param    {Object}   headers
 */
SiteDispatcher.setMethod(function getSite(headers) {

	// Get the host (including port)
	var domain = headers.host;

	// Split it by colons
	domain = domain.split(':');

	// The first part is the domain
	domain = domain[0];

	return this.domains[domain];
});

/**
 * Handle a new proxy request
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 * 
 * @param    {IncommingMessage}   req
 * @param    {ServerResponse}     res
 */
SiteDispatcher.setMethod(function request(req, res) {

	var that = this,
	    domain,
	    read,
	    site,
	    hit;

	req.startTime = Date.now();

	// Get the hit id
	hit = ++this.hitCounter;

	if (!req.socket.connectionId) {

		req.socket.connectionId = ++this.connectionCounter;
		res.socket.connectionId = req.socket.connectionId;
	}

	req.connectionId = req.socket.connectionId;
	req.hitId = hit;

	req.headers.hitId = hit;
	req.headers.connectionId = req.connectionId;
	
	site = this.getSite(req.headers);

	if (!site) {

		if (this.fallbackAddress) {
			return this.proxy.web(req, res, {target: this.fallbackAddress});
		}

		res.writeHead(404, {'Content-Type': 'text/plain'});
		res.end('There is no such domain here!');
	} else {

		// Only register this hit if the error count has not been set
		// meaning it's the first time this request has passed through here
		if (!req.errorCount) {
			site.registerHit(req, res);
		}

		site.getAddress(function gotAddress(address) {
			that.proxy.web(req, res, {target: address});
		});
	}
});

/**
 * Get a free port number,
 * and immediately reserve it for the given site
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.0.1
 *
 * @param    {Develry.Site}   site   A site instance
 */
SiteDispatcher.setMethod(function getPort(site) {

	var port = this.firstPort;

	while (port !== this.proxyPort && typeof this.ports[port] !== 'undefined') {
		port++;
	}

	this.ports[port] = site;

	return port;
});

/**
 * Free up the given port number
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {Number}   portNumber
 */
SiteDispatcher.setMethod(function freePort(portNumber) {
	delete this.ports[portNumber];
});

/**
 * Update the sites
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {Object}   sitesById   An object of site records by their id
 */
SiteDispatcher.setMethod(function update(sitesById) {

	var removed,
	    created,
	    shared,
	    id;

	// Pause the dispatcher queue
	this.queue.pause();

	removed = alchemy.getDifference(this.ids, sitesById);

	// Destroy all the removed id sites
	for (id in removed) {
		this.ids[id].remove();
	}

	created = alchemy.getDifference(sitesById, this.ids);

	// Create all the new sites
	for (id in created) {
		new Classes.Develry.Site(this, created[id]);
	}

	shared = alchemy.getShared(this.ids, sitesById);

	// Update all the existing sites
	for (id in shared) {
		this.ids[id].update(shared[id]);
	}

	// Resume the queue
	this.queue.start();
});

/**
 * Make basic field information about a model available
 *
 * @author   Jelle De Loecker   <jelle@kipdola.be>
 * @since    0.0.1
 * @version  0.0.1
 */
Resource.register('sitestat', function(data, callback) {

	var siteId   = alchemy.castObjectId(data.id),
	    result   = {},
	    process,
	    site,
	    pid;

	if (!siteId) {
		return callback({err: 'no id given'});
	}

	site = alchemy.dispatcher.ids[siteId];

	if (!site) {
		return callback({err: 'site does not exist'});
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

	callback(result);
});

/**
 * Kill the requested pid
 *
 * @author   Jelle De Loecker   <jelle@kipdola.be>
 * @since    0.0.1
 * @version  0.0.1
 */
Resource.register('sitestat-kill', function(data, callback) {

	var siteId   = alchemy.castObjectId(data.id),
	    result   = {},
	    process,
	    site,
	    pid;

	if (!siteId) {
		return callback({err: 'no id given'});
	}

	site = alchemy.dispatcher.ids[siteId];

	if (!site) {
		return callback({err: 'site does not exist'});
	}

	process = site.processes[data.pid];

	if (!process) {
		return callback({err: 'pid does not exist'});
	}

	process.kill();

	callback({success: 'process killed'});
});

/**
 * Start a new process
 *
 * @author   Jelle De Loecker   <jelle@kipdola.be>
 * @since    0.0.1
 * @version  0.0.1
 */
Resource.register('sitestat-start', function(data, callback) {

	var siteId   = alchemy.castObjectId(data.id),
	    result   = {},
	    process,
	    site,
	    pid;

	if (!siteId) {
		return callback({err: 'no id given'});
	}

	site = alchemy.dispatcher.ids[siteId];

	if (!site) {
		return callback({err: 'site does not exist'});
	}

	site.start();

	callback({success: 'process started'});
});

/**
 * Get available logs
 *
 * @author   Jelle De Loecker   <jelle@kipdola.be>
 * @since    0.0.2
 * @version  0.0.2
 */
Resource.register('sitestat-logs', function(data, callback) {

	var siteId = alchemy.castObjectId(data.id),
	    result = {},
	    Proclog = Model.get('Proclog'),
	    process,
	    site,
	    pid;

	if (!siteId) {
		return callback({err: 'no id given'});
	}

	site = alchemy.dispatcher.ids[siteId];

	if (!site) {
		return callback({err: 'site does not exist'});
	}

	Proclog.find('all', {conditions: {site_id: siteId}, fields: ['_id', 'created', 'updated']}, function(err, data) {
		data = Object.extract(data, '$..Proclog');
		data = Array.cast(data);
		callback(data);
	});
});

/**
 * Get log
 *
 * @author   Jelle De Loecker   <jelle@kipdola.be>
 * @since    0.0.2
 * @version  0.0.2
 */
Resource.register('sitestat-log', function(data, callback) {

	var logId  = alchemy.castObjectId(data.logid),
	    Proclog = Model.get('Proclog');

	if (!logId) {
		return callback({err: 'no id given'});
	}

	Proclog.find('all', {conditions: {_id: logId}}, function(err, data) {
		data = Object.extract(data, '$..Proclog');
		callback(data);
	});
});