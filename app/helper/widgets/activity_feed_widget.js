/**
 * Activity Feed Widget
 * Shows recent activity events
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
const ActivityFeed = Function.inherits('Hohenheim.Widget', function ActivityFeed() {});

// Widget metadata
ActivityFeed.setCategory('monitoring');
ActivityFeed.setIcon('list-timeline');
ActivityFeed.setTitle('Activity Feed');

/**
 * Configure the schema
 */
ActivityFeed.constitute(function prepareSchema() {
	this.schema.addField('max_items', 'Number', {
		title: 'Maximum items',
		default: 20,
	});
});

/**
 * Populate the widget
 */
ActivityFeed.setMethod(function populateWidget() {
	let container = this.createElement('he-activity-feed');
	container.setAttribute('max-items', this.config.max_items || 20);
	this.widget.append(container);
});
