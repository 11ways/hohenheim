var site_types  = alchemy.getClassGroup('site_type'),
    parsePasswd = alchemy.use('parse-passwd'),
    GreenLock   = alchemy.use('greenlock'),
    local_ips   = alchemy.shared('local_ips'),
    local_users = alchemy.shared('local_users'),
    http2_proxy = alchemy.use('http2-proxy'),
    libpath     = alchemy.use('path'),
    http2       = alchemy.use('http2'),
    http        = alchemy.use('http'),
    util        = alchemy.use('util'),
    net         = alchemy.use('net'),
    tls         = alchemy.use('tls'),
    os          = alchemy.use('os'),
    fs          = alchemy.use('fs');

const readFileAsync = util.promisify(fs.readFile),
      challenge_prefix = '/.well-known/acme-challenge/',
      refresh_stagger = Math.round(Math.PI * 5 * (60 * 1000)), // +/- 15 minutes
      refresh_offset = Math.round(Math.PI * 2 * (60 * 60 * 1000)), // +/- 6.25 hours
      small_stagger = Math.round(Math.PI * (30 * 1000)), // +/- 30 seconds
      servername_re = /^[a-z0-9\.\-]+$/i;

global.MATCHED_GROUPS = Symbol('matched_groups');

/**
 * The Site Dispatcher class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.4.0
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

	// Sni cache
	this.sni_domain_cache = {};

	// The rendered not-found template
	this.not_found_message = null;

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
 * @version  0.4.0
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

	process.on('exit', this.createExitHandler('exit'));
	process.on('SIGINT', this.createExitHandler('SIGINT'));
	process.on('SIGUSR1', this.createExitHandler('SIGUSR1'));
	process.on('SIGUSR2', this.createExitHandler('SIGUSR2'));

	// Bind some methods already
	this.boundModifyIncomingRequest = this.modifyIncomingRequest.bind(this);
	this.boundModifyOutgoingResponse = this.modifyOutgoingResponse.bind(this);
	this.boundDefaultWebHandler = this.defaultWebHandler.bind(this);
	this.boundDefaultWSHandler = this.defaultWSHandler.bind(this);
});

/**
 * Create an exit handler
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 */
SiteDispatcher.setMethod(function createExitHandler(type) {

	const that = this;

	return function onExit() {

		var site,
		    proc,
		    id,
		    i;

		log.warning('Hohenheim is exiting: ' + type);

		for (id in that.ids) {
			site = that.ids[id];

			if (!site.processes) {
				continue;
			}

			for (i = 0; i < site.processes.length; i++) {
				proc = site.processes[i];

				if (proc) {
					proc.kill();
				}
			}
		}
	};

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
 * @version  0.4.2
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
				address  : config.address,
				old      : false,
			});
		}
	}

	temp.sortByPath(1, 'title');

	local_ips['any'] = {
		title   : 'Any IP',
		any     : true,
		old     : false,
	};

	let seen = {};

	for (i = 0; i < temp.length; i++) {
		config = temp[i];
		seen[config.address] = true;
		local_ips[config.address] = config;
	}

	for (let ip in local_ips) {
		let entry = local_ips[ip];

		if (!seen[ip] && !entry.old && !entry.any) {
			entry.old = true;
			entry.title = 'Old: ' + entry.title;
		}
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
	//this.proxy = httpProxy.createProxyServer();
	this.proxy = http2_proxy;

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

	// Are we using a socket file?
	if (typeof this.proxyPort == 'string' && !parseInt(this.proxyPort)) {
		let stat;

		try {
			stat = fs.statSync(this.proxyPort);
		} catch (err) {
			// File not found, so it's safe to use
		}

		if (stat) {
			log.info('Found existing socketfile at', this.proxyPort, ', need to remove it');
			fs.unlinkSync(this.proxyPort);
		}
	}

	// Make the proxy server listen on the given port
	this.server.listen(this.proxyPort, function areListening() {

		var address = that.server.address();

		if (typeof address == 'string') {
			log.info('HTTP server listening on socket file', address);

			// Make readable by everyone
			if (alchemy.settings.socketfile_chmod) {
				fs.chmodSync(address, alchemy.settings.socketfile_chmod);
			}
		}
	});

	// Do not limit the incoming connections
	this.server.maxHeadersCount = 0;

	// See if there is a specific ipv6 address defined
	// (Default is to listen to all of them)
	if (this.ipv6Address) {
		this.server_ipv6 = http.createServer(this.request.bind(this));
		this.server_ipv6.listen(this.proxyPort, this.ipv6Address);
	}
});

