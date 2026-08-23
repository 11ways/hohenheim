package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.InstanceResource;
import be.elevenways.hohenheim.server.instance.InstanceExposure;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Destroying a workload must not strand the sites that exposed it: the confirmation NAMES
 * them and the service DISABLES them, so a hostname stops answering instead of serving a
 * permanent 503 with its certificate still in place.
 *
 * AIDEV-NOTE: the guarantee is asserted on {@code InstanceService.destroy}, not on the row
 * action, precisely because the API and the release engine reach the service and never the
 * button. The wording is asserted on the resource, which is the only half a non-UI caller
 * legitimately does not get.
 */
class InstanceExposureTest {

    private static SqlDatasource datasource;
    private static int hostId;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        FakeNativeDaemons.register();
        Db.run(datasource, () -> hostId = host("exposure-host"));
    }

    @Test
    void destroyingAnExposedWorkloadDisablesItsSitesAndTheDialogNamesThemFirst() {
        Db.run(datasource, () -> {
            // 1. A workload with two live sites in front of it, one of them holding a
            //    real route claim, plus a bystander site on a DIFFERENT workload.
            int instanceId = instanceRecord("exposed-workload");
            int bystanderId = instanceRecord("other-workload");
            Row shop = site("Shop", "shop", instanceId);
            Row blog = site("Blog", "blog", instanceId);
            Row bystander = site("Bystander", "bystander", bystanderId);
            Row shopDomain = domain(shop, "shop.exposure.test");
            new InstanceService().deploy(instanceId);

            assertThat(reloadDomain(shopDomain).get(SiteDomainModel.LIVE_ROUTE_KEY))
                .as("step 1: the exposed site really is routing before the destroy")
                .isNotNull();

            // 2. The dialog an operator sees BEFORE clicking names every site that will
            //    be disabled -- the fact that only exists per record.
            InstanceResource resource = new InstanceResource();
            Row record = Models.get(InstanceModel.class).findById(instanceId);
            assertThat(resource.deleteConfirmationFor(record).body().key())
                .as("step 2: the per-record confirmation switches to the stranding wording")
                .isEqualTo("delete_confirm_stranding");
            assertThat(InstanceExposure.liveSiteNamesExposing(instanceId))
                .as("step 2: and it names BOTH exposing sites, not just one")
                .containsExactly("Blog", "Shop");
            assertThat(resource.deleteConfirmation().body().key())
                .as("step 2: while the record-LESS confirmation can only speak about the"
                    + " type, which is why the record-aware hook exists")
                .isEqualTo("delete_confirm");

            // 3. A workload nothing exposes keeps the generic wording: the warning is
            //    never bolted onto a destroy that strands nobody.
            Row unexposed = Models.get(InstanceModel.class)
                .findById(instanceRecord("unexposed-workload"));
            assertThat(resource.deleteConfirmationFor(unexposed).body().key())
                .as("step 3: an unexposed workload gets no stranding sentence")
                .isEqualTo("delete_confirm");

            // 4. THE GUARANTEE, on the service: destroy disables every site that exposed
            //    the workload, and only those.
            new InstanceService().destroy(instanceId);
            assertThat(reload(shop).get(SiteModel.ENABLED))
                .as("step 4: the exposing site was disabled instead of left at 503")
                .isEqualTo(false);
            assertThat(reload(blog).get(SiteModel.ENABLED))
                .as("step 4: every exposing site, not just the first")
                .isEqualTo(false);
            assertThat(reload(bystander).get(SiteModel.ENABLED))
                .as("step 4: a site exposing ANOTHER workload is untouched")
                .isEqualTo(true);

            // 5. DISABLED, never deleted: the site, its hostname and its upstream link all
            //    survive, so re-pointing it at another workload is one edit away.
            assertThat(reload(shop))
                .as("step 5: the site record survives the destroy").isNotNull();
            assertThat(reload(shop).get(SiteModel.DELETED_AT))
                .as("step 5: and is not trashed").isNull();
            assertThat(reload(shop).get(SiteModel.INSTANCE_ID))
                .as("step 5: its upstream link is kept -- the operator repoints it")
                .isEqualTo(instanceId);
            assertThat(reloadDomain(shopDomain))
                .as("step 5: the hostname row survives with it").isNotNull();
            assertThat(reloadDomain(shopDomain).get(SiteDomainModel.HOSTNAME))
                .as("step 5: hostnames belong to the SITE, never to the workload")
                .isEqualTo("shop.exposure.test");

            // 6. And it really stopped routing: the disable rides the site write pipeline,
            //    so the live route claim is released rather than held by a dead backend.
            assertThat(reloadDomain(shopDomain).get(SiteDomainModel.LIVE_ROUTE_KEY))
                .as("step 6: the released hostname no longer holds a live route claim")
                .isNull();
            Models.get(ReleasedRouteClaimModel.class).find().delete();
        });
    }

    // -- fixtures ---------------------------------------------------------------

    private static int host(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        HostFixtures.acknowledgePosture(row);
        HostPreflight.store(name, new HostPreflight.Report(List.of(
            new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "fake daemon"),
            new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                HostPreflight.STATUS_PASS, true, "fake kernel-truth lane")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return Models.get(ServerModel.class).findByName(name).get(ServerModel.ID);
    }

    private static int instanceRecord(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static Row site(String name, String slug, int instanceId) {
        Model sites = Models.get(SiteModel.class);
        Row existing = sites.find().where(SiteModel.SLUG.eq(slug)).first();
        if (existing != null) {
            return existing;
        }
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, InstanceUpstreamKind.ID.toString());
        row.set(SiteModel.INSTANCE_ID, instanceId);
        row.set(SiteModel.SETTINGS, Map.of());
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, true);
        sites.save(row);
        return row;
    }

    private static Row domain(Row site, String hostname) {
        Row row = Models.get(SiteDomainModel.class).createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        Models.get(SiteDomainModel.class).save(row);
        return row;
    }

    /** Re-read a site from storage: the disable happens inside the service, not on our copy. */
    private static Row reload(Row site) {
        return Models.get(SiteModel.class).findById(site.get(SiteModel.ID));
    }

    private static Row reloadDomain(Row domain) {
        return Models.get(SiteDomainModel.class).findById(domain.get(SiteDomainModel.ID));
    }
}
