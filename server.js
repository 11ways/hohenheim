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
require('alchemymvc');

// Change the umask so child process will leave created files & sockets
// readable by others in their group
process.umask(2);

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

// Get the posix package
var posix = require('posix');

// Increase the file limit
posix.setrlimit('nofile', {soft: 60000});