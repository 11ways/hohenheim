/**
 * The HeTrafficChart element
 * Shows traffic chart using uPlot
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
const HeTrafficChart = Function.inherits('Alchemy.Element.App', 'HeTrafficChart');

/**
 * The stylesheet to use
 */
HeTrafficChart.setStylesheetFile('he_dashboard');

/**
 * Which metric to display
 */
HeTrafficChart.setAttribute('metric', {type: 'string', default: 'requests'});

/**
 * Chart height
 */
HeTrafficChart.setAttribute('height', {type: 'number', default: 200});

/**
 * Current chart data
 */
HeTrafficChart.setAssignedProperty('data', null);

/**
 * uPlot chart instance
 */
HeTrafficChart.setAssignedProperty('chart', null);

/**
 * Build the initial HTML structure when connected to DOM
 */
HeTrafficChart.setMethod(function connected() {

	// Only build structure once
	if (this._built) {
		return;
	}

	this._built = true;

	// Create the section wrapper
	let section = this.createElement('section');
	section.className = 'dashboard-widget';
	section.setAttribute('aria-labelledby', 'chart-header');

	// Create header
	let header = this.createElement('h3');
	header.id = 'chart-header';
	header.className = 'widget-header';
	header.textContent = 'Traffic';
	section.appendChild(header);

	// Create loading indicator
	let loading = this.createElement('div');
	loading.className = 'widget-loading';
	loading.textContent = 'Loading...';
	section.appendChild(loading);

	// Create chart container
	let container = this.createElement('div');
	container.className = 'chart-container';

	// Create empty state overlay
	let emptyOverlay = this.createElement('div');
	emptyOverlay.className = 'chart-empty-overlay';
	emptyOverlay.innerHTML = '<al-icon icon-name="chart-line"></al-icon><span>No proxy traffic yet</span><small>Traffic is measured on ports 80/443</small>';
	emptyOverlay.hidden = true;
	container.appendChild(emptyOverlay);

	section.appendChild(container);
	this.appendChild(section);
});

/**
 * Element has been added to the DOM
 */
HeTrafficChart.setMethod(function introduced() {

	if (!Blast.isBrowser) {
		return;
	}

	this.provider = Classes.Develry.Client.DashboardDataProvider.getInstance();
	this.provider.subscribe();

	// Define callback first
	this._onHistoryUpdate = (history) => {
		// Hide loading state
		let loading = this.querySelector('.widget-loading');
		if (loading) {
			loading.hidden = true;
		}

		let metric = this.getAttribute('metric') || 'requests';
		this.data = history[metric] || [];
		this.updateChart();
	};

	// Register listener before checking initial data
	this.provider.on('history_update', this._onHistoryUpdate);

	// Check for initial data after registering listener
	if (this.provider.history) {
		this._onHistoryUpdate(this.provider.history);
	}

	// Initialize chart
	this.initChart();
});

/**
 * Element has been removed from the DOM
 */
HeTrafficChart.setMethod(function removed() {

	if (this.provider) {
		this.provider.removeListener('history_update', this._onHistoryUpdate);
		this.provider.unsubscribe();
		this.provider = null;
	}

	if (this.chart) {
		this.chart.destroy();
		this.chart = null;
	}

	if (this._resizeObserver) {
		this._resizeObserver.disconnect();
		this._resizeObserver = null;
	}
});

/**
 * Initialize the uPlot chart
 */
HeTrafficChart.setMethod(async function initChart() {

	let container = this.querySelector('.chart-container');

	if (!container) {
		return;
	}

	// Load uPlot if not already loaded
	if (typeof uPlot === 'undefined') {
		hawkejs.scene.enableStyle('https://unpkg.com/uplot@1.6.24/dist/uPlot.min.css');
		await hawkejs.require('https://unpkg.com/uplot@1.6.24/dist/uPlot.iife.min.js');
	}

	if (typeof uPlot === 'undefined') {
		console.error('[HeTrafficChart] Failed to load uPlot');
		return;
	}

	let height = parseInt(this.getAttribute('height')) || 200;

	const opts = {
		width: container.clientWidth || 400,
		height: height,
		series: [
			{},
			{
				stroke: '#3b82f6',
				width: 2,
				fill: 'rgba(59, 130, 246, 0.1)',
			}
		],
		scales: {
			x: {time: true},
			y: {auto: true},
		},
		axes: [
			{stroke: '#888', grid: {stroke: '#333'}},
			{stroke: '#888', grid: {stroke: '#333'}},
		],
	};

	let data = [[], []];
	this.chart = new uPlot(opts, data, container);

	// Handle resize
	this._resizeObserver = new ResizeObserver(() => {
		if (this.chart && container.clientWidth) {
			this.chart.setSize({width: container.clientWidth, height: height});
		}
	});
	this._resizeObserver.observe(container);
});

/**
 * Update the chart with new data
 */
HeTrafficChart.setMethod(function updateChart() {

	if (!this.chart || !this.data) {
		return;
	}

	// Check if we have any actual traffic
	let hasTraffic = this.data && this.data.some(point => point.value > 0);
	let overlay = this.querySelector('.chart-empty-overlay');
	if (overlay) {
		overlay.hidden = hasTraffic;
	}

	let timestamps = [];
	let values = [];

	for (let point of this.data) {
		timestamps.push(point.timestamp / 1000);
		values.push(point.value || 0);
	}

	this.chart.setData([timestamps, values]);
});
