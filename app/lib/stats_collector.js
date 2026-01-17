/**
 * The StatsCollector class
 * Collects and maintains statistics for the live dashboard
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {Develry.SiteDispatcher}   dispatcher   The site dispatcher instance
 * @param    {Object}                   options      Configuration options
 */
const StatsCollector = Function.inherits('Informer', 'Develry', function StatsCollector(dispatcher, options) {

	if (!dispatcher) {
		throw new Error('StatsCollector requires a dispatcher instance');
	}

	options = options || {};

	// Reference to the SiteDispatcher
	this.dispatcher = dispatcher;

	// Sample interval in milliseconds (default: 2 seconds)
	this.sampleInterval = options.sampleInterval || 2000;

	// Maximum number of samples to keep (default: 150 = 5 minutes at 2s intervals)
	this.maxSamples = options.maxSamples || 150;

	// RingBuffer for global statistics
	this.globalSamples = new Classes.Develry.RingBuffer(this.maxSamples);

	// Map of siteId -> {ring: RingBuffer, prev: sample}
	this.siteSamples = new Map();

	// Previous global sample for rate calculation
	this.prevGlobalSample = null;

	// Maximum number of activities to keep (default: 50)
	this.maxActivities = options.maxActivities || 50;

	// RingBuffer for recent activities
	this.activities = new Classes.Develry.RingBuffer(this.maxActivities);

	// The sampling interval timer
	this._intervalId = null;

	// Whether the collector is currently running
	this._running = false;

	// Persistence configuration
	this.persistenceInterval = options.persistenceInterval || 60 * 1000; // Default: every minute

	// Track the last persisted values for delta calculation
	this.lastPersistedSiteStats = new Map();

	// The persistence interval timer
	this._persistenceIntervalId = null;

	// Track current minute period start for aggregation
	this._currentMinutePeriod = null;

	// Aggregated samples within the current minute period
	this._minuteAggregates = new Map();
});

/**
 * Start collecting statistics at the configured interval.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @return   {StatsCollector}   Returns this for chaining
 */
StatsCollector.setMethod(function start() {

	if (this._running) {
		return this;
	}

	this._running = true;

	const that = this;

	// Collect immediately on start
	this.collect();

	// Subscribe to site lifecycle events
	this.subscribeSiteEvents();

	// Then collect at regular intervals
	this._intervalId = setInterval(function doCollect() {
		that.collect();
	}, this.sampleInterval);

	// Start persistence timer
	this._persistenceIntervalId = setInterval(function doPersist() {
		that.persistStats();
	}, this.persistenceInterval);

	return this;
});

/**
 * Subscribe to site lifecycle events for activity tracking.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
StatsCollector.setMethod(function subscribeSiteEvents() {

	const that = this;

	// Listen for site registration
	this.dispatcher.on('site_added', function onSiteAdded(site) {
		that.registerActivity('info', 'Site registered: ' + site.name, {
			site_name: site.name,
			site_id: site.id,
		});
		
		// Subscribe to process events for NodeSite instances
		if (site.process_list) {
			that.subscribeToSite(site);
		}
	});

	// Also subscribe to existing sites
	for (let id in this.dispatcher.ids) {
		let site = this.dispatcher.ids[id];
		if (site.process_list) {
			this.subscribeToSite(site);
		}
	}
});

/**
 * Subscribe to a specific site's events.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {Develry.Site}   site   The site to subscribe to
 */
StatsCollector.setMethod(function subscribeToSite(site) {

	const that = this;
	const siteName = site.name;
	const siteId = site.id;

	// Listen for new child processes
	site.on('child', function onChild(proc) {
		that.registerActivity('started', 'Process started (PID: ' + proc.pid + ')', {
			site_name: siteName,
			site_id: siteId,
		});

		// Listen for this process exiting
		proc.on('exit', function onExit(code, signal) {
			let type = code === 0 ? 'stopped' : 'error';
			let msg = code === 0 
				? 'Process stopped (PID: ' + proc.pid + ')'
				: 'Process crashed (PID: ' + proc.pid + ', code: ' + code + ')';
			
			that.registerActivity(type, msg, {
				site_name: siteName,
				site_id: siteId,
			});
		});
	});
});

