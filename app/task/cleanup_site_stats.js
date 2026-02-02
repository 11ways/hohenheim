/**
 * The task to clean up old site statistics
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.1
 * @version  0.7.1
 */
const CleanupSiteStats = Function.inherits('Alchemy.Task', 'Hohenheim.Task', 'CleanupSiteStats');

/**
 * Run every hour (at minute 17 to avoid busy times)
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.1
 * @version  0.7.1
 */
CleanupSiteStats.addFallbackCronSchedule('17 * * * *');

/**
 * The function to execute
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.1
 * @version  0.7.1
 */
CleanupSiteStats.setMethod(async function executor() {

	this.todo = 3; // 3 period types to process
	this.done = 0;

	const SiteStats = Model.get('SiteStats');

	this.log('Starting site stats cleanup...');

	// Retention configuration (in days)
	const retention = {
		minute : 1,   // Keep minute data for 1 day
		hour   : 30,  // Keep hourly data for 30 days
		day    : 365, // Keep daily data for 1 year
	};

	let total_removed = 0;

	for (let period_type in retention) {
		// Check if task was stopped
		if (this.has_stopped) {
			this.log('Task stopped, aborting cleanup');
			return;
		}

		let days = retention[period_type];
		let cutoff = new Date(Date.now() - (days * 24 * 60 * 60 * 1000));

		this.log('Cleaning up', period_type, 'stats older than', cutoff.toISOString());

		try {
			let crit = SiteStats.find();
			crit.where('period_type').equals(period_type);
			crit.where('period_start').lt(cutoff);

			// Count before removing
			let count = await SiteStats.find('count', crit);

			if (count > 0) {
				// Remove in batches to avoid locking the database
				let removed = await SiteStats.remove(crit);
				this.log('Removed', count, period_type, 'stats records');
				total_removed += count;
			} else {
				this.log('No old', period_type, 'stats to remove');
			}
		} catch (err) {
			this.log('Error cleaning up', period_type, 'stats:', err.message);
			alchemy.registerError(err, {context: 'Failed to cleanup ' + period_type + ' site stats'});
		}

		this.done++;
		this.report(this.done / this.todo);
	}

	this.log('Cleanup complete, removed', total_removed, 'total records');
	this.report(1);
});
