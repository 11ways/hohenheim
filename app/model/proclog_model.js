/**
 * The Proclog Model
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.2
 * @version  0.1.0
 */
var Proclog = Function.inherits('Alchemy.Model.App', function Proclog(conduit, options) {
	Proclog.super.call(this, conduit, options);
});

/**
 * Constitute the class wide schema
 *
 * @author   Jelle De Loecker <jelle@develry.be>
 * @since    0.1.0
 * @version  0.1.0
 */
Proclog.constitute(function addFields() {

	// The log object
	this.addField('log', 'Object', {array: true});

	// This belongs to a certain site
	this.belongsTo('Site');
});