/**
 * The HeStatsOverview element
 * Shows key metrics as stat cards
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
const HeStatsOverview = Function.inherits('Alchemy.Element.App', 'HeStatsOverview');

/**
 * The stylesheet to use
 */
HeStatsOverview.setStylesheetFile('he_dashboard');

/**
 * Stat card configurations
 */
const STAT_CARDS = [
	{name: 'requests', label: 'Requests/sec', icon: 'gauge-high'},
	{name: 'hits', label: 'Total Requests', icon: 'arrow-right-arrow-left'},
	{name: 'connections', label: 'Connections', icon: 'link'},
	{name: 'sites', label: 'Active Sites', icon: 'server'},
	{name: 'processes', label: 'Processes', icon: 'microchip'},
];

/**
 * Current stats
 */
HeStatsOverview.setAssignedProperty('stats', null);

/**
 * Build the initial HTML structure when connected to DOM
 */
HeStatsOverview.setMethod(function connected() {

	// Only build structure once
	if (this._built) {
		return;
	}

	this._built = true;

	// Create the section wrapper
	let section = this.createElement('section');
	section.className = 'dashboard-widget';
	section.setAttribute('aria-labelledby', 'stats-header');

	// Create header
	let header = this.createElement('h3');
	header.id = 'stats-header';
	header.className = 'widget-header';
	header.textContent = 'System Overview';
	section.appendChild(header);

	// Create loading indicator
	let loading = this.createElement('div');
	loading.className = 'widget-loading';
	loading.textContent = 'Loading...';
	section.appendChild(loading);

	// Create stats container
	let container = this.createElement('div');
	container.className = 'stats-overview';

	// Create each stat card
	for (let config of STAT_CARDS) {
		let card = this.createStatCard(config);
		container.appendChild(card);
	}

	section.appendChild(container);
	this.appendChild(section);
});

/**
 * Create a stat card element
 */
HeStatsOverview.setMethod(function createStatCard(config) {

	let card = this.createElement('div');
	card.className = 'stat-card';
	card.dataset.stat = config.name;

	let iconDiv = this.createElement('div');
	iconDiv.className = 'stat-icon';
	
	let icon = this.createElement('al-icon');
	icon.setAttribute('icon-name', config.icon);
	iconDiv.appendChild(icon);
	card.appendChild(iconDiv);

	let content = this.createElement('div');
	content.className = 'stat-content';

	let value = this.createElement('div');
	value.className = 'stat-value';
	value.textContent = '-';
	content.appendChild(value);

	let label = this.createElement('div');
	label.className = 'stat-label';
	label.textContent = config.label;
	content.appendChild(label);

	card.appendChild(content);

	return card;
});

/**
 * Element has been added to the DOM
 */
HeStatsOverview.setMethod(function introduced() {

	if (!Blast.isBrowser) {
		return;
	}

	this.provider = Classes.Develry.Client.DashboardDataProvider.getInstance();
	this.provider.subscribe();

	// Define callback first
	this._onStatsUpdate = (stats) => {
		this.stats = stats;
		this.updateDisplay();
	};

	// Register listener before checking initial data
	this.provider.on('stats_update', this._onStatsUpdate);

	// Check for initial data after registering listener
	if (this.provider.stats) {
		this._onStatsUpdate(this.provider.stats);
	}
});

/**
 * Element has been removed from the DOM
 */
HeStatsOverview.setMethod(function removed() {

	if (this.provider) {
		this.provider.removeListener('stats_update', this._onStatsUpdate);
		this.provider.unsubscribe();
		this.provider = null;
	}
});

/**
 * Update the display with current stats
 */
HeStatsOverview.setMethod(function updateDisplay() {

	if (!this.stats) {
		return;
	}

	// Hide loading state
	let loading = this.querySelector('.widget-loading');
	if (loading) {
		loading.hidden = true;
	}

	this.updateStatCard('requests', this.stats.requestsPerMin1 || this.stats.requestsPerSec);
	this.updateStatCard('hits', this.stats.hitCounter);
	this.updateStatCard('connections', this.stats.connectionCounter);
	this.updateStatCard('sites', this.stats.activeSites);
	this.updateStatCard('processes', this.stats.totalProcesses);
});

/**
 * Update a single stat card
 */
HeStatsOverview.setMethod(function updateStatCard(name, value) {

	let card = this.querySelector('[data-stat="' + name + '"]');

	if (!card) {
		return;
	}

	let valueEl = card.querySelector('.stat-value');

	if (valueEl) {
		valueEl.textContent = this.formatValue(value);
	}
});

/**
 * Format a value
 */
HeStatsOverview.setMethod(function formatValue(val) {

	if (val == null) {
		return '-';
	}

	if (val >= 1000000) {
		return (val / 1000000).toFixed(1) + 'M';
	} else if (val >= 1000) {
		return (val / 1000).toFixed(1) + 'K';
	} else if (Number.isInteger(val)) {
		return val.toString();
	} else {
		return val.toFixed(1);
	}
});
