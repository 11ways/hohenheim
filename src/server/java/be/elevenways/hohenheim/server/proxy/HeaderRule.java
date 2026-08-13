package be.elevenways.hohenheim.server.proxy;

/** One configured request- or response-header rule; a blank value means "remove". */
record HeaderRule(String name, String value) {}
