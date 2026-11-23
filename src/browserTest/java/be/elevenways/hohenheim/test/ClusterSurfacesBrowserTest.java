package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static be.elevenways.hohenheim.HohenheimSlugs.ADMIN;

/**
 * The surface of the Servers list: its columns, its two header verbs and its row verbs, for the operator alone.
 *
 * AIDEV-NOTE: the stored set ({@code /panel-surfaces/cluster.txt}) pins the list as it shipped; a failing comparison
 * is a changed surface, never a file to refresh.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class ClusterSurfacesBrowserTest extends HohenheimTestBase {

    private static final String ENTRY = HohenheimSlugs.LINKED_SERVERS;
    private static AccessContext operator;
    private static AccessContext tenant;

    @BeforeAll
    static void seed() {
        operator = TenantConduits.operator();
        UUID user = ApiSupport.user("cluster-surfaces@hohenheim.local", "Cluster Surfaces Tenant");
        tenant = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(user, "Cluster Surfaces Tenant")));
    }

    @Test
    void theServersListKeepsItsSurface() {
        SurfaceBaselines pinned = SurfaceBaselines.load(ClusterSurfacesBrowserTest.class,
            "/panel-surfaces/cluster.txt");

        // 1. An administrator reads the list with its verbs; anybody else is refused the installation's servers.
        pinned.check(capture(SurfaceCase.of(ADMIN, ENTRY, "operator", operator)));
        pinned.check(capture(SurfaceCase.of(ADMIN, ENTRY, "tenant", tenant)
            .refusedFor(ZenitRefusalReason.FORBIDDEN)));

        // 2. Every pinned case participates in the exact comparison.
        pinned.finish();
    }

    private static PanelSurfaces capture(SurfaceCase fixture) {
        return PanelSurfaces.capture(fixture);
    }
}
