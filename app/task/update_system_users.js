/**
 * The task to update available system users
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
const UpdateSystemUsers = Function.inherits('Alchemy.Task', 'Hohenheim.Task', 'UpdateSystemUsers');

/**
 * Force this task to run every hour
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
UpdateSystemUsers.addFallbackCronSchedule('14 * * * *');

/**
 * The function to execute
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.3
 * @version  0.5.3
 */
UpdateSystemUsers.setMethod(function executor() {
	return alchemy.dispatcher.getLocalUsers();
});