/**
 * The Request Model
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.2
 * @version  0.1.0
 */
var Request = Function.inherits('Alchemy.AppModel', function RequestModel(conduit, options) {
	RequestModel.super.call(this, conduit, options);
});

/**
 * Constitute the class wide schema
 *
 * @author   Jelle De Loecker <jelle@develry.be>
 * @since    0.1.0
 * @version  0.1.0
 */
Request.constitute(function addFields() {

	// This belongs to a certain site
	this.belongsTo('Site');

	this.addField('host', 'String');
	this.addField('path', 'String');
	this.addField('status', 'Number');
	this.addField('request_size', 'Number');
	this.addField('response_size', 'Number');
	this.addField('referer', 'String');
	this.addField('user_agent', 'String');
	this.addField('remote_address', 'String');
	this.addField('duration', 'Number');
});

/**
 * Save the given data in the database
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {Object}   data   The data to save
 */
Request.setMethod(function registerHit(data) {
	this.save(data);
});