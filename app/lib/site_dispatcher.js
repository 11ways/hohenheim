var site_types  = alchemy.getClassGroup('site_type'),
    LeChallenge = alchemy.use('le-challenge-fs'),
    LeCertbot   = alchemy.use('le-store-certbot'),
    LeSniAuto   = alchemy.use('le-sni-auto'),
    GreenLock   = alchemy.use('greenlock'),
    httpProxy   = require('http-proxy'),
    libpath     = require('path'),
    http        = require('http');

/**
 * The Site Dispatcher class
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

	// Listen to the site update event
	alchemy.on('siteUpdate', this.update.bind(this));

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

	// Init greenlock (let's encrypt)
	this.initGreenlock();

	// Create the proxy
	this.proxy = httpProxy.createProxyServer({});

	// Modify proxy request headers
	this.proxy.on('proxyReq', function onProxyReq(proxyReq, req, res, options) {

		var site;

		// Let the target server know hohenheim is in front of it
		proxyReq.setHeader('X-Proxied-By', 'hohenheim');

		// Get the target site
		site = that.getSite(req.headers);

		// Set the custom header values
		if (site && site.domain.headers && site.domain.headers.length) {
			site.domain.headers.forEach(function eachHeader(header) {
				if (header.name) {
					proxyReq.setHeader(header.name, header.value);
				}
			});
		}
	});

	// Create the server
	this.server = http.createServer(this.request.bind(this));

	// Make the proxy server listen on the given port
	this.server.listen(this.proxyPort);

	// See if there is a specific ipv6 address defined
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
 * Create the LetsEncrypt
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.2.0
 */
