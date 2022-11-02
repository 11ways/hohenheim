let open_terminal,
    open_link;

/**
 * The Site-Control element
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
const SiteControl = Function.inherits('Alchemy.Element.App', 'SiteControl');

/**
 * The template to use for the content of this element
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
SiteControl.setTemplateFile('elements/site_control');

/**
 * The stylesheet to load for this element
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
SiteControl.setStylesheetFile('site_stat');

/**
 * The ID of the site record
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
SiteControl.setAttribute('site-id');

/**
 * The info table
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
SiteControl.addElementGetter('control_table', 'al-table');

/**
 * Load specific data for other elements
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 *
 * @param    {Object}       config
 * @param    {HTMLElement}  element
 */
SiteControl.setMethod(function loadData(config, element) {

	if (!this.site_id) {
		return;
	}

	const pledge = new Pledge();

	this.getResource({name: 'sitestat', cache: false}, {site_id: this.site_id}, (err, res) => {

		if (err) {
			return pledge.reject(err);
		}

		let entry,
		    rows = [],
		    pid;

		for (pid in res.processes) {
			entry = res.processes[pid];
			entry.id = pid;
			entry.pid = pid;
			entry.start_time = Date.create(entry.start_time);

			let kill = new Classes.Alchemy.Form.Action.Url({
				name : 'kill',
				icon : 'skull-crossbones',
				placement : ['row', 'context'],
				url       : alchemy.routeUrl('sitestat-kill', {
					id  : this.site_id,
					pid : pid,
				}),
			});

			let isolate = new Classes.Alchemy.Form.Action.Url({
				name : 'isolate',
				icon : 'snowflake',
				placement : ['row', 'context'],
				url       : alchemy.routeUrl('sitestat-isolate', {
					id  : this.site_id,
					pid : pid,
				}),
			});

			let terminal = new Classes.Hohenheim.TerminalAction({
				site_id : this.site_id,
				pid     : pid,
				placement : ['row', 'context'],
			});

			entry.$hold = {
				actions : [kill, isolate, terminal],
			};

			rows.push(entry);
		}

		pledge.resolve(rows);
	});

	return pledge;
});

/**
 * Queue a refresh
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 *
 * @param    {Number}   wait_in_ms
 */
SiteControl.setMethod(function queueRefresh(wait_in_ms) {

	if (wait_in_ms == null) {
		wait_in_ms = 500;
	}

	if (this._queued_refresh) {
		clearTimeout(this._queued_refresh);
	}

	this._queued_refresh = setTimeout(() => {
		this._queued_refresh = null;
		this.refresh();
	}, wait_in_ms);
});

/**
 * Refresh the info
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
SiteControl.setMethod(function refresh() {
	let table = this.control_table;

	if (table) {
		table.loadRemoteData();
	}
});

/**
 * Close the open terminal
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
SiteControl.setMethod(function closeTerminal() {

	if (!open_terminal) {
		return;
	}

	open_terminal.dispose();
	open_terminal = null;

	open_link.destroy();
	open_link = null;

	let terminal_element = this.querySelector('.xterminal');

	if (terminal_element) {
		terminal_element.innerHTML = '';
	}
});

/**
 * Open a specific terminal
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 *
 * @param    {Number}   pid
 */
SiteControl.setMethod(function openTerminal(pid) {

	this.closeTerminal();

	if (!pid) {
		return;
	}

	let term = new Terminal();
	open_terminal = term;

	const fit_addon = new FitAddon.FitAddon();
	term.loadAddon(fit_addon);

	// Default size
	term.cols = 80
	term.rows = 24
	term.normalMouse = true;
	term.mouseEvents = true;

	let terminal_wrapper = this.querySelector('.xterminal');
	terminal_wrapper.innerHTML = '';

	let terminal_element = document.createElement('div');
	terminal_wrapper.append(terminal_element);

	term.open(terminal_element);

	let data = {
		pid     : pid,
		width   : term.cols,
		height  : term.rows,
		site_id : this.site_id,
	};

	const link = alchemy.linkup('terminallink', data, function ready() {

		var input_stream = link.createStream();

		link.submit('input_stream', {}, input_stream);

		term.onData(d => {
			input_stream.write(d);
		});

		term.onBinary(b => {
			input_stream.write(b);
		});
	});

	open_link = link;

	link.on('resize', function onResize(data) {
		//term.renderer.clear();
		term.resize(data.cols, data.rows);
	});

	link.on('output_stream', function gotOutput(data, stream) {
		stream.on('data', function onData(d) {
			term.write(''+d);
		});
	});

	term.onResize(Function.throttle(function resized() {
		link.submit('resize');
	}, 100, false, true));

	setTimeout(function getProposedSize() {
		let geo = fit_addon.proposeDimensions();

		//term.renderer.clear();

		term.resize(geo.cols, geo.rows);
		link.submit('propose_geometry', geo);
	}, 50);
});

/**
 * The element has been removed from the dom
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
SiteControl.setMethod(function disconnected() {
	this.closeTerminal();
});

/**
 * The element has been added to the dom for the first time
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.5.0
 * @version  0.5.0
 */
SiteControl.setMethod(function introduced() {

	const that = this;

	let start_button = this.querySelector('.js-start-process');

	start_button.addEventListener('activate', function onActivation(e) {

		if (!that.site_id) {
			start_button.setState('invalid-site-id', 2500, 'idle');
			return;
		}

		hawkejs.scene.helpers.Alchemy.getResource('sitestat-start', {id: that.site_id, cache: false}, function(err, data) {

			if (err) {
				start_button.setState('start-error');
				console.error(err);
				return;
			}

			that.queueRefresh(1000);
		});

		start_button.setState('busy', 2000, 'idle');
	});

	let table = this.control_table;

	table.addEventListener('click', e => {

		// Ignore clicks outside of the actions column
		if (!e.target.queryUp('.aft-actions')) {
			return;
		}

		that.queueRefresh(1000);
	});

	table.fieldset = [
		{
			name : 'pid',
			options : {
				type : 'number',
				purpose : 'view',
			}
		},
		{
			name : 'port',
			options : {
				type : 'number',
				purpose : 'view',
			}
		},
		{
			name : 'fingerprints',
			options : {
				type : 'number',
				purpose : 'view',
				suffix  : 'clients',
			}
		},
		{
			name : 'start_time',
			options : {
				type     : 'datetime',
				purpose  : 'view',
				title    : 'Uptime',
				time_ago : true,
			}
		},
		{
			name : 'cpu',
			options : {
				type : 'number',
				purpose : 'view',
				suffix  : '%',
			}
		},
		{
			name : 'memory',
			options : {
				type : 'number',
				purpose : 'view',
				suffix  : 'MiB',
			}
		},
		{
			name : 'status',
			options : {
				type : 'string',
				purpose : 'view',
			}
		},
	];

	this.queueRefresh(500);

	this.addIntervalListener(() => {

		if (!this.isVisible()) {
			return;
		}

		this.queueRefresh();
	}, 15000);
});