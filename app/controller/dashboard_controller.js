/**
 * The Dashboard Controller class
 * Handles the live stats linkup for dashboard widgets
 *
 * @constructor
 * @extends       Alchemy.Controller.App
 *
 * @author        Jelle De Loecker   <jelle@elevenways.be>
 * @since         0.7.0
 * @version       0.7.0
 */
const Dashboard = Function.inherits('Alchemy.Controller.App', function Dashboard(conduit, options) {
	Dashboard.super.call(this, conduit, options);
});

/**
 * The live linkup action - handles WebSocket connection for real-time stats
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {Alchemy.Conduit}   conduit
 * @param    {Linkup}            linkup
 * @param    {Object}            data
 */
Dashboard.setAction(function live(conduit, linkup, data) {

	if (!alchemy.statsCollector) {
		linkup.submit('error', {message: 'Stats collector not available'});
		return linkup.destroy();
	}

	const collector = alchemy.statsCollector;

	// Send initial state with history
	let init_data = {
		state      : collector.getDashboardState(),
		history    : {
			requests  : collector.getGlobalTimeSeries('requestsPerSec', 150),
			bandwidth : collector.getGlobalTimeSeries('incomingBytesPerSec', 150),
		},
		activities : collector.activities?.toArray() || [],
	};

	linkup.submit('init', init_data);

	// Subscribe to stats updates
	const onSample = (timestamp) => {
		linkup.submit('stats', collector.getDashboardState());
	};

	collector.on('sample', onSample);

	// Subscribe to activity events
	const onActivity = (activity) => {
		linkup.submit('activity', activity);
	};

	collector.on('activity', onActivity);

	// Handle ping for latency measurement
	linkup.on('ping', (data) => {
		linkup.submit('pong', {
			client_time : data.client_time,
			server_time : Date.now()
		});
	});

	// Cleanup on disconnect
	conduit.on('disconnect', () => {
		collector.off('sample', onSample);
		collector.off('activity', onActivity);
	});
});