/**
 * Create the LetsEncrypt
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.4.0
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

	// Create the greenlock instance
	this.greenlock = GreenLock.create({
		// for an RFC 8555 / RFC 7231 ACME client user agent
		packageAgent    : alchemy.package.name + '/' + alchemy.package.version,
		packageRoot     : PATH_TEMP,
		configDir       : libpath.resolve(PATH_TEMP, 'greenlock.d'),
		manager         : '@greenlock/manager',
		maintainerEmail : alchemy.settings.letsencrypt_email,
		subscriberEmail : alchemy.settings.letsencrypt_email,
		staging         : !!alchemy.settings.letsencrypt_staging,

		notify: function notify(event, details) {

			if (event == 'error') {
				console.error('Greenlock error:', details);
			} else if (debug) {
				console.log('Greenlock notification:', event, details);
			}

		}
	});

	this.greenlock.manager.defaults({
		agreeToTerms: true,
		subscriberEmail: alchemy.settings.letsencrypt_email,
		store: {
			module: 'greenlock-store-fs',
			basePath: libpath.resolve(PATH_TEMP, 'letsencrypt', 'etc'),
		}
	});

	// Create the HTTPS/http2 server
	this.https_server = http2.createSecureServer({
		allowHTTP1  : true,
		SNICallback : function sniCallback(servername, next) {
			return that.SNICallback(servername, next);
		}
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
 * Get wildcard name
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 */
function wildcardname(domainname) {
	return '*.' + domainname.split('.').slice(1).join('.');
}

/**
 * Get a valid domain name
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 */
function validDomainname(domainname) {
	// format and (lightly) sanitize sni so that users can be naive
	// and not have to worry about SQL injection or fs discovery

	domainname = (domainname || '').toLowerCase();

	// hostname labels allow a-z, 0-9, -, and are separated by dots
	// _ is sometimes allowed, but not as a "hostname", and not by Let's Encrypt ACME
	// REGEX // https://www.codeproject.com/Questions/1063023/alphanumeric-validation-javascript-without-regex
	return servername_re.test(domainname) && -1 === domainname.indexOf('..');
}

/**
 * Get a random refresh offset
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @return   {Number}
 */
function randomRefreshOffset() {
	var stagger = Math.round(refresh_stagger / 2) - Math.round(Math.random() * refresh_stagger);
	return refresh_offset + stagger;
}

/**
 * SNI Callback
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 */
SiteDispatcher.setMethod(function SNICallback(domainname, callback) {

	if (typeof domainname != 'string') {
		return callback(new Error('SNI failure: invalid domainname'));
	}

	let site = this.getSite(domainname);

	if (!site) {
		log.error('Failed to find "' + domainname + '", ignoring SNI request');
		return callback(new Error('Domain "' + domainname + '" was not found on this server'));
	}

	let secure_context = null,
	    meta = this.getDomainMetaCache(domainname);

	// If a meta is set, it's possible a context already exists
	if (meta) {
		secure_context = this.getCachedSecureContext(domainname, meta);
	}

	if (secure_context) {
		return callback(null, secure_context);
	}

	return this.getFreshSecureContext(domainname, meta, callback);
});

/**
 * Get the cache for a certain domain
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @param    {String}   domainname
 *
 * @return   {Object}
 */
SiteDispatcher.setMethod(function getDomainMetaCache(domainname, create) {
	let cache = this.sni_domain_cache[domainname];

	if (!cache && create) {
		cache = {
			secure_context: {
				_valid : false
			}
		};

		this.sni_domain_cache[domainname] = cache;
	}

	return cache;
});

