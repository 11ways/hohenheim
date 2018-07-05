var domains = alchemy.shared('Domains.by_name');

/**
 * The Domain Model class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.0
 * @version  0.3.0
 */
var Domain = Function.inherits('Alchemy.Model.App', function Domain(conduit, options) {
	Domain.super.call(this, conduit, options);

	this.on('saved', function saved(data) {
		log.info('Domain', data.name, 'has been saved');
		this.getDomains();
	});
});

/**
 * Sort by name by default
 *
 * @author   Jelle De Loecker <jelle@develry.be>
 * @since    0.3.0
 * @version  0.3.0
 *
 * @type {Object}
 */
Domain.prepareProperty('sort', function sort() {
	return {name: 1};
});

/**
 * Constitute the class wide schema
 *
 * @author   Jelle De Loecker <jelle@develry.be>
 * @since    0.3.0
 * @version  0.3.0
 */
Domain.constitute(function addFields() {

	// The actual domain
	this.addField('name', 'String');

	// Enable wildcard support?
	this.addField('enable_wildcard', 'Boolean');
});

/**
 * Configure chimera for this model
 *
 * @author   Jelle De Loecker <jelle@develry.be>
 * @since    0.3.0
 * @version  0.3.0
 */
Domain.constitute(function chimeraConfig() {

	var list,
	    edit;

	if (!this.chimera) {
		return;
	}

	// Get the list group
	list = this.chimera.getActionFields('list');

	list.addField('name');
	list.addField('enable_wildcard');

	// Get the edit group
	edit = this.chimera.getActionFields('edit');

	edit.addField('name');
	edit.addField('enable_wildcard');
});

/**
 * Get all the domains in the database
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.3.0
 * @version  0.3.0
 *
 * @param    {Function}   callback
 */
Domain.setMethod(function getDomains(callback) {

	var that = this;

	that.find('all', {document: true}, function gotRecords(err, results) {

		var by_name = {};

		results.forEach(function eachSite(site) {

			if (!site.name) {
				return;
			}

			by_name[site.name] = site;
		});

		alchemy.overwrite(domains, by_name);

		// Emit the domainUpdate event
		alchemy.emit('domainUpdate', by_name);

		if (callback) {
			callback(null, by_name);
		}
	});
});