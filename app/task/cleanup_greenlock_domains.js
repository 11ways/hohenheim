const libpath = require('path');
const fs = require('fs').promises;

/**
 * The task to clean up stale Greenlock domains
 *
 * @constructor
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
const CleanupGreenlockDomains = Function.inherits('Alchemy.Task', 'Hohenheim.Task', 'CleanupGreenlockDomains');

/**
 * Run every 6 hours (at minute 23 to avoid busy times)
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
CleanupGreenlockDomains.addFallbackCronSchedule('23 */6 * * *');

/**
 * The function to execute
 *
 * @author   Jelle De Loecker   <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 */
CleanupGreenlockDomains.setMethod(async function executor() {

	const dispatcher = alchemy.dispatcher;

	if (!dispatcher) {
		this.log('No dispatcher available, skipping');
		return;
	}

	if (!dispatcher.greenlock || !LETSENCRYPT_ENABLED) {
		this.log('Letsencrypt/Greenlock is not enabled, skipping');
		return;
	}

	this.todo = 0;
	this.done = 0;

	// Read the Greenlock config file
	const configPath = libpath.resolve(PATH_TEMP, 'greenlock.d', 'config.json');

	let configData;

	try {
		const configContent = await fs.readFile(configPath, 'utf8');
		configData = JSON.parse(configContent);
	} catch (err) {
		this.log('Could not read Greenlock config:', err.message);
		return;
	}

	if (!configData.sites || !Array.isArray(configData.sites)) {
		this.log('No sites found in Greenlock config');
		return;
	}

	this.log('Found', configData.sites.length, 'sites in Greenlock config');

	// Get all currently valid domains from the dispatcher
	const validDomains = new Set();

	for (let domain in dispatcher.domains) {
		// Skip wildcards and regex patterns
		if (!domain.includes('*') && !domain.includes('(')) {
			validDomains.add(domain);
		}
	}

	this.log('Found', validDomains.size, 'valid domains in dispatcher');

	// Find Greenlock sites that are no longer in our config
	const sitesToRemove = [];

	for (let site of configData.sites) {
		if (!site.subject) {
			continue;
		}

		// Skip if already marked as deleted
		if (site.deletedAt) {
			continue;
		}

		// Skip wildcards
		if (site.subject.includes('*')) {
			continue;
		}

		// Check if this domain is still valid
		if (!validDomains.has(site.subject) && !dispatcher.getSite(site.subject)) {
			sitesToRemove.push(site.subject);
		}
	}

	if (sitesToRemove.length === 0) {
		this.log('No stale domains found');
		this.report(1);
		return;
	}

	this.todo = sitesToRemove.length;
	this.log('Found', this.todo, 'stale domains to remove:', sitesToRemove.join(', '));

	// Remove each stale domain
	for (let domain of sitesToRemove) {
		this.report(this.done / this.todo);

		// Check if task was stopped
		if (this.has_stopped) {
			this.log('Task stopped, aborting cleanup');
			return;
		}

		try {
			await dispatcher.removeFromGreenlock(domain);
			this.log('Removed domain:', domain);
		} catch (err) {
			this.log('Failed to remove domain', domain + ':', err.message);
		}

		this.done++;
	}

	this.report(1);
	this.log('Cleanup complete, removed', this.done, 'domains');
});