SiteDispatcher.setMethod(function initGreenlock() {

	var that = this,
	    server_type,
	    path_etc,
	    path_var,
	    debug,
	    Site;

	if (this._inited_greenlock) {
		return;
	}

	this._inited_greenlock = true;

	if (alchemy.settings.letsencrypt === false) {
		return console.warn('Letsencrypt support is disabled');
	}

	if (!alchemy.settings.letsencrypt_email) {
		return console.error('Can\'t enable letsencrypt: no letsencrypt_email is set');
	}

	if (!alchemy.settings.letsencrypt_challenge) {
		alchemy.settings.letsencrypt_challenge = 'http-01';
	}

	if (alchemy.settings.debug && alchemy.settings.letsencrypt_debug) {
		console.warn('Enabling letsencrypt debugging');
		debug = true;
	} else {
		debug = false;
	}

	if (alchemy.settings.environment != 'live' || alchemy.settings.debug || alchemy.settings.letsencrypt_debug) {
		console.warn('Using letsencrypt staging servers');
		server_type = GreenLock.stagingServerUrl;
	} else {
		server_type = GreenLock.productionServerUrl;
	}

	// Construct the paths to where the certificates and challenges will be kept
	path_etc = libpath.resolve(PATH_TEMP, 'letsencrypt', 'etc');
	path_var = libpath.resolve(PATH_TEMP, 'letsencrypt', 'var');

	// Create a site model instance
	Site = Model.get('Site');

	// Create the certificate store
	this.le_store = LeCertbot.create({
		configDir   : path_etc,
		webrootPath : path_var,
		debug       : debug
	});

	// Create the challenge handler
	this.le_handler = LeChallenge.create({
		webrootPath : path_var,
		debug       : debug
	});

	// Create the auto sni creator
	this.le_sni = LeSniAuto.create({
		renewWithin : 10 * 24 * 60 * 60 * 1000,  // do not renew more than 10 days before expiration
		renewBy     : 5 * 24 * 60 * 60 * 1000,   // do not wait more than 5 days before expiration
		tlsOptions  : {
			rejectUnauthorized : true,           // These options will be used with tls.createSecureContext()
			requestCert        : false,          // in addition to key (privkey.pem) and cert (cert.pem + chain.pem),
			ca                 : null,           // which are provided by letsencrypt
			crl                : null
		},
		getCertificates : function getCertificates(domain, certs, cb) {

			var hostnames,
			    settings,
			    options,
			    site;

			site = that.getSite(domain);

			if (!site) {
				return cb(new Error('Domain "' + domain + '" was not found on this server'));
			} else {
				site = site.site._record;
			}

			// Get all the hostnames for this site
			// We DON'T bundle domains anymore. If 1 breaks, all of them break!
			//hostnames = site.getHostnames(domain);

			// Get the site settings
			settings = site.settings;

			options = {
				domains       : [domain],
				email         : settings.letsencrypt_email || alchemy.settings.letsencrypt_email,
				agreeTos      : true,
				rsaKeySize    : 2048,
				challengeType : settings.letsencrypt_challenge || alchemy.settings.letsencrypt_challenge
			};

			that.greenlock.register(options).then(function onResult(result) {
				cb(null, result);
			}, function onError(err) {
				cb(err);
			});
		}
	});

	// Create the greenlock instance
	this.greenlock = GreenLock.create({
		server          : server_type,
		store           : this.le_store,
		challenges      : {
			'http-01'   : this.le_handler,
			'tls-sni-01': this.le_handler
		},
		challengeType   : alchemy.settings.letsencrypt_challenge,
		agreeToTerms    : true,
		sni             : this.le_sni,
		debug           : debug,
		approveDomains  : function approveDomains(opts, certs, callback) {

			if (opts.domain.endsWith('.acme.invalid')) {
				console.error('ACME.INVALID should not get this far?', opts, certs);
				return;
			}

			if (certs) {
				opts.domains = certs.altnames;
			} else {
				opts.email = alchemy.settings.letsencrypt_email;
				opts.agreeTos = true;
			}

			return callback(null, {options: opts, certs: certs});
		}
	});

	// Create the greenlock middleware
	this.le_middleware = this.greenlock.middleware();

	// Create the HTTPS server
	this.https_server = require('https').createServer(this.greenlock.httpsOptions);

	// Listen for HTTPS requests
	this.https_server.on('request', function gotRequest(req, res) {

		// Do the letsencrypt middleware
		that.le_middleware(req, res, function didMiddleware() {
			// Letsencrypt didn't need to intercept, continuing
			that.request(req, res, true);
		});
	});

	// Listen on the HTTPS port
	this.https_server.listen(443);
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

	if (!req) {
		throw new Error('Request error without request? ' + error);
	}

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
 * @version  0.2.0
 * 
 * @param    {Object}   headers
 */
SiteDispatcher.setMethod(function getSite(headers) {

	// Get the host (including port)
	var matches,
	    domain,
	    entry,
	    site,
	    key;

	if (typeof headers == 'string') {
		domain = headers;
	} else {
		domain = headers.host;
	}

	if (!domain) {
		console.warn('No host header found in:', headers);
		return null;
	}

	// Split it by colons
	domain = domain.split(':');

	// The first part is the domain
	domain = domain[0];

	if (this.domains[domain] != null) {
		return this.domains[domain];
	}

	for (key in this.domains) {
		entry = this.domains[key];

		if (entry.site.matches(domain)) {
			return entry;
		}
	}

	return null;
});

/**
 * Handle a new proxy request
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.2.0
 * 
 * @param    {IncommingMessage}   req
 * @param    {ServerResponse}     res
 * @param    {Boolean}            skip_le   Skips letsconnect middleware if true
 */
SiteDispatcher.setMethod(function request(req, res, skip_le) {

	var that = this,
	    new_location,
	    domain,
	    read,
	    site,
	    host,
	    hit;

	if (skip_le == null) {
		req.startTime = Date.now();
	}

	// Use the letsencrypt middleware first
	if (skip_le !== true && alchemy.settings.letsencrypt !== false) {

		this.le_middleware(req, res, function done() {
			// Greenlock didn't do anything, we can continue
			that.request(req, res, true);
		});

		return;
	}

	// Detect infinite loops
	// @TODO: this will break after the first loop,
	// maybe add a counter to allow more loops in case it's wanted functionality?
	if (req.headers['x-proxied-by'] == 'hohenheim') {
		res.writeHead(508, {'Content-Type': 'text/plain'});
		return res.end('Loop detected!');
	}

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

		// When using letsencrypt, redirect to HTTPS
		// @TODO: disable for certain sites?
		if (alchemy.settings.letsencrypt && !req.connection.encrypted && site.site.settings.letsencrypt_force !== false) {
			host = req.headers.host;
			new_location = 'https://' + host.replace(/:\d+/, ':' + 443) + req.url;

			res.writeHead(302, {'Location': new_location});
			res.end();
			return;
		}

		// Only register this hit if the error count has not been set
		// meaning it's the first time this request has passed through here
		if (!req.errorCount) {
			site.site.registerHit(req, res);
		}

		site.site.checkBasicAuth(req, res, function done() {
			site.site.getAddress(function gotAddress(address) {
				that.proxy.web(req, res, {target: address});
			});
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

	var SiteConstructor,
	    removed,
	    created,
	    shared,
	    id;

	// Pause the dispatcher queue
	this.queue.pause();

	console.log('Update:', sitesById);

	removed = alchemy.getDifference(this.ids, sitesById);

	// Destroy all the removed id sites
	for (id in removed) {
		this.ids[id].remove();
	}

	created = alchemy.getDifference(sitesById, this.ids);

	console.log('Created:', created);

	// Create all the new sites
	for (id in created) {
		SiteConstructor = site_types[created[id].site_type];

		if (!SiteConstructor) {
			SiteConstructor = Classes.Develry.Site;
		}

		new SiteConstructor(this, created[id]);
	}

	shared = alchemy.getShared(this.ids, sitesById);

	// Update all the existing sites
	for (id in shared) {
		console.log('Updating', id);
		this.ids[id].update(shared[id]);
	}

	console.log('Current domains:', this.domains);

	// Resume the queue
	this.queue.start();
});