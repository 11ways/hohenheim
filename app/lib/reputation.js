let reputations = alchemy.getCache('ip_reputations');

/**
 * Keep track of remote-address reputations
 *
 * @constructor
 *
 * @author   Jelle De Loecker <jelle@elevenways.be>
 * @since    0.6.0
 * @version  0.6.0
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
 * @version  0.6.0
 */
Reputation.setMethod(function registerDomainMiss() {
	this.total_domain_name_misses++;
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