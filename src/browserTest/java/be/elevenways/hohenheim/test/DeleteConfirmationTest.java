package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.hohenheim.server.cms.AccessListResource;
import be.elevenways.hohenheim.server.cms.AuthProviderResource;
import be.elevenways.hohenheim.server.cms.CertificateResource;
import be.elevenways.hohenheim.server.cms.DnsPeerResource;
import be.elevenways.hohenheim.server.cms.DnsRecordResource;
import be.elevenways.hohenheim.server.cms.DnsZoneResource;
import be.elevenways.hohenheim.server.cms.EnvironmentResource;
import be.elevenways.hohenheim.server.cms.EnvironmentVariableResource;
import be.elevenways.hohenheim.server.cms.ManageDnsRecordResource;
import be.elevenways.hohenheim.server.cms.NotificationChannelResource;
import be.elevenways.hohenheim.server.cms.ServerResource;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A delete dialog must name what deleting THIS record takes with it: the zone's origin,
 * its record count, what resolves inside it and whether it answers for the very hostname
 * the admin panel is being reached at -- and the same per-record naming for sites,
 * certificates and individual DNS records.
 *
 * AIDEV-NOTE: asserted on the resources rather than through the rendered page, because
 * the confirmation is the ONLY half of a delete that a non-UI caller legitimately does
 * not get; the delete itself is proven by the write-path tests.
 */
class DeleteConfirmationTest {

