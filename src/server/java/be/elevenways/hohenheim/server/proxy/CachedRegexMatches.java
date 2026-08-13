package be.elevenways.hohenheim.server.proxy;

import java.util.List;

/** All regex routes whose pattern matched this hostname, with their capture groups. */
record CachedRegexMatches(List<RouteMatch> matches, long cachedAt) {}
