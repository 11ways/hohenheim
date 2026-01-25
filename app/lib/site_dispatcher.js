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
      servername_re = /^[a-z0-9\.\-]+$/i,
      NEWLINE_RE = /[\r\n]/g;

global.MATCHED_GROUPS = Symbol('matched_groups');

/**
 * The Site Dispatcher class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.7.0
 */
const SiteDispatcher = Function.inherits('Informer', 'Develry', function SiteDispatcher(options) {

	// Initialize domain miss log stream as null (lazy initialization)
	this._domain_miss_log_stream = null;
	this._domain_miss_log_stream_initialized = false;

	let that = this;

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

	// Sni cache (max 1000 entries, 24h TTL)
	this.sni_domain_cache = alchemy.getCache('sni_domain_cache', {max_length: 1000, max_age: 24 * 60 * 60 * 1000});

	// Cache for domains that don't match any site (prevents repeated regex matching)
	this.negative_domain_cache = alchemy.getCache('negative_domain_cache', {max_length: 5000, max_age: 5 * 60 * 1000});

	// Cache for domains that matched via regex (prevents repeated regex matching)
	this.regex_match_cache = alchemy.getCache('regex_match_cache', {max_length: 5000, max_age: 5 * 60 * 1000});

	// Registry for site-specific caches (remcache instances)
	// These are pruned periodically to clean up expired entries
	this.site_caches = new Map();

	// The rendered not-found template
	this.not_found_message = null;

	// Create the queue
	this.queue = Function.createQueue();

	// Start the queue by getting the sites first
	this.queue.start(function gettingSites(done) {
		Function.parallel(function getSites(next) {
			Pledge.done(that.Site.updateSites(), next);
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
	alchemy.on('site_update', this.update.bind(this));

	this.init();
});

/**
 * Initialize the dispatcher
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.2
 * @version  0.5.3
 */
SiteDispatcher.setMethod(async function init() {

	var that = this;

	// Populate the available users
	await this.getLocalUsers();

	// Get the local ip addresses
	this.getLocalIps();

	// Create the proxy server
	this.startProxy();

	process.on('exit', this.createExitHandler('exit'));
	process.on('SIGINT', this.createExitHandler('SIGINT'));
	process.on('SIGUSR1', this.createExitHandler('SIGUSR1'));
	process.on('SIGUSR2', this.createExitHandler('SIGUSR2'));

	// Bind some methods already
	this.boundModifyIncomingRequest = this.modifyIncomingRequest.bind(this);
	this.boundModifyOutgoingResponse = this.modifyOutgoingResponse.bind(this);
	this.boundDefaultWebHandler = this.defaultWebHandler.bind(this);
	this.boundDefaultWSHandler = this.defaultWSHandler.bind(this);

	// Start periodic pruning of site caches (every 20 minutes)
	this.startSiteCachePruning();
});

/**
 * Register a site's cache for periodic pruning
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {string}         site_id   The site's ID
 * @param    {Develry.Cache}  cache     The cache instance to register
 */
SiteDispatcher.setMethod(function registerSiteCache(site_id, cache) {
	if (site_id && cache) {
		this.site_caches.set(site_id, cache);
	}
});

/**
 * Unregister a site's cache
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {string}   site_id   The site's ID
 */
SiteDispatcher.setMethod(function unregisterSiteCache(site_id) {
	this.site_caches.delete(site_id);

	// Reset the iterator since the map changed
	this.site_cache_prune_iterator = null;
});

/**
 * Start the periodic site cache pruning (staggered)
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
SiteDispatcher.setMethod(function startSiteCachePruning() {

	const that = this;

	// Target: complete a full cycle through all caches in ~20 minutes
	const FULL_CYCLE_MS = 20 * 60 * 1000;

	// Fixed interval between prune ticks (30 seconds)
	const PRUNE_INTERVAL = 30 * 1000;

	// How many ticks we have per full cycle (40 ticks in 20 minutes)
	const TICKS_PER_CYCLE = Math.floor(FULL_CYCLE_MS / PRUNE_INTERVAL);

	// Iterator for cycling through caches
	this.site_cache_prune_iterator = null;

	const pruneNext = () => {

		// Calculate how many caches to prune this tick
		// to ensure a full cycle completes in ~20 minutes
		let site_count = that.site_caches.size;

		if (site_count > 0) {
			let caches_per_tick = Math.ceil(site_count / TICKS_PER_CYCLE);
			that.pruneNextSiteCaches(caches_per_tick);
		}

		// Schedule next prune at fixed interval
		that.site_cache_prune_timeout = setTimeout(pruneNext, PRUNE_INTERVAL);

		if (that.site_cache_prune_timeout.unref) {
			that.site_cache_prune_timeout.unref();
		}
	};

	// Start the first prune after a short delay
	this.site_cache_prune_timeout = setTimeout(pruneNext, PRUNE_INTERVAL);

	if (this.site_cache_prune_timeout.unref) {
		this.site_cache_prune_timeout.unref();
	}
});

/**
 * Prune the next N site caches in the rotation
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {number}   count   Number of caches to prune (default: 1)
 */
SiteDispatcher.setMethod(function pruneNextSiteCaches(count) {

	if (this.site_caches.size === 0) {
		return;
	}

	if (!count || count < 1) {
		count = 1;
	}

	for (let i = 0; i < count; i++) {
		// Get or create iterator
		if (!this.site_cache_prune_iterator) {
			this.site_cache_prune_iterator = this.site_caches.entries();
		}

		// Get next cache
		let next = this.site_cache_prune_iterator.next();

		// If we've reached the end, start over
		if (next.done) {
			this.site_cache_prune_iterator = this.site_caches.entries();
			next = this.site_cache_prune_iterator.next();

			// Still nothing? Map must be empty now
			if (next.done) {
				return;
			}
		}

		let [site_id, cache] = next.value;
		let length_before = cache.length;

		cache.prune();

		if (cache.length < length_before && alchemy.settings.debugging?.debug) {
			log.info('Pruned site cache for', site_id, ':', length_before - cache.length, 'entries removed');
		}
	}
});

/**
 * Create an exit handler
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.6.0
 */
SiteDispatcher.setMethod(function createExitHandler(type) {

	const that = this;

	return function onExit() {

		log.warning('Hohenheim is exiting: ' + type);

		for (let id in that.ids) {
			let site = that.ids[id];
			let process_count = site.process_list?.length || 0;

			if (!process_count) {
				continue;
			}

			for (let i = 0; i < process_count; i++) {
				let proc = site.process_list[i];

				if (proc) {
					proc.kill();
				}
			}
		}
	};

});

/**
 * Get or create the domain miss log stream (lazy initialization)
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @return   {WriteStream|null}
 */
SiteDispatcher.setMethod(function getDomainMissLogStream() {

	// Return cached stream if already initialized
	if (this._domain_miss_log_stream_initialized) {
		return this._domain_miss_log_stream;
	}

	this._domain_miss_log_stream_initialized = true;

	// Check if logging is enabled
	if (typeof LOG_DOMAIN_MISSES !== 'undefined' && !LOG_DOMAIN_MISSES) {
		return null;
	}

	// Get the log path
	let log_path = typeof DOMAIN_MISSES_LOG_PATH !== 'undefined' ? DOMAIN_MISSES_LOG_PATH : null;

	if (!log_path) {
		return null;
	}

	// Ensure directory exists
	let dir = libpath.dirname(log_path);

	try {
		fs.mkdirSync(dir, {recursive: true});
	} catch (err) {
		// Directory might already exist or we can't create it
		if (err.code !== 'EEXIST') {
			log.warn('Could not create domain miss log directory:', err);
		}
	}

	try {
		this._domain_miss_log_stream = fs.createWriteStream(log_path, {flags: 'a'});
		return this._domain_miss_log_stream;
	} catch (err) {
		log.warn('Could not open domain miss log file:', err);
		return null;
	}
});

/**
 * Log a domain miss for fail2ban integration
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {string}            ip        The client IP address
 * @param    {string}            domain    The requested domain
 * @param    {IncomingMessage}   req       The HTTP request (optional, may be null for SNI callbacks)
 */
SiteDispatcher.setMethod(function logDomainMiss(ip, domain, req) {

	let stream = this.getDomainMissLogStream();

	if (!stream) {
		return;
	}

	// Only log IPs that have shown suspicious behavior recently
	// This filters out legitimate mistakes (typos, old bookmarks, etc.)
	// while catching bots that probe multiple domains quickly
	let threshold = DOMAIN_MISSES_LOG_THRESHOLD;

	if (threshold > 0) {
		let reputation = Classes.Hohenheim.Reputation.get(ip);

		if (!reputation) {
			return;
		}

		// Calculate window in milliseconds (default 10 minutes)
		let window_ms = (DOMAIN_MISSES_WINDOW_MINUTES || 10) * 60 * 1000;

		// Check recent miss count (uses lazy cleanup internally)
		let recent_count = reputation.getRecentMissCount(window_ms, threshold);

		if (recent_count < threshold) {
			return; // Not enough recent misses, don't log
		}
	}

	let timestamp = new Date().toISOString();
	let path = req?.url || '-';
	let user_agent = req?.headers?.['user-agent'] || '-';

	// Sanitize values to prevent log injection
	domain = String(domain || '-').replace(NEWLINE_RE, '');
	path = String(path).replace(NEWLINE_RE, '');
	user_agent = String(user_agent).replace(NEWLINE_RE, '');
	ip = String(ip || '-').replace(NEWLINE_RE, '');

	let log_line = `${timestamp} DOMAIN_MISS ip=${ip} domain=${domain} path=${path} user_agent=${JSON.stringify(user_agent)}\n`;

	stream.write(log_line);
});

/**
 * Update the local users
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.5.3
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

	return local_users;
});

/**
 * Get the local ip addresses
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.5.3
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

	return local_ips;
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
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.2.0
 * @version  0.6.0
 */
SiteDispatcher.setMethod(function initGreenlock() {

	var that = this,
	    server_url,
	    debug,
	    Site;

	if (this._inited_greenlock) {
		return;
	}

	if (!this.proxyPortHttps) {
		return log.warn('HTTPS is disabled');
	}

	this._inited_greenlock = true;

	if (LETSENCRYPT_ENABLED === false) {
		return log.warn('Letsencrypt support is disabled');
	}

	if (!LETSENCRYPT_EMAIL) {
		return log.error('Can\'t enable letsencrypt: no letsencrypt_email is set');
	}

	if (alchemy.settings.debug && LETSENCRYPT_DEBUG) {
		console.warn('Enabling letsencrypt debugging');
		debug = true;
	} else {
		debug = false;
	}

	if (alchemy.settings.environment != 'live' || alchemy.settings.debug || LETSENCRYPT_DEBUG) {
		console.warn('Using letsencrypt staging servers');
		server_url = 'https://acme-staging-v02.api.letsencrypt.org/directory';
	} else {
		server_url = 'https://acme-v02.api.letsencrypt.org/directory';
	}

	// Create a site model instance
	Site = Model.get('Site');

	// Create the greenlock instance
	this.greenlock = GreenLock.create({
		// for an RFC 8555 / RFC 7231 ACME client user agent
		packageAgent    : alchemy.package.name + '/' + alchemy.package.version,
		packageRoot     : PATH_TEMP,
		configDir       : libpath.resolve(PATH_TEMP, 'greenlock.d'),
		manager         : '@greenlock/manager',
		maintainerEmail : LETSENCRYPT_EMAIL,
		subscriberEmail : LETSENCRYPT_EMAIL,
		staging         : !!LETSENCRYPT_STAGING,

		notify: function notify(event, details) {
			if (event == 'error') {
				console.error('Greenlock error:', details);

				if (details?.code == 'E_ACME') {
					if (details.context == 'cert_issue' && details.subject) {
						// Just remove the troublesome domain from Greenlock
						// @TODO: This doesn't actually do anything...
						that.greenlock.manager.remove({subject: details.subject});
					}
				}

			} else if (debug) {
				console.log('Greenlock notification:', event, details);
			}

		}
	});

	this.greenlock.manager.defaults({
		agreeToTerms: true,
		subscriberEmail: LETSENCRYPT_EMAIL,
		store: {
			module: 'greenlock-store-fs',
			basePath: libpath.resolve(PATH_TEMP, 'letsencrypt', 'etc'),
		}
	});

	// Create the HTTPS/http2 server
	this.https_server = http2.createSecureServer({
		allowHTTP1  : true,
		SNICallback : function sniCallback(servername, next) {
			return that.SNICallback(servername, this, next);
		}
	});

	// Listen for incoming connections
	this.https_server.on('connection', socket => {
		let reputation = Classes.Hohenheim.Reputation.get(socket);

		if (reputation.isNegative()) {
			socket.destroy();
			return;
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
 * @version  0.6.0
 *
 * @param    {string}     domainname
 * @param    {TLSSocket}  socket
 * @param    {function}   callback
 */
SiteDispatcher.setMethod(function SNICallback(domainname, socket, callback) {

	if (typeof domainname != 'string') {
		return callback(new Error('SNI failure: invalid domainname'));
	}

	let reputation = Classes.Hohenheim.Reputation.get(socket);
	reputation.registerDomainRequest(domainname);

	let site = this.getSite(domainname);

	if (!site) {
		reputation.registerDomainMiss(domainname);

		// Log domain miss for fail2ban (SNI stage - no HTTP request yet)
		this.logDomainMiss(socket.remoteAddress, domainname, null);

		alchemy.distinctProblem('sni-unknown-domain-' + domainname, 'Failed to find "' + domainname + '", ignoring SNI request', {
			// Allow the warning to repeat every 15 minutes
			repeat_after: 15 * 60 * 1000,
		});

		return callback(new Error('Domain "' + domainname + '" was not found on this server'));
	}

	reputation.registerDomainHit();

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
 * @version  0.7.0
 *
 * @param    {String}   domainname
 *
 * @return   {Object}
 */
SiteDispatcher.setMethod(function getDomainMetaCache(domainname, create) {
	let cache = this.sni_domain_cache.get(domainname);

	if (!cache && create) {
		cache = {
			secure_context: {
				_valid : false
			}
		};

		this.sni_domain_cache.set(domainname, cache);
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
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.4.0
 * @version  0.7.0
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
	}

	let site_record = site._record;

	// Need to add this to greenlock first
	if (!meta) {
		let all_hostnames = site_record.getHostnames(true),
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
			subscriberEmail : site_record.settings.letsencrypt_email || LETSENCRYPT_EMAIL,
		});
	}

	// Add timeout to prevent greenlock from hanging indefinitely
	// (e.g., network issues, Let's Encrypt rate limits, etc.)
	let timed_out = false;
	let timeout = setTimeout(() => {
		timed_out = true;
		callback(new Error('Greenlock timeout after 30 seconds'));
	}, 30 * 1000);

	this.greenlock.get({
		servername: domainname
	}).then(function gotResult(result) {

		if (timed_out) {
			return;
		}

		clearTimeout(timeout);

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

			that.sni_domain_cache.set(name, meta);
		}

		callback(null, meta.secure_context);
	}).catch(function onError(err) {

		if (timed_out) {
			return;
		}

		clearTimeout(timeout);
		alchemy.registerError(err, {context: 'Greenlock SNI error'});
		return callback(err);
	});
});

/**
 * Remove a domain from Greenlock so it stops trying to renew certificates.
 * 
 * Note: Greenlock's manager.remove() is broken (missing return statement),
 * so we manually get the site, set deletedAt, and save it back.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {string}   domain
 */
SiteDispatcher.setMethod(async function removeFromGreenlock(domain) {

	if (!LETSENCRYPT_ENABLED || !this.greenlock) {
		return;
	}

	// Skip wildcard and regex patterns - they're not real Greenlock subjects
	if (domain.includes('*') || domain.includes('(')) {
		return;
	}

	try {
		// Get the site from Greenlock's manager
		let site = await this.greenlock.manager.get({servername: domain});

		if (!site) {
			return;
		}

		// Mark as deleted and save back
		site.deletedAt = Date.now();
		await this.greenlock.manager.set(site);

	} catch (err) {
		log.warn('Error removing domain from Greenlock:', domain, err.message);
	}

	// Also clear from our SNI cache
	if (this.sni_domain_cache) {
		this.sni_domain_cache.remove(domain);
	}
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
		alchemy.registerError(err, {context: 'Greenlock challenge error'});
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
 * @version  0.5.4
 * 
 * @param    {Error}              error
 * @param    {IncommingMessage}   req
 * @param    {ServerResponse}     res
 */
SiteDispatcher.setMethod(function requestError(error, req, res) {

	if (!req) {
		throw new Error('Request error without request? ' + error);
	}

	if (req.code === 'ECONNREFUSED') {
		this.respondWithError(res, 'refused', error);
		return;
	}

	this.respondWithError(res, 'unreachable', error);
});

/**
 * Get the site object based on the headers
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.6.0
 * 
 * @param    {string|Object}   req_or_domain
 *
 * @return   {Develry.Site}
 */
SiteDispatcher.setMethod(function getSite(req_or_domain) {
	let pair = this.getSiteDomainPair(req_or_domain);
	return pair?.site;
});

/**
 * Get the site object based on the headers
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.7.0
 * 
 * @param    {string|Object}   req_or_domain
 *
 * @return   {Object<string, Develry.Site>}
 */
SiteDispatcher.setMethod(function getSiteDomainPair(req_or_domain) {

	// Get the host (including port)
	let headers,
	    domain,
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
		// Return cached result if we've already looked this up for this request
		if (req.hohenheim_site !== undefined) {
			return req.hohenheim_site;
		}

		headers = req.headers;

		if (req.socket) {
			ip = req.socket.localAddress;
		}
	}

	if (headers) {
		domain = headers[':authority'] || headers.host;
	}

	if (!domain) {
		if (req) {
			req.hohenheim_site = null;
		}
		return null;
	}

	// Helper to cache result on request object before returning
	const cacheAndReturn = (result) => {
		if (req) {
			req.hohenheim_site = result;
		}
		return result;
	};

	let matches,
	    entry,
	    key;

	// Strip port from domain if present
	let colonIndex = domain.indexOf(':');
	if (colonIndex > -1) {
		domain = domain.substring(0, colonIndex);
	}

	// Check negative cache first to avoid repeated regex matching for unknown domains
	let cache_key = domain + (ip ? ':' + ip : '');

	if (this.negative_domain_cache.get(cache_key)) {
		return cacheAndReturn(null);
	}

	// Check positive regex match cache
	let cached_match = this.regex_match_cache.get(cache_key);

	if (cached_match) {
		if (req && cached_match.groups) {
			req[MATCHED_GROUPS] = cached_match.groups;
		}

		return cacheAndReturn(cached_match.entry);
	}

	if (this.domains[domain] != null) {
		entry = this.domains[domain];

		// When we don't have to match an ip address,
		// just return the entry
		if (!ip) {
			return cacheAndReturn(entry);
		}

		// We do have an ip address to match
		if (matches = entry.site.matches(domain, ip)) {

			if (req && typeof matches == 'object') {
				req[MATCHED_GROUPS] = matches;
			}

			return cacheAndReturn(entry);
		}
	}

	for (key in this.domains) {
		entry = this.domains[key];

		if (matches = entry.site.matches(domain, ip)) {

			if (req && typeof matches == 'object') {
				req[MATCHED_GROUPS] = matches;
			}

			// Cache this positive regex match to avoid repeated regex matching
			this.regex_match_cache.set(cache_key, {
				entry  : entry,
				groups : typeof matches == 'object' ? matches : null,
			});

			return cacheAndReturn(entry);
		}
	}

	// Cache this negative result to avoid repeated regex matching
	this.negative_domain_cache.set(cache_key, true);

	return cacheAndReturn(null);
});

/**
 * Handle a new proxy request
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.2.0
 * @version  0.6.0
 * 
 * @param    {IncommingMessage}   req
 * @param    {Socket}             socket
 * @param    {Buffer}             head
 */
SiteDispatcher.setMethod(function websocketRequest(req, socket, head) {

	const that = this;

	// Detect infinite loops
	// @TODO: this will break after the first loop,
	// maybe add a counter to allow more loops in case it's wanted functionality?
	if (req.headers['x-proxied-by'] == 'hohenheim') {
		return socket.end();
	}

	// Get the hit id
	let hit = ++this.hitCounter;

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

	let site = this.getSite(req);

	if (!site) {

		if (this.fallbackAddress) {
			return that.forwardRequest(req, socket, this.fallbackAddress, head);
		}

		socket.end('There is no such domain here!');
	} else {

		// Track WebSocket bytes for this site
		that.trackWebSocketBytes(socket, site);

		site.getAddress(req, function gotAddress(err, address) {

			if (err) {
				return socket.end('Error: ' + err);
			}

			//that.proxy.ws(req, socket, {target: address});
			that.forwardRequest(req, socket, address, head);
		});
	}
});

/**
 * Track bytes transferred over a WebSocket connection for a site.
 * This wraps the socket's write method and listens to data events
 * to track incoming and outgoing bytes.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {Socket}         socket   The WebSocket socket
 * @param    {Develry.Site}   site     The site instance
 */
SiteDispatcher.setMethod(function trackWebSocketBytes(socket, site) {

	// Prevent double-tracking if called multiple times for the same socket
	if (socket._hohenheimTracked) {
		return;
	}
	socket._hohenheimTracked = true;

	// Track bytes read (incoming from client)
	let bytesReadStart = socket.bytesRead || 0;

	// Track bytes written (outgoing to client)
	let bytesWrittenStart = socket.bytesWritten || 0;

	// When the socket closes, calculate the total bytes transferred
	socket.once('close', function onClose() {
		let bytesRead = (socket.bytesRead || 0) - bytesReadStart;
		let bytesWritten = (socket.bytesWritten || 0) - bytesWrittenStart;

		// Add to site's totals
		site.incoming += bytesRead;
		site.outgoing += bytesWritten;
		site.hitCounter++;
	});
});

/**
 * Respond with an error
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.4.0
 * @version  0.7.0
 * 
 * @param    {ServerResponse}    res
 * @param    {String}            type
 * @param    {Error}             error   The original error
 */
SiteDispatcher.setMethod(function respondWithError(res, type, error) {

	let fallback,
	    status,
	    prop,
	    problem_id;

	if (type == 'not_found') {
		status = 404;
		fallback = 'There is no such domain here!';
		prop = 'not_found_message';
		problem_id = 'not-found-domain';
	} else if (type == 'unreachable') {
		status = 502;
		prop = 'unreachable_message';
		problem_id = 'unreachable-site';
		fallback = 'Failed to reach server!';
	} else if (type == 'refused') {
		status = 502;
		prop = 'refused_message';
		problem_id = 'refused-site';
		fallback = 'Connection refused!';
	} else {
		problem_id = 'unknown-problem';
	}

	if (error && error.address) {
		if (error.address) {
			problem_id += '-' + error.address;
		}
	}

	alchemy.distinctProblem(problem_id, fallback || problem_id, {
		error,
		// Allow the warning to repeat every 15 minutes
		repeat_after: 15 * 60 * 1000,
	});

	let cached = this[prop];

	// Get the message from the global variable or use fallback
	let message;
	if (prop == 'not_found_message') {
		message = NOT_FOUND_MESSAGE || fallback;
	} else if (prop == 'unreachable_message') {
		message = UNREACHABLE_MESSAGE || fallback;
	} else {
		message = fallback;
	}

	if (cached === false) {
		res.writeHead(status, {'Content-Type': 'text/plain'});
		return res.end(message);
	}

	if (cached) {
		res.writeHead(404, {'Content-Type': 'text/html'});
		return res.end(cached);
	}

	let that = this;

	let variables = {
		base_url : BASE_URL_FOR_TEMPLATE,
		message  : message
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
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.6.0
 * 
 * @param    {IncomingMessage}    req
 * @param    {ServerResponse}     res
 * @param    {Boolean}            skip_le   Skips letsconnect middleware if true
 */
SiteDispatcher.setMethod(function request(req, res, skip_le) {

	if (skip_le == null) {
		req.startTime = Date.now();
	}

	// Use the letsencrypt middleware first
	// (This looks for the acme challenges)
	if (skip_le !== true && LETSENCRYPT_ENABLED !== false && this.proxyPortHttps) {

		this.greenlockMiddleware(req, res, () => {
			// Greenlock didn't do anything, we can continue
			this.request(req, res, true);
		});

		return;
	}

	// Detect infinite loops
	// @TODO: this will break after the first loop,
	// maybe add a counter to allow more loops in case it's wanted functionality?
	if (req.headers['x-proxied-by'] == 'hohenheim' && req.headers['x-hohenheim-id'] == alchemy.discovery_id) {
		res.writeHead(508, {'Content-Type': 'text/plain'});
		return res.end('Loop detected!');
	}

	// Get the hit id
	let hit = ++this.hitCounter;

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

	let site = this.getSite(req);

	if (!site) {
		// Log domain miss for fail2ban (HTTP request stage)
		let client_ip = req.socket?.remoteAddress || req.connection?.remoteAddress;
		this.logDomainMiss(client_ip, req.headers?.host, req);

		if (this.fallbackAddress) {
			return this.forwardRequest(req, res, this.fallbackAddress);
		}

		this.respondWithError(res, 'not_found');
	} else {

		// When using letsencrypt, redirect to HTTPS
		if (LETSENCRYPT_ENABLED && this.proxyPortHttps && !req.connection.encrypted) {

			// Is HTTPS forced for all sites?
			let force_https = this.force_https;

			// If https is not forced, see if it is forced in the site's config
			if (!force_https && site.settings && site.settings.letsencrypt_force) {
				force_https = true;
			}

			if (force_https) {
				let host = req.headers.host;
				let new_location = 'https://' + host.replace(/:\d+/, ':' + this.proxyPortHttps) + req.url;

				res.writeHead(302, {'Location': new_location});
				res.end();
				return;
			}
		}

		// Only register this hit if the error count has not been set
		// meaning it's the first time this request has passed through here
		if (!req.errorCount) {
			site.registerHit(req, res);
		}

		site.checkAuthenticationAndHandleRequest(req, res);
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
 * @version  0.5.4
 *
 * @param    {Buffer}   ws_head   The websocket head buffer
 */
SiteDispatcher.setMethod(function forwardRequest(req, res, forward_address, ws_head) {

	// @TODO: parse the forward address earlier?
	if (typeof forward_address == 'string') {
		let url = RURL.parse(forward_address);

		forward_address = {
			hostname : url.hostname,
			port     : url.port || 80,
			protocol : url.protocol.slice(0, -1),
		};
	}

	let config = {
		...forward_address,
		onReq : this.boundModifyIncomingRequest,
		onRes : ws_head ? null : this.boundModifyOutgoingResponse,
		timeout      : 10 * 60 * 1000,
		proxyTimeout : 10 * 60 * 1000,
	};

	if (ws_head) {
		// In this case, res is actually a socket
		this.proxy.ws(req, res, ws_head, config, this.boundDefaultWSHandler);
	} else {
		this.proxy.web(req, res, config, this.boundDefaultWebHandler);
	}
});

/**
 * Modify incoming proxy request
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.6.0
 *
 * @param    {IncomingMessage}   req
 * @param    {Object}            options   HTTP2-Proxy options object
 */
SiteDispatcher.setMethod(function modifyIncomingRequest(req, options) {

	let headers = options.headers,
	    host = req.headers.host || req.headers[':authority'];

	headers['X-Proxied-By'] = 'hohenheim';
	headers['X-Hohenheim-Id'] = alchemy.discovery_id;

	if (req.connection?.remoteAddress) {

		const hohenheim_key = req.headers['x-hohenheim-key'];

		let x_forwarded_for,
		    x_real_ip;

		// If there is a hohenheim key, and it is correct,
		// we can use the original header information
		if (hohenheim_key && REMOTE_PROXY_KEYS?.length && REMOTE_PROXY_KEYS.includes(hohenheim_key)) {
			// See if there already is an x-forwarded-for
			let forwarded_for = req.headers['x-forwarded-for'],
			    real_ip = req.headers['x-real-ip'];
			
			if (!forwarded_for && real_ip) {
				forwarded_for = real_ip;
			}

			if (forwarded_for && !real_ip) {
				real_ip = forwarded_for.split(',')[0].trim();
			}

			if (forwarded_for) {
				forwarded_for += ', ' + req.connection.remoteAddress;
			} else {
				forwarded_for = req.connection.remoteAddress;
			}

			if (!real_ip) {
				real_ip = req.connection.remoteAddress;
			}

			x_forwarded_for = forwarded_for;
			x_real_ip = real_ip;
		}

		if (!x_forwarded_for) {
			x_forwarded_for = req.connection.remoteAddress;
		}

		if (!x_real_ip) {
			x_real_ip = req.connection.remoteAddress;
		}

		// Set the original ip address
		headers['X-Real-IP'] = x_real_ip;
		headers['X-Forwarded-For'] = x_forwarded_for;
	}

	if (host) {
		headers['X-Forwarded-Host'] = host;
		headers['Host'] = host;
	}

	// Get the target site (cached on req.hohenheim_site by getSiteDomainPair)
	let site_domain_pair = this.getSiteDomainPair(req);

	if (site_domain_pair) {
		// Set the custom header values
		if (site_domain_pair.domain?.headers?.length) {
			for (let header of site_domain_pair.domain.headers) {
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
			}
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
 * @version  0.6.0
 */
SiteDispatcher.setMethod(function getTestPort(start) {

	let port = start || this.firstPort;

	while (port < 65535 && port !== this.proxyPort && typeof this.ports[port] !== 'undefined') {
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
	    timed_out = false,
	    port;

	// Timeout after 30 seconds to prevent hanging if ports are exhausted
	let timeout = setTimeout(() => {
		timed_out = true;
		callback(new Error('Timeout finding free port after 30 seconds'));
	}, 30 * 1000);

	Function.while(function test() {
		if (timed_out) {
			return false;
		}

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

		if (timed_out) {
			return;
		}

		clearTimeout(timeout);

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
 * Register a site by a domain
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.7.0
 *
 * @param    {string}         hostname
 * @param    {Develry.Site}   site
 */
SiteDispatcher.setMethod(function registerSiteByHostname(hostname, site) {
	this.domains[hostname] = {
		site   : site,
		domain : hostname,
	};

	// Clear domain caches when a new hostname is registered
	// (it might have been cached differently before)
	this.negative_domain_cache.clear();
	this.regex_match_cache.clear();
});

/**
 * Update the sites
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.7.0
 *
 * @param    {Map}   sites_by_id   A map of site records by their id
 */
SiteDispatcher.setMethod(function update(sites_by_id) {

	let SiteConstructor,
	    site,
	    name,
	    key,
	    id;

	// Pause the dispatcher queue while we update
	this.queue.pause();

	log.info('Updating sites ...');

	// An object of all the removed sites
	let removed = alchemy.getDifference(this.ids, sites_by_id);

	// Destroy all the removed id sites
	for (id in removed) {
		this.ids[id].remove();
	}

	let created = alchemy.getDifference(sites_by_id, this.ids);

	// Create all the new sites
	for (id in created) {
		site = created[id];

		log.info('Enabling site', id, site.name);

		SiteConstructor = site_types[site.site_type];

		if (!SiteConstructor) {
			SiteConstructor = Classes.Develry.Site;
		}

		let new_site = new SiteConstructor(this, site);

		// Emit event so StatsCollector and other listeners can subscribe
		this.emit('site_added', new_site);
	}

	let shared = alchemy.getShared(this.ids, sites_by_id);

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