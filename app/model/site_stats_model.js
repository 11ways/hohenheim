/**
 * The SiteStats Model
 * Stores aggregated statistics snapshots for sites
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
const SiteStats = Function.inherits('Alchemy.Model.App', 'SiteStats');

/**
 * Constitute the class wide schema
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
SiteStats.constitute(function addFields() {

	// This belongs to a certain site
	this.belongsTo('Site');

	// The period this snapshot covers
	this.addField('period_type', 'Enum', {
		values: {
			minute : 'Minute',
			hour   : 'Hour',
			day    : 'Day',
		}
	});

	// Start of the period
	this.addField('period_start', 'Datetime', {index: true});

	// Traffic stats
	this.addField('incoming_bytes', 'BigInt', {default: 0});
	this.addField('outgoing_bytes', 'BigInt', {default: 0});

	// Request stats
	this.addField('request_count', 'BigInt', {default: 0});

	// Process stats (averages over the period)
	this.addField('avg_process_count', 'Number', {default: 0});
	this.addField('max_process_count', 'Number', {default: 0});
	this.addField('avg_cpu', 'Number', {default: 0});
	this.addField('max_cpu', 'Number', {default: 0});
	this.addField('avg_mem', 'Number', {default: 0});
	this.addField('max_mem', 'Number', {default: 0});

	// Number of samples aggregated into this record
	this.addField('sample_count', 'Number', {default: 0});

	// Add indexes for efficient querying
	this.addIndex('site_period', {
		fields: ['site_id', 'period_type', 'period_start'],
		unique: true,
	});
});

/**
 * Store aggregated stats for a site
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {Object}   data   The aggregated stats data
 */
SiteStats.setMethod(async function storeAggregatedStats(data) {

	// Try to find existing record for this site/period
	let crit = this.find();
	crit.where('site_id').equals(data.site_id);
	crit.where('period_type').equals(data.period_type);
	crit.where('period_start').equals(data.period_start);

	let existing = await this.find('first', crit);

	if (existing) {
		// Update existing record by adding to totals and recalculating averages
		let total_samples = existing.sample_count + data.sample_count;

		existing.incoming_bytes = BigInt(existing.incoming_bytes || 0) + BigInt(data.incoming_bytes || 0);
		existing.outgoing_bytes = BigInt(existing.outgoing_bytes || 0) + BigInt(data.outgoing_bytes || 0);
		existing.request_count = BigInt(existing.request_count || 0) + BigInt(data.request_count || 0);

		// Weighted average for process count
		existing.avg_process_count = (
			(existing.avg_process_count * existing.sample_count) +
			(data.avg_process_count * data.sample_count)
		) / total_samples;

		existing.max_process_count = Math.max(existing.max_process_count, data.max_process_count);

		// Weighted average for CPU
		existing.avg_cpu = (
			(existing.avg_cpu * existing.sample_count) +
			(data.avg_cpu * data.sample_count)
		) / total_samples;

		existing.max_cpu = Math.max(existing.max_cpu, data.max_cpu);

		// Weighted average for memory
		existing.avg_mem = (
			(existing.avg_mem * existing.sample_count) +
			(data.avg_mem * data.sample_count)
		) / total_samples;

		existing.max_mem = Math.max(existing.max_mem, data.max_mem);

		existing.sample_count = total_samples;

		await existing.save();
	} else {
		// Create new record
		await this.save(data);
	}
});

/**
 * Get stats for a site within a time range
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {ObjectId|String}   site_id      The site ID
 * @param    {String}            period_type  'minute', 'hour', or 'day'
 * @param    {Date}              start_date   Start of range
 * @param    {Date}              end_date     End of range
 *
 * @return   {Array}   Array of stat records
 */
SiteStats.setMethod(async function getStatsForSite(site_id, period_type, start_date, end_date) {

	let crit = this.find();
	crit.where('site_id').equals(site_id);
	crit.where('period_type').equals(period_type);
	crit.where('period_start').gte(start_date);
	crit.where('period_start').lte(end_date);
	crit.sort(['period_start', 'asc']);

	return this.find('all', crit);
});

/**
 * Clean up old stats records
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {Object}   retention   Retention config {minute: days, hour: days, day: days}
 */
SiteStats.setMethod(async function cleanupOldStats(retention) {

	retention = retention || {
		minute : 1,   // Keep minute data for 1 day
		hour   : 30,  // Keep hourly data for 30 days
		day    : 365, // Keep daily data for 1 year
	};

	let now = new Date();

	for (let period_type in retention) {
		let days = retention[period_type];
		let cutoff = new Date(now.getTime() - (days * 24 * 60 * 60 * 1000));

		let crit = this.find();
		crit.where('period_type').equals(period_type);
		crit.where('period_start').lt(cutoff);

		await this.remove(crit);
	}
});
