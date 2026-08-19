package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.source.GitProviderKindInfo;
import be.elevenways.protoblast.common.annotation.BlastDiscoverable;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Server-side half of a git provider kind: builds the API client for a provider row.
 * Implementations are discovered at compile time and register themselves via
 * {@code typeId()} -- adding a kind ships one class, registered nowhere manually
 * (the InstanceKindHandler shape).
 */
@BlastDiscoverable(registrar = "be.elevenways.hohenheim.server.source.GitProviderKinds#register")
public interface GitProviderKind extends GitProviderKindInfo {

    /**
     * @param baseUrl the row's validated base URL (never blank when
     *        {@link #requiresBaseUrl()}), null or blank meaning the kind's public host
     */
    @NonNull GitProviderClient clientFor(@NonNull Row provider, @Nullable String baseUrl);
}
