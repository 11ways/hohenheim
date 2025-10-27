const libpath = require('path');
let ProteusRealm;

/**
 * The Site class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.6.0
 *
 * @param    {Develry.SiteDispatcher}   siteDispatcher
 * @param    {Object}                   record
 */
const Site = Function.inherits('Alchemy.Base', 'Develry', function Site(siteDispatcher, record) {

	// The site dispatcher
	this.dispatcher = siteDispatcher;

	// The id in the database
	this.id = record._id;

	// The incoming bytes
	this.incoming = 0;

	// The outgoing bytes
	this.outgoing = 0;

	// Counters per path
	this.pathCounters = {};

	// The redirecthost
	this.redirectHost = siteDispatcher.redirectHost;

	// The request log model
	this.Log = Model.get('Request');

	// The ProcLog
	this.Proclog = Model.get('Proclog');

	// The site settings
	this.settings = record.settings || {};

	// The optional proteus realm id
	this.proteus_realm_id = null;

	// The optional proteus realm permission to check
	this.proteus_realm_permission = null;

	// The optional basic auth credentials
	this.basic_auth = null;

	this.update(record);
});

// Use "dispatcher" instead of "parent" property
Site.setDeprecatedProperty('parent', 'dispatcher');

/**
 * This is a wrapper class
 */
Site.makeAbstractClass();

/**
 * This wrapper class starts a new group
 */
Site.startNewGroup('site_type');

/**
 * Return the class-wide schema
 *
 * @type   {Schema}
 */
Site.setProperty(function schema() {
	return this.constructor.schema;
});

/**
 * Remote cache the instances can use
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.4.0
 * @version  0.4.0
 *
 * @type     {Develry.Cache}
 */
Site.prepareProperty(function remcache() {
	return new Classes.Develry.Cache();
});

/**
 * Set the site type schema
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.1.0
 * @version  0.5.3
 */
Site.constitute(function setSchema() {

	var schema;

	// Create a new schema
	schema = new Classes.Alchemy.Schema(this);

	// If letsencrypt is enabled, allow the user to set certain parameters
	if (alchemy.settings.letsencrypt) {
		schema.addField('letsencrypt_email', 'String');
		schema.addField('letsencrypt_force', 'Boolean', {default: true});
	}

	schema.addField('delay', 'Number', {
		description: 'Delay in ms before forwarding the request',
	});

	this.schema = schema;
});

/**
 * See if this site matches the given hostname
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.6.0
 *
 * @param    {String}    hostname   The hostname
 * @param    {String}    [ip]       The optional ip to match
 *
 * @return   {Boolean|Object}   Returns true if the hostname matches
 */
Site.setMethod(function matches(hostname, ip) {

	if (!hostname) {
		return false;
	}

	let domain,
	    ip2,
	    i,
	    j;

	if (ip) {
		ip2 = '::ffff:' + ip;
	}

	for (i = 0; i < this.domains.length; i++) {
		domain = this.domains[i];

		// If an ip is given, it has to match
		if (ip) {
			let found = false;

			// If no ips are configured here, continue
			if (!domain.listen_on) {
				continue;
			}

			for (j = 0; j < domain.listen_on.length; j++) {
				let entry = domain.listen_on[j];

				if (entry == 'any' || entry == ip || entry == ip2) {
					found = true;
					break;
				}
			}

			if (!found) {
				continue;
			}
		}

		if (domain.hostname && domain.hostname.length) {
			for (j = 0; j < domain.hostname.length; j++) {
				if (domain.hostname[j] == hostname) {
					return true;
				}
			}
		}

		let regex_count = domain.regexes?.length || 0;

		if (regex_count > 0) {

			// Do not allow git subdomains in regexes (temporary workaround for brute force protection)
			if (hostname.indexOf('git.') > -1 || hostname.indexOf('gitlab.') > -1) {
				continue;
			}

			// Skip hostnames that contain double www subdomains
			if (hostname.indexOf('www.www.') > -1) {
				continue;
			}

			if (hostname.includes('notexist')) {
				continue;
			}

			if (hostname.includes('.www')) {
				continue;
			}

			if (hostname.includes('wwww')) {
				continue;
			}

			let matched;

			for (j = 0; j < regex_count; j++) {
				matched = domain.regexes[j].exec(hostname);

				if (matched !== null) {

					let count_allowed_dots = (''+domain.regexes[j]).count('\\.'),
					    count_found_dots = hostname.count('.');

					// If the amount of dots in the regex is less than the amount of dots in the hostname,
					// the regex is probably too broad
					if ((count_allowed_dots+1) < count_found_dots) {
						continue;
					}

					if (matched.groups) {
						let groups = matched.groups;

						// Brute force protection:
						// Checks the "project" match in domains using a Regex matcher
						if (groups.project && groups.project.indexOf('.') > -1) {
							return false;
						}

						return matched.groups;
					}

					return true;
				}
			}
		}
	}

	return false;
});

