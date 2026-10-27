package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What the detail row under one deploy shows (the shared deploy-detail partial): its failure in words, its captured
 * log and, for a release, the output of the build that made its image.
 *
 * AIDEV-NOTE: the CALLER decides which text a reader of its panel may see (a delegated panel gets a tenant-safe
 * reason, never the daemon's own text); an instance's Deploys tab and a stack's Deployments tab both build one.
 *
 * @param error    the failure as the reader may read it, "" for none
 * @param log      the deploy's own log, "" for none
 * @param buildLog the log of the build behind a release's image, "" for none
 * @author Jelle De Loecker
 * @since 0.1.0
 */
@HawkeyeClass
public record DeployDetail(@NonNull String error, @NonNull String log, boolean hasLog, @NonNull String buildLog,
                           boolean hasBuildLog) {

    /** @return the detail of these texts, a null or blank one counting as none */
    public static @NonNull DeployDetail of(@Nullable String error, @Nullable String log, @Nullable String buildLog) {
        return new DeployDetail(error == null ? "" : error, log == null ? "" : log, log != null && !log.isBlank(),
            buildLog == null ? "" : buildLog, buildLog != null && !buildLog.isBlank());
    }
}
