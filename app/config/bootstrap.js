/**
 *
 * Alchemy: Node.js MVC Framework
 * Copyright 2013-2017
 *
 * Licensed under The MIT License
 * Redistributions of files must retain the above copyright notice.
 *
 * @copyright   Copyright 2013-2017
 * @since       0.0.1
 * @license     MIT License (http://www.opensource.org/licenses/mit-license.php)
 */

// Register Hohenheim's custom settings group
const HOHENHEIM = Classes.Alchemy.Setting.SYSTEM.createGroup('hohenheim');

// Proxy server ports
HOHENHEIM.addSetting('proxy_port', {
	type            : 'integer',
	default         : 80,
	description     : 'Port for the HTTP proxy server',
	global_variable : 'PROXY_PORT',
});

HOHENHEIM.addSetting('proxy_port_https', {
	type            : 'integer',
	default         : 443,
	description     : 'Port for the HTTPS proxy server',
	global_variable : 'PROXY_PORT_HTTPS',
});

HOHENHEIM.addSetting('ipv6_address', {
	type            : 'string',
	default         : '',
	description     : 'IPv6 address to bind to (empty to disable)',
	global_variable : 'IPV6_ADDRESS',
});

HOHENHEIM.addSetting('fallback_address', {
	type            : 'string',
	default         : '',
	description     : 'Fallback address for unmatched requests (e.g., http://localhost:8080)',
	global_variable : 'FALLBACK_ADDRESS',
});

HOHENHEIM.addSetting('force_https', {
	type            : 'boolean',
	default         : true,
	description     : 'Force HTTPS redirects for all sites',
	global_variable : 'FORCE_HTTPS',
});

HOHENHEIM.addSetting('base_url_for_template', {
	type            : 'string',
	default         : '',
	description     : 'Base URL used in error page templates',
	global_variable : 'BASE_URL_FOR_TEMPLATE',
});

HOHENHEIM.addSetting('not_found_message', {
	type            : 'string',
	default         : "We couldn't find this domain 😿. Are you sure it should be here?",
	description     : 'Message shown when a domain is not found',
	global_variable : 'NOT_FOUND_MESSAGE',
});

HOHENHEIM.addSetting('unreachable_message', {
	type            : 'string',
	default         : "We couldn't reach this server 😿. If this is unexpected, let us know at support@elevenways.be",
	description     : 'Message shown when a server is unreachable',
	global_variable : 'UNREACHABLE_MESSAGE',
});

HOHENHEIM.addSetting('first_port', {
	type            : 'integer',
	default         : 4748,
	description     : 'First port to use for child node instances',
	global_variable : 'FIRST_PORT',
});

HOHENHEIM.addSetting('redirect_host', {
	type            : 'string',
	default         : 'localhost',
	description     : 'Host to use for internal redirects',
	global_variable : 'REDIRECT_HOST',
});

HOHENHEIM.addSetting('letsencrypt', {
	type            : 'boolean',
	default         : true,
	description     : 'Enable Letsencrypt/Greenlock SSL support',
	global_variable : 'LETSENCRYPT_ENABLED',
});

HOHENHEIM.addSetting('letsencrypt_email', {
	type            : 'string',
	default         : '',
	description     : 'Email address for Letsencrypt TOS',
	global_variable : 'LETSENCRYPT_EMAIL',
});

HOHENHEIM.addSetting('letsencrypt_challenge', {
	type            : 'string',
	default         : 'http-01',
	description     : 'Letsencrypt challenge type',
	global_variable : 'LETSENCRYPT_CHALLENGE',
});

HOHENHEIM.addSetting('letsencrypt_debug', {
	type            : 'boolean',
	default         : false,
	description     : 'Enable Letsencrypt debugging (uses staging server)',
	global_variable : 'LETSENCRYPT_DEBUG',
});

HOHENHEIM.addSetting('letsencrypt_staging', {
	type            : 'boolean',
	default         : false,
	description     : 'Use Letsencrypt staging server',
	global_variable : 'LETSENCRYPT_STAGING',
});