/**
 * Get a secure context
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @param    {String}   domainname
 * @param    {Object}   meta          The cache for this domain
 */
SiteDispatcher.setMethod(function getCachedSecureContext(domainname, meta) {

	// Renew in the background if needed
	if (!meta.refresh_at || Date.now() >= meta.refresh_at) {
		this.getFreshSecureContext(domainname, meta);
	}

	return meta.secure_context;
});

/**
 * Get a fresh secure context
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @param    {String}   domainname
 * @param    {Object}   meta          The cache for this domain
 * @param    {Function} callback
 */
SiteDispatcher.setMethod(function getFreshSecureContext(domainname, meta, callback) {

	if (!callback) {
		callback = Function.thrower;
	}

	if (!meta && !validDomainname(domainname)) {
		return callback(new Error('The domain name "' + domainname + '" is not valid for LetsEncrypt'));
	}

	if (meta) {
		meta.refresh_at = Date.now() + randomRefreshOffset();
	}

	const that = this;

	let site = this.getSite(domainname);

	if (!site) {
		return callback(new Error('Domain "' + domainname + '" was not found on this server'));
	} else {
		site = site.site._record;
	}

	// Need to add this to greenlock first
	if (!meta) {
		let all_hostnames = site.getHostnames(true),
		    main_domain = all_hostnames[0];

		// Handle regex domain names individually
		if (!main_domain) {
			all_hostnames = [domainname];
			main_domain = domainname;
		}

		// Ignore other domains, each domain will get its own certificate
		// because it's a real pain to handle this otherwise
		main_domain = domainname;
		all_hostnames = [main_domain];

		this.greenlock.add({
			subject         : main_domain,
			altnames        : all_hostnames,
			subscriberEmail : site.settings.letsencrypt_email || alchemy.settings.letsencrypt_email,
		});
	}

	this.greenlock.get({
		servername: domainname
	}).then(function gotResult(result) {

		if (!meta) {
			meta = that.getDomainMetaCache(domainname, true);
		}

		// prevent from being punked by bot trolls
		// (We'll recreate the object later)
		meta.refresh_at = Date.now() + small_stagger;

		if (!result) {
			return callback();
		}

		let pems = result.pems,
		    site = result.site;

		if (!pems || !pems.cert) {
			return callback();
		}

		meta = {
			refresh_at     : Date.now() + randomRefreshOffset(),
			secure_context : tls.createSecureContext({
				key  : pems.privkey,
				cert : pems.cert + '\n' + pems.chain + '\n'
			})
		};

		meta.secure_context._valid = true;

		let names = [],
		    name,
		    i;

		if (result.altnames) {
			names.include(result.altnames);
		}

		if (site.altnames) {
			names.include(site.altnames);
		}

		if (result.subject) {
			names.include(result.subject);
		}

		if (site.subject) {
			names.include(site.subject);
		}

		for (i = 0; i < names.length; i++) {
			name = names[i];

			that.sni_domain_cache[name] = meta;
		}

		callback(null, meta.secure_context);
	}).catch(function onError(err) {
		console.log('Greenlock error:', err);
		return callback(err);
	});
});

/**
 * Greenlock middleware
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 */
SiteDispatcher.setMethod(function greenlockMiddleware(req, res, next) {

	var hostname = this.sanitizeHostname(req);

	// Skip unless the path begins with /.well-known/acme-challenge/
	if (!hostname || req.url.indexOf(challenge_prefix) !== 0) {
		return next();
	}

	let token = req.url.slice(challenge_prefix.length);

	this.greenlock.challenges.get({
		type       : 'http-01',
		servername : hostname,
		token      : token
	}).then(function gotResult(result) {

		var key_auth = result && result.keyAuthorization;

		if (key_auth && typeof key_auth == 'string') {
			res.setHeader('Content-Type', 'text/plain; charset=utf-8');
			res.end(key_auth);
			return;
		}

		res.statusCode = 404;
		res.setHeader('Content-Type', 'application/json; charset=utf-8');

		res.end(JSON.stringify({
			error: {
				message: "domain '" + hostname + "' has no token '" + token + "'."
			}
		}));
	}).catch(function gotError(err) {
		res.end('Internal Server Error [1003]: See logs for details.');
	});
});

