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
alchemy.usePlugin('styleboost');
alchemy.usePlugin('i18n');
alchemy.usePlugin('acl', {baselayout: 'layouts/base', bodylayout: 'layouts/body', mainlayout: ['acl_main', 'admin_main', 'main'], mainblock: 'main', contentblock: 'content'});
alchemy.usePlugin('menu');
alchemy.usePlugin('web-components');
alchemy.usePlugin('chimera', {title: 'Hohenheim'});

/**
 * Ensure the access log path can be reached
 * @TODO: make 'done()' work
 */
alchemy.sputnik.before('startServer', function beforeStartServer(done) {

	if (!alchemy.settings.log_access_to_file) {
		return;
	}

	let libpath = alchemy.use('path'),
	    fs = alchemy.use('fs');

	let path = libpath.dirname(alchemy.settings.log_access_path);

	try {
		fs.mkdirSync(path);
	} catch (err) {
		if (err.code !== 'EEXIST') {
			log.warn('Disabling access.log file:', err);
			alchemy.settings.log_access_to_file = false;
		}
	}
});