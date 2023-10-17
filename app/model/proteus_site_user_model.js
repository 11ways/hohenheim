/**
 * The Proteus Site User model:
 * Identities get stored in here per-site for those that
 * use the proteus authentication layer
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.5.3
 * @version  0.5.3
 */
const ProteusSiteUser = Function.inherits('Alchemy.Model', 'ProteusSiteUser');

/**
 * Constitute the class wide schema
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
ProteusSiteUser.constitute(function addFields() {

	this.addField('username', 'String');

	this.addField('title', 'String', {
		description : 'The text that will be used to represent this record',
	});

	this.addField('proteus_uid', 'BigInt', {
		description : 'The unique identifier number',
	});

	this.addField('proteus_handle', 'String', {
		description : 'The human-readable representation of the identifier',
	});

	this.addField('nickname', 'String', {
		description : 'A nickname for this user',
	});

	this.addField('given_name', 'String', {
		description : 'The given name of this user',
	});

	this.addField('family_name', 'String', {
		description : 'The family name of this user'
	});

	this.addIndex('proteus_uid', {
		sparse : true,
	});

	this.addIndex('proteus_handle', {
		sparse : true,
	});

	this.belongsTo('ProteusRealm');

	// The user's permissions
	this.addField('permissions', 'Permissions');
});

/**
 * Configure chimera for this model
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
ProteusSiteUser.constitute(function chimeraConfig() {

	if (!this.chimera) {
		return;
	}

	let list = this.chimera.getActionFields('list'),
	    edit = this.chimera.getActionFields('edit');

	list.addField('ProteusRealm.title', {title: 'Proteus Realm'});
	list.addField('proteus_handle', {readonly: true});
	list.addField('nickname', {readonly: true});
	list.addField('given_name', {readonly: true});
	list.addField('family_name', {readonly: true});

	edit.addField('proteus_realm_id');
	edit.addField('proteus_handle', {readonly: true});
	edit.addField('nickname', {readonly: true});
	edit.addField('given_name', {readonly: true});
	edit.addField('family_name', {readonly: true});
	edit.addField('permissions', {readonly: true});
});

/**
 * Create a persistent login cookie
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @param    {String}   existing   The existing session to remove
 * @param    {Function} callback
 */
ProteusSiteUser.setDocumentMethod(async function createPersistentCookie(existing, callback) {

	if (typeof existing == 'function') {
		callback = existing;
		existing = null;
	}

	const that = this;
	const pledge = new Pledge();
	pledge.done(callback);

	if (!this.ProteusRealm) {
		await this.populate('ProteusRealm');
	}

	Function.parallel(function session(next) {
		Crypto.randomHex(16, next);
	}, function token(next) {
		Crypto.randomHex(16, next);
	}, async function done(err, result) {

		if (err) {
			return pledge.reject(err);
		}

		try {

			let data = {
				identifier : result[0],
				token      : result[1],
				user_id    : that.$pk,
			};

			const Persistent = Model.get('ProteusPersistentCookie');
			let doc = Persistent.createDocument(data);
			doc.proteus_handle = that.proteus_handle;

			// Register the cookie without awaiting it
			that.ProteusRealm.getClientInstance().registerPersistentLoginCookie(doc);

			await doc.save();

			pledge.resolve(doc);
		} catch (err) {
			pledge.reject(err);
		}
	});

	return pledge;
});