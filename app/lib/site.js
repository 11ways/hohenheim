var libpath = require('path');

/**
 * The Site class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.4.0
 *
 * @param    {Develry.SiteDispatcher}   siteDispatcher
 * @param    {Object}                   record
 */
var Site = Function.inherits('Alchemy.Base', 'Develry', function Site(siteDispatcher, record) {

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
 * @version  0.2.0
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

	// Add delay time in ms
	schema.addField('delay', 'Number');

	// Add basic auth settings
	schema.addField('basic_auth', 'String', {array: true});

	this.schema = schema;
});

/**
 * See if this site matches the given hostname
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.5.3
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

		if (domain.regexes) {

			// Do not allow git subdomains in regexes (temporary workaround for brute force protection)
			if (hostname.indexOf('git.') > -1 || hostname.indexOf('gitlab.') > -1) {
				continue;
			}

			let matched;

			for (j = 0; j < domain.regexes.length; j++) {
				matched = domain.regexes[j].exec(hostname);

				if (matched !== null) {

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
 * @version  0.1.0
 */
Site.setMethod(function cleanParent() {

	var domain,
	    name;

	delete this.dispatcher.ids[this.id];

	// Remove this instance from the parent's domains
	for (domain in this.dispatcher.domains) {
		if (this.dispatcher.domains[domain] == this) {
			delete this.dispatcher.domains[domain];
		}
	}

	// Remove this instance from the dispatcher's names
	for (name in this.dispatcher.names) {
		if (this.dispatcher.names[name] == this) {
			delete this.dispatcher.names[name];
		}
	}
});

/**
 * Update this site,
 * recreate the entries in the parent dispatcher
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.5.1
 *
 * @param    {Object}   record
 */
Site.setMethod(function update(record) {

	var that = this;

	// The db record itself
	this._record = record;

	this.name = record.name;
	this.domains = record.domain || [];
	this.settings = record.settings || {};
	this.script = this.settings.script;

	if (this.script) {
		this.cwd = libpath.dirname(this.script);
	}

	// Remove this instance from the parent
	this.remove();

	// Add by id
	this.dispatcher.ids[this.id] = this;

	// Store it by each domain name
	this.domains.forEach(function eachDomain(domain) {

		var temp;

		if (domain.hostname) {

			temp = {
				site   : that,
				domain : domain
			};

			domain.hostname.forEach(function eachHostname(hostname) {

				var regex;

				if (!hostname) {
					log.warn('Site', record.name, 'has no hostname in entry', domain);
					return;
				}

				// Check for regexes
				if (hostname[0] == '/') {
					regex = RegExp.interpret(hostname);
				} else if (hostname.indexOf('*') > -1 || hostname.indexOf('?') > -1) {
					regex = interpretWildcard(hostname, 'i');
				}

				if (regex) {
					if (!domain.regexes) {
						domain.regexes = [];
					}

					domain.regexes.push(regex);
				}

				// Ignore accidental 'null' (string) values
				if (hostname && hostname != 'null') {
					that.dispatcher.domains[hostname] = temp;
				}
			});
		}
	});

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
 * Check for basic auth
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.2.0
 */
Site.setMethod(function checkBasicAuth(req, res, next) {

	var b64auth,
	    truthy;

	if (this.settings.basic_auth && this.settings.basic_auth.length) {

		for (let i = 0; i < this.settings.basic_auth.length; i++) {
			let val = this.settings.basic_auth[i];

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
			for (let i = 0; i < this.settings.basic_auth.length; i++) {
				if (this.settings.basic_auth[i] == credentials) {
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
 * @version  0.3.0
 * 
 * @param    {IncommingMessage}   req
 * @param    {ServerResponse}     res
 * @param    {Function}           callback
 */
Site.setMethod(function registerHit(req, res, callback) {

	var that = this,
	    bytesPrevRead,
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

	res.on('close', finalizeHitRegister);
	res.on('finish', finalizeHitRegister);

	function finalizeHitRegister() {

		var bytesPrevWritten,
		    bytesWritten,
		    sent;

		if (finished) {
			return;
		}

		finished = true;

		bytesPrevWritten = req.socket.prevWritten || 0;
		bytesWritten = req.socket.bytesWritten;
		sent = bytesWritten - bytesPrevWritten;

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
		req.socket.prevWritten = bytesWritten;

		that.Log.registerHit({
			created        : start,
			site_id        : that.id,
			host           : req.headers.host,
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