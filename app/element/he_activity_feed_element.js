/**
 * The HeActivityFeed element
 * Displays a list of recent activities
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
const HeActivityFeed = Function.inherits('Alchemy.Element.App', 'HeActivityFeed');

HeActivityFeed.setStylesheetFile('he_dashboard');

HeActivityFeed.setAttribute('max-items', {type: 'number', default: 20});

HeActivityFeed.setAssignedProperty('activities', null, function onActivitiesChanged(activities) {
	this.renderActivities();
	return activities;
});

/**
 * Build the initial HTML structure when connected to DOM
 */
HeActivityFeed.setMethod(function connected() {

	if (this._built) {
		return;
	}

	this._built = true;

	let section = this.createElement('section');
	section.className = 'dashboard-widget';
	section.setAttribute('aria-labelledby', 'activity-header');

	let header = this.createElement('h3');
	header.id = 'activity-header';
	header.className = 'widget-header';
	header.textContent = 'Recent Activity';
	section.appendChild(header);

	let loading = this.createElement('div');
	loading.className = 'widget-loading';
	loading.textContent = 'Loading...';
	section.appendChild(loading);

	let container = this.createElement('div');
	container.className = 'activity-feed';

	let list = this.createElement('div');
	list.className = 'activity-list';
	container.appendChild(list);

	section.appendChild(container);
	this.appendChild(section);
});

/**
 * Subscribe to data provider when added to DOM
 */
HeActivityFeed.setMethod(function introduced() {

	if (!Blast.isBrowser) {
		return;
	}

	this.provider = Classes.Develry.Client.DashboardDataProvider.getInstance();
	this.provider.subscribe();

	this._onActivitiesUpdate = (activities) => {
		let max = parseInt(this.getAttribute('max-items')) || 20;
		this.activities = activities.slice(0, max);
	};

	this.provider.on('activities_update', this._onActivitiesUpdate);

	if (this.provider.activities) {
		this._onActivitiesUpdate(this.provider.activities);
	}
});

/**
 * Cleanup when removed from DOM
 */
HeActivityFeed.setMethod(function removed() {

	if (this.provider) {
		this.provider.removeListener('activities_update', this._onActivitiesUpdate);
		this.provider.unsubscribe();
		this.provider = null;
	}
});

HeActivityFeed.addElementGetter('list_container', '.activity-list');

/**
 * Render the activities list
 */
HeActivityFeed.setMethod(function renderActivities() {

	let container = this.list_container;

	if (!container) {
		return;
	}

	let loading = this.querySelector('.widget-loading');
	if (loading) {
		loading.hidden = true;
	}

	let activities = this.activities;

	Hawkejs.removeChildren(container);

	if (!activities || activities.length === 0) {
		let empty = document.createElement('div');
		empty.className = 'activity-empty';
		empty.textContent = 'No recent activity';
		container.appendChild(empty);
		return;
	}

	for (let activity of activities) {
		let item = this.createActivityItem(activity);
		container.appendChild(item);
	}
});

/**
 * Create a single activity item element
 */
HeActivityFeed.setMethod(function createActivityItem(activity) {

	let type_class = this.getTypeClass(activity.type);
	let icon_name = this.getTypeIcon(activity.type);

	let item = document.createElement('div');
	item.className = 'activity-item ' + type_class;

	let iconDiv = document.createElement('div');
	iconDiv.className = 'activity-icon';
	let icon = document.createElement('al-icon');
	icon.setAttribute('icon-name', icon_name);
	iconDiv.appendChild(icon);
	item.appendChild(iconDiv);

	let content = document.createElement('div');
	content.className = 'activity-content';

	let message = document.createElement('div');
	message.className = 'activity-message';
	message.textContent = activity.message || '';
	content.appendChild(message);

	if (activity.site_name) {
		let site = document.createElement('div');
		site.className = 'activity-site';
		site.textContent = activity.site_name;
		content.appendChild(site);
	}

	item.appendChild(content);

	let time = document.createElement('al-time-ago');
	time.className = 'activity-time';
	if (activity.timestamp) {
		time.date = activity.timestamp;
	}
	item.appendChild(time);

	return item;
});

/**
 * Get the CSS class for an activity type
 */
HeActivityFeed.setMethod(function getTypeClass(type) {

	switch (type) {
		case 'error':
		case 'crash':
		case 'failed':
			return 'activity-error';

		case 'warning':
		case 'degraded':
			return 'activity-warning';

		case 'success':
		case 'started':
		case 'recovered':
			return 'activity-success';

		case 'info':
		default:
			return 'activity-info';
	}
});

/**
 * Get the icon name for an activity type
 */
HeActivityFeed.setMethod(function getTypeIcon(type) {

	switch (type) {
		case 'error':
		case 'crash':
		case 'failed':
			return 'circle-exclamation';

		case 'warning':
		case 'degraded':
			return 'triangle-exclamation';

		case 'success':
		case 'started':
			return 'circle-check';

		case 'recovered':
			return 'heart-pulse';

		case 'stopped':
			return 'circle-stop';

		case 'request':
			return 'arrow-right-arrow-left';

		case 'info':
		default:
			return 'circle-info';
	}
});
