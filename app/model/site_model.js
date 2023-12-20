let sites_by_id = new Map(),
    local_ips = alchemy.shared('local_ips');

/**
 * The Site Model class
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.3.0
 */
const Site = Function.inherits('Alchemy.Model.App', 'Site');

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
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.1.0
 * @version  0.6.0
 */
Site.constitute(function addFields() {

	let site_types = alchemy.getClassGroup('site_type'),
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

	// Allow excluding domains from letsencrypt
	domain_schema.addField('exclude_from_letsencrypt', 'Boolean');

	this.addField('domain', 'Schema', {array: true, schema: domain_schema});

	this.belongsTo('ProteusRealm', {
		description: 'The Proteus realm to use for authentication',
	});

	this.addField('proteus_realm_permission', 'String', {
		description: 'The permissions needed to access this site (defaults to hohenheim.site.{slug})',
	});

	// Add basic auth settings
	this.addField('basic_auth', 'String', {
		description: 'Basic authentication credentials (Not used if Proteus is enabled)',
		array: true,
	});

	this.addBehaviour('Sluggable');
});

/**
 * Configure chimera for this model
 *
 * @author   Jelle De Loecker <jelle@develry.be>
 * @since    0.1.0
 * @version  0.6.0
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
	list.addField('slug');

	// Get the edit group
	edit = this.chimera.getActionFields('edit');

	edit.addField('name');
	edit.addField('slug');
	edit.addField('site_type');
	edit.addField('settings');

	// Add domains in a new tab
	edit.addField('domain', {
		group: 'domains',
	});

	// Add authentication settings in a new security tab
	edit.addField('proteus_realm_id', {group: 'security'})
	edit.addField('proteus_realm_permission', {group: 'security'})
	edit.addField('basic_auth', {group: 'security'})

	// Add statistics & control field in a new tab
	edit.addField('_id', {
		group   : 'control',
		view    : 'site_stat',
		wrapper : 'site_stat',
		title   : 'Control',
	});
});

/**
 * Do something after this has been saved
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.6.0
 * @version  0.6.0
 *
 * @param    {Object}   data      The data object that was saved
 * @param    {Object}   options   Save options
 */
Site.setMethod(function afterSave(data, options) {
	log.info('Site', data._id+'', 'has been saved');
	this.updateSites();
});

/**
 * Do something before the document is sent to the database
 * (And after the validation has passed)
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.6.0
 * @version  0.6.0
 */
Site.setMethod(function beforeCommit(doc) {
	if (!doc.proteus_realm_permission) {
		doc.proteus_realm_permission = 'hohenheim.site.' + doc.slug;
	}
});

/**
 * Update all the sites
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.6.0
 * @version  0.6.0
 */
Site.setMethod(async function updateSites() {

	let sites_by_id = await this.getSites();

	// Emit the siteUpdate event
	alchemy.emit('site_update', sites_by_id);
});

/**
 * Get all the sites in the database
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.0.1
 * @version  0.6.0
 */
Site.setMethod(async function getSites() {

	let results = await this.find('all');

	// Clear the old map
	sites_by_id.clear();

	for (let site of results) {

		let site_id = site._id + '';
		sites_by_id.set(site_id, site);

		if (!site.domain) {
			continue;
		}

		for (let domain of site.domain) {

			if (domain.listen_on?.length) {
				let length = domain.listen_on.length,
				    config,
				    ip,
				    i;

				for (i = 0; i < length; i++) {

					ip = domain.listen_on[i];

					// Skip "null" string, they're just a mistake
					if (ip == 'null') {
						continue;
					}

					config = local_ips[ip];

					if (!config) {
						local_ips[ip] = {
							old   : true,
							title : 'Old: ' + ip,
						};
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
		}
	}

	return sites_by_id;
});

/**
 * Get all the hostnames for this site in an array
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.2.0
 * @version  0.4.0
 *
 * @param    {Boolean}   for_letsencrypt
 *
 * @return   {Array}
 */
Site.setDocumentMethod(function getHostnames(for_letsencrypt) {

	var result = [];

	for (let i = 0; i < this.domain.length; i++) {
		let domain = this.domain[i];

		if (for_letsencrypt && domain.exclude_from_letsencrypt) {
			continue;
		}

		for (let j = 0; j < domain.hostname.length; j++) {

			if (!domain.hostname[j]) {
				continue;
			}

			if (domain.hostname[j][0] == '/') {
				continue;
			}

			result.push(domain.hostname[j]);
		}
	}

	result.sort(function sortByLength(a, b){
		return a.length - b.length || a.localeCompare(b);
	});

	return result;
});