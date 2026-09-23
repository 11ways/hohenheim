package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.RouteClaims;
import be.elevenways.hohenheim.server.proxy.RouteClaims.ClaimConflict;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE site enable invariant, installed on the SiteModel write pipeline: no disabled site goes live on a
 * route an enabled site already owns, on any writer.
 *
 * AIDEV-NOTE: moved out of SiteResource (review finding, 2026-09): a write-pipeline hook is a MODEL
 * concern, and living inside a CMS resource made it look like one resource's check. It stays in this
 * package only because the refusal wording it shares with the domain route invariant
 * ({@code ClaimRefusals}, {@code SiteDomainResource.refuseEnableRouteConflicts}) is package-private
 * here. Installed by HohenheimWriteHooks at the MODULES boot stage, exactly where SiteResource's
 * installer used to be called from.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class SiteEnableInvariant {

    private static volatile boolean installed;

    private SiteEnableInvariant() {
    }

    /**
     * Install THE enable invariant on the SiteModel write pipeline so every writer
     * of a live transition passes through exactly one check: the admin form, the
     * toggle action, the delegated /manage save, the generic revision-restore
     * endpoint, seeds, and any future writer.
     *
     * AIDEV-NOTE: this MUST live in the write pipeline, never in the resource layer.
     * The framework's RESTORE_REVISION endpoint is registered for EVERY revisionable
     * RowResource -- callable even when the resource hides its revision subpage --
     * and it restores a snapshot via RevisionableBehaviour.restore -> model.save
     * DIRECTLY, running no resource-layer hook. A delegated tenant could restore a
     * formerly-enabled revision after another site took the hostname and silently
     * seize the route (SiteDispatcher resolves first-wins). A before-write hook is
     * the one seam every save funnels through. Do NOT move this back into updateRow
     * / toggleAction as a per-path check -- that is the very bypass this closes.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        // The scan: produces the specific, localized refusal an operator can act on. It
        // runs inside the one write transaction SiteModel.save declares, so on the
        // serialized SQLite engine it cannot go stale and is the authoritative refusal
        // for overlapping listener sets (see RouteClaims); the claim stamp below feeds
        // the unique index that backstops identical keys.
        //
        // AIDEV-NOTE: beforeVALIDATE, not beforeWrite. Both tiers run inside the same
        // Schema.beforeWrite pass on EVERY save path (there is no way to reach the
        // datasource past one but not the other), so the bypass argument above is
        // unchanged; the split exists so the diagnosis runs before the row is judged and
        // the authoritative claim runs last, immediately before the datasource write.
        SiteModel.SCHEMA.addBeforeValidateHook(context -> {
            Row stored = storedSiteOf(context.getRow());
            if (stored != null && willBeLive(context.getRow(), stored) && !RouteClaims.isLive(stored)) {
                refuseConflictingEnable(stored);
            }
        });
        // The AUTHORITATIVE claim: rewrites this site's live_route_key column, whose UNIQUE
        // index is the only thing that can refuse a route to the loser of a simultaneous
        // enable. Runs on every site write, not just a transition, so a route edited while
        // the site is live re-claims under its new key.
        SiteModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            Row stored = storedSiteOf(row);
            if (stored == null) {
                return;
            }
            try {
                RouteClaims.restamp(stored.get(SiteModel.ID), willBeLive(row, stored));
            } catch (ClaimConflict conflict) {
                throw refusalFor(conflict);
            }
        });
    }

    /**
     * The stored site a write targets, or null for a create -- a site with no id has no
     * domain rows yet and therefore claims nothing.
     */
    private static @Nullable Row storedSiteOf(@Nullable Row row) {
        if (row == null || !row.has(SiteModel.ID.getName()) || row.get(SiteModel.ID) == null) {
            return null;
        }
        return Models.get(SiteModel.class).findById(row.get(SiteModel.ID));
    }

    /** Whether the site will route traffic AFTER this write, reading through partial rows. */
    private static boolean willBeLive(@NonNull Row row, @NonNull Row stored) {
        Object enabled = row.has(SiteModel.ENABLED.getName())
            ? row.get(SiteModel.ENABLED) : stored.get(SiteModel.ENABLED);
        Object deletedAt = row.has(SiteModel.DELETED_AT.getName())
            ? row.get(SiteModel.DELETED_AT) : stored.get(SiteModel.DELETED_AT);
        return Boolean.TRUE.equals(enabled) && deletedAt == null;
    }

    /**
     * Translate the unique-index refusal into the SAME violation the advisory scan
     * produces, so a tenant who lost the race is told what happened instead of seeing a
     * driver error -- the whole point of the constraint is that the loser is TOLD.
     */
    private static @NonNull Violations refusalFor(@NonNull ClaimConflict conflict) {
        Row holder = RouteClaims.holderSiteOf(conflict.getKey());
        Integer holderId = holder != null ? holder.get(SiteModel.ID) : null;
        String hostname = RouteClaims.hostnameOf(conflict.getKey());
        return Violations.ofField("enabled", true,
            ClaimRefusals.heldBy(holderId, holder,
                site -> CmsSupport.violationText("enable_route_conflict")
                    .withArg("hostname", hostname)
                    .withArg("site", holder != null ? site : "?"),
                CmsSupport.violationText(ClaimRefusals.ENABLE_HOSTNAME_UNAVAILABLE)
                    .withArg("hostname", hostname)));
    }

    /**
     * THE enable check, invoked only from the write-pipeline hook above.
     *
     * AIDEV-NOTE: enabling puts a site's domain rows into the global route table, and
     * sites that do not route are EXEMPT from the cross-site route-identity check -- so a
     * site staged on someone else's hostname seizes it the moment it goes live. The hook
     * calls this only for a routeless->live transition, so an already-live re-save never
     * self-conflicts. Routeless is RouteClaims.isLive, not a bare enabled check: a
     * soft-deleted site keeps enabled=true, so restoring one is a transition into the route
     * table exactly like an enable, and an enabled-only guard would wave it through unchecked.
     *
     * @throws Violations when going live would collide with a live site's route
     */
    private static void refuseConflictingEnable(@NonNull Row existing) {
        SiteDomainResource.refuseEnableRouteConflicts(existing.get(SiteModel.ID));
    }
}
