/**
 * The Alchemy Node Site class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.0
 * @version  0.3.0
 *
 * @param    {Develry.SiteDispatcher}   siteDispatcher
 * @param    {Object}                   record
 */
var AlchemySite = Function.inherits('Develry.NodeSite', function AlchemySite(siteDispatcher, record) {
	AlchemySite.super.call(this, siteDispatcher, record);

	this.default_args = ['--stream-janeway'];
});

/**
 * Add the site type fields
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.1.0
 * @version  0.3.0
 */
AlchemySite.constitute(function addFields() {

	// Wait for the child to tell us it's ready?
	this.schema.addField('wait_for_ready', 'Boolean', {default: true});
});