/**
 * Stop collecting statistics.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @return   {StatsCollector}   Returns this for chaining
 */
StatsCollector.setMethod(function stop() {

	if (!this._running) {
		return this;
	}

	this._running = false;

	if (this._intervalId) {
		clearInterval(this._intervalId);
		this._intervalId = null;
	}

	if (this._persistenceIntervalId) {
		clearInterval(this._persistenceIntervalId);
		this._persistenceIntervalId = null;
	}

	return this;
});

/**
 * Collect one sample from all sources.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
StatsCollector.setMethod(function collect() {

	let timestamp = Date.now();

	// Collect global stats
	this.collectGlobal(timestamp);

	// Collect per-site stats
	this.collectSites(timestamp);

	// Emit the sample event
	this.emit('sample', timestamp);
});

/**
 * Collect global dispatcher statistics.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {Number}   timestamp   The current timestamp
 */
StatsCollector.setMethod(function collectGlobal(timestamp) {

	let activeSites = 0,
	    totalProcesses = 0,
	    totalIncoming = 0,
	    totalOutgoing = 0;

	// Count active sites, total processes, and sum bandwidth
	for (let id in this.dispatcher.ids) {
		let site = this.dispatcher.ids[id];

		activeSites++;

		// Sum incoming/outgoing bytes from all sites
		totalIncoming += site.incoming || 0;
		totalOutgoing += site.outgoing || 0;

		// Count processes for NodeSite instances
		if (site.process_list) {
			totalProcesses += site.process_list.length;
		}
	}

	// Calculate rates from previous sample
	let requestsPerSec = 0,
	    connectionsPerSec = 0,
	    incomingBytesPerSec = 0,
	    outgoingBytesPerSec = 0;

	if (this.prevGlobalSample) {
		let timeDelta = (timestamp - this.prevGlobalSample.timestamp) / 1000;

		if (timeDelta > 0) {
			let hitDelta = this.dispatcher.hitCounter - this.prevGlobalSample.hitCounter;
			let connDelta = this.dispatcher.connectionCounter - this.prevGlobalSample.connectionCounter;
			let inDelta = totalIncoming - this.prevGlobalSample.totalIncoming;
			let outDelta = totalOutgoing - this.prevGlobalSample.totalOutgoing;

			// Handle counter reset (negative delta)
			requestsPerSec = hitDelta >= 0 ? hitDelta / timeDelta : 0;
			connectionsPerSec = connDelta >= 0 ? connDelta / timeDelta : 0;
			incomingBytesPerSec = inDelta >= 0 ? inDelta / timeDelta : 0;
			outgoingBytesPerSec = outDelta >= 0 ? outDelta / timeDelta : 0;
		}
	}

	let sample = {
		timestamp           : timestamp,
		hitCounter          : this.dispatcher.hitCounter,
		connectionCounter   : this.dispatcher.connectionCounter,
		totalIncoming       : totalIncoming,
		totalOutgoing       : totalOutgoing,
		activeSites         : activeSites,
		totalProcesses      : totalProcesses,
		requestsPerSec      : requestsPerSec,
		connectionsPerSec   : connectionsPerSec,
		incomingBytesPerSec : incomingBytesPerSec,
		outgoingBytesPerSec : outgoingBytesPerSec,
	};

	// Store as previous for next rate calculation
	this.prevGlobalSample = sample;

	// Push to ring buffer
	this.globalSamples.push(sample);
});

/**
 * Collect statistics for all sites.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {Number}   timestamp   The current timestamp
 */
