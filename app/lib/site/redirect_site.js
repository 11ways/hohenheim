/**
 * The Redirect Site class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.4
 * @version  0.3.4
 *
 * @param    {Develry.SiteDispatcher}   siteDispatcher
 * @param    {Object}                   record
 */
var RedirectSite = Function.inherits('Develry.Site', function RedirectSite(siteDispatcher, record) {
	RedirectSite.super.call(this, siteDispatcher, record);
});

/**
 * Add the site type fields
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.4
 * @version  0.3.4
 */
RedirectSite.constitute(function addFields() {
	// Url to redirect to
	this.schema.addField('target_url', 'String');

	// Make this a permanent redirect?
	this.schema.addField('is_permanent', 'Boolean', {default: false});
});

/**
 * Handle a request without proxying
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.4
 * @version  0.3.4
 */
RedirectSite.setMethod(function handleRequest(req, res) {

	var status;

	if (!this.settings.target_url) {
		res.writeHead(404, {'Content-Type': 'text/plain'});
		res.end('Not found');
		return;
	}

	if (this.settings.is_permanent) {
		status = 301;
	} else {
		status = 302;
	}

	res.writeHead(status, {
		Location: this.settings.target_url
	});

	res.end();
});