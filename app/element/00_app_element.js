/**
 * The App custom element.
 * Elements with only 1 word as their name will be prefixed with "al-"
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
const AppElement = Function.inherits('Alchemy.Element', 'App');

/**
 * Don't register this as a custom element,
 * but don't let child classes inherit this
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
AppElement.setStatic('is_abstract_class', true, false);