StatsCollector.setMethod(function collectSites(timestamp) {

	let currentSiteIds = new Set();

	// Iterate through all registered sites
	for (let id in this.dispatcher.ids) {
		let site = this.dispatcher.ids[id];

		currentSiteIds.add(id);

		// Get or create the site's sample storage
		let siteData = this.siteSamples.get(id);

		if (!siteData) {
			siteData = {
				ring : new Classes.Develry.RingBuffer(this.maxSamples),
				prev : null,
			};

			this.siteSamples.set(id, siteData);
		}

		// Collect the sample for this site
		let sample = this.collectSiteSample(site, timestamp, siteData.prev);

		// Store as previous for next rate calculation
		siteData.prev = sample;

		// Push to the site's ring buffer
		siteData.ring.push(sample);
	}

	// Clean up removed sites
	for (let id of this.siteSamples.keys()) {
		if (!currentSiteIds.has(id)) {
			this.siteSamples.delete(id);
		}
	}
});

/**
 * Collect a single sample for a site.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {Develry.Site}   site          The site instance
 * @param    {Number}         timestamp     The current timestamp
 * @param    {Object}         prevSample    The previous sample for rate calculation
 *
 * @return   {Object}   The collected sample
 */
StatsCollector.setMethod(function collectSiteSample(site, timestamp, prevSample) {

	let processCount = 0,
	    readyCount = 0,
	    processes = [],
	    totalCpu = 0,
	    totalMem = 0;

	// Collect process stats if this is a NodeSite
	if (site.process_list) {
		processCount = site.process_list.length;

		for (let i = 0; i < site.process_list.length; i++) {
			let proc = site.process_list[i];

			if (proc.ready) {
				readyCount++;
			}

			let cpu = proc.cpu || 0;
			let mem = proc.mem || 0;

			totalCpu += cpu;
			totalMem += mem;

			processes.push({
				pid      : proc.pid,
				cpu      : cpu,
				mem      : mem,
				uptime   : proc.startTime ? timestamp - proc.startTime : 0,
				isolated : !!proc.isolated,
			});
		}
	}

	// Calculate byte rates and request rates from previous sample
	let incomingBytesPerSec = 0,
	    outgoingBytesPerSec = 0,
	    requestsPerSec = 0;

	if (prevSample) {
		let timeDelta = (timestamp - prevSample.timestamp) / 1000;

		if (timeDelta > 0) {
			let inDelta = site.incoming - prevSample.incoming;
			let outDelta = site.outgoing - prevSample.outgoing;
			let hitDelta = site.hitCounter - prevSample.hitCounter;

			// Handle counter reset (negative delta)
			incomingBytesPerSec = inDelta >= 0 ? inDelta / timeDelta : 0;
			outgoingBytesPerSec = outDelta >= 0 ? outDelta / timeDelta : 0;
			requestsPerSec = hitDelta >= 0 ? hitDelta / timeDelta : 0;
		}
	}

	// Calculate average CPU (avoid division by zero)
	let avgCpu = processCount > 0 ? totalCpu / processCount : 0;

	return {
		timestamp           : timestamp,
		incoming            : site.incoming,
		outgoing            : site.outgoing,
		hitCounter          : site.hitCounter,
		processCount        : processCount,
		readyCount          : readyCount,
		processes           : processes,
		incomingBytesPerSec : incomingBytesPerSec,
		outgoingBytesPerSec : outgoingBytesPerSec,
		requestsPerSec      : requestsPerSec,
		avgCpu              : avgCpu,
		totalMem            : totalMem,
	};
});

/**
 * Calculate rate from cumulative counters over a time window.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {Array}    samples     Array of samples with timestamp and value
 * @param    {String}   valueKey    The key to get the cumulative value from
 * @param    {Number}   windowMs    The time window in milliseconds
 *
 * @return   {Number}   The calculated rate per second
 */
