/**
 * The task to aggregate site statistics
 * Rolls up minute data into hourly, and hourly into daily
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.1
 * @version  0.7.1
 */
const AggregateSiteStats = Function.inherits('Alchemy.Task', 'Hohenheim.Task', 'AggregateSiteStats');

/**
 * Run every hour (at minute 5, after minute data is complete)
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.1
 * @version  0.7.1
 */
AggregateSiteStats.addFallbackCronSchedule('5 * * * *');

/**
 * The function to execute
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.1
 * @version  0.7.1
 */
AggregateSiteStats.setMethod(async function executor() {

	const SiteStats = Model.get('SiteStats');

	this.log('Starting site stats aggregation...');

	// Aggregate any minute data that's ready (older than 1 hour)
	await this.aggregateMinuteToHour(SiteStats);

	// Aggregate any hourly data that's ready (older than 1 day)
	await this.aggregateHourToDay(SiteStats);

	this.log('Aggregation complete');
	this.report(1);
});

/**
 * Aggregate minute data into hourly data.
 * Finds all complete hours that have minute data but no hourly record yet.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.1
 * @version  0.7.1
 *
 * @param    {Model}   SiteStats   The SiteStats model instance
 */
AggregateSiteStats.setMethod(async function aggregateMinuteToHour(SiteStats) {

	// Only aggregate complete hours (at least 1 hour old)
	let cutoff = new Date();
	cutoff.setMinutes(0, 0, 0);

	// Don't go back further than the retention period (1 day for minute data)
	let oldest = new Date(Date.now() - (24 * 60 * 60 * 1000));

	this.log('Looking for minute data to aggregate (between', oldest.toISOString(), 'and', cutoff.toISOString() + ')');

	// Find all minute records in the window
	let crit = SiteStats.find();
	crit.where('period_type').equals('minute');
	crit.where('period_start').gte(oldest);
	crit.where('period_start').lt(cutoff);

	let minuteRecords = await SiteStats.find('all', crit);

	if (!minuteRecords || minuteRecords.length === 0) {
		this.log('No minute data to aggregate');
		return;
	}

	this.log('Found', minuteRecords.length, 'minute records to check');

	// Group by site_id and hour
	let hourGroups = new Map();

	for (let record of minuteRecords) {
		let siteId = String(record.site_id);
		let hourStart = new Date(record.period_start);
		hourStart.setMinutes(0, 0, 0);
		let hourKey = siteId + '_' + hourStart.getTime();

		if (!hourGroups.has(hourKey)) {
			hourGroups.set(hourKey, {
				site_id: siteId,
				hour_start: hourStart,
				records: [],
			});
		}

		hourGroups.get(hourKey).records.push(record);
	}

	this.log('Grouped into', hourGroups.size, 'site-hour combinations');

	// Check which hours already have hourly records and aggregate missing ones
	let aggregated = 0;

	for (let [hourKey, group] of hourGroups) {
		if (this.has_stopped) {
			this.log('Task stopped, aborting aggregation');
			return;
		}

		// Check if hourly record already exists
		let existsCrit = SiteStats.find();
		existsCrit.where('site_id').equals(group.site_id);
		existsCrit.where('period_type').equals('hour');
		existsCrit.where('period_start').equals(group.hour_start);

		let existing = await SiteStats.find('first', existsCrit);

		if (existing) {
			// Already aggregated, skip
			continue;
		}

		// Aggregate and store
		try {
			let aggregatedData = this.aggregateRecords(group.records);

			await SiteStats.storeAggregatedStats({
				site_id           : group.site_id,
				period_type       : 'hour',
				period_start      : group.hour_start,
				incoming_bytes    : aggregatedData.incoming_bytes,
				outgoing_bytes    : aggregatedData.outgoing_bytes,
				request_count     : aggregatedData.request_count,
				avg_process_count : aggregatedData.avg_process_count,
				max_process_count : aggregatedData.max_process_count,
				avg_cpu           : aggregatedData.avg_cpu,
				max_cpu           : aggregatedData.max_cpu,
				avg_mem           : aggregatedData.avg_mem,
				max_mem           : aggregatedData.max_mem,
				sample_count      : aggregatedData.sample_count,
			});

			aggregated++;
		} catch (err) {
			this.log('Error aggregating hour data for site', group.site_id + ':', err.message);
			alchemy.registerError(err, {context: 'Failed to aggregate hourly stats for site ' + group.site_id});
		}
	}

	this.log('Created', aggregated, 'new hourly records');
});

/**
 * Aggregate hourly data into daily data.
 * Finds all complete days that have hourly data but no daily record yet.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.1
 * @version  0.7.1
 *
 * @param    {Model}   SiteStats   The SiteStats model instance
 */