    private static final String ORIGIN = "delete-confirm.test";

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void everyDeleteDialogNamesWhatThisRecordTakesWithIt() {
        Db.run(datasource, () -> {
            DnsZoneResource zones = new DnsZoneResource();

            // 1. A zone nothing depends on yet: the dialog NAMES the origin and how many
            //    stored records go with it, and gates the click behind typing the origin.
            int zoneId = zone(ORIGIN);
            record(zoneId, "www");
            record(zoneId, "mail");
            Row zone = Models.get(DnsZoneModel.class).findById(zoneId);

            ConfirmationSpec named = zones.deleteConfirmationFor(zone);
            assertThat(named.body().key())
                .as("step 1: a zone with no dependents gets the named wording")
                .isEqualTo("delete_confirm_named");
            assertThat(named.body().args().get("origin"))
                .as("step 1: and it names the zone").isEqualTo(ORIGIN);
            assertThat(named.body().args().get("records"))
                .as("step 1: with the count of records that go with it").isEqualTo(2L);
            assertThat(named.requireTypedConfirmation())
                .as("step 1: typing the origin is what arms the button")
                .isEqualTo(ORIGIN);

            // 2. The record-LESS dialog can only speak about the type, which is exactly
            //    why the record-aware hook exists -- and it arms no typed confirmation,
            //    because there is no origin to type.
            assertThat(zones.deleteConfirmation().body().key())
                .as("step 2: the type-level dialog keeps the generic wording")
                .isEqualTo("delete_confirm");
            assertThat(zones.deleteConfirmation().requireTypedConfirmation())
                .as("step 2: and demands no typed phrase").isNull();

            // 3. A site hostname and a certificate inside the zone: both are named, so an
            //    operator sees what loses its DNS before clicking.
            int siteId = site("shop", "shop." + ORIGIN);
            certificate("wildcard", "*." + ORIGIN + ", shop." + ORIGIN);

            ConfirmationSpec dependents = zones.deleteConfirmationFor(zone);
            assertThat(dependents.body().key())
                .as("step 3: dependents switch the zone to the dependent wording")
                .isEqualTo("delete_confirm_dependents");
            assertThat(String.valueOf(dependents.body().args().get("dependents")))
                .as("step 3: the site hostname and the certificate names are both named")
                .contains("shop." + ORIGIN)
                .contains("*." + ORIGIN);

            // 4. A zone that answers for the hostname THIS request arrived on: the one
            //    delete that can lock the operator out of the surface they are clicking in.
            TenantConduits.arrivingAt("https://panel." + ORIGIN, () -> {
                ConfirmationSpec locking = zones.deleteConfirmationFor(zone);
                assertThat(locking.body().key())
                    .as("step 4: the admin-hostname warning replaces the plain one")
                    .isEqualTo("delete_confirm_admin_dependents");
                assertThat(locking.body().args().get("host"))
                    .as("step 4: and names the hostname the panel is being reached at")
                    .isEqualTo("panel." + ORIGIN);
            });

            // 5. A request arriving on a hostname OUTSIDE the zone is never warned about
            //    a lockout that cannot happen.
            TenantConduits.arrivingAt("https://panel.elsewhere.test", () ->
                assertThat(zones.deleteConfirmationFor(zone).body().key())
                    .as("step 5: an unrelated admin hostname keeps the dependent wording")
                    .isEqualTo("delete_confirm_dependents"));

            // 6. The site dialog names the hostnames that stop answering.
            SiteResource sites = new SiteResource();
            Row shop = Models.get(SiteModel.class).findById(siteId);
            ConfirmationSpec siteConfirm = sites.deleteConfirmationFor(shop);
            assertThat(siteConfirm.body().key())
                .as("step 6: a site with hostnames gets the hostname wording")
                .isEqualTo("delete_confirm_hostnames");
            assertThat(siteConfirm.body().args().get("hostnames"))
                .as("step 6: naming the hostname that stops answering")
                .isEqualTo("shop." + ORIGIN);
            assertThat(siteConfirm.body().args().get("name"))
                .as("step 6: and the site it belongs to").isEqualTo("shop");

            // 7. A site with no hostname bound gets the generic body instead of a
            //    sentence naming nothing.
            Row bare = Models.get(SiteModel.class).findById(site("bare", null));
            assertThat(sites.deleteConfirmationFor(bare).body().key())
                .as("step 7: a hostname-less site keeps the generic wording")
                .isEqualTo("delete_confirm");

            // 8. The certificate dialog names the domains whose HTTPS stops working.
            CertificateResource certificates = new CertificateResource();
            Row wildcard = Models.get(CertificateModel.class).find()
                .where(CertificateModel.NICE_NAME.eq("wildcard")).first();
            ConfirmationSpec certConfirm = certificates.deleteConfirmationFor(wildcard);
            assertThat(certConfirm.body().key())
                .as("step 8: a certificate with domains gets the domain wording")
                .isEqualTo("delete_confirm_domains");
            assertThat(String.valueOf(certConfirm.body().args().get("domains")))
                .as("step 8: naming every name it secures")
                .contains("*." + ORIGIN)
                .contains("shop." + ORIGIN);

            // 9. A certificate with no stored names cannot name any, and says the rest.
            Row nameless = Models.get(CertificateModel.class).findById(certificate("empty", ""));
            assertThat(certificates.deleteConfirmationFor(nameless).body().key())
                .as("step 9: a nameless certificate keeps the generic wording")
                .isEqualTo("delete_confirm");

            // 10. A DNS record's dialog names the record, its TYPE, its VALUE and the ZONE
            //     it answers in -- the generic dialog names only the owner label, which
            //     tells an operator nothing about what stops resolving.
            DnsRecordResource records = new DnsRecordResource();
            Row visualQa = Models.get(DnsRecordModel.class)
                .findById(record(zoneId, "visual-qa"));

            ConfirmationSpec recordConfirm = records.deleteConfirmationFor(visualQa);
            assertThat(recordConfirm.body().key())
                .as("step 10: a complete record gets the named wording")
                .isEqualTo("delete_confirm_named");
            assertThat(recordConfirm.body().filters().get("scope"))
                .as("step 10: scoped to the record catalog, not the zone's same-named entry")
                .isEqualTo("dns_record");
            assertThat(recordConfirm.body().args().get("name"))
                .as("step 10: the name is ABSOLUTE -- an owner label alone is ambiguous"
                    + " across zones")
                .isEqualTo("visual-qa." + ORIGIN);
            assertThat(recordConfirm.body().args().get("type"))
                .as("step 10: and names the record type").isEqualTo("A");
            assertThat(recordConfirm.body().args().get("value"))
                .as("step 10: and the value that stops being answered")
                .isEqualTo("203.0.113.10");
            assertThat(recordConfirm.body().args().get("origin"))
                .as("step 10: and the zone it is removed from").isEqualTo(ORIGIN);

            // 11. The apex record names the zone itself rather than "@.zone".
            Row apex = Models.get(DnsRecordModel.class).findById(record(zoneId, "@"));
            assertThat(records.deleteConfirmationFor(apex).body().args().get("name"))
                .as("step 11: the apex owner resolves to the origin, never '@.origin'")
                .isEqualTo(ORIGIN);

            // 12. The tenant-facing subclass inherits the SAME composing method -- there is
            //     one home for this wording, and the zone's Records tab renders its rows
            //     through this very hook too.
            assertThat(new ManageDnsRecordResource().deleteConfirmationFor(visualQa).body())
                .as("step 12: /manage speaks the same sentence as the admin panel")
                .isEqualTo(recordConfirm.body());

            // 13. A record whose zone reference dangles cannot name a zone, so it keeps the
            //     framework's record-named body instead of a sentence with a hole in it.
            Row orphan = Models.get(DnsRecordModel.class).findById(record(zoneId, "orphan"));
            orphan.set(DnsRecordModel.ZONE_ID, null);
            ConfirmationSpec orphanConfirm = records.deleteConfirmationFor(orphan);
            assertThat(orphanConfirm.body().key())
                .as("step 13: an unresolvable zone falls back to the framework's body"
                    + " rather than a sentence with a hole in it")
                .isEqualTo("delete_confirm");
            assertThat(orphanConfirm.body().args().get("origin"))
                .as("step 13: and names no zone at all").isNull();
        });
    }

