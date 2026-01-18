/**
 * Stats Overview Widget
 * Shows key metrics as stat cards
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
const StatsOverview = Function.inherits('Hohenheim.Widget', function StatsOverview() {});

// Widget metadata
StatsOverview.setCategory('monitoring');
StatsOverview.setIcon('gauge-high');
StatsOverview.setTitle('Stats Overview');

/**
 * Populate the widget
 */
StatsOverview.setMethod(function populateWidget() {

	let container = this.createElement('he-stats-overview');
	this.widget.append(container);
});
