var site_types  = alchemy.getClassGroup('site_type'),
    LeChallenge = alchemy.use('le-challenge-fs'),
    parsePasswd = alchemy.use('parse-passwd'),
    LeCertbot   = alchemy.use('le-store-certbot'),
    LeSniAuto   = alchemy.use('le-sni-auto'),
    GreenLock   = alchemy.use('greenlock'),
    local_ips   = alchemy.shared('local_ips'),
    local_users = alchemy.shared('local_users'),
    httpProxy   = alchemy.use('http-proxy'),
    libpath     = alchemy.use('path'),
    spdy        = alchemy.use('spdy'),
    http        = alchemy.use('http'),
    util        = alchemy.use('util'),
    net         = alchemy.use('net'),
    os          = alchemy.use('os'),
    fs          = alchemy.use('fs');

const readFileAsync = util.promisify(fs.readFile);

/**
 * The Site Dispatcher class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.3.0
 */
var SiteDispatcher = Function.inherits('Informer', 'Develry', function SiteDispatcher(options) {

	var that = this;

	if (!options) {
		options = {};
	}

	// Get the site model
	this.Site = Model.get('Site');

	// Get the domain model
	this.Domain = Model.get('Domain');

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

	// Force https (if it is enabled?)
	this.force_https = options.force_https == null ? true : options.force_https;

	// Create the queue
	this.queue = Function.createQueue();

	// Start the queue by getting the sites first
	this.queue.start(function gettingSites(done) {
		Function.parallel(function getSites(next) {
			that.Site.getSites(function done() {
				next();
			});
		}, function getDomains(next) {
			that.Domain.getDomains(next);
		}, function _done(err) {

			if (err) {
				console.error('Error starting queue!', err);
			}

			done();
		});
	});

	// Listen to the site update event
	alchemy.on('siteUpdate', this.update.bind(this));

	this.init();
});

/**
 * Initialize the dispatcher
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.2
 * @version  0.3.2
 */
SiteDispatcher.setMethod(async function init() {

	var that = this;

	// Populate the available users
	await this.getLocalUsers();

	// Get the local ip addresses
	this.getLocalIps();

	// Create the proxy server
	this.startProxy();

	// Update users & node versions every hour
	setInterval(function doUpdate() {
		Classes.Develry.NodeSite.updateVersions();
		that.getLocalUsers();
		that.getLocalIps();
	}, 60 * 60 * 1000);
});

/**
 * Update the local users
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.3.2
 */