    /**
     * The four dialogs the 2026-08-27 pass found saying only "this cannot be undone": an
     * access list (whose rules cascade and whose gate silently OPENS), a host, an
     * environment and a notification channel.
     */
    @Test
    void thePreviouslyGenericDialogsNameTheirOwnConsequences() {
        Db.run(datasource, () -> {
            AccessListResource lists = new AccessListResource();

            // 1. A list nothing uses: the dialog names it and how many rules go with it,
            //    and says in so many words that nothing is gated by it.
            int listId = accessList("Office");
            rule(listId);
            rule(listId);
            Row list = Models.get(AccessListModel.class).findById(listId);

            ConfirmationSpec unused = lists.deleteConfirmationFor(list);
            assertThat(unused.body().key())
                .as("step 1: an unused list gets the named wording")
                .isEqualTo("delete_confirm_named");
            assertThat(unused.body().filters().get("scope"))
                .as("step 1: from the access-list catalog").isEqualTo("access_list");
            assertThat(unused.body().args().get("name"))
                .as("step 1: naming the list").isEqualTo("Office");
            assertThat(unused.body().args().get("rules"))
                .as("step 1: and the rules that go with it").isEqualTo(2L);

            // 2. Once a site and a protected path name the list, the dialog names THEM --
            //    this is the dangerous direction: their gate does not break, it opens.
            int siteId = site("guarded", null);
            Row guarded = Models.get(SiteModel.class).findById(siteId);
            guarded.set(SiteModel.ACCESS_LIST_ID, listId);
            Models.get(SiteModel.class).save(guarded);
            protectedPath(siteId, listId, "/admin");

            ConfirmationSpec gating = lists.deleteConfirmationFor(list);
            assertThat(gating.body().key())
                .as("step 2: a list in use switches to the gating wording")
                .isEqualTo("delete_confirm_gating");
            assertThat(String.valueOf(gating.body().args().get("gated")))
                .as("step 2: naming the site and the protected path that stop being gated")
                .contains("guarded")
                .contains("/admin");

            // 3. The record-LESS dialog can only speak about the type, and does.
            assertThat(lists.deleteConfirmation().body().filters().get("scope"))
                .as("step 3: the type-level dialog is the access-list one, not the generic")
                .isEqualTo("access_list");

            // 4. A host carrying stored workloads: the delete is OFFERED and dead, with the
            //    count on screen, rather than orphaning every row that names the host.
            ServerResource servers = new ServerResource();
            AccessContext operator = AccessContext.of(TenantConduits.stubFor(null));
            int hostId = server("delete-confirm-host");
            Row host = Models.get(ServerModel.class).findById(hostId);
            assertThat(servers.deleteUnavailableReason(host, operator))
                .as("step 4: an empty host is deletable").isNull();

            stack("payments", hostId);
            Microcopy inUse = servers.deleteUnavailableReason(host, operator);
            assertThat(inUse).as("step 4: a host with workloads is not").isNotNull();
            assertThat(inUse.key()).isEqualTo("delete_in_use");
            assertThat(inUse.args().get("workloads"))
                .as("step 4: naming how many still point at it").isEqualTo(1L);

            // 5. The local host is the machine Hohenheim runs on: its delete was enforced
            //    only at submit, so the button was offered and always failed. The row is
            //    the boot's own -- the name is unique and this test never creates it.
            Row local = Models.get(ServerModel.class).find()
                .where(ServerModel.NAME.eq(ServerService.LOCAL)).first();
            assertThat(local).as("step 5: the boot registered the local host").isNotNull();
            assertThat(servers.deleteUnavailableReason(local, operator))
                .as("step 5: the local host explains itself instead of failing on click")
                .isNotNull()
                .extracting(Microcopy::key).isEqualTo("delete_local");

            // 6. The remaining two speak for themselves rather than through the framework's
            //    "this cannot be undone".
            assertThat(new EnvironmentResource().deleteConfirmation().body().filters().get("scope"))
                .as("step 6: the environment dialog states the refusal policy it enforces")
                .isEqualTo("environment");
            assertThat(new NotificationChannelResource().deleteConfirmation()
                    .body().filters().get("scope"))
                .as("step 6: and the channel dialog names the deliveries that stop")
                .isEqualTo("notification_channel");
        });
    }

