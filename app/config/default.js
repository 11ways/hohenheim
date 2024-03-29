/**
 * Default configuration
 *
 * Licensed under The MIT License
 * For full copyright and license information, please see the LICENSE.txt
 * Redistributions of files must retain the above copyright notice.
 *
 * @copyright   Copyright 2013-2017
 * @since       0.1.0
 * @license     http://www.opensource.org/licenses/mit-license.php MIT License
 */
module.exports = {

	// Should fallback translations be allowed?
	// (False by default: can cause a mix of different languages per page)
	allow_fallback_translations: false,

	// Force alchemy to assume it's using https
	// (when using a reverse proxy that doesn't tell us this, for example)
	assume_https: false,

	// Use babel for compiling client-side scripts
	babel: false,

	// Enable caching
	cache: true,

	// Start instance in client mode
	client_mode: false,

	// Gzip/deflate response compression
	compression: true,

	// Use cookies
	cookies: 'cookie_key_or_false_to_disable',

	// The domain for which the cookies should be set
	cookie_domain: false,

	// Use LESS
	css_less: true,

	// Enable SASS support
	css_sass: true,

	// Use PostCSS
	css_post: true,

	// Disable debugging
	debug: false,

	// Decode json, multipart, urlencode in body
	decoding: true,

	// The default file hash method
	file_hash_algorithm: 'sha1',

	// Should uncaught errors be handled (instead of crashing the server)
	handle_uncaught: true,

	// Enable hawkejs on the client side
	hawkejs_client: true,

	// Don't allow access to the info page
	info_page: false,

	// Show a list of all tasks in Janeway
	janeway_task_menu: true,

	// Allow use of JSON-dry in non-hawkejs responses
	json_dry_response: true,

	// Kill the process when a file changes
	kill_on_file_change: false,

	// Override kill extensions
	// kill_extensions: ['js'],

	// Show the lag menu entry
	lag_menu : true,

	// Extra import paths
	less_import_paths: false,

	// Enable Hohenheim's letsencrypt/greenlock support
	letsencrypt: true,

	// Letsencrypt email for the TOS
	letsencrypt_email: '',

	// The challenge type (tls-sni-01 is currently broken, as of 2017-01)
	letsencrypt_challenge: 'http-01',

	// Enable letsencrypt debugging
	// Also switches to staging server.
	letsencrypt_debug: false,

	// Log access to database?
	log_access_to_database: false,

	// Log access to file?
	log_access_to_file: true,

	// Path to the access log
	log_access_path: '/var/log/hohenheim/access.log',

	// Set the debug level
	log_level: 4,

	// Enable debug stack trace (slow)
	log_trace: false,

	// Enable debugTrace for log.debug calls
	log_trace_debug: true,

	// Enable debugTrace for log.error calls
	log_trace_error: true,

	// Listen to logTrace by default
	log_trace_info: null,
	log_trace_warn: null,
	log_trace_verbose: null,

	// How long query results are cached (falsy to disable)
	model_query_cache_duration: '60 minutes',

	// How many assoc data queries are allowed to run at the same time
	model_assoc_parallel_limit: 8,

	// Minify CSS
	minify_css: true,

	// Minify javascript files
	minify_js: true,

	// Extra n locations
	n_locations: [],

	// The port to run the server on
	port: 3001,

	// Do an extensive and expensive search for modules
	search_for_modules: false,

	// Type of sessions to use: 'server', 'cookie', 'persistent'
	sessions: 'cookie',

	// The session key (for server & persistent sessions)
	session_key: 'session_key',

	// The length of the session
	session_length: '20 minutes',

	// Show a list of all sessions in Janeway
	session_menu: false,

	// Detect when this node server is too busy
	// 70ms is the default, and would result in a 200ms latency lag
	toobusy: 70,

	// Enable websockets
	websockets: true
};