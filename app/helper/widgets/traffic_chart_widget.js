/**
 * Traffic Chart Widget
 * Shows requests/bandwidth over time
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
const TrafficChart = Function.inherits('Hohenheim.Widget', function TrafficChart() {});

/**
 * Set the title
 */
TrafficChart.setProperty('title', 'Traffic Chart');

/**
 * Configure the schema
 */
TrafficChart.constitute(function prepareSchema() {
	this.schema.addField('metric', 'Enum', {
		title: 'Metric',
		values: {requests: 'Requests/sec', bandwidth: 'Bandwidth'},
		default: 'requests',
	});
	
	this.schema.addField('height', 'Number', {
		title: 'Chart height (px)',
		default: 200,
	});
});

/**
 * Populate the widget
 */
TrafficChart.setMethod(function populateWidget() {
	let container = this.createElement('he-traffic-chart');
	container.setAttribute('metric', this.config.metric || 'requests');
	container.setAttribute('height', this.config.height || 200);
	this.widget.append(container);
});