    /**
     * The 2026-08-29 cross-reference pass: a DNS peer names the zones that stop notifying
     * it and is dead while a secondary replicates from it, an auth provider is dead naming
     * the sites it gates, and an environment variable says what is removed and when the
     * removal lands.
     */
    @Test
    void theCrossReferenceDialogsNameTheirZonesSitesAndEnvironments() {
        Db.run(datasource, () -> {
            AccessContext operator = AccessContext.of(TenantConduits.stubFor(null));
            DnsPeerResource peers = new DnsPeerResource();

            // 1. A peer nothing links to: the dialog names it and says nothing notifies it.
            int peerId = peer("ns2");
            Row peer = Models.get(DnsPeerModel.class).findById(peerId);
            assertThat(peers.deleteUnavailableReason(peer, operator))
                .as("step 1: an unreferenced peer is deletable").isNull();
            ConfirmationSpec lonely = peers.deleteConfirmationFor(peer);
            assertThat(lonely.body().key()).as("step 1: the named wording").isEqualTo("delete_confirm_named");
            assertThat(lonely.body().filters().get("scope")).isEqualTo("dns_peer");
            assertThat(lonely.body().args().get("name")).as("step 1: naming the peer").isEqualTo("ns2");

            // 2. Two primary zones link it as a NOTIFY/AXFR target: the dialog names them.
            int notified = zone("notified." + ORIGIN);
            int alsoNotified = zone("also." + ORIGIN);
            zonePeer(notified, peerId);
            zonePeer(alsoNotified, peerId);
            ConfirmationSpec linked = peers.deleteConfirmationFor(peer);
            assertThat(linked.body().key()).as("step 2: links switch to the linked wording")
                .isEqualTo("delete_confirm_linked");
            assertThat(String.valueOf(linked.body().args().get("zones")))
                .as("step 2: naming every zone that stops notifying the peer")
                .contains("notified." + ORIGIN)
                .contains("also." + ORIGIN);

            // 3. A secondary zone replicates from it: the delete is OFFERED and dead,
            //    naming the zone that would decay.
            Row replica = Models.get(DnsZoneModel.class).findById(zone("replica." + ORIGIN));
            replica.set(DnsZoneModel.ROLE, DnsZoneModel.ROLE_SECONDARY);
            replica.set(DnsZoneModel.PRIMARY_PEER_ID, peerId);
            Models.get(DnsZoneModel.class).save(replica);
            Microcopy replicated = peers.deleteUnavailableReason(peer, operator);
            assertThat(replicated).as("step 3: a peer a secondary replicates from is dead").isNotNull();
            assertThat(replicated.key()).isEqualTo("delete_in_use");
            assertThat(String.valueOf(replicated.args().get("zones")))
                .as("step 3: naming the secondary zone").contains("replica." + ORIGIN);

            // 4. An auth provider nothing names is deletable and its dialog speaks for
            //    itself; one gating a live site is dead naming the site.
            AuthProviderResource providers = new AuthProviderResource();
            int providerId = authProvider("Office SSO");
            Row provider = Models.get(SiteAuthProviderModel.class).findById(providerId);
            assertThat(providers.deleteUnavailableReason(provider, operator))
                .as("step 4: an unreferenced provider is deletable").isNull();
            assertThat(providers.deleteConfirmation().body().filters().get("scope"))
                .as("step 4: the provider dialog is its own, not the generic").isEqualTo("auth_provider");
            Row intranet = Models.get(SiteModel.class).findById(site("intranet", null));
            intranet.set(SiteModel.AUTH_PROVIDER_ID, providerId);
            Models.get(SiteModel.class).save(intranet);
            Microcopy gating = providers.deleteUnavailableReason(provider, operator);
            assertThat(gating).as("step 4: a provider gating a site is dead").isNotNull();
            assertThat(gating.key()).isEqualTo("delete_in_use");
            assertThat(String.valueOf(gating.args().get("sites")))
                .as("step 4: naming the site").contains("intranet");

            // 5. A trashed site no longer holds it, but an access rule naming it does --
            //    with the rules-only wording, since there is no site to name.
            intranet.set(SiteModel.DELETED_AT, Instant.now());
            Models.get(SiteModel.class).save(intranet);
            assertThat(providers.deleteUnavailableReason(provider, operator))
                .as("step 5: a trashed site releases the provider").isNull();
            providerRule(accessList("Staff"), providerId);
            Microcopy ruled = providers.deleteUnavailableReason(provider, operator);
            assertThat(ruled).as("step 5: a rule naming the provider keeps it dead").isNotNull();
            assertThat(ruled.key()).isEqualTo("delete_in_use_rules");
            assertThat(ruled.args().get("rules")).as("step 5: counting the rules").isEqualTo(1L);

            // 6. An environment variable's dialog names the key and the environment it
            //    leaves; the record-less one still says when the removal lands.
            EnvironmentVariableResource variables = new EnvironmentVariableResource();
            int environmentId = environment("production");
            Row variable = Models.get(InstanceVariableModel.class)
                .findById(environmentVariable(environmentId, "DATABASE_URL"));
            ConfirmationSpec named = variables.deleteConfirmationFor(variable);
            assertThat(named.body().key()).as("step 6: the named wording").isEqualTo("delete_confirm_named");
            assertThat(named.body().filters().get("scope")).isEqualTo("environment_variable");
            assertThat(named.body().args().get("key")).as("step 6: naming the key").isEqualTo("DATABASE_URL");
            assertThat(named.body().args().get("environment")).as("step 6: and the environment")
                .isEqualTo("production");
            assertThat(variables.deleteConfirmation().body().filters().get("scope"))
                .as("step 6: the record-less dialog is the variable's own").isEqualTo("environment_variable");

            // 7. A variable whose environment reference dangles cannot name one and keeps
            //    the type-level body rather than a sentence with a hole in it.
            variable.set(InstanceVariableModel.ENVIRONMENT_ID, null);
            assertThat(variables.deleteConfirmationFor(variable).body().key())
                .as("step 7: an unresolvable environment falls back to the type-level body")
                .isEqualTo("delete_confirm");
        });
    }