AggregateSiteStats.setMethod(async function aggregateHourToDay(SiteStats) {

	// Only aggregate complete days (at least 1 day old)
	let cutoff = new Date();
	cutoff.setHours(0, 0, 0, 0);

	// Don't go back further than the retention period (30 days for hourly data)
	let oldest = new Date(Date.now() - (30 * 24 * 60 * 60 * 1000));

	this.log('Looking for hourly data to aggregate (between', oldest.toISOString(), 'and', cutoff.toISOString() + ')');

	// Find all hourly records in the window
	let crit = SiteStats.find();
	crit.where('period_type').equals('hour');
	crit.where('period_start').gte(oldest);
	crit.where('period_start').lt(cutoff);

	let hourlyRecords = await SiteStats.find('all', crit);

	if (!hourlyRecords || hourlyRecords.length === 0) {
		this.log('No hourly data to aggregate');
		return;
	}

	this.log('Found', hourlyRecords.length, 'hourly records to check');

	// Group by site_id and day
	let dayGroups = new Map();

	for (let record of hourlyRecords) {
		let siteId = String(record.site_id);
		let dayStart = new Date(record.period_start);
		dayStart.setHours(0, 0, 0, 0);
		let dayKey = siteId + '_' + dayStart.getTime();

		if (!dayGroups.has(dayKey)) {
			dayGroups.set(dayKey, {
				site_id: siteId,
				day_start: dayStart,
				records: [],
			});
		}

		dayGroups.get(dayKey).records.push(record);
	}

	this.log('Grouped into', dayGroups.size, 'site-day combinations');

	// Check which days already have daily records and aggregate missing ones
	let aggregated = 0;

	for (let [dayKey, group] of dayGroups) {
		if (this.has_stopped) {
			this.log('Task stopped, aborting aggregation');
			return;
		}

		// Check if daily record already exists
		let existsCrit = SiteStats.find();
		existsCrit.where('site_id').equals(group.site_id);
		existsCrit.where('period_type').equals('day');
		existsCrit.where('period_start').equals(group.day_start);

		let existing = await SiteStats.find('first', existsCrit);

		if (existing) {
			// Already aggregated, skip
			continue;
		}

		// Aggregate and store
		try {
			let aggregatedData = this.aggregateRecords(group.records);

			await SiteStats.storeAggregatedStats({
				site_id           : group.site_id,
				period_type       : 'day',
				period_start      : group.day_start,
				incoming_bytes    : aggregatedData.incoming_bytes,
				outgoing_bytes    : aggregatedData.outgoing_bytes,
				request_count     : aggregatedData.request_count,
				avg_process_count : aggregatedData.avg_process_count,
				max_process_count : aggregatedData.max_process_count,
				avg_cpu           : aggregatedData.avg_cpu,
				max_cpu           : aggregatedData.max_cpu,
				avg_mem           : aggregatedData.avg_mem,
				max_mem           : aggregatedData.max_mem,
				sample_count      : aggregatedData.sample_count,
			});

			aggregated++;
		} catch (err) {
			this.log('Error aggregating daily data for site', group.site_id + ':', err.message);
			alchemy.registerError(err, {context: 'Failed to aggregate daily stats for site ' + group.site_id});
		}
	}

	this.log('Created', aggregated, 'new daily records');
});

/**
 * Aggregate an array of records into a single summary
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.1
 * @version  0.7.1
 *
 * @param    {Array}   records   Array of SiteStats documents
 *
 * @return   {Object}  Aggregated data
 */
AggregateSiteStats.setMethod(function aggregateRecords(records) {

	let incoming_bytes = BigInt(0);
	let outgoing_bytes = BigInt(0);
	let request_count = BigInt(0);
	let max_process_count = 0;
	let max_cpu = 0;
	let max_mem = 0;

	// For weighted averages: sum(avg * sample_count) / sum(sample_count)
	let weighted_sum_process_count = 0;
	let weighted_sum_cpu = 0;
	let weighted_sum_mem = 0;
	let total_sample_count = 0;

	for (let record of records) {
		// Sum traffic stats
		incoming_bytes += BigInt(record.incoming_bytes || 0);
		outgoing_bytes += BigInt(record.outgoing_bytes || 0);
		request_count += BigInt(record.request_count || 0);

		// Track max values
		max_process_count = Math.max(max_process_count, record.max_process_count || 0);
		max_cpu = Math.max(max_cpu, record.max_cpu || 0);
		max_mem = Math.max(max_mem, record.max_mem || 0);

		// Accumulate weighted sums for average calculation
		let sample_count = record.sample_count || 1;
		weighted_sum_process_count += (record.avg_process_count || 0) * sample_count;
		weighted_sum_cpu += (record.avg_cpu || 0) * sample_count;
		weighted_sum_mem += (record.avg_mem || 0) * sample_count;
		total_sample_count += sample_count;
	}

	// Calculate weighted averages
	let avg_process_count = total_sample_count > 0 ? weighted_sum_process_count / total_sample_count : 0;
	let avg_cpu = total_sample_count > 0 ? weighted_sum_cpu / total_sample_count : 0;
	let avg_mem = total_sample_count > 0 ? weighted_sum_mem / total_sample_count : 0;

	return {
		incoming_bytes    : incoming_bytes,
		outgoing_bytes    : outgoing_bytes,
		request_count     : request_count,
		avg_process_count : avg_process_count,
		max_process_count : max_process_count,
		avg_cpu           : avg_cpu,
		max_cpu           : max_cpu,
		avg_mem           : avg_mem,
		max_mem           : max_mem,
		sample_count      : total_sample_count,
	};
});
