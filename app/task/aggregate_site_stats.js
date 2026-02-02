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

	// Aggregate any minute data that's ready (complete hours)
	await this.aggregateMinuteToHour(SiteStats);

	// Aggregate any hourly data that's ready (complete days)
	await this.aggregateHourToDay(SiteStats);

	this.log('Aggregation complete');
	this.report(1);
});

/**
 * Aggregate minute data into hourly data.
 * Processes one hour at a time to limit memory usage.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.1
 * @version  0.7.1
 *
 * @param    {Model}   SiteStats   The SiteStats model instance
 */
AggregateSiteStats.setMethod(async function aggregateMinuteToHour(SiteStats) {

	// Find the oldest minute record to know where to start
	let oldestCrit = SiteStats.find();
	oldestCrit.where('period_type').equals('minute');
	oldestCrit.sort(['period_start', 'asc']);
	oldestCrit.limit(1);

	let oldestRecord = await SiteStats.find('first', oldestCrit);

	if (!oldestRecord) {
		this.log('No minute data to aggregate');
		return;
	}

	// Start from the oldest record's hour
	let currentHour = new Date(oldestRecord.period_start);
	currentHour.setMinutes(0, 0, 0);

	// End before the current hour (only aggregate complete hours)
	let endHour = new Date();
	endHour.setMinutes(0, 0, 0);

	this.log('Aggregating minute data from', currentHour.toISOString(), 'to', endHour.toISOString());

	let totalAggregated = 0;
	let totalSkipped = 0;

	// Process one hour at a time
	while (currentHour < endHour) {
		if (this.has_stopped) {
			this.log('Task stopped, aborting aggregation');
			return;
		}

		let nextHour = new Date(currentHour.getTime() + (60 * 60 * 1000));

		// Find all minute records for this specific hour
		let crit = SiteStats.find();
		crit.where('period_type').equals('minute');
		crit.where('period_start').gte(currentHour);
		crit.where('period_start').lt(nextHour);

		// Group by site_id
		let siteGroups = new Map();

		for await (let record of crit) {
			let siteId = String(record.site_id);

			if (!siteGroups.has(siteId)) {
				siteGroups.set(siteId, []);
			}

			siteGroups.get(siteId).push(record);
		}

		// Process each site's data for this hour
		for (let [siteId, records] of siteGroups) {
			// Check if hourly record already exists
			let existsCrit = SiteStats.find();
			existsCrit.where('site_id').equals(siteId);
			existsCrit.where('period_type').equals('hour');
			existsCrit.where('period_start').equals(currentHour);

			let existing = await SiteStats.find('first', existsCrit);

			if (existing) {
				totalSkipped++;
				continue;
			}

			// Aggregate and store
			try {
				let aggregatedData = this.aggregateRecords(records);

				await SiteStats.storeAggregatedStats({
					site_id           : siteId,
					period_type       : 'hour',
					period_start      : currentHour,
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

				totalAggregated++;
			} catch (err) {
				this.log('Error aggregating hour data for site', siteId + ':', err.message);
				alchemy.registerError(err, {context: 'Failed to aggregate hourly stats for site ' + siteId});
			}
		}

		currentHour = nextHour;
	}

	this.log('Created', totalAggregated, 'hourly records, skipped', totalSkipped, 'existing');
});

/**
 * Aggregate hourly data into daily data.
 * Processes one day at a time to limit memory usage.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.1
 * @version  0.7.1
 *
 * @param    {Model}   SiteStats   The SiteStats model instance
 */
AggregateSiteStats.setMethod(async function aggregateHourToDay(SiteStats) {

	// Find the oldest hourly record to know where to start
	let oldestCrit = SiteStats.find();
	oldestCrit.where('period_type').equals('hour');
	oldestCrit.sort(['period_start', 'asc']);
	oldestCrit.limit(1);

	let oldestRecord = await SiteStats.find('first', oldestCrit);

	if (!oldestRecord) {
		this.log('No hourly data to aggregate');
		return;
	}

	// Start from the oldest record's day
	let currentDay = new Date(oldestRecord.period_start);
	currentDay.setHours(0, 0, 0, 0);

	// End before the current day (only aggregate complete days)
	let endDay = new Date();
	endDay.setHours(0, 0, 0, 0);

	this.log('Aggregating hourly data from', currentDay.toISOString(), 'to', endDay.toISOString());

	let totalAggregated = 0;
	let totalSkipped = 0;

	// Process one day at a time
	while (currentDay < endDay) {
		if (this.has_stopped) {
			this.log('Task stopped, aborting aggregation');
			return;
		}

		let nextDay = new Date(currentDay.getTime() + (24 * 60 * 60 * 1000));

		// Find all hourly records for this specific day
		let crit = SiteStats.find();
		crit.where('period_type').equals('hour');
		crit.where('period_start').gte(currentDay);
		crit.where('period_start').lt(nextDay);

		// Group by site_id
		let siteGroups = new Map();

		for await (let record of crit) {
			let siteId = String(record.site_id);

			if (!siteGroups.has(siteId)) {
				siteGroups.set(siteId, []);
			}

			siteGroups.get(siteId).push(record);
		}

		// Process each site's data for this day
		for (let [siteId, records] of siteGroups) {
			// Check if daily record already exists
			let existsCrit = SiteStats.find();
			existsCrit.where('site_id').equals(siteId);
			existsCrit.where('period_type').equals('day');
			existsCrit.where('period_start').equals(currentDay);

			let existing = await SiteStats.find('first', existsCrit);

			if (existing) {
				totalSkipped++;
				continue;
			}

			// Aggregate and store
			try {
				let aggregatedData = this.aggregateRecords(records);

				await SiteStats.storeAggregatedStats({
					site_id           : siteId,
					period_type       : 'day',
					period_start      : currentDay,
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

				totalAggregated++;
			} catch (err) {
				this.log('Error aggregating daily data for site', siteId + ':', err.message);
				alchemy.registerError(err, {context: 'Failed to aggregate daily stats for site ' + siteId});
			}
		}

		currentDay = nextDay;
	}

	this.log('Created', totalAggregated, 'daily records, skipped', totalSkipped, 'existing');
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
