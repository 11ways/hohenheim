package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.model.Models;
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

    private static volatile boolean sourceRegistered = false;

    public ManagePanel() {
        super(Identifier.of("hohenheim", "manage"), "manage",
            Microcopy.of("title").withFilter("scope", "manage"), ACCESS);
    }

    /**
     * Derives panel eligibility from walk-confirmed record grants while preserving
     * explicit global grants. Installed by {@code HohenheimHostWiring} at the
     * MODULES stage (never a Panel-constructor side effect): the checker and the
     * site source must exist the moment the server accepts requests, and boot-seam
     * installation makes that ordering structural.
     */
    public static synchronized void installEligibilityPolicy() {
        PermissionChecker current = Zenit.getPermissionChecker();
        if (current instanceof ManageEligibilityChecker) {
            return;
        }
        Zenit.setPermissionChecker(new ManageEligibilityChecker(current));
    }

    /**
     * Widens ONLY the boolean face of the panel's ACCESS permission: an explicit
     * resolver decision (either way) wins, and an abstain falls back to "holds a
     * walk-confirmed manage grant on at least one site". zenit-cms checks a
     * panel's access as a plain permission, so this layer is what makes a
     * grant-holding tenant eligible for /manage without a second global grant.
     *
     * AIDEV-NOTE: decide() MUST pass through to the delegate untouched. The
     * predecessor (EffectiveManagePermissionChecker) overrode only
     * hasPermission, so it inherited the interface default decide() -- which
     * maps false to abstain -- and thereby made an operator's explicit global
     * DENY invisible to RecordCapabilities row 2 (GATE_DENIED) in every
     * hohenheim install. Pinned by CapabilityWalkTest step 3.
     */
    private record ManageEligibilityChecker(PermissionChecker delegate) implements PermissionChecker {
        @Override
        public boolean hasPermission(Conduit conduit, Permission permission) {
            if (!ACCESS.equals(permission) || conduit == null) {
                return this.delegate.hasPermission(conduit, permission);
            }
            Boolean decision = this.delegate.decide(conduit, permission);
            if (decision != null) {
                return decision;
            }
            // Abstain: eligibility follows the record grants. Each candidate is
            // confirmed through the precedence walk (no recursion: the walk
            // consults this checker only for OTHER permissions -- the admin
            // bypass -- and for decide(), which passes through above).
            return !HohenheimAccess.managedSiteIds(RecordSourceGate.accessContextOf(conduit)).isEmpty();
        }

        @Override
        public @Nullable Boolean decide(Conduit conduit, @NonNull Permission permission) {
            return this.delegate.decide(conduit, permission);
        }
    }

    @Override
    public @NonNull List<PanelPeer> buildPeers() {
        return List.of(new ManageSiteResource(), new ManageDomainResource());
    }

    /**
     * THE SiteModel default source, serving the admin pickers AND the /manage
     * tenant surface through one per-principal scope (admins unconstrained,
     * tenants their granted sites, everyone else nothing). Registered
     * server-side (not in HohenheimSources) because the scope reads zenit-auth
     * record grants, which common code cannot see.
     * AIDEV-NOTE: the browser registry legitimately lacks this source, and
     * that is SAFE only because the framework treats the browser registry as
     * a subset: widget-config revival tolerates browser-unresolvable tokens
     * and client re-renders reach /zn/records/{token} by TOKEN (zenit-widget
     * RecordsFunctions/ChartFunctions). Naming this token in a widget config
     * used to kill soft navigation onto /admin/dashboard (revival rejected
     * 'hohenheim.site' against the browser registry) -- pinned by
     * NavigationTest.softNavDashboardKeepsStatTitlesIconsAndAttentionEntries
     * and zenit-cms's ServerOnlySourceSoftNavBrowserTest. The trade-off of
     * staying server-only: client-side source pickers and rule vocabularies
     * cannot offer it.
     * AIDEV-NOTE: this replaced the separate "hohenheim.manage_site" source --
     * two sources with identical semantics over one model were a shadowing
     * hazard.
     */
    public static synchronized void registerSiteSource() {
        if (sourceRegistered) {
            return;
        }
        sourceRegistered = true;
        // override, not register: the manage panel deliberately serves a WIDER audience
        // than the sites panel's own access permission -- any principal with a grant,
        // scoped to the sites that grant covers. The registry refuses a silent widening
        // of the derived default, and this is the verb that declares one.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(SiteModel.class)
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
        Set<Integer> ids = HohenheimAccess.managedSiteIds(ctx);
        if (ids.isEmpty()) {
            return impossible();
        }
        return SiteModel.ID.in(ids);
    }

    /** Matches nothing; NEVER ID.in(empty), which some backends reject or widen. */
    static @NonNull Criteria impossible() {
        return Models.get(SiteModel.class).matchNone();
    }

    /**
     * NAV-ONLY scope probe shared by the /manage peers: admins always have
     * scope; everyone else needs at least one walk-confirmed managed site.
     * Cheap per render thanks to the conduit-scoped managedSiteIds memo.
     */
    static boolean hasManageScope(@NonNull AccessContext ctx) {
        return HohenheimAccess.isAdmin(ctx) || !HohenheimAccess.managedSiteIds(ctx).isEmpty();
    }
}
