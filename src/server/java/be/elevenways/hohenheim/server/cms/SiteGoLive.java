package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * How a site is about to enter the route table, which decides the wording of every refusal that stops it.
 *
 * AIDEV-NOTE: a restore out of the Trash is judged exactly like an enable (SiteEnableInvariant), but an operator
 * who clicked Restore must not be told "Cannot enable this site". Each member carries its own refusal keys so
 * the enable seam never spells them itself.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
enum SiteGoLive {

    ENABLE("enable_route_conflict", "enable_route_overlap", "enable_hostname_unavailable",
        "enable_route_quarantined"),
    RESTORE("restore_route_conflict", "restore_route_overlap", "restore_hostname_unavailable",
        "restore_route_quarantined");

    private final String routeConflictKey;
    private final String routeOverlapKey;
    private final String hostnameUnavailableKey;
    private final String routeQuarantinedKey;

    SiteGoLive(@NonNull String routeConflictKey, @NonNull String routeOverlapKey,
               @NonNull String hostnameUnavailableKey, @NonNull String routeQuarantinedKey) {
        this.routeConflictKey = routeConflictKey;
        this.routeOverlapKey = routeOverlapKey;
        this.hostnameUnavailableKey = hostnameUnavailableKey;
        this.routeQuarantinedKey = routeQuarantinedKey;
    }

    /** A trashed stored site goes live by being restored, any other by being enabled. */
    static @NonNull SiteGoLive of(@NonNull Row stored) {
        return stored.get(SiteModel.DELETED_AT) != null ? RESTORE : ENABLE;
    }

    /** The refusal naming the site that already routes the identical hostname. */
    @NonNull String routeConflictKey() {
        return this.routeConflictKey;
    }

    /** The refusal naming the site whose pattern overlaps the hostname. */
    @NonNull String routeOverlapKey() {
        return this.routeOverlapKey;
    }

    /** The neutral refusal for a reader who may not learn which site holds the route. */
    @NonNull String hostnameUnavailableKey() {
        return this.hostnameUnavailableKey;
    }

    /** The refusal for a hostname still in its release quarantine. */
    @NonNull String routeQuarantinedKey() {
        return this.routeQuarantinedKey;
    }
}
