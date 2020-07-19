/**
 * Server
 *
 * The place where the magic starts
 *
 * Alchemy: a node.js framework
 * Copyright 2013-2017, Jelle De Loecker
 *
 * Licensed under The MIT License
 * Redistributions of files must retain the above copyright notice.
 *
 * @copyright     Copyright 2013-2017, Jelle De Loecker
 * @link          
 * @license       MIT License (http://www.opensource.org/licenses/mit-license.php)
 */
const libpath = require('path');
require('alchemymvc');

/**
 * Overwrite an existing object without breaking references
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.0.1
 *
 * @param   {object}   target   The object to overwrite
 * @param   {object}   obj      The object to replace it with
 */
alchemy.overwrite = function overwrite(target, obj) {

	var key;

	for (key in target) {
		delete target[key];
	}

	for (key in obj) {
		target[key] = obj[key];
	}
};

// Intercept uncaught exceptions so the server won't crash
// @todo: this should be expanded and integrated into alchemy itself
process.on('uncaughtException', function(error) {
	// Indicate we caught an exception
	alchemy.printLog('error', ['Uncaught Exception!', String(error), error], {err: error, level: -2});
});

alchemy.start(function onAlchemyReady() {

	// Do certain things when alchemy is ready
	log.info('Creating site dispatcher');

	// Create the dispatcher
	alchemy.dispatcher = new Classes.Develry.SiteDispatcher({

		// Set to false to disable fallback
		fallbackAddress : alchemy.settings.fallbackAddress || null,

		// localhost is the default value
		redirectHost    : alchemy.settings.redirectHost || 'localhost',

		// Listen to this ipv6 address, too
		ipv6Address     : alchemy.settings.ipv6Address || '',

		// The port on which the proxy will listen (probably 80)
		proxyPort       : alchemy.settings.proxyPort || 80,

		// The https proxy port
		proxyPortHttps  : alchemy.settings.proxyPortHttps,

		// The first port to use for child node instances
		firstPort       : alchemy.settings.firstPort || 4748,

		// Force https?
		force_https     : alchemy.settings.force_https == null ? true : alchemy.settings.force_https
	});
});

// Get the path to xterm
let xterm_dir = libpath.dirname(alchemy.findModule('xterm', {require: false}).module_path),
    xterm_path = libpath.resolve(xterm_dir, 'xterm.js');

// Serve the main xterm.js file
Router.use('/scripts/xterm.js', function getXterm(req, res, next) {
	req.conduit.serveFile(xterm_path);
});

// Get the path to the xterm-fit addon
let fit_dir = libpath.dirname(alchemy.findModule('xterm-addon-fit', {require: false}).module_path),
    fit_path = libpath.resolve(fit_dir, 'xterm-addon-fit.js');

console.log(fit_path)

// Serve the xterm.js fit addon
Router.use('/scripts/xterm.fit.js', function getXtermFit(req, res, next) {
	req.conduit.serveFile(fit_path);
});


// Get the posix package
var posix = require('posix');

// Increase the file limit
posix.setrlimit('nofile', {soft: 60000});