/**
 * Sanitize the hostname
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @return   {String}
 */
SiteDispatcher.setMethod(function sanitizeHostname(req) {

	var hostname = req.hostname || req.headers['x-forwarded-host'] || (req.headers.host || '');

	// we can trust XFH because spoofing causes no harm in this
	// limited use-case scenario
	// (and only telebit would be legitimately setting XFH)
	let servername = hostname.toLowerCase().replace(/:.*/, '');

	try {
		req.hostname = servername;
	} catch (e) {
		// read-only express property
	}

	if (req.headers['x-forwarded-host']) {
		req.headers['x-forwarded-host'] = servername;
	}

	try {
		req.headers.host = servername;
	} catch (e) {
		// TODO is this a possible error?
	}

	return (servername_re.test(servername) && -1 === servername.indexOf('..') && servername) || '';
});

/**
 * Handle request errors
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.4.1
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
		//log.error('Retried connection', req.connectionId, 'four times, giving up on:', req.url);
		this.respondWithError(res, 'unreachable', error);
	} else {
		let that = this;

		// Try the request again after 100ms
		setTimeout(function retry() {
			that.request(req, res);
		}, 100);
	}
});

/**
 * Get the site object based on the headers
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.4.1
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
		domain = headers[':authority'] || headers.host;
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
		if (matches = entry.site.matches(domain, ip)) {

			if (req && typeof matches == 'object') {
				req[MATCHED_GROUPS] = matches;
			}

			return entry;
		}
	}

	for (key in this.domains) {
		entry = this.domains[key];

		if (matches = entry.site.matches(domain, ip)) {

			if (req && typeof matches == 'object') {
				req[MATCHED_GROUPS] = matches;
			}

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
			return that.forwardRequest(req, socket, this.fallbackAddress, head);
		}

		socket.end('There is no such domain here!');
	} else {

		site.site.getAddress(req, function gotAddress(err, address) {

			if (err) {
				return socket.end('Error: ' + err);
			}

			//that.proxy.ws(req, socket, {target: address});
			that.forwardRequest(req, socket, address, head);
		});
	}
});

/**
 * Respond with an error
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.4.0
 * @version  0.4.1
 * 
 * @param    {ServerResponse}    res
 * @param    {String}            type
 * @param    {Error}             error   The original error
 */
SiteDispatcher.setMethod(function respondWithError(res, type, error) {

	let fallback,
	    status,
	    prop;

	if (type == 'not_found') {
		status = 404;
		fallback = 'There is no such domain here!';
		prop = 'not_found_message';
	} else if (type == 'unreachable') {
		status = 502;
		prop = 'unreachable_message';
		fallback = 'Failed to reach server!';

		if (error) {
			console.error('Failed to reach site:', error);
		}
	}

	let cached = this[prop];

	if (cached === false) {
		res.writeHead(status, {'Content-Type': 'text/plain'});
		return res.end(alchemy.settings[prop] || fallback);
	}

	if (cached) {
		res.writeHead(404, {'Content-Type': 'text/html'});
		return res.end(cached);
	}

	let that = this;

	let variables = {
		base_url : alchemy.settings.base_url_for_template,
		message  : alchemy.settings[prop] || fallback
	};

	alchemy.hawkejs.render('static/error', variables, function gotHtml(err, result) {

		if (err || !result) {
			that[prop] = false;
		} else {
			that[prop] = result;
		}

		that.respondWithError(res, type);
	});
});

