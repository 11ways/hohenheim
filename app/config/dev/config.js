module.exports = {

	debugging: {
		// Enable debugging
		debug: true,

		// Allow access to the info page
		info_page: true,

		// Kill the process when a file changes
		kill_on_file_change: true,
	},

	performance: {

		// Disable cache
		cache: false,

		// Show the lag menu
		janeway_lag_menu : true,
	},

	frontend: {
		stylesheet: {
			// Disable CSS minification
			minify: false,
		},

		javascript: {
			// Disable JS minification
			minify: false,
		}
	},

	sessions: {
		janeway_menu: true,
	},

	// Enable debug stack trace
	log_trace: true,
};