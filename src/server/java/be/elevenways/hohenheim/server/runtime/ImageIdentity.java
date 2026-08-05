package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The workload's resolved image identity (mutable ref + immutable daemon id), shared
 * by both snapshot capability halves for the backup manifest.
 */
public record ImageIdentity(@NonNull String reference, @Nullable String id) {}
