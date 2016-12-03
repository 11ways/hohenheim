return;

/**
 * Sita statistics & control field
 *
 * @author   Jelle De Loecker   <jelle@codedor.be>
 * @since    0.0.1
 * @version  0.0.1
 */
alchemy.create('ModelEditorField', function SiteStatMEF() {

	this.input = function input(callback) {

		var that = this;
		this.fieldView = 'site_stat';
		
		callback();
	};

	/**
	 * Modify the return value before saving
	 *
	 * @author   Jelle De Loecker   <jelle@codedor.be>
	 * @since    0.0.1
	 * @version  0.0.1
	 */
	this.save = function save(callback) {

		this.value = undefined;
		callback();
	};

});