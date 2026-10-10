package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * The /manage projection of an admin entry: its id under the manage_ prefix, a tenant scope, its tabs without the
 * admin history, and how a tenant finds it.
 *
 * AIDEV-NOTE: a twin is either {@link #listed} (a sidebar row or a cluster member, so it takes the nav-only
 * {@code hasInScopeRecords} probe that hides it from a tenant holding nothing there) or {@link #reached} (opened from
 * the record or toolbar that owns it, out of the sidebar, where zenit-cms never asks that probe). Setting the nav
 * presence and the probe in one call is what keeps a sidebar twin from shipping without its probe, as the access lists
 * did until DD6. The probe never gates the route: the scope does.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class ManageTwin {

    private static final String PREFIX = "manage_";

    private ManageTwin() {
    }

    /** @return the twin's id: the admin entry's (or action's) id path under the manage_ prefix */
    public static @NonNull Identifier id(@NonNull String adminPath) {
        return HohenheimIds.id(PREFIX + adminPath);
    }

    /**
     * A twin in the tenant's sidebar, shown while the tenant holds anything it lists.
     *
     * @param holdsAny the nav-only probe; cheap, and never a row count
     */
    public static <T> PanelResource.@NonNull Builder<T> listed(PanelResource.@NonNull Builder<T> twin,
                                                              @NonNull Predicate<AccessContext> holdsAny) {
        return twin.showInNav(true).hasInScopeRecords(Objects.requireNonNull(holdsAny, "holdsAny cannot be null"));
    }

    /**
     * A row twin in the tenant's sidebar: {@link #listed} over its tenant scope and tabs.
     *
     * @param holdsAny the nav-only probe; cheap, and never a row count
     */
    public static PanelResource.@NonNull Builder<Row> listed(PanelResource.@NonNull Builder<Row> twin,
                                                             @NonNull RowScope scope, @NonNull ResourceTabs<Row> tabs,
                                                             @NonNull Predicate<AccessContext> holdsAny) {
        return listed(projected(twin, scope, tabs), holdsAny);
    }

    /** A row twin reached through the page that owns it, never a sidebar row, so it takes no probe. */
    public static PanelResource.@NonNull Builder<Row> reached(PanelResource.@NonNull Builder<Row> twin,
                                                              @NonNull RowScope scope,
                                                              @NonNull ResourceTabs<Row> tabs) {
        return projected(twin, scope, tabs).showInNav(false);
    }

    /** @throws IllegalArgumentException when the tabs carry the admin history, which stays off the delegated surface */
    private static PanelResource.@NonNull Builder<Row> projected(PanelResource.@NonNull Builder<Row> twin,
                                                                 @NonNull RowScope scope,
                                                                 @NonNull ResourceTabs<Row> tabs) {
        if (tabs.history()) {
            throw new IllegalArgumentException("a /manage twin never carries the admin history tab");
        }
        return twin.scope(Objects.requireNonNull(scope, "scope cannot be null")).tabs(tabs);
    }
}
