/**
 * The task to update node.js versions
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
const UpdateNodeVersions = Function.inherits('Alchemy.Task', 'Hohenheim.Task', 'UpdateNodeVersions');

/**
 * Force this task to run every hour
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
UpdateNodeVersions.addFallbackCronSchedule('11 * * * *');

/**
 * The function to execute
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
UpdateNodeVersions.setMethod(function executor() {
	return Classes.Develry.NodeSite.updateVersions();
});