StatsCollector.setMethod(function calculateRate(samples, valueKey, windowMs) {

	if (!samples || samples.length < 2) {
		return 0;
	}

	let now = Date.now();
	let windowStart = now - windowMs;

	// Find the first sample within the window
	let startSample = null;
	let endSample = samples[samples.length - 1];

	for (let i = 0; i < samples.length; i++) {
		if (samples[i].timestamp >= windowStart) {
			// Use the sample just before the window if available
			startSample = i > 0 ? samples[i - 1] : samples[i];
			break;
		}
	}

	if (!startSample || startSample === endSample) {
		return 0;
	}

	let timeDelta = (endSample.timestamp - startSample.timestamp) / 1000;

	if (timeDelta <= 0) {
		return 0;
	}

	let valueDelta = endSample[valueKey] - startSample[valueKey];

	// Handle counter reset
	if (valueDelta < 0) {
		return 0;
	}

	return valueDelta / timeDelta;
});

/**
 * Get current global statistics snapshot with rates.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @return   {Object}   Current global stats
 */
StatsCollector.setMethod(function getGlobalStats() {

	let latest = this.globalSamples.peek();

	if (!latest) {
		return {
			timestamp         : Date.now(),
			hitCounter        : this.dispatcher.hitCounter,
			connectionCounter : this.dispatcher.connectionCounter,
			activeSites       : Object.keys(this.dispatcher.ids).length,
			totalProcesses    : 0,
			requestsPerSec    : 0,
			connectionsPerSec : 0,
			requestsPerMin1   : 0,
			requestsPerMin5   : 0,
		};
	}

	let samples = this.globalSamples.toArray();

	// Calculate rolling rates
	let requestsPerMin1 = this.calculateRate(samples, 'hitCounter', 60 * 1000);
	let requestsPerMin5 = this.calculateRate(samples, 'hitCounter', 5 * 60 * 1000);

	return {
		...latest,
		requestsPerMin1 : requestsPerMin1,
		requestsPerMin5 : requestsPerMin5,
	};
});

/**
 * Get statistics for a specific site.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {String}   siteId   The site's ID
 *
 * @return   {Object|null}   Site stats or null if not found
 */
StatsCollector.setMethod(function getSiteStats(siteId) {

	let siteData = this.siteSamples.get(siteId);

	if (!siteData) {
		return null;
	}

	let latest = siteData.ring.peek();

	if (!latest) {
		return null;
	}

	let samples = siteData.ring.toArray();

	// Calculate rolling byte rates
	let incomingPerMin1 = this.calculateRate(samples, 'incoming', 60 * 1000);
	let incomingPerMin5 = this.calculateRate(samples, 'incoming', 5 * 60 * 1000);
	let outgoingPerMin1 = this.calculateRate(samples, 'outgoing', 60 * 1000);
	let outgoingPerMin5 = this.calculateRate(samples, 'outgoing', 5 * 60 * 1000);

	// Calculate rolling request rates
	let requestsPerMin1 = this.calculateRate(samples, 'hitCounter', 60 * 1000);
	let requestsPerMin5 = this.calculateRate(samples, 'hitCounter', 5 * 60 * 1000);

	// Get the site instance for additional info
	let site = this.dispatcher.ids[siteId];

	return {
		...latest,
		siteId          : siteId,
		name            : site ? site.name : 'Unknown',
		incomingPerMin1 : incomingPerMin1,
		incomingPerMin5 : incomingPerMin5,
		outgoingPerMin1 : outgoingPerMin1,
		outgoingPerMin5 : outgoingPerMin5,
		requestsPerMin1 : requestsPerMin1,
		requestsPerMin5 : requestsPerMin5,
	};
});

/**
 * Get statistics for all sites.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @return   {Array}   Array of site stats objects
 */
StatsCollector.setMethod(function getAllSiteStats() {

	let result = [];

	for (let id in this.dispatcher.ids) {
		let stats = this.getSiteStats(id);

		if (stats) {
			result.push(stats);
		}
	}

	return result;
});

