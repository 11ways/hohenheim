var sitesByName = alchemy.shared('Sites.byName'),
    sitesByDomain = alchemy.shared('Sites.byDomain'),
    sitesById = alchemy.shared('Sites.byId');

/**
 * The Site Model class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 */
var Site = Function.inherits('Alchemy.AppModel', function SiteModel(conduit, options) {
	SiteModel.super.call(this, conduit, options);

	this.on('saved', function saved() {
		this.getSites();
	});
});

/**
 * Constitute the class wide schema
 *
 * @author   Jelle De Loecker <jelle@develry.be>
 * @since    0.1.0
 * @version  0.1.0
 */
Site.constitute(function addFields() {
	this.addField('name', 'String');
	this.addField('domain', 'String', {array: true});
	this.addField('script', 'String');
	this.addField('url', 'String');
});

/**
 * Configure chimera for this model
 *
 * @author   Jelle De Loecker <jelle@develry.be>
 * @since    0.1.0
 * @version  0.1.0
 */
Site.constitute(function chimeraConfig() {

	var list,
	    edit;

	if (!this.chimera) {
		return;
	}

	// Get the list group
	list = this.chimera.getActionFields('list');

	list.addField('name');
	list.addField('domain');

	// Get the edit group
	edit = this.chimera.getActionFields('edit');

	edit.addField('name');
	edit.addField('domain');
	edit.addField('script');
	edit.addField('url');

	// @TODO: Add stat & control buttons
});

/**
 * Get all the sites in the database
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {Function}   callback
 */
Site.setMethod(function getSites(callback) {
	var that = this;

	that.find('all', {document: false}, function gotRecords(err, results) {

		var byName = {},
		    byDomain = {},
		    byId = {};

		results.filter(function(value) {

			var site = value['Site'];

			// Store it by each domain name
			site.domain.filter(function(domainName) {
				byDomain[domainName] = site;
			});

			// Store it by site name
			byName[site.name] = site;

			// Store it by id
			byId[site._id] = site;
		});

		alchemy.overwrite(sitesByDomain, byDomain);
		alchemy.overwrite(sitesByName, byName);
		alchemy.overwrite(sitesById, byId);

		// Emit the siteUpdate event
		that.emit('siteUpdate', sitesById, sitesByDomain, sitesByName);

		if (callback) {
			callback(sitesById, sitesByDomain, sitesByName);
		}
	});
});