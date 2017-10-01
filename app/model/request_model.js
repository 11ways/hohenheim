var fs = alchemy.use('fs');

/**
 * The Request Model
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.2
 * @version  0.1.0
 */
var Request = Function.inherits('Alchemy.AppModel', function RequestModel(conduit, options) {
	RequestModel.super.call(this, conduit, options);
});

/**
 * File to write to
 *
 * @author   Jelle De Loecker <jelle@develry.be>
 * @since    0.1.0
 * @version  0.1.0
 */
Request.setProperty(function access_log_file() {
	if (!this._access_log_file) {
		this._access_log_file = fs.createWriteStream(alchemy.settings.log_access_path);
	}

	return this._access_log_file;
});

/**
 * Constitute the class wide schema
 *
 * @author   Jelle De Loecker <jelle@develry.be>
 * @since    0.1.0
 * @version  0.1.0
 */
Request.constitute(function addFields() {

	// This belongs to a certain site
	this.belongsTo('Site');

	this.addField('host', 'String');
	this.addField('path', 'String');
	this.addField('status', 'Number');
	this.addField('request_size', 'Number');
	this.addField('response_size', 'Number');
	this.addField('referer', 'String');
	this.addField('user_agent', 'String');
	this.addField('remote_address', 'String');
	this.addField('duration', 'Number');
});

/**
 * Log the hit, either in the database or the access log
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.2.1
 *
 * @param    {Object}   data   The data to save
 */
Request.setMethod(function registerHit(data, req, res) {

	if (alchemy.settings.log_access_to_database) {
		this.save(data);
	}

	if (alchemy.settings.log_access_to_file && this.access_log_file) {

		let referer,
		    version;

		if (data.referer) {
			referer = data.referer;
		} else {
			referer = '-';
		}

		if (req.socket && req.socket.alpnProtocol) {
			version = req.socket.alpnProtocol.toUpperCase();
		} else {
			version = 'HTTP/' + req.httpVersion;
		}

		let log = data.remote_address + ' - - ['
		        + data.created.format('d/M/Y\\:H:i:s O') + '] '
		        + '"' + req.method + ' ' + data.path + ' ' + version + '" '
		        + data.status + ' ' + data.response_size + ' "' + referer + '" '
		        + JSON.stringify(data.user_agent);

		this.access_log_file.write(log + '\n');
	}
});