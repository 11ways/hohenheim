/**
 * Update the site statistics
 *
 * @author   Jelle De Loecker   <jelle@develry.be>
 * @since    0.0.1
 * @version  0.1.0
 *
 * @param    {ObjectId}   siteId
 */
var updateSite = function updateSite(siteId, update_logs) {

	var $this,
	    open_term;

	if (!String(siteId).isObjectId()) {
		return;
	}

	if (update_logs == null) {
		update_logs = true;
	}

	$this = $('div[data-site-stats][data-site-id="' + siteId + '"]');
	$logs = $('div[data-site-logs][data-site-id="' + siteId + '"]');

	hawkejs.scene.helpers.Alchemy.getResource('sitestat', {id: siteId}, function gotSitestats(err, result) {

		var process,
		    html,
		    pid,
		    mem,
		    cpu;

		if (err) {
			console.log('Sitestat error:', err);
			return;
		}

		html = '<table class="table table-striped">';
		html += '<tr><th>Pid</th><th>Port</th><th>Fingerprints</th><th>Uptime</th><th>Cpu</th><th>Memory</th><th>Actions</th></tr>';

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
			html += '<td>';

			if (process.port) {
				html += '<a href="http://' + location.hostname + ':' + process.port + '">' + process.port + '</a><br>';
			}

			if (process.isolate) {
				html += 'ISOLATED';
			}

			html += '</td>';

			html += '<td>' + (process.fingerprints || 0) + '</td>';

			html += '<td><span class="timeago" datetime="';
			html +=  (new Date(process.startTime)).format('Y-m-d H:i:s') + '"></span></td>'

			// Cpu
			html += '<td>' + cpu + '</td>';

			// Memory
			html += '<td>' + mem + '</td>';

			// Actions
			html += '<td>';

			html += '<button class="btn btn-danger" data-kill-pid="' + pid + '"><i class="fa fa-crosshairs"></i> Kill</button> ';

			if (!process.isolate) {
				html += '<button class="btn btn-warning" data-isolate-pid="' + pid + '"><i class="fa fa-crosshairs"></i> Isolate</button> ';
			}

			html += '<button class="btn btn-success" data-term-pid="' + pid + '"><i class="fa fa-terminal"></i> Terminal</button> ';

			html += '</td>';

			html += '</tr>';
		}

		html += '</table>';

		// Set the generated table as the html
		$this.html(html);

		// Show the uptime
		hawkejs.require(['timeago', 'timeago.locales'], function gotTimeago() {
			var timeagoInstance = timeago(),
			    nodes = $this.find('.timeago').get();

			timeagoInstance.render(nodes, hawkejs.scene.exposed.active_prefix || 'en');
		});

		// Kill a process
		$this.on('click', 'button[data-kill-pid]', function(e) {

			var $this = $(this);

			e.preventDefault();

			hawkejs.scene.helpers.Alchemy.getResource('sitestat-kill', {id: siteId, pid: $this.attr('data-kill-pid')}, function killed(err, data) {

				if (data.err) {
					chimeraFlash(data.err);
				} else {
					chimeraFlash('Process ' + $this.attr('data-kill-pid') + ' has been killed');
					updateSite(siteId);
				}
			});
		});

		// Isolate a process
		$this.on('click', 'button[data-isolate-pid]', function(e) {

			var $this = $(this);

			e.preventDefault();

			hawkejs.scene.helpers.Alchemy.getResource('sitestat-isolate', {id: siteId, pid: $this.attr('data-isolate-pid')}, function isolated(err, data) {

				if (data.err) {
					chimeraFlash(data.err);
				} else {
					chimeraFlash('Process ' + $this.attr('data-isolate-pid') + ' has been isolated');
					updateSite(siteId);
				}
			});
		});

		// Show a terminal
		$this.on('click', 'button[data-term-pid]', function onShowTerm(e) {

			var $this = $(this),
			    data,
			    term;

			e.preventDefault();

			if (open_term) {
				open_term.destroy();
			}

			// Create a new terminal
			term = new Terminal();
			open_term = term;

			// Default size
			term.cols = 80
			term.rows = 24
			term.normalMouse = true;
			term.mouseEvents = true;

			term.open(document.getElementById('terminal'));

			data = {
				pid     : $this.attr('data-term-pid'),
				width   : term.cols,
				height  : term.rows,
				site_id : siteId
			};

			var link = alchemy.linkup('terminallink', data, function ready() {

				var input_stream = link.createStream();

				link.submit('input_stream', {}, input_stream);

				term.on('data', function onData(d) {
					input_stream.write(d);
				});
			});

			link.on('resize', function onResize(data) {
				term.renderer.clear();
				term.resize(data.cols, data.rows);
			});

			link.on('output_stream', function gotOutput(data, stream) {
				stream.on('data', function onData(d) {
					term.write(''+d);
				});
			});

			term.on('resize', function resized() {
				link.submit('redraw');
			});

			setTimeout(function getProposedSize() {
				var geo = term.proposeGeometry();
				term.renderer.clear();
				term.resize(geo.cols, geo.rows);
				link.submit('propose_geometry', geo);
			}, 50);
		});

		setTimeout(function refresh() {
			if ($this.is(':visible')) {
				updateSite(siteId, false);
			} else {
				// @TODO; add a check to see if the element has actually been REMOVED
				setTimeout(refresh, 30 * 1000);
			}
		}, 15 * 1000)
	});

	if (!update_logs) {
		return;
	}

	// Show available logs
	hawkejs.scene.helpers.Alchemy.getResource('sitestat-logs', {id: siteId}, function(err, result) {

		var html = '';

		if (err) {
			console.error('Err:', err);
			return;
		}

		if (!result) {
			result = [];
		}

		html += '<table class="table table-striped">';
		html += '<tr><th></th><th>Created</th><th>Updated</th></tr>';

		result.forEach(function eachResult(entry) {

			if (!entry) {
				return;
			}

			html += '<tr><td><button class="btn" data-log-id="' + entry._id + '">View</button></td>';
			html += '<td>' + entry.created.format('D d M Y H:i:s') + '</td>';
			html += '<td>' + entry.updated.format('D d M Y H:i:s') + '</td>';
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

					html = '<div title="' + Date.create(line.time).format('D d M Y H:i:s') + '" style="position:relative;">';

					if (newdate != prevdate) {
						html += '<span style="position:absolute;right:0;" class="terminal-timestamp">' + Date.create(line.time).format('D d M Y H:i:s') + '</span>';
					}

					html += line.html;
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

			updateSite(siteId);
		});
	});

});