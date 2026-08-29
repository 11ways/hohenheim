package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.HostnameAuthority;
import be.elevenways.hohenheim.server.cms.ManageDashboard;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.hohenheim.server.tls.CertificateAuthority;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The tenant boundary around HOSTNAMES on an installation with an operator catch-all:
 * a tenant's exact row decides its own name under a foreign wildcard (DNS and
 * certificates alike), the names it does not decide refuse with one neutral sentence that
 * cannot tell a taken hostname from a free one, an administrator keeps the detailed
 * sentence, and the delegated panel offers no tier the node has switched off. Driven at
 * the MODEL through TenantConduits, because the write pipeline is the gate the /manage
 * forms merely render.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantHostnameIsolationTest extends HohenheimTestBase {

    /** Every hostname here ends in this, so no other class in the shared fork can cover it. */
    private static final String ZONE = "tenant-iso.test";

    private static UserPrincipal alice;
    private static UserPrincipal admin;
    private static Integer aliceId;
    private static Integer aliceSiteId;
    private static Integer aliceDomainId;
    private static Integer bobSiteId;
    private static Integer catchAllSiteId;
    private static Integer zoneId;

    @BeforeAll
    static void seed() {
        // The two tenant sites, then the operator's catch-all over both -- the starfleet
        // shape (site "Starfleet catch-all", domain *.starfleet.life). Grants come LAST:
        // the wildcard row is a system write judged owner-scoped, and two sites nobody
        // has been granted yet compare as one operator owner. The conflict scan names the
        // FIRST conflicting row in id order, so the identical row (Bob's) must be older
        // than the wildcard for the administrator's step 2 to see the "taken" sentence.
        aliceSiteId = site("Tenant-iso Alice", "tenant-iso-alice");
        aliceDomainId = domain(aliceSiteId, "a." + ZONE, SiteDomainModel.MATCH_EXACT);
        bobSiteId = site("Tenant-iso Bob", "tenant-iso-bob");
        domain(bobSiteId, "b." + ZONE, SiteDomainModel.MATCH_EXACT);
        catchAllSiteId = site("Tenant-iso catch-all", "tenant-iso-catch-all");
        domain(catchAllSiteId, "*." + ZONE, SiteDomainModel.MATCH_WILDCARD);

        Row aliceRow = user("alice-iso@hohenheim.local", "Alice Iso");
        aliceId = aliceRow.get(UserModel.ID);
        alice = new UserPrincipal(aliceId, "Alice Iso");
        Row bobRow = user("bob-iso@hohenheim.local", "Bob Iso");
        RecordGrants.grant(GrantSubjectType.USER, aliceId, SiteModel.MODEL_ID,
            aliceSiteId, HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, bobRow.get(UserModel.ID), SiteModel.MODEL_ID,
            bobSiteId, HohenheimAccess.MANAGE, true);

        // The harness administrator, revived as a principal for the detailed-sentence half.
        Row adminRow = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        admin = new UserPrincipal(adminRow.get(UserModel.ID), "Test Admin");

        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, ZONE);
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1." + ZONE);
        zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@" + ZONE);
        zone.set(DnsZoneModel.ROLE, DnsZoneModel.ROLE_PRIMARY);
        zone.set(DnsZoneModel.ENABLED, true);
        zones.save(zone);
        zoneId = zone.get(DnsZoneModel.ID);
    }

    @Test
    @Order(1)
    void theMostSpecificCoveringRowDecidesANameExactlyAsRoutingDoes() {
        AccessContext ctx = AccessContext.of(TenantConduits.stubFor(alice));
        HostnameAuthority.Snapshot snapshot = HostnameAuthority.Snapshot.load();

        // 1. Both rows cover the tenant's name; only the exact one DECIDES it.
        assertThat(snapshot.covering("a." + ZONE))
            .as("step 1: the catch-all and the exact row both cover the name")
            .hasSize(2);
        assertThat(snapshot.deciding("a." + ZONE))
            .extracting(row -> (Integer) row.get(SiteDomainModel.ID))
            .as("step 1: the exact row is the one deciding it, as the dispatcher routes it")
            .containsExactly(aliceDomainId);

        // 2. So the tenant answers for its own hostname under the foreign wildcard...
        assertThat(HostnameAuthority.canManage(snapshot, ctx, "a." + ZONE))
            .as("step 2: a tenant manages its exact hostname under an operator catch-all")
            .isTrue();

        // 3. ...and for nothing the wildcard alone decides: a free name is the wildcard
        //    owner's namespace, a deeper name under the tenant's own host too.
        assertThat(HostnameAuthority.canManage(snapshot, ctx, "zzz." + ZONE))
            .as("step 3: a name only the foreign wildcard covers is not the tenant's")
            .isFalse();
        assertThat(HostnameAuthority.canManage(snapshot, ctx, "deep.a." + ZONE))
            .as("step 3: an exact row covers exactly one host, not the names beneath it")
            .isFalse();
        assertThat(HostnameAuthority.canManage(snapshot, ctx, "b." + ZONE))
            .as("step 3: another tenant's exact hostname is decided by that tenant's row")
            .isFalse();

        // 4. The DNS write lane rides the same decision: the own name is authorable, the
        //    free one under the catch-all is not.
        assertThat((Object) refusalOf(alice, () -> record("a", "192.0.2.10")))
            .as("step 4: a tenant authors DNS for its exact hostname under the catch-all")
            .isNull();
        Violations refused = refusalOf(alice, () -> record("zzz", "192.0.2.11"));
        assertThat((Object) refused).as("step 4: a name the wildcard alone covers is refused").isNotNull();
        assertThat(refused.all().get(0).message().key())
            .as("step 4: with the neutral hostname-authority refusal")
            .isEqualTo("tenant_record_not_authorized");

        // 5. And so does a certificate order: the exact hostname is authorized and
        //    attributed to the tenant's OWN row, the wildcard's names refuse NOT_MANAGED
        //    (served by the catch-all, so never NOT_SERVED), an unserved name NOT_SERVED.
        CertificateAuthority.Requester tenant = CertificateAuthority.Requester.ofSubject(aliceId);
        assertThat(CertificateAuthority.authorize(tenant, List.of("a." + ZONE)))
            .as("step 5: the order is authorized and attributed to the deciding row")
            .containsEntry("a." + ZONE, aliceDomainId);
        for (String foreign : List.of("zzz." + ZONE, "*." + ZONE, "b." + ZONE)) {
            assertThatThrownBy(() -> CertificateAuthority.authorize(tenant, List.of(foreign)))
                .as("step 5: " + foreign + " is served by a site the tenant does not manage")
                .isInstanceOf(CertificateAuthority.Refused.class)
                .extracting(refusal -> ((CertificateAuthority.Refused) refusal).refusal())
                .isEqualTo(CertificateAuthority.Refusal.NOT_MANAGED);
        }
        assertThatThrownBy(() -> CertificateAuthority.authorize(tenant, List.of("a.nobody.test")))
            .as("step 5: a name no row covers stays NOT_SERVED")
            .isInstanceOf(CertificateAuthority.Refused.class)
            .extracting(refusal -> ((CertificateAuthority.Refused) refusal).refusal())
            .isEqualTo(CertificateAuthority.Refusal.NOT_SERVED);
    }

    @Test
    @Order(2)
    void aForeignClaimRefusesWithOneNeutralSentenceForATenantAndTheDetailedOneForAnAdmin() {
        // 1. A tenant claiming another tenant's hostname and a tenant claiming a FREE
        //    hostname under the catch-all get the SAME violation -- key, arguments, field --
        //    so probing the form cannot tell a taken name from a free one, and neither
        //    sentence names a site or a pattern.
        Violations taken = refusalOf(alice, () -> domain(aliceSiteId, "b." + ZONE,
            SiteDomainModel.MATCH_EXACT));
        Violations free = refusalOf(alice, () -> domain(aliceSiteId, "zzz." + ZONE,
            SiteDomainModel.MATCH_EXACT));
        assertThat((Object) taken).as("step 1: the taken hostname is refused").isNotNull();
        assertThat((Object) free).as("step 1: the free hostname under the catch-all is refused").isNotNull();
        Violation takenRefusal = taken.all().get(0);
        Violation freeRefusal = free.all().get(0);
        assertThat(takenRefusal.message().key())
            .as("step 1: the neutral sentence")
            .isEqualTo("hostname_unavailable");
        assertThat(takenRefusal.message())
            .as("step 1: byte-identical to the free case: same key, same filters, same args")
            .isEqualTo(freeRefusal.message());
        assertThat(takenRefusal.message().args().asMap())
            .as("step 1: and it carries no fact about the holder")
            .isEmpty();
        assertThat(takenRefusal.fieldName())
            .as("step 1: both anchor on the hostname the tenant typed")
            .isEqualTo(freeRefusal.fieldName())
            .isEqualTo("hostname");

        // 2. The administrator keeps the actionable sentences, naming the holder.
        Violations adminTaken = refusalOf(admin, () -> domain(aliceSiteId, "b." + ZONE,
            SiteDomainModel.MATCH_EXACT));
        assertThat(adminTaken.all().get(0).message().key())
            .as("step 2: an administrator is told the route is claimed")
            .isEqualTo("route_taken_other_site");
        assertThat(String.valueOf(adminTaken.all().get(0).message().args().asMap().get("site")))
            .as("step 2: and by whom")
            .isEqualTo("Tenant-iso Bob");
        Violations adminFree = refusalOf(admin, () -> domain(aliceSiteId, "zzz." + ZONE,
            SiteDomainModel.MATCH_EXACT));
        assertThat(adminFree.all().get(0).message().key())
            .as("step 2: an administrator is told which wildcard the name falls under")
            .isEqualTo("route_overlaps_other_site");
        assertThat(adminFree.all().get(0).message().args().asMap())
            .as("step 2: pattern and holding site included")
            .containsEntry("hostname", "*." + ZONE)
            .containsEntry("site", "Tenant-iso catch-all");

        // 3. Nothing was written by any of the four refusals.
        assertThat(Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.SITE_ID.eq(aliceSiteId)).count())
            .as("step 3: the tenant's site still holds exactly its own row")
            .isEqualTo(1L);
    }

    /**
     * The update half of the same rule the DNS lane already honoured: an EXISTING claim is
     * decided by its own row, so a foreign wildcard covering it may not re-refuse every
     * later edit of it -- while the name that claim does not cover stays refused.
     */
    @Test
    @Order(3)
    void anExistingClaimIsNeverReRefusedByTheWildcardItAlreadyOutranks() {
        var domains = Models.get(SiteDomainModel.class);
        var sites = Models.get(SiteModel.class);

        // 1. Saving the tenant's own exact row UNCHANGED under the operator catch-all
        //    succeeds: the row already holds the claim and routing decides for it.
        Row own = domains.findById(aliceDomainId);
        assertThat((String) own.get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("precondition: the row holds its claim live")
            .isNotNull();
        assertThat((Object) refusalOf(alice, () -> {
            Row unchanged = domains.findById(aliceDomainId);
            unchanged.set(SiteDomainModel.FORCE_SSL, true);
            domains.save(unchanged);
        })).as("step 1: an unchanged claim is not re-refused by the foreign wildcard over it")
            .isNull();

        // 2. Changing the hostname to another name the wildcard covers introduces a NEW
        //    claim there, and that is refused with the one neutral sentence.
        Violations moved = refusalOf(alice, () -> {
            Row changed = domains.findById(aliceDomainId);
            changed.set(SiteDomainModel.HOSTNAME, "moved." + ZONE);
            domains.save(changed);
        });
        assertThat((Object) moved).as("step 2: moving onto a wildcard-covered name is refused").isNotNull();
        assertThat(moved.all().get(0).message().key())
            .as("step 2: with the neutral sentence, exactly like a create there")
            .isEqualTo("hostname_unavailable");
        assertThat((String) domains.findById(aliceDomainId).get(SiteDomainModel.HOSTNAME))
            .as("step 2: and the stored hostname is untouched")
            .isEqualTo("a." + ZONE);

        // 3. Re-saving the site with enable UNCHANGED succeeds too: the enable seam runs
        //    the same rule and a site that already routes introduces no claim.
        assertThat((Object) refusalOf(alice, () -> {
            Row site = sites.findById(aliceSiteId);
            site.set(SiteModel.ENABLED, true);
            sites.save(site);
        })).as("step 3: an already-routing site re-saves under the foreign wildcard")
            .isNull();

        // 4. The staging two-step stays closed: a routeless site's rows hold no claim, so
        //    a name only the foreign wildcard covers is refused when it goes live.
        Integer stagedSiteId = site("Tenant-iso staged", "tenant-iso-staged");
        RecordGrants.grant(GrantSubjectType.USER, aliceId, SiteModel.MODEL_ID,
            stagedSiteId, HohenheimAccess.MANAGE, true);
        Row staged = sites.findById(stagedSiteId);
        staged.set(SiteModel.ENABLED, false);
        sites.save(staged);
        domain(stagedSiteId, "staged." + ZONE, SiteDomainModel.MATCH_EXACT);
        Violations enabled = refusalOf(alice, () -> {
            Row site = sites.findById(stagedSiteId);
            site.set(SiteModel.ENABLED, true);
            sites.save(site);
        });
        assertThat((Object) enabled)
            .as("step 4: enabling a site staged on the wildcard's namespace is refused")
            .isNotNull();
        assertThat(enabled.all().get(0).fieldName())
            .as("step 4: anchored on enabled, like every refusal on that path")
            .isEqualTo("enabled");
    }

    /**
     * AIDEV-NOTE: the role snapshot is process-global, Panel.peers() memoizes per instance
     * and a Panel self-registers in its constructor, so this asserts the panel's peer
     * DECLARATION for each role set (ManagePanel.declarePeers, which buildPeers returns and
     * peersBySlug -- the route dispatch -- memoizes) rather than an HTTP round trip against
     * the shared server, exactly like DashboardRoleGatingTest asserts the collectors.
     */
    @Test
    @Order(4)
    void theDelegatedPanelOffersNoTierTheNodeHasSwitchedOff() {
        Set<Role> booted = EnumSet.noneOf(Role.class);
        for (Role role : Role.values()) {
            if (HohenheimRoles.enabled(role)) {
                booted.add(role);
            }
        }
        AccessContext ctx = AccessContext.of(TenantConduits.stubFor(alice));
        try {
            // 1. The full node: every projection is declared and the overview lists instances.
            roles(EnumSet.allOf(Role.class));
            assertThat(slugsOf(ManagePanel.declarePeers()))
                .as("step 1: a full node projects the instance tier")
                .contains("instances", "databases", "instance-databases");
            assertThat(instanceSources(new ManageDashboard().widgets(ctx)))
                .as("step 1: and the overview carries the instance list")
                .isNotEmpty();

            // 2. The starfleet shape: proxy/DNS/firewall on, every workload tier off. The
            //    instance, database and instance-database projections have no route, the
            //    overview offers no instance list, and the proxy tier's own peers stay.
            roles(EnumSet.of(Role.PROXY, Role.DNS, Role.FIREWALL));
            List<String> appliance = slugsOf(ManagePanel.declarePeers());
            assertThat(appliance)
                .as("step 2: every workload-tier projection is absent with its role off")
                .doesNotContain("instances", "databases", "instance-databases",
                    "instance-templates", "instance-snapshots", "instance-backups");
            assertThat(appliance)
                .as("step 2: the proxy and DNS tiers, and the tier-less projects, stay")
                .contains("sites", "domains", "dns-records", "certificates",
                    "git-providers", "access-lists", "projects");
            assertThat(instanceSources(new ManageDashboard().widgets(ctx)))
                .as("step 2: the overview offers no instance list for a tier with no route")
                .isEmpty();

            // 3. DNS off drops the record authoring peer and nothing else of the proxy tier.
            roles(EnumSet.of(Role.PROXY));
            List<String> proxyOnly = slugsOf(ManagePanel.declarePeers());
            assertThat(proxyOnly)
                .as("step 3: no DNS authoring without the DNS role")
                .doesNotContain("dns-records");
            assertThat(proxyOnly)
                .as("step 3: the proxy tier is untouched")
                .contains("domains");
        } finally {
            roles(booted);
        }
    }

    /** The zone cascade rides the model funnel: a model delete sweeps the records. */
    @Test
    @Order(5)
    void deletingAZoneThroughTheModelSweepsItsRecords() {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        assertThat(records.find().where(DnsRecordModel.ZONE_ID.eq(zoneId)).count())
            .as("precondition: the tenant's record from step 4 above is stored")
            .isEqualTo(1L);
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        zones.delete(zones.findById(zoneId));
        assertThat(records.find().where(DnsRecordModel.ZONE_ID.eq(zoneId)).count())
            .as("a zone deleted past the admin resource still takes its records with it")
            .isZero();
    }

    // --- Fixture helpers ---------------------------------------------------------------

    private static List<String> slugsOf(List<PanelPeer> peers) {
        List<String> slugs = new ArrayList<>();
        for (PanelPeer peer : peers) {
            slugs.add(peer.slug());
        }
        return slugs;
    }

    /** The instance record widgets on the overview, wherever the sections nest them. */
    private static List<WidgetInstance> instanceSources(WidgetTree tree) {
        List<WidgetInstance> found = new ArrayList<>();
        for (WidgetInstance widget : tree.widgets()) {
            if ("hohenheim.instance".equals(widget.config().get("source"))) {
                found.add(widget);
            }
            found.addAll(instanceSources(widget.children()));
        }
        return found;
    }

    /** Declare the node's role set and snapshot it, the way a boot's settings load does. */
    private static void roles(Set<Role> enabled) {
        for (Role role : Role.values()) {
            HohenheimSettings.VALUES.setValue(role.setting(), enabled.contains(role));
        }
        HohenheimRoles.capture();
    }

    private static Row user(String email, String name) {
        Row row = AuthModels.users().createEmptyRow();
        row.set(UserModel.EMAIL, email);
        row.set(UserModel.DISPLAY_NAME, name);
        row.set(UserModel.ENABLED, true);
        row.set(UserModel.CREATED_AT, Instant.now());
        row.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(row);
        return row;
    }

    private static Integer site(String name, String slug) {
        var model = Models.get(SiteModel.class);
        Row row = model.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        model.save(row);
        return row.get(SiteModel.ID);
    }

    private static Integer domain(Integer siteId, String hostname, String matchType) {
        var model = Models.get(SiteDomainModel.class);
        Row row = model.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, siteId);
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, matchType);
        model.save(row);
        return row.get(SiteDomainModel.ID);
    }

    private static void record(String name, String value) {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row record = records.createEmptyRow();
        record.set(DnsRecordModel.ZONE_ID, zoneId);
        record.set(DnsRecordModel.NAME, name);
        record.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_A);
        record.set(DnsRecordModel.VALUE, value);
        record.set(DnsRecordModel.TTL, 300);
        record.set(DnsRecordModel.ENABLED, true);
        records.save(record);
    }

    /** Run {@code body} as the given principal; the refusal it produces, or null when it passed. */
    private static Violations refusalOf(UserPrincipal principal, Runnable body) {
        return catchThrowableOfType(() -> TenantConduits.as(principal, body), Violations.class);
    }
}
