let reputations = alchemy.getCache('ip_reputations');

/**
 * Keep track of remote-address reputations
 *
 * @constructor
 *
 * @author   Jelle De Loecker <jelle@elevenways.be>
 * @since    0.6.0
 * @version  0.7.0
 */
const Reputation = Function.inherits('Alchemy.Base', 'Hohenheim', function Reputation(remote_address) {

	// The remote address (IP) as a string
	this.remote_address = remote_address;

	// Domain name misses
	this.total_domain_name_misses = 0;

	// Domain name hits
	this.total_domain_name_hits = 0;

	// New domains created
	this.new_domains_created = 0;

	// All domains requested
	this.all_requested_domain_names = new Set();

	// Domains that were missed with timestamps (domain -> timestamp)
	this.missed_domain_times = new Map();
});

/**
 * Get a reputation for the given socket/address
 *
 * @author   Jelle De Loecker <jelle@elevenways.be>
 * @since    0.6.0
 * @version  0.6.0
 *
 * @param    {string|Socket}   input
 */
Reputation.setStatic(function get(input) {

	if (!input) {
		return;
	}

	if (typeof input != 'string') {
		input = input.remoteAddress;
	}

	if (!input) {
		return;
	}

	let reputation = reputations.get(input);

	if (!reputation) {
		reputation = new Reputation(input);
		reputations.set(input, reputation);
	}

	return reputation;
});

/**
 * Is this a negative reputation?
 *
 * @author   Jelle De Loecker <jelle@elevenways.be>
 * @since    0.6.0
 * @version  0.6.0
 */
Reputation.setMethod(function isNegative() {

	if (this.total_domain_name_misses > 25) {
		return true;
	}

	return false;
});

/**
 * Register a domain name request
 *
 * @author   Jelle De Loecker <jelle@elevenways.be>
 * @since    0.6.0
 * @version  0.6.0
 */
Reputation.setMethod(function registerDomainRequest(domain) {
	this.all_requested_domain_names.add(domain);
});

/**
 * Register a domain name miss
 *
 * @author   Jelle De Loecker <jelle@elevenways.be>
 * @since    0.6.0
 * @version  0.7.0
 *
 * @param    {string}   domain
 */
Reputation.setMethod(function registerDomainMiss(domain) {
	// Always update timestamp (refreshes the window for this domain)
	let is_new = !this.missed_domain_times.has(domain);
	this.missed_domain_times.set(domain, Date.now());

	// Only increment counters for NEW domains (keep existing behavior for isNegative())
	if (is_new) {
		this.total_domain_name_misses++;
	}
});

/**
 * Get count of unique domain misses within the time window.
 * Uses lazy cleanup - only iterates when necessary.
 *
 * @author   Jelle De Loecker <jelle@elevenways.be>
 * @since    0.7.0
 * @version  0.7.0
 *
 * @param    {number}   window_ms   Time window in milliseconds
 * @param    {number}   threshold   Only iterate if total misses >= threshold
 *
 * @return   {number}
 */
Reputation.setMethod(function getRecentMissCount(window_ms, threshold) {

	// Fast path: if total unique domains ever seen is below threshold, no iteration needed
	if (this.missed_domain_times.size < threshold) {
		return this.missed_domain_times.size;
	}

	// Only iterate when we MIGHT exceed threshold
	let now = Date.now();
	let count = 0;
	let to_delete = [];

	for (let [domain, time] of this.missed_domain_times) {
		if (now - time < window_ms) {
			count++;
		} else {
			to_delete.push(domain); // Mark for cleanup
		}
	}

	// Clean old entries while we're here
	for (let domain of to_delete) {
		this.missed_domain_times.delete(domain);
	}

	return count;
});

/**
 * Register a domain name hit
 *
 * @author   Jelle De Loecker <jelle@elevenways.be>
 * @since    0.6.0
 * @version  0.6.0
 */
Reputation.setMethod(function registerDomainHit() {
	this.total_domain_name_hits++;

	if (this.total_domain_name_hits % 10 == 0 && this.total_domain_name_misses > 2) {
		this.total_domain_name_misses--;
	}
});