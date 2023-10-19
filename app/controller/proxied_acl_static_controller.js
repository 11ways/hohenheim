const PROTEUS = Symbol('proteus');

/**
 * The ACL Static Controller for Proxied conduits
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
const ProxiedAclStatic = Function.inherits('Alchemy.Controller.AclStatic', 'ProxiedAclStatic');

/**
 * Get the Proteus client instance
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @type     {Alchemy.Acl.Proteus}
 */
ProxiedAclStatic.setProperty(function proteus() {
	return this[PROTEUS];
}, function setValue(value) {
	this[PROTEUS] = value;
});

/**
 * Show a verificatin error
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
ProxiedAclStatic.setMethod(function showVerificationError(type) {
	this.set('message', 'Authentication error: '  + type)
	this.render('static/error');
});

/**
 * The user has succesfully authenticated against the Proteus server.
 * (The user's permission will still be checked in the `site` class later)
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.6.0
 *
 * @param    {ProteusSiteUser}   UserData
 * @param    {Boolean}           remember
 */
ProxiedAclStatic.setMethod(function allow(UserData, remember) {

	let afterLogin = this.session('afterLogin'),
	    that = this;

	// Remove the session
	this.session('afterLogin', null);

	if (!afterLogin || !afterLogin.url) {
		afterLogin = {url: '/'};
	}

	// Store the proteus_identity in the session
	this.session('proteus_identity', UserData);

	if (remember) {
		UserData.createPersistentCookie(function gotCookie(err, result) {

			if (!err) {
				that.cookie('acpl', {i: result.identifier, t: result.token}, {expires: 'never'});
			}

			that.conduit.redirect(afterLogin);
		});
	} else {
		that.conduit.redirect(afterLogin);
	}
});
