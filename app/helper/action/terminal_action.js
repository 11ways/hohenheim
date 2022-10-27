/**
 * The Terminal action
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
const TerminalAction = Function.inherits('Alchemy.Form.Action', 'Hohenheim', 'TerminalAction');

/**
 * The PID of the process
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
TerminalAction.addConfigProperty('pid');

/**
 * The site_id of the process
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
TerminalAction.addConfigProperty('site_id');

/**
 * The icon of the action
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
TerminalAction.setProperty('icon', 'rectangle-terminal');

/**
 * Execute this action programatically
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 *
 * @param    {Event}   event   Optional event
 */
TerminalAction.setMethod(function execute(event) {

	if (!Blast.isBrowser || !this.pid || !this.site_id) {
		return;
	}

	let parent = event.target.queryUp('site-control');

	if (parent) {
		parent.openTerminal(this.pid);
	}
});