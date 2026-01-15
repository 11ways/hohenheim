/**
 * Default configuration
 *
 * Licensed under The MIT License
 * For full copyright and license information, please see the LICENSE.txt
 * Redistributions of files must retain the above copyright notice.
 *
 * @copyright   Copyright 2013-2024
 * @since       0.1.0
 * @license     http://www.opensource.org/licenses/mit-license.php MIT License
 */
module.exports = {
	hohenheim: {
		// Enable Hohenheim's letsencrypt/greenlock support
		letsencrypt: true,

		// Letsencrypt email for the TOS
		letsencrypt_email: '',

		// The challenge type
		letsencrypt_challenge: 'http-01',

		// Enable letsencrypt debugging (uses staging server)
		letsencrypt_debug: false,

		// Log access to database?
		log_access_to_database: false,

		// Log access to file?
		log_access_to_file: true,

		// Path to the access log
		log_access_path: '/var/log/hohenheim/access.log',
	}
};
