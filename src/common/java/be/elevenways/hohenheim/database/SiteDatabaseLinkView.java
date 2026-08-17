package be.elevenways.hohenheim.database;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One managed database attached to a site, as the Databases tab renders it.
 *
 * {@code status} is the badge state derived from {@code DatabaseModel.STATUS}, so the tab
 * shows the same label, icon and colour the databases list does and cannot fall behind a
 * status the model gains. It is null in exactly ONE case: the link points at a database
 * row that no longer exists, which is not a status at all -- {@code missing} says so, and
 * the template renders that case on its own instead of leaking a synthetic token into the
 * status vocabulary.
 *
 * @param varNames the injected environment-variable names, already assembled
 */
@HawkeyeClass
public record SiteDatabaseLinkView(
    int id,
    @Nullable String dbName,
    @NonNull String engine,
    @Nullable EnumBadgeState status,
    boolean missing,
    boolean primary,
    @NonNull String prefix,
    @NonNull String varNames,
    @NonNull RouteTarget editTarget,
    @Nullable RouteTarget dbTarget
) {
}
