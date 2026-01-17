/**
 * Dashboard Data Provider
 * Manages a single linkup connection and broadcasts data to widget elements
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */

// This class is only meant for the client side
if (Blast.isNode) {
	return;
}

const DashboardDataProvider = Function.inherits('Informer', 'Develry.Client', function DashboardDataProvider() {
	// Call parent constructor
	DashboardDataProvider.super.call(this);
});

/**
 * The singleton instance
 */
let instance = null;

/**
 * Get the singleton instance
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @return   {DashboardDataProvider}
 */
DashboardDataProvider.setStatic(function getInstance() {

	if (!instance) {
		instance = new DashboardDataProvider();
	}

	return instance;
});

/**
 * The current stats data
 */
DashboardDataProvider.setProperty('stats', null);

/**
 * The current sites data
 */
DashboardDataProvider.setProperty('sites', null);

/**
 * The history data
 */
DashboardDataProvider.setProperty('history', null);

/**
 * The activities array
 */
DashboardDataProvider.setProperty('activities', null);

/**
 * Whether we're connected
 */
DashboardDataProvider.setProperty('connected', false);

/**
 * The linkup instance
 */
DashboardDataProvider.setProperty('linkup', null);

/**
 * Number of active subscribers
 */
DashboardDataProvider.setProperty('subscriber_count', 0);

/**
 * Subscribe to data updates - call this when a widget is added to the DOM
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
DashboardDataProvider.setMethod(function subscribe() {
	this.subscriber_count++;
	
	// Connect if this is the first subscriber
	if (this.subscriber_count === 1) {
		this.connect();
	}
	
	return this;
});

/**
 * Unsubscribe - call this when a widget is removed from the DOM
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
DashboardDataProvider.setMethod(function unsubscribe() {
	this.subscriber_count--;
	
	// Disconnect if no more subscribers
	if (this.subscriber_count <= 0) {
		this.subscriber_count = 0;
		this.disconnect();
	}
	
	return this;
});

/**
 * Connect to the server
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
DashboardDataProvider.setMethod(function connect() {

	if (this.linkup) {
		return;
	}

	if (!Blast.isBrowser) {
		return;
	}

	alchemy.enableWebsockets();

	try {
		this.linkup = alchemy.linkup('dashboardlive', {});
	} catch (err) {
		console.error('Failed to create dashboard linkup:', err);
		this.connected = false;
		this.scheduleReconnect();
		return;
	}

	this.linkup.on('init', (data) => {
		this.handleInit(data);
	});

	this.linkup.on('stats', (data) => {
		this.handleStats(data);
	});

	this.linkup.on('activity', (data) => {
		this.handleActivity(data);
	});

	this.linkup.on('error', (err) => {
		console.error('[DashboardDataProvider] Linkup error:', err);
		this.connected = false;
		this.emit('connection_change', false);
	});

	this.linkup.on('close', () => {
		this.connected = false;
		this.linkup = null;
		this.emit('connection_change', false);
		
		if (this.subscriber_count > 0) {
			this.scheduleReconnect();
		}
	});
});

/**
 * Disconnect from the server
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
DashboardDataProvider.setMethod(function disconnect() {

	if (this._reconnect_timeout) {
		clearTimeout(this._reconnect_timeout);
		this._reconnect_timeout = null;
	}

	if (this.linkup) {
		this.linkup.destroy();
		this.linkup = null;
	}

	this.connected = false;
});

/**
 * Schedule a reconnection attempt
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
DashboardDataProvider.setMethod(function scheduleReconnect() {

	if (this._reconnect_timeout) {
		clearTimeout(this._reconnect_timeout);
	}

	this._reconnect_timeout = setTimeout(() => {
		this._reconnect_timeout = null;
		
		if (this.subscriber_count > 0) {
			this.connect();
		}
	}, 5000);
});

/**
 * Handle init data
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
DashboardDataProvider.setMethod(function handleInit(data) {

	this.connected = true;

	if (data.state) {
		this.stats = data.state.global;
		this.sites = data.state.sites;
	}

	if (data.history) {
		this.history = data.history;
	}

	this.activities = this.activities || [];

	this.emit('connection_change', true);
	this.emit('stats_update', this.stats);
	this.emit('sites_update', this.sites);
	this.emit('history_update', this.history);
});

/**
 * Handle stats update
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
DashboardDataProvider.setMethod(function handleStats(data) {

	if (data.global) {
		this.stats = data.global;
		this.emit('stats_update', this.stats);
	}

	if (data.sites) {
		this.sites = data.sites;
		this.emit('sites_update', this.sites);
	}

	// Update history
	if (data.timestamp && this.history) {
		this.history.requests = this.history.requests || [];

		this.history.requests.push({
			timestamp: data.timestamp,
			value: data.global?.requestsPerSec || 0,
		});

		// Keep last 150 points
		if (this.history.requests.length > 150) {
			this.history.requests.shift();
		}

		this.emit('history_update', this.history);
	}
});

/**
 * Handle activity event
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
DashboardDataProvider.setMethod(function handleActivity(data) {

	this.activities = this.activities || [];
	this.activities.unshift(data);

	if (this.activities.length > 100) {
		this.activities.pop();
	}

	this.emit('activity', data);
	this.emit('activities_update', this.activities);
});
