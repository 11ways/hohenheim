/**
 * Dev Environment configuration:
 * these settings override the default.js and can be overridden by local.js
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

	// Disable debug on live
	debug: false,

	// Allow minifying css (of admin interface)
	minify_css: true,

	// Disable JS minification (of admin interface)
	minify_js: false
};