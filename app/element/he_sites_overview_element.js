/**
 * The HeSitesOverview element
 * Shows a grid of site cards with detailed stats
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
const HeSitesOverview = Function.inherits('Alchemy.Element.App', 'HeSitesOverview');

/**
 * The stylesheet to use
 */
HeSitesOverview.setStylesheetFile('he_dashboard');

/**
 * Maximum number of sites to show
 */
HeSitesOverview.setAttribute('max-sites', {type: 'number', default: 12});

/**
 * Current sites data
 */
HeSitesOverview.setAssignedProperty('sites', null);

/**
 * Build the initial HTML structure when connected to DOM
 */
HeSitesOverview.setMethod(function connected() {

	// Only build structure once
	if (this._built) {
		return;
	}

	this._built = true;

	// Create the section wrapper
	let section = this.createElement('section');
	section.className = 'dashboard-widget';
	section.setAttribute('aria-labelledby', 'sites-header');

	// Create header
	let header = this.createElement('h3');
	header.id = 'sites-header';
	header.className = 'widget-header';
	header.textContent = 'Active Sites';
	section.appendChild(header);

	// Create loading indicator
	let loading = this.createElement('div');
	loading.className = 'widget-loading';
	loading.textContent = 'Loading...';
	section.appendChild(loading);

	// Create sites container
	let container = this.createElement('div');
	container.className = 'sites-overview';

	// Create the grid
	let grid = this.createElement('div');
	grid.className = 'sites-grid';
	container.appendChild(grid);

	section.appendChild(container);
	this.appendChild(section);
});

/**
 * Element has been added to the DOM
 */
HeSitesOverview.setMethod(function introduced() {

	if (!Blast.isBrowser) {
		return;
	}

	this.provider = Classes.Develry.Client.DashboardDataProvider.getInstance();
	this.provider.subscribe();

	// Define callback first
	this._onSitesUpdate = (sites) => {
		this.sites = sites;
		this.renderSites();
	};

	// Register listener before checking initial data
	this.provider.on('sites_update', this._onSitesUpdate);

	// Check for initial data after registering listener
	if (this.provider.sites) {
		this._onSitesUpdate(this.provider.sites);
	}
});

/**
 * Element has been removed from the DOM
 */
HeSitesOverview.setMethod(function removed() {

	if (this.provider) {
		this.provider.removeListener('sites_update', this._onSitesUpdate);
		this.provider.unsubscribe();
		this.provider = null;
	}
});

/**
 * Render the sites grid
 */
HeSitesOverview.setMethod(function renderSites() {

	let container = this.querySelector('.sites-grid');

	if (!container) {
		return;
	}

	// Hide loading state
	let loading = this.querySelector('.widget-loading');
	if (loading) {
		loading.hidden = true;
	}

	let sites = this.sites || [];
	let maxSitesAttr = this.getAttribute('max-sites');
	let maxSites = (maxSitesAttr && maxSitesAttr !== 'undefined') ? parseInt(maxSitesAttr, 10) : 12;
	
	if (isNaN(maxSites) || maxSites <= 0) {
		maxSites = 12;
	}

	// Sort by activity (processes first, then by bandwidth)
	sites = sites.slice().sort((a, b) => {
		// Sites with processes come first
		if (a.processCount !== b.processCount) {
			return b.processCount - a.processCount;
		}
		// Then sort by total bandwidth
		let aBw = (a.incomingBytesPerSec || 0) + (a.outgoingBytesPerSec || 0);
		let bBw = (b.incomingBytesPerSec || 0) + (b.outgoingBytesPerSec || 0);
		return bBw - aBw;
	});

	// Limit
	sites = sites.slice(0, maxSites);

	// Clear existing content
	Hawkejs.removeChildren(container);

	if (sites.length === 0) {
		let empty = document.createElement('div');
		empty.className = 'sites-empty';
		empty.textContent = 'No sites configured';
		container.appendChild(empty);
		return;
	}

	for (let site of sites) {
		let card = this.createSiteCard(site);
		container.appendChild(card);
	}
});

/**
 * Determine site status based on stats
 */
HeSitesOverview.setMethod(function getSiteStatus(site) {

	if (site.processCount > 0 && site.readyCount === site.processCount) {
		return 'healthy';
	}

	if (site.processCount > 0 && site.readyCount < site.processCount) {
		return 'degraded';
	}

	if (site.processCount > 0) {
		return 'running';
	}

	// No processes - could be static site or idle
	if ((site.incomingBytesPerSec || 0) > 0 || (site.outgoingBytesPerSec || 0) > 0) {
		return 'running';
	}

	return 'idle';
});

/**
 * Get status label
 */
HeSitesOverview.setMethod(function getStatusLabel(status) {

	switch (status) {
		case 'healthy': return 'Healthy';
		case 'running': return 'Running';
		case 'degraded': return 'Degraded';
		case 'idle': return 'Idle';
		case 'down': return 'Down';
		default: return 'Unknown';
	}
});

/**
 * Create a site card element using DOM APIs (XSS-safe)
 */
HeSitesOverview.setMethod(function createSiteCard(site) {

	let card = document.createElement('div');
	card.className = 'site-card';

	let status = this.getSiteStatus(site);
	card.classList.add('status-' + status);

	// Header with name and status badge
	let header = document.createElement('div');
	header.className = 'site-header';

	let nameSpan = document.createElement('span');
	nameSpan.className = 'site-name';
	nameSpan.textContent = site.name || 'Unknown';
	header.appendChild(nameSpan);

	let statusBadge = document.createElement('span');
	statusBadge.className = 'site-status status-' + status;
	statusBadge.textContent = this.getStatusLabel(status);
	header.appendChild(statusBadge);

	card.appendChild(header);

	// Stats row
	let stats = document.createElement('div');
	stats.className = 'site-stats';

	// Processes stat
	let procStat = this.createStatBlock(
		site.processCount || 0,
		'Procs'
	);
	stats.appendChild(procStat);

	// Request count
	let requestStat = this.createStatBlock(
		this.formatNumber(site.hitCounter || 0),
		'Requests'
	);
	stats.appendChild(requestStat);

	// Requests per second (rate)
	let reqPerSec = site.requestsPerSec || 0;
	let rateStat = this.createStatBlock(
		reqPerSec > 0 ? reqPerSec.toFixed(1) + '/s' : '0/s',
		'Rate'
	);
	stats.appendChild(rateStat);

	card.appendChild(stats);

	return card;
});

/**
 * Create a stat block element
 */
HeSitesOverview.setMethod(function createStatBlock(value, label) {

	let block = document.createElement('div');
	block.className = 'site-stat';

	let valueEl = document.createElement('div');
	valueEl.className = 'site-stat-value';
	valueEl.textContent = value;
	block.appendChild(valueEl);

	let labelEl = document.createElement('div');
	labelEl.className = 'site-stat-label';
	labelEl.textContent = label;
	block.appendChild(labelEl);

	return block;
});

/**
 * Format large numbers with K/M/B suffixes
 */
HeSitesOverview.setMethod(function formatNumber(num) {

	if (num == null || num === 0) {
		return '0';
	}

	if (num < 1000) {
		return String(num);
	}

	if (num < 1000000) {
		return (num / 1000).toFixed(1) + 'K';
	}

	if (num < 1000000000) {
		return (num / 1000000).toFixed(1) + 'M';
	}

	return (num / 1000000000).toFixed(1) + 'B';
});
