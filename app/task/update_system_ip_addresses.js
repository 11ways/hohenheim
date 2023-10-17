/**
 * The task to update the current available IP addresses
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
const UpdateIpAddresses = Function.inherits('Alchemy.Task', 'Hohenheim.Task', 'UpdateIpAddresses');

/**
 * Force this task to run every hour
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
UpdateIpAddresses.addFallbackCronSchedule('8 * * * *');

/**
 * The function to execute
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
UpdateIpAddresses.setMethod(function executor() {
	return alchemy.dispatcher.getLocalIps();
});