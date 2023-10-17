/**
 * The Proxied Conduit Class:
 * represents a connection to a browser that is in the process of being proxied.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @param    {IncomingMessage}   req
 * @param    {ServerResponse}    res
 */
const ProxiedConduit = Function.inherits('Alchemy.Conduit.Http', function Proxied(req, res) {
	Proxied.super.call(this, req, res, null);
});

/**
 * Override the original `initHttp` method:
 * basically skips all parsing of the route
 * & calling of the controller & middleware
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 *
 * @param    {IncomingMessage}   req
 * @param    {ServerResponse}    res
 * @param    {Router}            router   Will always be null
 */
ProxiedConduit.setMethod(async function initHttp(req, res, router) {
	this.setReqRes(req, res);
	this.parseUrl();
});

/**
 * The session cookie name to use.
 * It is *important* that this is different from `alchemy.settings.session_key`
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    1.3.18
 * @version  1.3.18
 */
ProxiedConduit.setProperty(function session_cookie_name() {
	return 'hh_site_session_id';
});