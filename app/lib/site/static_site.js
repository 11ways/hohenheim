var ecstatic = alchemy.use('ecstatic');

/**
 * The Static Site class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.2
 * @version  0.3.2
 *
 * @param    {Develry.SiteDispatcher}   siteDispatcher
 * @param    {Object}                   record
 */
var StaticSite = Function.inherits('Develry.Site', function StaticSite(siteDispatcher, record) {
	StaticSite.super.call(this, siteDispatcher, record);
});

/**
 * Get the ecstatic instance
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.2
 * @version  0.3.2
 */
StaticSite.setProperty(function ecstatic_instance() {

	if (this._einstance) {
		return this._einstance;
	}

	if (!this.settings.path) {
		return null;
	}

	let settings = this.settings;

	this._einstance = ecstatic({
		root         : settings.path,
		showDir      : settings.autoindex,
		autoIndex    : settings.autoindex,
		showDotfiles : settings.show_hidden_files,
	});

	return this._einstance;
});

/**
 * Add the site type fields
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.2
 * @version  0.3.2
 */
StaticSite.constitute(function addFields() {
	this.schema.addField('path', 'String');

	// Use index.html file when getting a folder?
	this.schema.addField('indexes', 'Boolean', {default: true});

	// Auto generate index file?
	this.schema.addField('autoindex', 'Boolean', {default: true});

	// Show hidden files?
	this.schema.addField('show_hidden_files', 'Boolean', {default: false});
});

/**
 * Handle a request without proxying
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.2
 * @version  0.3.2
 */
StaticSite.setMethod(function handleRequest(req, res) {

	var instance = this.ecstatic_instance;

	if (!instance) {
		return res.end();
	}

	instance(req, res);
});


/**
 * Update this site,
 * recreate the entries in the parent dispatcher
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.2
 * @version  0.3.2
 *
 * @param    {Object}   record
 */
StaticSite.setMethod(function update(record) {
	// Call the parent method
	update.super.call(this, record);

	this._einstance = false;
});