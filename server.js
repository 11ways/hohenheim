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
const ROOT_STAGE = require('alchemymvc');

// Change the umask so child process will leave created files & sockets
// readable by others in their group
process.umask(2);

// Get the posix package and increase the file limit
try {
	var posix = require('posix');
	posix.setrlimit('nofile', {soft: 60000});
} catch (err) {
	// posix module may need recompilation for current Node.js version
	console.warn('Could not load posix module:', err.message);
}

// Register dependency files after routes stage is ready
ROOT_STAGE.getStage('routes').addPreTask(function serveDependencies() {
	Router.serveDependencyFile('xterm', {
		file  : 'lib/xterm.js',
		alias : '/scripts/xterm.js',
	});
	Router.serveDependencyFile('xterm', {
		file  : 'css/xterm.css',
		alias : '/stylesheets/xterm.css',
	});
	Router.serveDependencyFile('xterm-addon-fit', {
		file  : 'lib/xterm-addon-fit.js',
		alias : '/scripts/xterm-addon-fit.js',
	});
});

ROOT_STAGE.getStage('load_core').addPostTask(async () => {

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

	await alchemy.start();

	// Do certain things when alchemy is ready
	log.info('Creating site dispatcher');

	// Create the dispatcher
	alchemy.dispatcher = new Classes.Develry.SiteDispatcher({

		// Set to false to disable fallback
		fallbackAddress : FALLBACK_ADDRESS || null,

		// localhost is the default value
		redirectHost    : REDIRECT_HOST,

		// Listen to this ipv6 address, too
		ipv6Address     : IPV6_ADDRESS || '',

		// The port on which the proxy will listen (probably 80)
		proxyPort       : PROXY_PORT,

		// The https proxy port
		proxyPortHttps  : PROXY_PORT_HTTPS,

		// The first port to use for child node instances
		firstPort       : FIRST_PORT,

		// Force https?
		force_https     : FORCE_HTTPS,

		// Messages for error pages
		not_found_message    : NOT_FOUND_MESSAGE,
		unreachable_message  : UNREACHABLE_MESSAGE,
	});

	// Create stats collector for dashboard
	alchemy.statsCollector = new Classes.Develry.StatsCollector(alchemy.dispatcher, {
		sampleInterval: 5000,  // 5 seconds
		maxSamples: 180,       // 15 minutes of data at 5s intervals
	});

	// Start collecting stats
	alchemy.statsCollector.start();
});
