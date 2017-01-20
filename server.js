/**
 * Server
 *
 * The place where the magic starts
 *
 * Alchemy: a node.js framework
 * Copyright 2013, Jelle De Loecker
 *
 * Licensed under The MIT License
 * Redistributions of files must retain the above copyright notice.
 *
 * @copyright     Copyright 2013, Jelle De Loecker
 * @link          
 * @license       MIT License (http://www.opensource.org/licenses/mit-license.php)
 */
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
	log.error('Uncaught Exception!', {err: error});

});

alchemy.start(function onAlchemyReady() {

	// Do certain things when alchemy is ready

	// Create the dispatcher
	alchemy.dispatcher = new Classes.Develry.SiteDispatcher({
		fallbackAddress: 'http://localhost:8080', // Set to false to disable fallback
		redirectHost: 'localhost', // localhost is the default value
		ipv6Address: alchemy.settings.ipv6Address || '', // Listen to this ipv6 address, too
		proxyPort: alchemy.settings.proxyPort || 80,
		firstPort: alchemy.settings.firstPort || 4748
	});

});