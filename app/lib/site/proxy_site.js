/**
 * The Proxy Site class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.1.0
 * @version  0.1.0
 *
 * @param    {Develry.SiteDispatcher}   siteDispatcher
 * @param    {Object}                   record
 */
const ProxySite = Function.inherits('Develry.Site', 'ProxySite');

/**
 * Add the site type fields
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.1.0
 * @version  0.5.3
 */
ProxySite.constitute(function addFields() {

	this.schema.addField('socket', 'String', {
		description: 'The path to the socket file to send the requests to (can contain {placeholders})'
	});

	this.schema.addField('url', 'String', {
		description: 'The URL to send the requests to (when no proxy is entered)'
	});

	this.schema.addField('ignore_certificates', 'Boolean', {
		description: 'Ignore certificate errors in case you\'re proxying to an HTTPS target',
		default    : false,
	});
});

/**
 * Update this site,
 * recreate the entries in the parent dispatcher
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @param    {Object}   record
 */
ProxySite.setMethod(function update(record) {
	update.super.call(this, record);

	this.proxy_url = null;

	if (this.settings.socket) {
		let record_socket_path = this.settings.socket,
		    has_assignments = record_socket_path.indexOf('{') > -1;

		this.getAddress = (req, callback) => {
			let socket_path = record_socket_path;

			if (has_assignments && req[MATCHED_GROUPS]) {
				socket_path = socket_path.assign(req[MATCHED_GROUPS]);
			}

			return callback(null, {
				socketPath         : socket_path,
				rejectUnauthorized : !this.settings.ignore_certificates,
			});
		};

		return;
	}

	if (this.settings.url) {
		let url = RURL.parse(this.settings.url);

		this.proxy_url = {
			hostname           : url.hostname,
			port               : url.port || 80,
			protocol           : url.protocol.slice(0, -1),
			rejectUnauthorized : !this.settings.ignore_certificates,
		};

		this.getAddress = (req, callback) => {
			callback(null, this.proxy_url);
		};

		return;
	}

	this.getAddress = (req, callback) => {
		return callback(new Error('Failed to find proxy address'));
	};
});

/**
 * Get an adress to proxy to.
 * This methods get overridden in the `update()` method
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.5.3
 *
 * @param    {IncomingMessage}   req
 * @param    {Function}          callback
 */
ProxySite.setMethod(function getAddress(req, callback) {
	return callback(null, this.proxy_url);
});

/**
 * Modify the response sent by the proxied server
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.2
 * @version  0.4.2
 *
 * @param    {ServerResponse}   res         The response being sent to the browser
 * @param    {IncomingMessage}  req         The original request
 * @param    {IncomingMessage}  proxy_res   The response comming from the proxy server
 * @param    {Object}           domain      The original domain info that matched this record
 */
ProxySite.setMethod(function modifyResponse(res, req, proxy_res, domain) {

	var location = proxy_res.headers['location'];

	if (!location) {
		return;
	}

	// Get the original request sent to the proxied server
	let proxy_req = proxy_res.req,
	    proxy_host = proxy_req.getHeader('host');

	// If no proxy host was found, get the url instead
	if (!proxy_host) {
		proxy_host = this._site.settings.url;

		if (proxy_host[proxy_host.length - 1] == '/') {
			proxy_host = proxy_host.slice(0, -1);
		}
	}

	if (!proxy_host) {
		return;
	}

	// Is the proxy host or url part of the location?
	// Then we need to remove it!
	if (location.indexOf(proxy_host) == -1) {
		return;
	}

	let url = RURL.parse(location);

	if (url.host == proxy_host) {
		location = url.path;
	} else {
		return;
	}

	proxy_res.headers['location'] = location;
});
