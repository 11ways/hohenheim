package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.security.PermissionChecker;
import be.elevenways.zenit.server.data.RecordSourceGate;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Delegated operator panel at /manage: only the sites (and their domains) the
 * principal holds a manage grant on, no installation-wide peers.
 */
public final class ManagePanel extends Panel {

    public static final Permission ACCESS = Permission.of("hohenheim.manage.access");

    /** The manage-scoped site record source id (per-principal picker + coercion scope). */
    public static final Identifier MANAGE_SITE_SOURCE = Identifier.of("hohenheim", "manage_site");

    private static volatile boolean sourceRegistered = false;

    public ManagePanel() {
        super(Identifier.of("hohenheim", "manage"), "manage",
            Microcopy.of("title").withFilter("scope", "manage"), ACCESS);
        installEffectiveAccessPolicy();
    }

    /** Derives panel eligibility from effective record grants while preserving explicit global grants. */
    private static synchronized void installEffectiveAccessPolicy() {
        PermissionChecker current = Zenit.getPermissionChecker();
        if (current instanceof EffectiveManagePermissionChecker) {
            return;
        }
        Zenit.setPermissionChecker(new EffectiveManagePermissionChecker(current));
    }

    private record EffectiveManagePermissionChecker(PermissionChecker delegate) implements PermissionChecker {
        @Override
        public boolean hasPermission(Conduit conduit, Permission permission) {
            if (!ACCESS.equals(permission) || conduit == null) {
                return this.delegate.hasPermission(conduit, permission);
            }
            var principal = RecordSourceGate.principalOf(conduit);
            Boolean decision = ZenitAuth.permissionResolver().decide(principal, permission.value());
            if (decision != null) {
                return decision;
            }
            return this.delegate.hasPermission(conduit, permission)
                || !HohenheimAccess.managedSiteIds(principal).isEmpty();
        }
    }

    @Override
    public @NonNull List<PanelPeer> buildPeers() {
        registerManageSiteSource();
        return List.of(new ManageSiteResource(), new ManageDomainResource());
    }

    /**
     * Registered server-side (not in HohenheimSources) because the access
     * scope reads zenit-auth record grants; pickers reach it by token through
     * the record-source endpoints, so the browser registry never needs it.
     */
    public static synchronized void registerManageSiteSource() {
        if (sourceRegistered) {
            return;
        }
        sourceRegistered = true;
        RecordSourceRegistry.INSTANCE.register(RecordSource.of(SiteModel.class)
            .id(MANAGE_SITE_SOURCE)
            .search(SiteModel.NAME, SiteModel.SLUG)
            .baseCriteria(() -> SiteModel.DELETED_AT.isNull())
            .accessCriteria(ManagePanel::siteScope)
            .build());
    }

    /**
     * @return null for admins (no extra constraint), an impossible criteria
     *         for principals without grants, else {@code ID IN (managed ids)}
     */
    static @Nullable Criteria siteScope(@NonNull AccessContext ctx) {
        if (HohenheimAccess.isAdmin(ctx)) {
            return null;
        }
        if (ctx.isAnonymous()) {
            return impossible();
        }
        Set<Integer> ids = HohenheimAccess.managedSiteIds(ctx.principal());
        if (ids.isEmpty()) {
            return impossible();
        }
        return SiteModel.ID.in(ids);
    }

    /** Matches nothing; NEVER ID.in(empty), which some backends reject or widen. */
    static @NonNull Criteria impossible() {
        return SiteModel.ID.eq(-1);
    }
}