HOHENHEIM.addSetting('log_access_to_database', {
	type            : 'boolean',
	default         : false,
	description     : 'Log access requests to the database',
	global_variable : 'LOG_ACCESS_TO_DATABASE',
});

HOHENHEIM.addSetting('log_access_to_file', {
	type            : 'boolean',
	default         : true,
	description     : 'Log access requests to a file',
	global_variable : 'LOG_ACCESS_TO_FILE',
});

HOHENHEIM.addSetting('log_access_path', {
	type            : 'string',
	default         : '/var/log/hohenheim/access.log',
	description     : 'Path to the access log file',
	global_variable : 'LOG_ACCESS_PATH',
});

HOHENHEIM.addSetting('n_locations', {
	type            : 'array',
	default         : [],
	description     : 'Extra N locations for node.js version management',
	global_variable : 'N_LOCATIONS',
});

HOHENHEIM.addSetting('remote_proxy_keys', {
	type            : 'array',
	default         : [],
	description     : 'Remote proxy keys for trusted upstream proxies',
	global_variable : 'REMOTE_PROXY_KEYS',
});

HOHENHEIM.addSetting('log_domain_misses', {
	type            : 'boolean',
	default         : true,
	description     : 'Log domain lookup failures to a separate file for fail2ban integration',
	global_variable : 'LOG_DOMAIN_MISSES',
});

HOHENHEIM.addSetting('domain_misses_log_path', {
	type            : 'string',
	default         : '/var/log/hohenheim/domain-misses.log',
	description     : 'Path to the domain misses log file',
	global_variable : 'DOMAIN_MISSES_LOG_PATH',
});

HOHENHEIM.addSetting('domain_misses_log_threshold', {
	type            : 'integer',
	default         : 5,
	description     : 'Only log domain misses to fail2ban log after this many unique domain misses (filters out legitimate mistakes, 0 to disable)',
	global_variable : 'DOMAIN_MISSES_LOG_THRESHOLD',
});

HOHENHEIM.addSetting('domain_misses_window_minutes', {
	type            : 'integer',
	default         : 10,
	description     : 'Time window in minutes for counting domain misses (only misses within this window count toward threshold)',
	global_variable : 'DOMAIN_MISSES_WINDOW_MINUTES',
});

alchemy.usePlugin('styleboost');
alchemy.usePlugin('i18n', alchemy.settings.i18n_settings);

alchemy.usePlugin('form');
alchemy.usePlugin('widget');

// Register Hohenheim's custom widget category for monitoring widgets
STAGES.getStage('load_app').addPostTask(function registerMonitoringCategory() {
	const Widget = Classes.Alchemy.Widget.Widget;

	Widget.registerCategory('MONITORING', {
		name  : 'monitoring',
		icon  : 'chart-line',
		order : 70,
	});
});

let sentry = alchemy.settings?.sentry;

if (sentry?.endpoint) {
	alchemy.usePlugin('sentry', {
		endpoint : sentry.endpoint,
		serve_browser_script_locally: sentry.serve_browser_script_locally ?? true,
	});
}

alchemy.usePlugin('acl');
alchemy.usePlugin('menu');

alchemy.usePlugin('media', alchemy.settings.media_settings);
alchemy.usePlugin('chimera', {title: 'Hohenheim'});

/**
 * Ensure the access log path can be reached
 */
STAGES.getStage('server').addPreTask(async function beforeStartServer() {

	if (!LOG_ACCESS_TO_FILE) {
		return;
	}

	let libpath = alchemy.use('path'),
	    fs = alchemy.use('fs');

	let path = libpath.dirname(LOG_ACCESS_PATH);

	try {
		fs.mkdirSync(path);
	} catch (err) {
		if (err.code !== 'EEXIST') {
			log.warn('Disabling access.log file:', err);
			alchemy.setSetting('hohenheim.log_access_to_file', false);
		}
	}
});