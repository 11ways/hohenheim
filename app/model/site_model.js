var sitesByName   = alchemy.shared('Sites.byName'),
    sitesByDomain = alchemy.shared('Sites.byDomain'),
    sitesById     = alchemy.shared('Sites.byId'),
    local_ips     = alchemy.shared('local_ips');

/**
 * The Site Model class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.3.0
 */
var Site = Function.inherits('Alchemy.Model.App', function Site(conduit, options) {
	Site.super.call(this, conduit, options);

	this.on('saved', function saved(data) {
		log.info('Site', data._id+'', 'has been saved');
		this.getSites();
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
Site.prepareProperty('sort', function sort() {
	return {name: 1};
});

/**
 * Constitute the class wide schema
 *
 * @author   Jelle De Loecker <jelle@develry.be>
 * @since    0.1.0
 * @version  0.2.0
 */
Site.constitute(function addFields() {

	var site_types = alchemy.getClassGroup('site_type'),
	    domain_schema = new Classes.Alchemy.Schema(),
	    header_schema = new Classes.Alchemy.Schema();

	// The name of the site
	this.addField('name', 'String');

	// The type of site
	this.addField('site_type', 'Enum', {values: site_types});

	// Site specific settings
	this.addField('settings', 'Schema', {schema: 'site_type'});

	// Header schema
	header_schema.addField('name', 'String');
	header_schema.addField('value', 'String');

	// The ip addresses to listen to
	domain_schema.addField('listen_on', 'Enum', {array: true, values: local_ips});

	// The hostname to listen to
	domain_schema.addField('hostname', 'String', {array: true});

	// Optional headers to forward
	domain_schema.addField('headers', 'Schema', {array: true, schema: header_schema});

	this.addField('domain', 'Schema', {array: true, schema: domain_schema});
});

/**
 * Configure chimera for this model
 *
 * @author   Jelle De Loecker <jelle@develry.be>
 * @since    0.1.0
 * @version  0.3.0
 */
Site.constitute(function chimeraConfig() {

	var list,
	    edit;

	if (!this.chimera) {
		return;
	}

	// Get the list group
	list = this.chimera.getActionFields('list');

	list.addField('site_type');
	list.addField('name');

	// Get the edit group
	edit = this.chimera.getActionFields('edit');

	edit.addField('name');
	edit.addField('site_type');
	edit.addField('settings');

	// Add domains in a new tab
	edit.addField('domains', 'domain');

	// Add statistics & control field in a new tab
	edit.addField('statistics', '_id', {type: 'SiteStat'});
});

/**
 * Get all the sites in the database
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.3.2
 *
 * @param    {Function}   callback
 */
Site.setMethod(function getSites(callback) {

	var that = this;

	that.find('all', {document: true, recursive: 0}, function gotRecords(err, results) {

		var byName = {},
		    byDomain = {},
		    byId = {};

		for (let site of Array.cast(results)) {

			// Store it by site name
			byName[site.name] = site;

			// Store it by id
			byId[site._id] = site;

			if (!site.domain) {
				continue;
			}

			for (let domain of site.domain) {
				let length,
				    config,
				    temp,
				    ip,
				    i;

				if (domain.listen_on && domain.listen_on.length) {
					length = domain.listen_on.length;

					for (i = 0; i < length; i++) {
						ip = domain.listen_on[i];

						// Skip "null" string, they're just a mistake
						if (ip == 'null') {
							continue;
						}

						config = local_ips[ip];

						if (!config) {
							local_ips[ip] = 'Old: ' + ip;
						} else if (config.family == 'IPv4') {
							// Add an IPv6-ified IPv4 address,
							// because on IPv6 enabled interfaces
							// these addresses get identified as such
							if (ip[0] != ':') {
								domain.listen_on.push('::ffff:' + ip);
							}
						}
					}
				}

				if (domain.hostname) {

					temp = {site, domain};

					domain.hostname.forEach(function eachHostname(hostname) {
						byDomain[hostname] = temp;
					});
				}
			}
		}

		alchemy.overwrite(sitesByDomain, byDomain);
		alchemy.overwrite(sitesByName, byName);
		alchemy.overwrite(sitesById, byId);

		// Emit the siteUpdate event
		alchemy.emit('siteUpdate', sitesById, sitesByDomain, sitesByName);

		if (callback) {
			callback(sitesById, sitesByDomain, sitesByName);
		}
	});
});

/**
 * Get all the hostnames for this site in an array
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.2.0
 *
 * @param    {String}   found_domain
 *
 * @return   {Array}
 */
Site.setDocumentMethod(function getHostnames(found_domain) {

	var result = [];

	for (let i = 0; i < this.domain.length; i++) {
		let domain = this.domain[i];

		for (let j = 0; j < domain.hostname.length; j++) {

			if (domain.hostname[j][0] == '/') {
				continue;
			}

			result.push(domain.hostname[j]);
		}
	}

	if (found_domain && result.indexOf(found_domain) == -1) {
		result.push(found_domain);
	}

	result.sort(function sortByLength(a, b){
		return b.length - a.length || a.localeCompare(b);
	});

	return result;
});