/**
 * Start a new process
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {Function}   callback
 */
Site.setMethod(function start(callback) {
	callback(new Error('Start method has not been implemented'));
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
 */
Site.setMethod(function getAddress(req, callback) {
	callback(new Error('GetAddress method has not been implemented'));
});

/**
 * Remove this site completely
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 */
Site.setMethod(function remove() {
	this.cleanParent();
});

/**
 * Remove this site from the parent entries
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.6.0
 */
Site.setMethod(function cleanParent() {

	const dispatcher = this.dispatcher;

	// Delete the entry by ID
	delete dispatcher.ids[this.id];

	// Remove this instance from the parent's domains
	for (let domain in dispatcher.domains) {
		if (dispatcher.domains[domain]?.site == this) {
			delete dispatcher.domains[domain];
		}
	}

	// Remove this instance from the dispatcher's names
	for (let name in dispatcher.names) {
		if (dispatcher.names[name] == this) {
			delete dispatcher.names[name];
		}
	}
});

/**
 * Update this site,
 * recreate the entries in the parent dispatcher
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.6.0
 *
 * @param    {Object}   record
 */
Site.setMethod(function update(record) {

	// The db record itself
	this._record = record;

	this.name = record.name;
	this.slug = record.slug || record.name?.slug();
	this.domains = record.domain || [];
	this.settings = record.settings || {};
	this.script = this.settings.script;

	this.proteus_realm_id = record.proteus_realm_id;
	this.proteus_realm_permission = record.proteus_realm_permission;
	this.basic_auth = record.basic_auth;

	// Another permission fallback check
	if (this.proteus_realm_id && !this.proteus_realm_permission) {
		this.proteus_realm_permission = 'hohenheim.site.' + this.slug;
	}

	if (this.script) {
		this.cwd = libpath.dirname(this.script);
	}

	// Remove this instance from the parent
	this.remove();

	// Add by id
	this.dispatcher.ids[this.id] = this;

	// Store it by each domain name
	for (let domain of this.domains) {

		// Clear old regexes before rebuilding to prevent accumulation
		domain.regexes = [];

		if (!domain.hostname?.length) {
			continue;
		}

		for (let hostname of domain.hostname) {

			if (!hostname) {
				log.warn('Site', record.name, 'has no hostname in entry', domain);
				continue;
			}

			let regex;

			// Check for regexes
			if (hostname[0] == '/') {
				regex = RegExp.interpret(hostname);
			} else if (hostname.indexOf('*') > -1 || hostname.indexOf('?') > -1) {
				regex = interpretWildcard(hostname, 'i');
			}

			if (regex) {
				domain.regexes.push(regex);
			}

			// Ignore accidental 'null' (string) values
			if (hostname && hostname != 'null') {
				this.dispatcher.registerSiteByHostname(hostname, this);
			}
		};
	};

	// Re-add the instance by name
	this.dispatcher.names[this.name] = this;

	// Emit the updat event
	this.emit('updated');
});

/**
 * Interpret wildcard strings
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.4
 * @version  0.3.4
 */
function interpretWildcard(str, flags) {
	var pattern = RegExp.escape(str).replace(/\\\*/g, '.*').replace(/\\\?/g, '.');

	if (!flags) {
		flags = 'g';
	} else if (flags.indexOf('g') == -1) {
		flags += 'g';
	}

	return RegExp.interpret(pattern, flags);
}

/**
 * Check any authentication & handle the request
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.6.0
 */
Site.setMethod(function checkAuthenticationAndHandleRequest(req, res) {

	if (this.proteus_realm_id) {
		return this.handleProteusAuth(req, res);
	}

	if (this.basic_auth?.length) {
		return this.checkBasicAuth(req, res, () => {
			this.handleRequest(req, res);
		});
	}

	// No authentication needed (by Hohenheim at least)
	// so let it continue
	return this.handleRequest(req, res);
});


/**
 * Get a local proteus realm document by its id.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @param    {ObjectId|String}   proteus_realm_id
 *
 * @return   {Document.ProteusRealm|Promise<Document.ProteusRealm>}
 */
Site.setMethod(function getProteusRealm(proteus_realm_id) {

	if (!proteus_realm_id) {
		return;
	}

	if (ProteusRealm == null) {
		ProteusRealm = Model.get('ProteusRealm');
	}

	return ProteusRealm.getCachedRealm(proteus_realm_id);
});

/**
 * Handle Proteus authentication & handle the request when done
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.6.0
 */
Site.setMethod(async function handleProteusAuth(req, res) {

	let realm = this.getProteusRealm(this.proteus_realm_id);

	if (Pledge.isThenable(realm)) {
		realm = await realm;
	}

	if (!realm) {
		throw new Error('Authentication error: Proteus realm not found');
	}

	let conduit = new Classes.Alchemy.Conduit.Proxied(req, res);
	let proteus_identity = conduit.session('proteus_identity');

	// If the identity if already set, everything is good!
	if (proteus_identity) {
		this.handleRequestWithProteusIdentity(req, res, proteus_identity);
		return true;
	}

	// See if there is a persistent cookie
	let acpl = conduit.cookie('acpl');

	if (acpl) {
		try {
			let Persistent = conduit.getModel('ProteusPersistentCookie');
			let user = await Persistent.getUserFromCookieForLogin(conduit, acpl);

			if (user) {
				conduit.session('proteus_identity', user);
				this.handleRequestWithProteusIdentity(req, res, user);
				return true;
			}

		} catch (err) {
			alchemy.registerError(err);
		}
	}

	let proteus_client = realm.getClientInstance();

	if (!proteus_client) {
		return false;
	}

	let type = conduit.param('proteus');

	if (type == 'verify') {
		let controller = Controller.get('ProxiedAclStatic', conduit);
		controller.proteus = proteus_client;
		controller.doAction('proteusVerifyLogin', [conduit]);
	} else {
		conduit.session('afterLogin', {
			url : '' + conduit.url,
		});

		proteus_client.startLogin(conduit, realm, this);
	}
});

/**
 * Handle a request with the given Proteus identity
 * (This checks for the correct permissions)
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.6.0
 * @version  0.6.0
 *
 * @param    {IncomingMessage}    req
 * @param    {ServerResponse}     res
 * @param    {Object}             identity
 */
Site.setMethod(function handleRequestWithProteusIdentity(req, res, identity) {

	if (this.proteus_realm_permission) {
		const permissions = identity.permissions;

		if (!permissions || !permissions.hasPermission(this.proteus_realm_permission)) {
			res.writeHead(403);
			res.end('Forbidden');
			return;
		}
	}

	this.handleRequest(req, res);
});

/**
 * Check for basic auth
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.2.0
 */
Site.setMethod(function checkBasicAuth(req, res, next) {

	var b64auth,
	    truthy;

	if (this.basic_auth && this.basic_auth.length) {

		for (let i = 0; i < this.basic_auth.length; i++) {
			let val = this.basic_auth[i];

			if (!val || val == 'null') {
				continue;
			} else {
				truthy = true;
				break;
			}
		}

		if (!truthy) {
			return next();
		}

		// Deny by default
		let deny = true;

		// Get the credentials after the initial 'Basic ' string
		let b64auth = (req.headers.authorization || '').split(' ')[1] || '';

		// Decode them
		let credentials = new Buffer(b64auth, 'base64').toString().trim();

		if (credentials) {
			for (let i = 0; i < this.basic_auth.length; i++) {
				if (this.basic_auth[i] == credentials) {
					deny = false;
					break;
				}
			}
		}

		if (deny) {
			res.writeHead(401, {'WWW-Authenticate': 'Basic realm="' + this._record.name + '"'});
			res.end('Unauthorized');
		} else {
			next();
		}
	} else {
		// No basic auth configured
		return next();
	}
});

/**
 * This site has been hit,
 * register some metrics
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.6.0
 * 
 * @param    {IncommingMessage}   req
 * @param    {ServerResponse}     res
 * @param    {Function}           callback
 */
Site.setMethod(function registerHit(req, res, callback) {

	const that = this;

	let written_http2 = 0;

	let bytesPrevRead,
	    remoteAddress,
	    bytesRead,
	    fullPath,
	    finished,
	    start,
	    path,
	    read;

	fullPath = req.url;

	// Create new date
	start = new Date();

	// Get the wanted path
	path = fullPath.split('?')[0];

	// Get the previous amount of bytes read on this socket
	bytesPrevRead = req.socket.prevRead || 0;
	bytesRead = req.socket.bytesRead;

	// The total amount of bytes read for this request
	read = bytesRead - bytesPrevRead;

	// Set the new previous read amount of bytes
	req.socket.prevRead = req.socket.bytesRead;

	// Get the remote address
	remoteAddress = req.socket.remoteAddress;

	if (req.httpVersionMajor == 2) {
		const write = res.write;
		res.write = function writeHook(chunk, encoding, callback) {
			written_http2 += chunk.length;
			return write.call(res, chunk, encoding, callback);
		};
	}

	res.on('close', finalizeHitRegister);
	res.on('finish', finalizeHitRegister);

	function finalizeHitRegister() {

		if (finished) {
			return;
		}

		let bytes_written,
		    sent;

		finished = true;

		if (req.httpVersionMajor == 2) {
			sent = written_http2;
		} else {
			let bytes_prev_written = req.socket.prevWritten || 0;
			bytes_written = req.socket.bytesWritten || 0;

			sent = bytes_written - bytes_prev_written;
		}

		if (isNaN(sent)) {
			sent = '-';
		}

		that.incoming += read;
		that.outgoing += sent;

		if (typeof that.pathCounters[path] === 'undefined') {
			that.pathCounters[path] = {
				incoming: 0,
				outgoing: 0
			};
		}

		that.pathCounters[path].incoming += read;
		that.pathCounters[path].outgoing += sent;

		// Set the new written amount
		req.socket.prevWritten = bytes_written;

		that.Log.registerHit({
			created        : start,
			site_id        : that.id,
			host           : req.headers.host || req.headers[':authority'],
			path           : fullPath,
			status         : res.statusCode,
			request_size   : read,
			response_size  : sent,
			referer        : req.headers.referer,
			user_agent     : req.headers['user-agent'],
			remote_address : remoteAddress,
			duration       : Date.now() - req.startTime
		}, req, res);

		if (Blast.DEBUG) {
			log.info(that.name, 'has now received', ~~(that.incoming/1024), 'KiBs and submitted', ~~(that.outgoing/1024), 'KiBs');
		}
	}
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

	const that = this;

	this.getAddress(req, function gotAddress(err, address) {

		if (err) {
			res.writeHead(500, {'Content-Type': 'text/plain'});
			res.end('' + err);
			return;
		}

		if (that.settings.delay) {
			setTimeout(function doDelay() {
				that.dispatcher.forwardRequest(req, res, address);
			}, that.settings.delay);
		} else {
			that.dispatcher.forwardRequest(req, res, address);
		}
	});
});