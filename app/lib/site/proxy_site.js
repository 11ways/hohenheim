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
var ProxySite = Function.inherits('Develry.Site', function ProxySite(siteDispatcher, record) {
	ProxySite.super.call(this, siteDispatcher, record);
});

/**
 * Add the site type fields
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.1.0
 * @version  0.4.1
 */
ProxySite.constitute(function addFields() {

	this.schema.addField('socket', 'String', {
		description: 'The path to the socket file to send the requests to (can contain {placeholders})'
	});

	this.schema.addField('url', 'String', {
		description: 'The URL to send the requests to (when no proxy is entered)'
	});
});

/**
 * Get an adress to proxy to
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.4.1
 *
 * @param    {IncomingMessage}   req
 * @param    {Function}          callback
 */
ProxySite.setMethod(function getAddress(req, callback) {

	if (this.settings.socket) {

		let socket_path = this.settings.socket;

		if (req[MATCHED_GROUPS] && socket_path.indexOf('{') > -1) {
			socket_path = socket_path.assign(req[MATCHED_GROUPS]);
		}

		return callback(null, {socketPath: socket_path});
	}

	return callback(null, this.settings.url);
});

/**
 * Modify the response sent by the proxied server
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.2
 * @version  0.3.2
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

	location = location.after(proxy_host);

	if (location[0] == ':') {
		location = '/' + location.after('/');
	}

	proxy_res.headers['location'] = location;
});