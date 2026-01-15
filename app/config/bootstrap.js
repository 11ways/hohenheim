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

alchemy.usePlugin('styleboost');
alchemy.usePlugin('i18n', alchemy.settings.i18n_settings);

alchemy.usePlugin('form');
alchemy.usePlugin('widget');

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