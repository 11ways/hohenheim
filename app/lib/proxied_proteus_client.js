/**
 * The Proteus client class for Proxied connections
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
const ProxiedProteus = Blast.Bound.Function.inherits('Alchemy.Acl.Proteus', 'ProxiedProteus');

/**
 * Create a return url
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @return   {String}
 */
ProxiedProteus.setMethod(function createReturnUrl(conduit) {

	let url = conduit.url.clone();
	url.param('proteus', 'verify');

	return '' + url;
});

/**
 * Create a polling url
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @return   {String}
 */
ProxiedProteus.setMethod(function createPollLoginUrl(conduit) {
	return false;
});

/**
 * Get the model to use for storing identities in
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.8.6
 * @version  0.8.6
 *
 * @return   {Model}
 */
ProxiedProteus.setMethod(function getIdentityModel() {
	return Model.get('ProteusSiteUser');
});

/**
 * Handle verification data
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @param    {ProxiedConduit}  conduit
 * @param    {Object}          proteus_info
 *
 * @return   {User}
 */
ProxiedProteus.setMethod(async function handleSuccessfulLoginResult(conduit, proteus_info) {

	let realm = conduit.session('proteus_realm');

	if (!realm) {
		return conduit.error('Authentication error: No proteus realm found');
	}

	let identity = proteus_info.identity;

	const User = this.getIdentityModel();

	let user = await User.findByValues({
		proteus_handle   : identity.handle,
		proteus_realm_id : realm._id,
	});

	if (!user && identity.uid) {
		user = await User.findByValues({
			proteus_uid      : identity.uid,
			proteus_realm_id : realm._id,
		});
	}

	let has_changes = false;

	if (!user) {
		user = User.createDocument();
		has_changes = true;

		// Also try to use the same primary key as Proteus
		user._id = identity._id;
		user.proteus_realm_id = realm._id;
	}

	await this.updateUserWithProteusInfo(user, proteus_info);

	conduit.session('proteusLoginSession', null);

	return user;
});

/**
 * Start a login
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @param    {ProxiedConduit}          conduit
 * @param    {Document.ProteusRealm}   realm
 * @param    {Site}                    site
 *
 * @return   {Object}
 */
ProxiedProteus.setMethod(async function startLogin(conduit, realm, site) {

	conduit.session('proteus_realm', realm);
	conduit.session('proteus_site', site);

	return startLogin.super.call(this, conduit, null);
});