/**
 * Site Statistics & Control Chimera Field
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {FieldType}
 */
var SiteStat = Function.inherits('Alchemy.ChimeraField', function SiteStatChimeraField(fieldType, options) {

	SiteStatChimeraField.super.call(this, fieldType, options);

	this.script_file = ['hohenheim/sitestats'];
	//this.style_file = 'rome/rome';

	this.viewname = 'site_stat';
	//this.viewwrapper = 'date';
});