/**
 * Handle a new proxy request
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.4.2
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
	    hit,
	    key;

	if (skip_le == null) {
		req.startTime = Date.now();
	}

	// Use the letsencrypt middleware first
	// (This looks for the acme challenges)
	if (skip_le !== true && alchemy.settings.letsencrypt !== false && this.proxyPortHttps) {

		this.greenlockMiddleware(req, res, function done() {
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

		if (res && res.socket) {
			res.socket.connectionId = req.socket.connectionId;
		}
	}

	req.connectionId = req.socket.connectionId;
	req.hitId = hit;

	req.headers.hitId = hit;
	req.headers.connectionId = req.connectionId;

	site = this.getSite(req);

	if (!site) {

		if (this.fallbackAddress) {
			return this.forwardRequest(req, res, this.fallbackAddress);
		}

		this.respondWithError(res, 'not_found');
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
 * Default web handler
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 */
SiteDispatcher.setMethod(function defaultWebHandler(err, req, res) {
	if (err) {
		return this.requestError(err, req, res);
	}
});

/**
 * Default websocket handler
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 */
SiteDispatcher.setMethod(function defaultWSHandler(err, req, socket, head) {
	if (err) {
		socket.destroy();
	}
});

/**
 * Proxy web request
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @param    {Buffer}   ws_head   The websocket head buffer
 */
SiteDispatcher.setMethod(function forwardRequest(req, res, forward_address, ws_head) {

	var config = {};

	// Original http-proxy forwarding
	//this.proxy.web(req, res, {target: forward_address});

	// @TODO: parse the forward address earlier?
	if (typeof forward_address == 'string') {
		let url = RURL.parse(forward_address);

		config.hostname = url.hostname;
		config.port = url.port || 80;
		config.protocol = url.protocol.slice(0, -1);
		
		// @TODO: if a path is set, add the req.originalUrl || req.url to the forward address path?

	} else if (typeof forward_address == 'object') {
		Object.assign(config, forward_address);
	}

	// @TODO: bind this beforehand?
	config.onReq = this.boundModifyIncomingRequest;

	if (ws_head) {
		// In this case, res is actually a socket
		this.proxy.ws(req, res, ws_head, config, this.boundDefaultWSHandler);
	} else {
		config.onRes = this.boundModifyOutgoingResponse;
		this.proxy.web(req, res, config, this.boundDefaultWebHandler);
	}
});

/**
 * Modify incoming proxy request
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.1
 *
 * @param    {IncomingMessage}   req
 * @param    {Object}            options   HTTP2-Proxy options object
 */
SiteDispatcher.setMethod(function modifyIncomingRequest(req, options) {

	var forwarded_for,
	    headers = options.headers,
	    host = req.headers.host || req.headers[':authority'],
	    site;

	headers['X-Proxied-By'] = 'hohenheim';

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
		headers['X-Real-IP'] = forwarded_for;
		headers['X-Forwarded-For'] = forwarded_for;
	}

	if (host) {
		headers['X-Forwarded-Host'] = host;
		headers['Host'] = host;
	}

	// Get the target site
	site = this.getSite(req);

	if (site) {
		req.hohenheim_site = site;

		// Set the custom header values
		if (site.domain && site.domain.headers && site.domain.headers.length) {
			site.domain.headers.forEach(function eachHeader(header) {
				if (header.name) {
					// Unset the header if it is an empty value
					if (!header.value) {
						if (headers[header.name]) {
							delete headers[header.name];
						}
					} else {
						headers[header.name] = header.value;
					}
				}
			});
		}
	}
});

/**
 * Modify outgoing proxy response
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @param    {IncomingMessage|Http2ServerRequest}   req
 * @param    {ServerResponse|Socket}                res
 * @param    {ServerResponse}                       proxyRes
 */
SiteDispatcher.setMethod(function modifyOutgoingResponse(req, res, proxyRes) {

	if (req.hohenheim_site && req.hohenheim_site.site.modifyResponse) {
		// The body can't really be modified since we haven't set `selfHandleResponse` yet
		req.hohenheim_site.site.modifyResponse(res, req, proxyRes, req.hohenheim_site.domain);
	}

	res.writeHead(proxyRes.statusCode, proxyRes.headers);
	proxyRes.pipe(res);
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