/**
 * Get time series data for a global metric.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {String}   metric      The metric key to extract
 * @param    {Number}   maxPoints   Maximum number of points to return (optional)
 *
 * @return   {Array}   Array of {timestamp, value} objects
 */
StatsCollector.setMethod(function getGlobalTimeSeries(metric, maxPoints) {

	let samples;

	if (maxPoints && maxPoints < this.globalSamples.length) {
		samples = this.globalSamples.getLast(maxPoints);
	} else {
		samples = this.globalSamples.toArray();
	}

	let result = new Array(samples.length);

	for (let i = 0; i < samples.length; i++) {
		result[i] = {
			timestamp : samples[i].timestamp,
			value     : samples[i][metric],
		};
	}

	return result;
});

/**
 * Get time series data for a site metric.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {String}   siteId      The site's ID
 * @param    {String}   metric      The metric key to extract
 * @param    {Number}   maxPoints   Maximum number of points to return (optional)
 *
 * @return   {Array}   Array of {timestamp, value} objects, or empty array if site not found
 */
StatsCollector.setMethod(function getSiteTimeSeries(siteId, metric, maxPoints) {

	let siteData = this.siteSamples.get(siteId);

	if (!siteData) {
		return [];
	}

	let samples;

	if (maxPoints && maxPoints < siteData.ring.length) {
		samples = siteData.ring.getLast(maxPoints);
	} else {
		samples = siteData.ring.toArray();
	}

	let result = new Array(samples.length);

	for (let i = 0; i < samples.length; i++) {
		result[i] = {
			timestamp : samples[i].timestamp,
			value     : samples[i][metric],
		};
	}

	return result;
});

/**
 * Get the complete dashboard state.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @return   {Object}   Dashboard state with global and site stats
 */
StatsCollector.setMethod(function getDashboardState() {

	return {
		global    : this.getGlobalStats(),
		sites     : this.getAllSiteStats(),
		timestamp : Date.now(),
	};
});

/**
 * Register an activity event.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {String}   type       Activity type (started, stopped, error, warning, etc.)
 * @param    {String}   message    Description of the activity
 * @param    {Object}   options    Additional options (site_name, site_id, etc.)
 */
StatsCollector.setMethod(function registerActivity(type, message, options) {

	options = options || {};

	let activity = {
		type      : type,
		message   : message,
		site_name : options.site_name || null,
		site_id   : options.site_id || null,
		timestamp : Date.now(),
	};

	this.activities.push(activity);
	this.emit('activity', activity);
});

/**
 * Get all recent activities.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @return   {Array}   Array of activity objects
 */
StatsCollector.setMethod(function getActivities() {
	return this.activities.toArray();
});

/**
 * Get the start of the current minute period.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @return   {Date}   The start of the current minute
 */
StatsCollector.setMethod(function getCurrentMinutePeriod() {
	let now = new Date();
	return new Date(now.getFullYear(), now.getMonth(), now.getDate(), now.getHours(), now.getMinutes(), 0, 0);
});

