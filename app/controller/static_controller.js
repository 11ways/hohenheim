/**
 * The Static Controller class
 *
 * @constructor
 * @extends       Alchemy.AppController
 *
 * @author        Jelle De Loecker   <jelle@develry.be>
 * @since         0.1.0
 * @version       0.1.0
 */
var Static = Function.inherits('Alchemy.AppController', function StaticController(conduit, options) {
	StaticController.super.call(this, conduit, options);
});

/**
 * The home action
 *
 * @author        Jelle De Loecker   <jelle@develry.be>
 * @since         0.1.0
 * @version       0.1.0
 *
 * @param   {Conduit}   conduit
 */
Static.setMethod(function home(conduit) {

	// Set information variables
	Controller.get('AlchemyInfo').setInfoVariables.call(this);

	// Set the `message` variable to be used inside the view file
	this.set('message', 'This is a standard message set in the <b>home</b> method of the <b>Static</b> controller');

	// Render a specific view
	this.render('static/home');
});
