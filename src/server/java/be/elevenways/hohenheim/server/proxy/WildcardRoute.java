package be.elevenways.hohenheim.server.proxy;

import be.elevenways.zenit.server.http.HostPattern;

/** A glob hostname route with its pattern parsed once at load time. */
record WildcardRoute(HostPattern pattern, RouteEntry entry) {}
