var site_types  = alchemy.getClassGroup('site_type'),
    LeChallenge = alchemy.use('le-challenge-fs'),
    parsePasswd = alchemy.use('parse-passwd'),
    LeCertbot   = alchemy.use('le-store-certbot'),
    LeSniAuto   = alchemy.use('le-sni-auto'),
    GreenLock   = alchemy.use('greenlock'),
    local_ips   = alchemy.shared('local_ips'),
    local_users = alchemy.shared('local_users'),
    httpProxy   = require('http-proxy'),
    libpath     = require('path'),
    http        = require('http'),
    net         = require('net'),
    os          = require('os');

/**
 * The Site Dispatcher class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.2.1
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

	// The https proxy port
	this.proxyPortHttps = options.proxyPortHttps,

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
	this.queue.start(function gettingSites(done) {
		that.Site.getSites(done);
	});

	// Listen to the site update event
	alchemy.on('siteUpdate', this.update.bind(this));

	// Populate the available users
	this.getLocalUsers();

	// Get the local ip addresses
	this.getLocalIps();

	// Create the proxy server
	this.startProxy();
});

/**
 * Get the local ip addresses
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.2.0
 */
SiteDispatcher.setMethod(function getLocalUsers() {

	var fullname,
	    result,
	    title,
	    user,
	    i;

	result = parsePasswd(fs.readFileSync('/etc/passwd', 'utf8'));

	result.sortByPath(1, 'username');

	for (i = 0; i < result.length; i++) {
		user = result[i];

		if (user.gecos) {
			fullname = user.gecos.split(',')[0] || '';
		} else {
			fullname = '';
		}

		title = user.username;

		if (fullname) {
			title += ' - ' + fullname + ' -';
		}

		title += ' (' + user.uid + ':' + user.gid + ')';

		local_users[user.uid] = {
			title : title,
			uid   : user.uid,
			gid   : user.gid,
			home  : user.homedir
		};
	}
});

/**
 * Get the local ip addresses
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.2.0
 */
SiteDispatcher.setMethod(function getLocalIps() {

	var interfaces = os.networkInterfaces(),
	    config,
	    iface,
	    name,
	    temp = [],
	    i;

	for (name in interfaces) {
		iface = interfaces[name];

		for (i = 0; i < iface.length; i++) {
			config = iface[i];

			temp.push({
				title    : config.family + ' ' + config.address,
				family   : config.family,
				internal : config.internal,
				address  : config.address
			});
		}
	}

	temp.sortByPath(1, 'title');

	for (i = 0; i < temp.length; i++) {
		config = temp[i];
		local_ips[config.address] = config;
	}
});

/**
 * Start the proxy server
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.2.0
 */
