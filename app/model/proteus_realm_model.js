const REALM_CACHE = alchemy.getCache('proteus_realm_cache');

/**
 * The ProteusRealm model:
 * ProteusRealm are specific authenticators that can be used
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
const ProteusRealm = Function.inherits('Alchemy.Model.App', 'ProteusRealm');

/**
 * Constitute the class wide schema
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
ProteusRealm.constitute(function addFields() {

	this.addField('title', 'String', {
		description : 'The title of this client',
	});

	this.addField('endpoint', 'String', {
		description : 'The endpoint of the Proteus server',
	});

	this.addField('realm_client', 'String', {
		description : 'The slug of this client',
	});

	this.addField('access_key', 'String', {
		description : 'The access key used to authenticate this client',
		private     : true,
	});
});

/**
 * Configure chimera for this model
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
ProteusRealm.constitute(function chimeraConfig() {

	if (!this.chimera) {
		return;
	}

	// Get the list group
	let list = this.chimera.getActionFields('list');

	list.addField('created');
	list.addField('title');
	list.addField('endpoint');
	list.addField('realm_client');

	// Get the edit group
	let edit = this.chimera.getActionFields('edit');

	edit.addField('title');
	edit.addField('endpoint');
	edit.addField('realm_client');
	edit.addField('access_key');
});

/**
 * Get a realm by its id, cached if possible
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @param    {ObjectId|String}   proteus_realm_id
 *
 * @return   {Document.ProteusRealm|Promise<Document.ProteusRealm>}
 */
ProteusRealm.setMethod(function getCachedRealm(proteus_realm_id) {

	if (typeof proteus_realm_id != 'string') {
		proteus_realm_id = ''+proteus_realm_id;
	}

	let result = REALM_CACHE.get(proteus_realm_id);

	if (!result) {
		let promise = this.findByPk(proteus_realm_id);
		REALM_CACHE.set(proteus_realm_id, promise);
		result = promise;

		Pledge.done(promise, (err, doc) => {

			if (err) {
				doc = null;
				alchemy.registerError(err);
			}

			REALM_CACHE.set(proteus_realm_id, doc);
		});
	}

	return result;
});

/**
 * Update the cache after saving a realm
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
ProteusRealm.setMethod(async function afterSave(main, options) {

	const id = ''+main._id;

	// Remove the old data from the cache
	REALM_CACHE.set(id, null);

	// Make sure we have a new copy of the data, as a document
	let doc = await this.findByPk(main._id);

	REALM_CACHE.set(id, doc);
});

/**
 * Get a Proteus client instance
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
ProteusRealm.setDocumentMethod(function getClientInstance() {

	if (!this.endpoint || !this.access_key || !this.realm_client) {
		return;
	}

	let client = new Classes.Alchemy.Acl.ProxiedProteus({
		endpoint     : this.endpoint,
		realm_client : this.realm_client,
		access_key   : this.access_key
	});

	return client;
});