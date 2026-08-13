package be.elevenways.hohenheim.server.proxy;

import java.util.List;
import java.util.regex.Pattern;

/** A regex hostname route, keeping its source so the dot-count ceiling can read it. */
record RegexRoute(String hostnamePattern, Pattern pattern, List<String> namedGroups,
                  RouteEntry entry) {}