    // -- fixtures ---------------------------------------------------------------

    private static int peer(String name) {
        Row row = Models.get(DnsPeerModel.class).createEmptyRow();
        row.set(DnsPeerModel.NAME, name);
        row.set(DnsPeerModel.TRANSFER_HOST, "192.0.2.10");
        Models.get(DnsPeerModel.class).save(row);
        return row.get(DnsPeerModel.ID);
    }

    private static void zonePeer(int zoneId, int peerId) {
        Row row = Models.get(DnsZonePeerModel.class).createEmptyRow();
        row.set(DnsZonePeerModel.ZONE_ID, zoneId);
        row.set(DnsZonePeerModel.PEER_ID, peerId);
        Models.get(DnsZonePeerModel.class).save(row);
    }

    private static int authProvider(String name) {
        Row row = Models.get(SiteAuthProviderModel.class).createEmptyRow();
        row.set(SiteAuthProviderModel.NAME, name);
        row.set(SiteAuthProviderModel.PROVIDER_TYPE, BasicAuthProviderType.ID.toString());
        row.set(SiteAuthProviderModel.CONFIG, Map.of());
        Models.get(SiteAuthProviderModel.class).save(row);
        return row.get(SiteAuthProviderModel.ID);
    }

    /** A provider leaf naming one provider, switched on so its data is complete. */
    private static void providerRule(int listId, int providerId) {
        Row row = Models.get(AccessRuleModel.class).createEmptyRow();
        row.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        row.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_AUTH_PROVIDER);
        row.set(AccessRuleModel.ENABLED, true);
        row.set(AccessRuleModel.DATA, Map.of(AccessRuleModel.PROVIDER_ID.getName(), providerId));
        Models.get(AccessRuleModel.class).save(row);
    }

    private static int environment(String name) {
        Row project = Models.get(ProjectModel.class).createEmptyRow();
        project.set(ProjectModel.NAME, "delete-confirm-" + name);
        Models.get(ProjectModel.class).save(project);
        Row row = Models.get(EnvironmentModel.class).createEmptyRow();
        row.set(EnvironmentModel.PROJECT_ID, project.get(ProjectModel.ID));
        row.set(EnvironmentModel.NAME, name);
        Models.get(EnvironmentModel.class).save(row);
        return row.get(EnvironmentModel.ID);
    }

    private static int environmentVariable(int environmentId, String key) {
        Row row = Models.get(InstanceVariableModel.class).createEmptyRow();
        row.set(InstanceVariableModel.ENVIRONMENT_ID, environmentId);
        row.set(InstanceVariableModel.KEY, key);
        row.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_PLAIN);
        row.set(InstanceVariableModel.PLAIN_VALUE, "postgres://db");
        Models.get(InstanceVariableModel.class).save(row);
        return row.get(InstanceVariableModel.ID);
    }

    private static int accessList(String name) {
        Row row = Models.get(AccessListModel.class).createEmptyRow();
        row.set(AccessListModel.NAME, name);
        Models.get(AccessListModel.class).save(row);
        return row.get(AccessListModel.ID);
    }

    private static void rule(int listId) {
        Row row = Models.get(AccessRuleModel.class).createEmptyRow();
        row.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        row.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_ALLOW);
        row.set(AccessRuleModel.ENABLED, true);
        row.set(AccessRuleModel.DATA, Map.of(AccessRuleModel.NETWORK.getName(), "10.0.0.0/8"));
        Models.get(AccessRuleModel.class).save(row);
    }

    private static void protectedPath(int siteId, int listId, String path) {
        Row row = Models.get(ProtectedPathModel.class).createEmptyRow();
        row.set(ProtectedPathModel.SITE_ID, siteId);
        row.set(ProtectedPathModel.ACCESS_LIST_ID, listId);
        row.set(ProtectedPathModel.PATH, path);
        Models.get(ProtectedPathModel.class).save(row);
    }

    private static int server(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        Models.get(ServerModel.class).save(row);
        return row.get(ServerModel.ID);
    }

    private static void stack(String name, int serverId) {
        Row row = Models.get(StackModel.class).createEmptyRow();
        row.set(StackModel.NAME, name);
        row.set(StackModel.SERVER_ID, serverId);
        Models.get(StackModel.class).save(row);
    }

    private static int zone(String origin) {
        Row row = Models.get(DnsZoneModel.class).createEmptyRow();
        row.set(DnsZoneModel.ORIGIN, origin);
        row.set(DnsZoneModel.ENABLED, true);
        row.set(DnsZoneModel.DEFAULT_TTL, 3600);
        row.set(DnsZoneModel.NEGATIVE_TTL, 300);
        row.set(DnsZoneModel.SOA_REFRESH, 7200);
        row.set(DnsZoneModel.SOA_RETRY, 3600);
        row.set(DnsZoneModel.SOA_EXPIRE, 1209600);
        Models.get(DnsZoneModel.class).save(row);
        return row.get(DnsZoneModel.ID);
    }

    private static int record(int zoneId, String name) {
        Row row = Models.get(DnsRecordModel.class).createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zoneId);
        row.set(DnsRecordModel.NAME, name);
        row.set(DnsRecordModel.TYPE, "A");
        row.set(DnsRecordModel.VALUE, "203.0.113.10");
        row.set(DnsRecordModel.TTL, 300);
        row.set(DnsRecordModel.ENABLED, true);
        Models.get(DnsRecordModel.class).save(row);
        return row.get(DnsRecordModel.ID);
    }

    private static int site(String slug, String hostname) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + slug));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        Models.get(SiteModel.class).save(row);
        int siteId = row.get(SiteModel.ID);
        if (hostname != null) {
            Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
            domain.set(SiteDomainModel.SITE_ID, siteId);
            domain.set(SiteDomainModel.HOSTNAME, hostname);
            domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
            Models.get(SiteDomainModel.class).save(domain);
        }
        return siteId;
    }

    private static int certificate(String niceName, String domains) {
        Row row = Models.get(CertificateModel.class).createEmptyRow();
        row.set(CertificateModel.NICE_NAME, niceName);
        row.set(CertificateModel.DOMAIN_NAMES_TEXT, domains);
        row.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        row.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        Models.get(CertificateModel.class).save(row);
        return row.get(CertificateModel.ID);
    }
}
