/**
 * The PersistentCookie Model class for Proteus logins on sites
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
const Persistent = Function.inherits('Alchemy.Model', 'ProteusPersistentCookie');

/**
 * Constitute the class wide schema
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
Persistent.constitute(function addFields() {

	this.addField('identifier', 'String', {
		description : 'The identifier of this cookie',
	});

	this.addField('token', 'String', {
		description : 'The token of this cookie',
	});

	this.addField('proteus_handle', 'String', {
		description : 'The optional proteus handle of this user',
	});

	this.belongsTo('User', 'ProteusSiteUser');
});

/**
 * Try to get the user from the given cookie.
 * If the cookie is no longer valid, or the user could not be found,
 * false will be returned
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @param    {Conduit}   conduit   The conduit connection
 * @param    {Object}    acpl      The actual cookie payload
 *
 * @return   {User}
 */
Persistent.setMethod(async function getUserFromCookieForLogin(conduit, acpl) {

	let criteria = this.find();
	criteria.where('identifier').equals(acpl.i);
	criteria.where('token').equals(acpl.t);
	criteria.select('User');

	let cookie;

	try {
		cookie = await this.find('first', criteria);
	} catch (err) {
		// Ignore errors
	}

	// Cookie database record could not be found, it's probably out-of-date
	// or manually deleted
	if (!cookie) {
		return false;
	}

	let result;

	try {
		result = await cookie.getUpdatedUserForLogin(conduit, acpl);
	} catch (err) {
		// Ignore errors
	}

	if (!result) {
		result = false;
	}

	return result;
});

/**
 * Get the updated user record
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @param    {Conduit}   conduit   The conduit connection
 * @param    {Object}    acpl      The actual cookie payload
 *
 * @return   {User}
 */
Persistent.setDocumentMethod(async function getUpdatedUserForLogin(conduit, acpl) {

	if (!this.User) {
		return false;
	}

	const User = conduit.getModel('ProteusSiteUser');
	let user = await User.findById(this.User.$pk);

	if (!user) {
		return false;
	}

	if (!user.ProteusRealm) {
		await user.populate('ProteusRealm');
	}

	return user.ProteusRealm.getClientInstance().remoteLoginWithPersistentCookie(this, user, conduit);
});