/**
 * Persist aggregated statistics to the database.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
StatsCollector.setMethod(async function persistStats() {

	let SiteStats = Model.get('SiteStats');

	if (!SiteStats) {
		return;
	}

	let currentPeriod = this.getCurrentMinutePeriod();

	// If we've moved to a new minute period, persist the previous period's data
	if (this._currentMinutePeriod && this._currentMinutePeriod.getTime() !== currentPeriod.getTime()) {
		await this.flushMinuteAggregates();
	}

	this._currentMinutePeriod = currentPeriod;

	// Aggregate current samples into minute buckets
	for (let id in this.dispatcher.ids) {
		let site = this.dispatcher.ids[id];
		let siteData = this.siteSamples.get(id);

		if (!siteData || !siteData.prev) {
			continue;
		}

		let sample = siteData.prev;
		let lastPersisted = this.lastPersistedSiteStats.get(id);

		// Calculate deltas since last persistence
		let incomingDelta = 0;
		let outgoingDelta = 0;
		let requestDelta = 0;

		if (lastPersisted) {
			incomingDelta = sample.incoming - lastPersisted.incoming;
			outgoingDelta = sample.outgoing - lastPersisted.outgoing;
			requestDelta = sample.hitCounter - lastPersisted.hitCounter;

			// Handle counter resets (negative deltas)
			if (incomingDelta < 0) incomingDelta = sample.incoming;
			if (outgoingDelta < 0) outgoingDelta = sample.outgoing;
			if (requestDelta < 0) requestDelta = sample.hitCounter;
		} else {
			// First time tracking this site
			incomingDelta = sample.incoming;
			outgoingDelta = sample.outgoing;
			requestDelta = sample.hitCounter;
		}

		// Get or create aggregate for this site
		let aggregate = this._minuteAggregates.get(id);

		if (!aggregate) {
			aggregate = {
				site_id           : id,
				period_type       : 'minute',
				period_start      : currentPeriod,
				incoming_bytes    : BigInt(0),
				outgoing_bytes    : BigInt(0),
				request_count     : BigInt(0),
				process_counts    : [],
				cpu_values        : [],
				mem_values        : [],
				sample_count      : 0,
			};

			this._minuteAggregates.set(id, aggregate);
		}

		// Add to aggregates
		aggregate.incoming_bytes += BigInt(incomingDelta);
		aggregate.outgoing_bytes += BigInt(outgoingDelta);
		aggregate.request_count += BigInt(requestDelta);
		aggregate.process_counts.push(sample.processCount);
		aggregate.cpu_values.push(sample.avgCpu);
		aggregate.mem_values.push(sample.totalMem);
		aggregate.sample_count++;

		// Update last persisted values
		this.lastPersistedSiteStats.set(id, {
			incoming   : sample.incoming,
			outgoing   : sample.outgoing,
			hitCounter : sample.hitCounter,
		});
	}
});

/**
 * Flush the minute aggregates to the database.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
StatsCollector.setMethod(async function flushMinuteAggregates() {

	let SiteStats = Model.get('SiteStats');

	if (!SiteStats) {
		return;
	}

	for (let [siteId, aggregate] of this._minuteAggregates) {
		if (aggregate.sample_count === 0) {
			continue;
		}

		// Calculate averages and maxes
		let avgProcessCount = aggregate.process_counts.reduce((a, b) => a + b, 0) / aggregate.process_counts.length;
		let maxProcessCount = Math.max(...aggregate.process_counts);
		let avgCpu = aggregate.cpu_values.reduce((a, b) => a + b, 0) / aggregate.cpu_values.length;
		let maxCpu = Math.max(...aggregate.cpu_values);
		let avgMem = aggregate.mem_values.reduce((a, b) => a + b, 0) / aggregate.mem_values.length;
		let maxMem = Math.max(...aggregate.mem_values);

		let data = {
			site_id           : siteId,
			period_type       : 'minute',
			period_start      : aggregate.period_start,
			incoming_bytes    : aggregate.incoming_bytes,
			outgoing_bytes    : aggregate.outgoing_bytes,
			request_count     : aggregate.request_count,
			avg_process_count : avgProcessCount,
			max_process_count : maxProcessCount,
			avg_cpu           : avgCpu,
			max_cpu           : maxCpu,
			avg_mem           : avgMem,
			max_mem           : maxMem,
			sample_count      : aggregate.sample_count,
		};

		try {
			await SiteStats.storeAggregatedStats(data);
		} catch (err) {
			alchemy.registerError(err, {context: 'Failed to persist site stats for site ' + siteId});
		}
	}

	// Clear aggregates
	this._minuteAggregates.clear();
});
