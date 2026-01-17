/**
 * The base Hohenheim Widget class
 *
 * All Hohenheim widgets inherit from this class.
 * They stay in the default 'widgets' group so they appear in the widget selector.
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
const HohenheimWidget = Function.inherits('Alchemy.Widget', 'Hohenheim.Widget', 'Widget');

/**
 * Make this an abstract class
 */
HohenheimWidget.makeAbstractClass();
