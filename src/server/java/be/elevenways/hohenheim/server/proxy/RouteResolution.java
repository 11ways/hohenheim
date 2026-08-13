package be.elevenways.hohenheim.server.proxy;

import org.checkerframework.checker.nullness.qual.Nullable;

/** hostnameKnown distinguishes wrong-path 404s from true domain misses. */
record RouteResolution(@Nullable RouteMatch match, boolean hostnameKnown) {}
