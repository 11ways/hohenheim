package be.elevenways.hohenheim.server.proxy;

import java.util.Map;

/** A selected route plus the regex capture groups that produced it, if any. */
record RouteMatch(RouteEntry entry, Map<String, String> groups) {}
