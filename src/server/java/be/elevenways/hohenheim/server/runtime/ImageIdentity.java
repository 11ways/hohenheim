package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The workload's resolved image identity (mutable ref + immutable daemon id), shared
 * by both snapshot capability halves for the backup manifest.
 *
 * @param reference the image reference, null for a workload created from no image (installed from media)
 */
public record ImageIdentity(@Nullable String reference, @Nullable String id) {}
