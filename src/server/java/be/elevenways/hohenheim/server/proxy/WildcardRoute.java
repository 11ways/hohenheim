package be.elevenways.hohenheim.server.proxy;

import java.util.regex.Pattern;

/** A glob hostname route with its pattern compiled once at load time. */
record WildcardRoute(Pattern pattern, RouteEntry entry) {}
