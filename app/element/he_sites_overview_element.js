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
 * Cached sorted sites (to reduce jitter)
 */
HeSitesOverview.setAssignedProperty('sortedSites', null);

/**
 * Last time we sorted the sites
 */
HeSitesOverview.setAssignedProperty('lastSortTime', 0);

/**
 * How often to re-sort (in milliseconds)
 */
HeSitesOverview.SORT_INTERVAL = 20000; // 20 seconds

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

	// Only re-sort periodically to reduce jitter
	let now = Date.now();
	let shouldSort = !this.sortedSites || (now - this.lastSortTime) > HeSitesOverview.SORT_INTERVAL;

	if (shouldSort) {
		// Sort by total request count (most stable metric)
		this.sortedSites = sites.slice().sort((a, b) => {
			return (b.hitCounter || 0) - (a.hitCounter || 0);
		});
		this.lastSortTime = now;
	} else {
		// Update the data in existing sort order
		let siteMap = new Map(sites.map(s => [s.siteId, s]));
		this.sortedSites = this.sortedSites.map(s => siteMap.get(s.siteId) || s).filter(s => siteMap.has(s.siteId));
		
		// Add any new sites at the end
		for (let site of sites) {
			if (!this.sortedSites.find(s => s.siteId === site.siteId)) {
				this.sortedSites.push(site);
			}
		}
	}

	// Limit
	sites = this.sortedSites.slice(0, maxSites);

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
	// Use 1-minute averaged rates for stability (falls back to instant rate)
	let incomingRate = site.incomingPerMin1 || site.incomingBytesPerSec || 0;
	let outgoingRate = site.outgoingPerMin1 || site.outgoingBytesPerSec || 0;
	if (incomingRate > 0 || outgoingRate > 0) {
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

	// Request count
	let requestStat = this.createStatBlock(
		this.formatNumber(site.hitCounter || 0),
		'Requests'
	);
	stats.appendChild(requestStat);

	// Request rate (adaptive: /h, /m, or /s based on volume)
	// Use 1-minute averaged rate for stability, fall back to instant rate for recent bursts
	let rate = site.requestsPerMin1 || site.requestsPerSec || 0;
	let rateStat = this.createStatBlock(
		this.formatRate(rate),
		'Rate'
	);
	stats.appendChild(rateStat);

	// Bandwidth (total in + out)
	let totalBandwidth = (site.incoming || 0) + (site.outgoing || 0);
	let bandwidthStat = this.createStatBlock(
		this.formatBytes(totalBandwidth),
		'Traffic'
	);
	stats.appendChild(bandwidthStat);

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

/**
 * Format a rate adaptively as /h, /m, or /s based on volume
 * - Less than 1 per minute (0.017/s): show as X/h
 * - Less than 0.5 per second (30/min): show as X/m
 * - 0.5/s or higher: show as X/s
 */
HeSitesOverview.setMethod(function formatRate(perSecond) {

	if (perSecond == null || perSecond === 0) {
		return '0/h';
	}

	// If less than 1 per minute (~0.017/s), show per hour
	if (perSecond < 1/60) {
		let perHour = perSecond * 3600;
		if (perHour < 10) {
			return perHour.toFixed(1) + '/h';
		}
		return Math.round(perHour) + '/h';
	}

	// If less than 2 per second, show per minute
	if (perSecond < 2) {
		let perMinute = perSecond * 60;
		if (perMinute < 10) {
			return perMinute.toFixed(1) + '/m';
		}
		return Math.round(perMinute) + '/m';
	}

	// Otherwise show per second
	if (perSecond < 10) {
		return perSecond.toFixed(1) + '/s';
	}

	return Math.round(perSecond) + '/s';
});

/**
 * Format bytes with appropriate unit (B, KB, MB, GB, TB)
 */
HeSitesOverview.setMethod(function formatBytes(bytes) {

	if (bytes == null || bytes === 0) {
		return '0 B';
	}

	const units = ['B', 'KB', 'MB', 'GB', 'TB'];
	let unitIndex = 0;
	let value = bytes;

	while (value >= 1024 && unitIndex < units.length - 1) {
		value /= 1024;
		unitIndex++;
	}

	if (value < 10) {
		return value.toFixed(1) + ' ' + units[unitIndex];
	}

	return Math.round(value) + ' ' + units[unitIndex];
});
