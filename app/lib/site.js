var libpath = require('path');

/**
 * The Site class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {Develry.SiteDispatcher}   siteDispatcher
 * @param    {Object}                   record
 */
var Site = Function.inherits('Alchemy.Base', 'Develry', function Site(siteDispatcher, record) {

	// The parent site dispatcher
	this.parent = siteDispatcher;

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

/**
 * This is an abstract class
 *
 * @type {Boolean}
 */
Site.setProperty('is_abstract_class', true);

/**
 * This class starts a new group
 *
 * @type {Boolean}
 */
Site.setProperty('starts_new_group', true);

/**
 * The name of this group
 *
 * @type {String}
 */
Site.setProperty('group_name', 'site_type');

/**
 * Return the class-wide schema
 *
 * @type   {Schema}
 */
Site.setProperty(function schema() {
	return this.constructor.schema;
});

/**
 * Set the site type schema
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.1.0
 * @version  0.1.0
 */
Site.constitute(function setSchema() {

	var schema;

	// Create a new schema
	schema = new Classes.Alchemy.Schema(this);
	this.schema = schema;
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
 * @version  0.1.0
 *
 * @param    {Function}   callback
 */
Site.setMethod(function getAddress(callback) {
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

	delete this.parent.ids[this.id];

	// Remove this instance from the parent's domains
	for (domain in this.parent.domains) {
		if (this.parent.domains[domain] == this) {
			delete this.parent.domains[domain];
		}
	}

	// Remove this instance from the parent's names
	for (name in this.parent.names) {
		if (this.parent.names[name] == this) {
			delete this.parent.names[name];
		}
	}
});

/**
 * Update this site,
 * recreate the entries in the parent dispatcher
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {Object}   record
 */
Site.setMethod(function update(record) {

	var that = this;

	// The db record itself
	this._record = record;

	this.name = record.name;
	this.domains = record.domain || [];
	this.script = record.script;

	if (this.script) {
		this.cwd = libpath.dirname(this.script);
	}

	// Remove this instance from the parent
	this.remove();

	// Add by id
	this.parent.ids[this.id] = this;

	// Add by domains
	this.domains.filter(function(domain) {
		that.parent.domains[domain] = that;
	});

	// Re-add the instance by name
	this.parent.names[this.name] = this;
});

/**
 * This site has been hit,
 * register some metrics
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
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
	    path,
	    read;

	fullPath = req.url;

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

	res.on('finish', function finalizeHitRegister() {

		var bytesPrevWritten = req.socket.prevWritten || 0,
		    bytesWritten = req.socket.bytesWritten,
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
			site_id: that.id,
			host: req.headers.host,
			path: fullPath,
			status: res.statusCode,
			request_size: read,
			response_size: sent,
			referer: req.headers.referer,
			user_agent: req.headers['user-agent'],
			remote_address: remoteAddress,
			duration: Date.now() - req.startTime
		});

		pr(that.name.bold + ' has now received ' + ~~(that.incoming/1024) + ' KiBs and submitted ' + ~~(that.outgoing/1024) + ' KiBs');
	});
});