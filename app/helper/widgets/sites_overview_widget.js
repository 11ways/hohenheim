/**
 * Sites Overview Widget
 * Shows a grid of site cards with status
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
const SitesOverview = Function.inherits('Hohenheim.Widget', function SitesOverview() {});

// Widget metadata
SitesOverview.setCategory('monitoring');
SitesOverview.setIcon('server');
SitesOverview.setTitle('Sites Overview');

/**
 * Configure the schema for widget settings
 */
SitesOverview.constitute(function prepareSchema() {
	this.schema.addField('max_sites', 'Number', {
		title: 'Maximum sites to show',
		default: 12,
	});
});

/**
 * Populate the widget
 */
SitesOverview.setMethod(function populateWidget() {
	let container = this.createElement('he-sites-overview');
	container.setAttribute('max-sites', this.config.max_sites);
	this.widget.append(container);
});