SiteDispatcher.setMethod(async function getLocalUsers() {

	var fullname,
	    result,
	    passwd,
	    title,
	    user,
	    i;

	// Get the file
	passwd = await readFileAsync('/etc/passwd', 'utf8');

	// Parse it
	result = parsePasswd(passwd);

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
 * @version  0.3.2
 */
SiteDispatcher.setMethod(function startProxy() {

	var that = this,
	    agent;

	log.info('Proxy server is starting on port', this.proxyPort);

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

		var forwarded_for,
		    site;

		// Let the target server know hohenheim is in front of it
		proxyReq.setHeader('X-Proxied-By', 'hohenheim');

		if (req.connection && req.connection.remoteAddress) {

			// See if there already is an x-forwarded-for
			forwarded_for = req.headers['x-forwarded-for'];

			// If there already was a forwarded header, append to it
			if (forwarded_for) {
				forwarded_for += ', ' + req.connection.remoteAddress;
			} else {
				forwarded_for = req.connection.remoteAddress;
			}

			// Set the original ip address
			proxyReq.setHeader('X-Real-IP', forwarded_for);
			proxyReq.setHeader('X-Forwarded-For', forwarded_for);
		}

		proxyReq.setHeader('X-Forwarded-Host', req.headers['host']);

		// Get the target site
		site = that.getSite(req);

		if (site) {
			req.hohenheim_site = site;

			// Set the custom header values
			if (site.domain && site.domain.headers && site.domain.headers.length) {
				site.domain.headers.forEach(function eachHeader(header) {
					if (header.name) {
						proxyReq.setHeader(header.name, header.value);
					}
				});
			}
		}
	});

	// Modify proxy response headers
	this.proxy.on('proxyRes', function onProxyRes(proxyRes, req, res) {
		if (req.hohenheim_site && req.hohenheim_site.site.modifyResponse) {
			// The body can't really be modified since we haven't set `selfHandleResponse` yet
			req.hohenheim_site.site.modifyResponse(res, req, proxyRes, req.hohenheim_site.domain);
		}
	});

	// Create the server
	this.server = http.createServer(this.request.bind(this));

	// Listen for websocket upgrades
	this.server.on('upgrade', function gotRequest(req, socket, head) {

		// Ignore insecure websocket requests on servers with https
		if (that.proxyPortHttps) {
			return;
		}

		that.websocketRequest(req, socket, head);
	});

	// Make the proxy server listen on the given port
	this.server.listen(this.proxyPort);

	// Do not limit the incoming connections
	this.server.maxHeadersCount = 0;

	// See if there is a specific ipv6 address defined
	// (Default is to listen to all of them)
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
 * @version  0.3.0
 */
SiteDispatcher.setMethod(function initGreenlock() {

	var that = this,
	    server_url,
	    path_log,
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
		server_url = 'https://acme-staging-v02.api.letsencrypt.org/directory';
	} else {
		server_url = 'https://acme-v02.api.letsencrypt.org/directory';
	}

	// Construct the paths to where the certificates and challenges will be kept
	path_etc = libpath.resolve(PATH_TEMP, 'letsencrypt', 'etc');
	path_var = libpath.resolve(PATH_TEMP, 'letsencrypt', 'var');
	path_log = libpath.resolve(PATH_TEMP, 'letsencrypt', 'log');

	// Create a site model instance
	Site = Model.get('Site');

	// Create the certificate store
	this.le_store = LeCertbot.create({
		configDir   : path_etc,
		webrootPath : path_var,
		logsDir     : path_log,
		debug       : debug
	});

	// Create the challenge handler
	this.le_handler = LeChallenge.create({
		webrootPath : path_var,
		debug       : debug
	});

	// Create the auto sni creator
	this.le_sni = LeSniAuto.create({
		renewWithin : 15 * 24 * 60 * 60 * 1000,  // do not renew more than 15 days before expiration
		renewBy     : 10 * 24 * 60 * 60 * 1000,   // do not wait more than 10 days before expiration
		tlsOptions  : {
			rejectUnauthorized : true,           // These options will be used with tls.createSecureContext()
			requestCert        : false,          // in addition to key (privkey.pem) and cert (cert.pem + chain.pem),
			ca                 : null,           // which are provided by letsencrypt
			crl                : null
		},
		getCertificates : function getCertificates(domain, certs, callback) {
			try {
				that.getCertificates(domain, certs, callback);
			} catch (err) {
				log.error('Error getting domain certificate for', domain, ':', err);
				callback(err);
			}
		}
	});

	// Create the greenlock instance
	this.greenlock = GreenLock.create({
		version         : 'v02',
		server          : server_url,
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

			var site;

			if (debug) {
				log.info('Approving domains', opts, certs);
			}

			// Opt-in to submit stats and get important updates
			opts.communityMember = true;

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

	// Create the HTTPS/http2 server using the `spdy` module
	this.https_server = spdy.createServer(this.greenlock.httpsOptions);

	// This listener attempts to fix an issue with SPDY where idle connections do
	// not close. Too many idle connections to our server (>4000) cause our server
	// to be sluggish or outright nonfunctional. See
	// https://github.com/spdy-http2/node-spdy/issues/338 and
	// https://github.com/nodejs/node/issues/4560.
	this.https_server.on('connection', function onSocket(socket) {
		// Set the socket's idle timeout in milliseconds. 2 minutes is the default
		// for Node's HTTPS server. We are currently using SPDY:
		// https://nodejs.org/api/https.html#https_server_settimeout_msecs_callback
		socket.setTimeout(1000 * 60 * 2);

		// Wait for the timeout event.
		// The socket will emit it when the idle timeout elapses.
		socket.on('timeout', function onTimeout() {
			// Call destroy again.
			socket.destroy();
		});
	});

	// Listen for HTTPS requests
	this.https_server.on('request', function gotRequest(req, res) {

		// Let the site know it's being run behind HTTPS
		req.headers['X-Forwarded-Proto'] = 'https';

		// Handle the request, skip the LE middleware
		that.request(req, res, true);
	});

	// Listen for HTTPS websocket upgrades
	this.https_server.on('upgrade', function gotRequest(req, socket, head) {
		that.websocketRequest(req, socket, head);
	});

	log.info('Secure HTTPS proxy server is starting on port', this.proxyPortHttps);

	// Listen on the HTTPS port
	this.https_server.listen(this.proxyPortHttps);
});

/**
 * Get domain certificates
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.0
 * @version  0.3.0
 * 
 * @param    {String}     domain
 * @param    {Object}     certs
 * @param    {Function}   res
 */
SiteDispatcher.setMethod(function getCertificates(domain, certs, callback) {

	var that = this,
	    domains_to_register,
	    domain_record,
	    challenge,
	    hostnames,
	    settings,
	    options,
	    site;

	site = that.getSite(domain);

	if (!site) {
		return callback(new Error('Domain "' + domain + '" was not found on this server'));
	} else {
		site = site.site._record;
	}

	// Get all the hostnames for this site
	// We DON'T bundle domains anymore. If 1 breaks, all of them break!
	//hostnames = site.getHostnames(domain);

	// Get the site settings
	settings = site.settings;

	// See if we have a domain record
	domain_record = that.Domain.getDomain(domain);

	// Wildcards aren't enabled yet, as the dns-01 challenge type still needs a lot of work
	if (false && domain_record) {
		domains_to_register = ['*.' + domain_record.name, domain_record.name];
		challenge = 'dns-01';
		log.info('Going to register domain wildcard', domain_record.name, 'using challenge', challenge);
	} else {
		domains_to_register = [domain];
		challenge = settings.letsencrypt_challenge || alchemy.settings.letsencrypt_challenge;
		log.info('Going to register domain', domain, 'using challenge', challenge);
	}

	options = {
		domains       : domains_to_register,
		email         : settings.letsencrypt_email || alchemy.settings.letsencrypt_email,
		agreeTos      : true,
		rsaKeySize    : 2048,
		challengeType : challenge
	};

	that.greenlock.register(options).then(function onResult(result) {
		callback(null, result);
	}, function onError(err) {
		callback(err);
	});
});

/**
 * Handle request errors
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.3.0
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

		site.site.getAddress(req, function gotAddress(err, address) {

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
 * @version  0.4.0
 * 
 * @param    {IncomingMessage}    req
 * @param    {ServerResponse}     res
 * @param    {Boolean}            skip_le   Skips letsconnect middleware if true
 */
SiteDispatcher.setMethod(function request(req, res, skip_le) {

	var that = this,
	    new_location,
	    force_https,
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
		if (alchemy.settings.letsencrypt && this.proxyPortHttps && !req.connection.encrypted) {

			// Is HTTPS forced for all sites?
			force_https = this.force_https;

			// If https is not forced, see if it is forced in the site's config
			if (!force_https && site.site.settings && site.site.settings.letsencrypt_force) {
				force_https = true;
			}

			if (force_https) {
				host = req.headers.host;
				new_location = 'https://' + host.replace(/:\d+/, ':' + 443) + req.url;

				res.writeHead(302, {'Location': new_location});
				res.end();
				return;
			}
		}

		// Only register this hit if the error count has not been set
		// meaning it's the first time this request has passed through here
		if (!req.errorCount) {
			site.site.registerHit(req, res);
		}

		site.site.checkBasicAuth(req, res, function done() {
			site.site.handleRequest(req, res);
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
 * Get a path to a socket file
 * and immediately reserve it for the given site
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @param    {Develry.Site}   site      A site instance
 * @param    {Function}       callback
 */
SiteDispatcher.setMethod(function getSocketfile(site, callback) {

	const that = this;

	let filename = site.id + '_' + Date.now() + Crypto.randomHex(8) + '.sock',
	    path = libpath.resolve(PATH_TEMP, filename);

	callback(null, path);
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