/**
 * Update the site statistics
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {ObjectId}   siteId
 */
var updateSite = function updateSite(siteId) {

	var $this;

	if (!String(siteId).isObjectId()) {
		return;
	}

	$this = $('div[data-site-stats][data-site-id="' + siteId + '"]');
	$logs = $('div[data-site-logs][data-site-id="' + siteId + '"]');

	hawkejs.scene.helpers.Alchemy.getResource('sitestat', {id: siteId}, function(err, result) {

		var process,
		    html,
		    pid,
		    mem,
		    cpu;

		console.log('Get resource result:', err, result);

		if (err) {
			console.log('Sitestat error:', err);
			return;
		}

		html = '<table class="table table-striped">';
		html += '<tr><th>Pid</th><th>Port</th><th>Uptime</th><th>Cpu</th><th>Memory</th><th>Actions</th></tr>';

		for (pid in result.processes) {

			// Create an alias to the process
			process = result.processes[pid];

			cpu = mem = '??';

			if (typeof process.cpu !== 'undefined') {
				cpu = process.cpu + ' %';
			}

			if (typeof process.mem !== 'undefined') {
				mem = process.mem + ' MiB';
			}

			html += '<tr>';

			html += '<td>' + pid + '</td>';
			html += '<td><a href="http://' + location.hostname + ':' + process.port + '">' + process.port + '</a></td>';

			html += '<td><span class="timeago" title="';
			html +=  (new Date(process.startTime)).toISOString() + '"></span></td>'

			// Cpu
			html += '<td>' + cpu + '</td>';

			// Memory
			html += '<td>' + mem + '</td>';

			// Actions
			html += '<td>';

			html += '<button class="btn btn-danger" data-kill-pid="' + pid + '"><i class="fa fa-crosshairs"></i> Kill</button> ';

			html += '</td>';

			html += '</tr>';
		}

		html += '</table>';

		// Set the generated table as the html
		$this.html(html);

		// Kill a process
		$this.on('click', 'button[data-kill-pid]', function(e) {

			var $this = $(this);

			e.preventDefault();

			hawkejs.scene.helpers.Alchemy.getResource('sitestat-kill', {id: siteId, pid: $this.attr('data-kill-pid')}, function(data) {

				if (data.err) {
					toastr.err(data.err);
				} else {
					toastr.success('Process ' + $this.attr('data-kill-pid') + ' has been killed');
					updateSite(siteId);
				}
			});
		});
	});

	// Show available logs
	hawkejs.scene.helpers.Alchemy.getResource('sitestat-logs', {id: siteId}, function(err, result) {

		var html = '';

		if (!result) {
			result = [];
		}

		console.log('Logs:', result);

		html += '<table class="table table-striped">';
		html += '<tr><th></th><th>Created</th><th>Updated</th></tr>';

		result.forEach(function eachResult(entry) {
			html += '<tr><td><button class="btn" data-log-id="' + entry._id + '">View</button></td>';
			html += '<td>' + entry.created + '</td>';
			html += '<td>' + entry.updated + '</td>';
			html += '</tr>';
		});

		html += '</table>';
		$logs.html(html);

		$logs.on('click', 'button[data-log-id]', function(e) {

			var $this = $(this);
			e.preventDefault();

			hawkejs.scene.helpers.Alchemy.getResource('sitestat-log', {logid: $this.data('log-id')}, function(err, log) {

				var prevdate;

				$view = $('#sitelogview');

				// Clear html
				$view.html('');

				log.log.forEach(function(line) {

					var newdate = ''+(new Date(line.time)),
					    html;

					html = '<div title="' + (new Date(line.time)) + '" style="position:relative;">';

					if (newdate != prevdate) {
						html += '<span style="position:absolute;right:0;">' + (new Date(line.time)) + '</span>';
					}

					html += line.html.replace(/\n/g, '<br>\n');
					html += '</div>';

					prevdate = newdate;

					$view.append(html);
				});
			});
		});
	});
};

hawkejs.scene.on({type: 'set', template: 'chimera/fields/site_stat_edit'}, function applyField(element, variables) {

	var $elements = $(element);

	$('[data-site-stats]', $elements).each(function() {

		var $this  = $(this),
		    siteId = $this.attr('data-site-id');

		updateSite(siteId);
	});

	$('[data-start-process]', $elements).click(function(e) {

		var $this = $(this),
		    siteId;

		e.preventDefault();

		siteId = $this.data('site-id');

		hawkejs.scene.helpers.Alchemy.getResource('sitestat-start', {id: siteId}, function(err, data) {

			if (err) {
				throw err;
			}

			console.log('New process has been started');
			updateSite(siteId);
		});
	});

});