SiteDispatcher.setMethod(function startProxy() {

	var that = this,
	    agent;

	log.info('Proxy server is starting on port ' + this.proxyPort);

	// Init greenlock (let's encrypt)
	this.initGreenlock();

	// Create an agent for keep-alive
	agent = new http.Agent({
		keepAlive  : true,
		maxSockets : Number.MAX_VALUE
	});

	// Create the proxy
	this.proxy = httpProxy.createProxyServer({agent: agent});

	// Modify proxy request headers
	this.proxy.on('proxyReq', function onProxyReq(proxyReq, req, res, options) {

		var site;

		// Let the target server know hohenheim is in front of it
		proxyReq.setHeader('X-Proxied-By', 'hohenheim');

		if (req.connection && req.connection.remoteAddress) {
			// Set the original ip address
			proxyReq.setHeader('X-Forwarded-For', req.connection.remoteAddress);
		}

		// Get the target site
		site = that.getSite(req);

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

	// Do not limit the incoming connections
	this.server.maxHeadersCount = 0;

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

	if (!this.proxyPortHttps) {
		return log.warn('HTTPS is disabled');
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

		// Let the site know it's being run behind HTTPS
		req.headers['X-Forwarded-Proto'] = 'https';

		// Do the letsencrypt middleware
		that.le_middleware(req, res, function didMiddleware() {
			// Letsencrypt didn't need to intercept, continuing
			that.request(req, res, true);
		});
	});

	// Listen for HTTPS websocket upgrades
	this.https_server.on('upgrade', function gotRequest(req, socket, head) {
		that.websocketRequest(req, socket, head);
	});

	// Listen on the HTTPS port
	this.https_server.listen(this.proxyPortHttps);
});

/**
 * Handle request errors
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.2.1
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
	// @TODO: This is PER SOCKET,
	// so shared keep-alive requests in total will only be tried 4 times
	if (req.errorCount > 4) {
		log.error('Retried connection', req.connectionId, 'four times, giving up on:', req.url);
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
 * @param    {String|Object}   req_or_domain
 */
SiteDispatcher.setMethod(function getSite(req_or_domain) {

	// Get the host (including port)
	var matches,
	    headers,
	    domain,
	    entry,
	    site,
	    key,
	    req,
	    ip;

	if (typeof req_or_domain == 'string') {
		domain = req_or_domain;
	} else if (req_or_domain && typeof req_or_domain == 'object') {
		if (req_or_domain.headers) {
			req = req_or_domain;
		} else {
			headers = req_or_domain;
		}
	}

	if (req) {
		headers = req.headers;

		if (req.socket) {
			ip = req.socket.localAddress;
		}
	}

	if (headers) {
		domain = headers.host;
	}

	if (!domain) {
		return null;
	}

	// Split it by colons
	domain = domain.split(':');

	// The first part is the domain
	domain = domain[0];

	if (this.domains[domain] != null) {
		entry = this.domains[domain];

		// When we don't have to match an ip address,
		// just return the entry
		if (!ip) {
			return entry;
		}

		// We do have an ip address to match
		if (entry.site.matches(domain, ip)) {
			return entry;
		}
	}

	for (key in this.domains) {
		entry = this.domains[key];

		if (entry.site.matches(domain, ip)) {
			return entry;
		}
	}

	return null;
});

/**
 * Handle a new proxy request
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.2.0
 * 
 * @param    {IncommingMessage}   req
 * @param    {Socket}             socket
 * @param    {Buffer}             head
 */
SiteDispatcher.setMethod(function websocketRequest(req, socket, head) {

	var that = this,
	    new_location,
	    domain,
	    read,
	    site,
	    host,
	    hit;

	// Detect infinite loops
	// @TODO: this will break after the first loop,
	// maybe add a counter to allow more loops in case it's wanted functionality?
	if (req.headers['x-proxied-by'] == 'hohenheim') {
		return socket.end();
	}

	// Get the hit id
	hit = ++this.hitCounter;

	// This will set the connectionId only ONCE per socket,
	// so multiple keep-alive requests will share this connectionId
	if (!req.socket.connectionId) {
		req.socket.connectionId = ++this.connectionCounter;
		socket.connectionId = req.socket.connectionId;
	}

	req.connectionId = req.socket.connectionId;
	req.hitId = hit;

	req.headers.hitId = hit;
	req.headers.connectionId = req.connectionId;

	site = this.getSite(req);

	if (!site) {

		if (this.fallbackAddress) {
			return this.proxy.ws(req, socket, {target: this.fallbackAddress});
		}

		socket.end('There is no such domain here!');
	} else {

		site.site.getAddress(function gotAddress(err, address) {

			if (err) {
				return socket.end('Error: ' + err);
			}

			that.proxy.ws(req, socket, {target: address});
		});
	}
});

/**
 * Handle a new proxy request
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.2.0
 * 
 * @param    {IncomingMessage}    req
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
	if (skip_le !== true && alchemy.settings.letsencrypt !== false && this.proxyPortHttps) {

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

	site = this.getSite(req);

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
			site.site.getAddress(function gotAddress(err, address) {

				if (err) {
					res.writeHead(500, {'Content-Type': 'text/plain'});
					res.end('' + err);
					return;
				}

				if (site.site.settings.delay) {
					setTimeout(function doDelay() {
						that.proxy.web(req, res, {target: address});
					}, site.site.settings.delay);
				} else {
					that.proxy.web(req, res, {target: address});
				}
			});
		});
	}
});

/**
 * Get a free port number,
 * and immediately reserve it for the given site
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.2.0
 */
SiteDispatcher.setMethod(function getTestPort(start) {

	var port = start || this.firstPort;

	while (port !== this.proxyPort && typeof this.ports[port] !== 'undefined') {
		port++;
	}

	return port;
});

/**
 * Get a free port number,
 * and immediately reserve it for the given site
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.2.0
 *
 * @param    {Develry.Site}   site      A site instance
 * @param    {Function}       callback
 */
SiteDispatcher.setMethod(function getPort(site, callback) {

	var that = this,
	    first_port = this.firstPort,
	    last_port = first_port + 5000,
	    test_port = this.getTestPort(),
	    port;

	Function.while(function test() {
		if (!port && test_port < last_port) {
			return true;
		}
	}, function testPort(next) {

		var probe;

		probe = net.createServer().listen(test_port, '::');

		// If listening is successfull, this port can be used
		probe.on('listening', function onListening() {
			probe.close();
			port = test_port;
			next();
		});

		// If there is an error, proceed to the next port
		probe.on('error', function onError() {
			test_port = that.getTestPort(test_port + 1);
			next();
		});
	}, function done(err) {

		if (err) {
			return callback(err);
		}

		if (!port) {
			return callback(new Error('Could not find free port'));
		}

		that.ports[port] = site;
		callback(null, port);
	});
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
 * @version  0.2.0
 *
 * @param    {Object}   sitesById   An object of site records by their id
 */
SiteDispatcher.setMethod(function update(sitesById) {

	var SiteConstructor,
	    removed,
	    created,
	    shared,
	    site,
	    name,
	    key,
	    id;

	// Pause the dispatcher queue
	this.queue.pause();

	log.info('Updating sites ...');

	removed = alchemy.getDifference(this.ids, sitesById);

	// Destroy all the removed id sites
	for (id in removed) {
		this.ids[id].remove();
	}

	created = alchemy.getDifference(sitesById, this.ids);

	// Create all the new sites
	for (id in created) {
		site = created[id];

		log.info('Enabling site', id, site.name);

		SiteConstructor = site_types[site.site_type];

		if (!SiteConstructor) {
			SiteConstructor = Classes.Develry.Site;
		}

		new SiteConstructor(this, site);
	}

	shared = alchemy.getShared(this.ids, sitesById);

	// Update all the existing sites
	for (id in shared) {
		site = shared[id];

		log.info('Updating site', id, site.name);
		this.ids[id].update(site);
	}

	log.info('Domains currently enabled:');

	for (key in this.domains) {

		if (this.domains[key] && this.domains[key].site) {
			name = this.domains[key].site.name;
		} else {
			name = null;
		}

		log.info(' -', key, '»', name);
	}

	// Resume the queue
	this.queue.start();
});