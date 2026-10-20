package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseEngines;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.hohenheim.test.DnsFixtures;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.database.EngineHandles;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.test.PanelEntryViews;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.CellMapper;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.cms.common.resource.ResourceHealth;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.cms.server.render.action.RowOffer;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.routing.UrlTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.OperationPipeline.Offer;
import be.elevenways.zenit.server.operation.SubjectReadPath;
import be.elevenways.zenit.test.support.DatasourceProbe;
import be.elevenways.zenit.test.support.EndpointConduit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every reading row-action rule of the admin lists asks its read ONCE per render, whatever the number of rows and of
 * groups: the delete availabilities of DeleteImpact, Back up now and Move to shared, Get a certificate, Revoke the
 * dyndns token, Roll back a stack, Detach install media and Stop forcing HTTPS; the presentation conditions of a
 * list's row actions (the domains list's certificate coverage, Open site); and the page-batched cells (the database
 * and engine state, the HTTPS of the Domains and Apps lists, the auth providers' "used by"). Each step also asserts
 * what SHOWS, with at least one row of each answer: the offers, the refusal's key and arguments, the cells, the items.
 *
 * AIDEV-NOTE: each step offers one operation over the very batch a list render builds
 * ({@code SubjectReadPath.records(source).batch}, which {@code PanelActionOffers.rowsForRender} builds per lane) and
 * counts the prepared statements naming each table the rule reads. The subject read itself names the subject's own
 * table, which no measured rule reads, so the counts below are the rule's alone: exact, and the same for one row as
 * for a page.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class GroupedReadsQueryCountJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "grp2h-";

    @Test
    void theDatabaseAndEngineRulesReadOncePerPage() {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            // 1. One shared engine holding one database: its delete is dead, one read of the databases.
            Row engine = engine(cleanup, "e0");
            database(cleanup, "s0", engine);
            Measured one = measure(DatabaseParts.DELETE_ENGINE, Models.get(DatabaseEngineModel.class),
                DatabaseEngineModel.ID, List.of(engine), "managed_databases");
            assertThat(one.statements()).as("step 1: one engine, one read").isEqualTo(
                Map.of("managed_databases", 1));
            assertThat(one.offers()).as("step 1: a holding engine's delete is dead")
                .allMatch(Offer.Unavailable.class::isInstance);

            // 2. Four engines, three holding one to three databases (three groups), one empty: still one read.
            List<Row> engines = new ArrayList<>(List.of(engine));
            for (int i = 1; i <= 3; i++) {
                Row holding = engine(cleanup, "e" + i);
                for (int j = 0; j < i; j++) {
                    database(cleanup, "s" + i + "-" + j, holding);
                }
                engines.add(holding);
            }
            Row empty = engine(cleanup, "e-empty");
            engines.add(empty);
            Measured page = measure(DatabaseParts.DELETE_ENGINE, Models.get(DatabaseEngineModel.class),
                DatabaseEngineModel.ID, engines, "managed_databases");
            assertThat(page.statements()).as("step 2: five engines, four groups: the same one read")
                .isEqualTo(one.statements());
            assertThat(page.offers().subList(0, 4)).as("step 2: every holding engine is dead")
                .allMatch(Offer.Unavailable.class::isInstance);
            assertThat(page.offers().get(4)).as("step 2: the empty engine's delete is live")
                .isInstanceOf(Offer.Available.class);

            // 3. One dedicated database whose engine runs, used by one app: delete dead, back up and move live.
            Row shop = dedicated(cleanup, "d0");
            app(cleanup, "app0", shop);
            assertDatabaseReads("step 3", List.of(shop), List.of(true));

            // 4. Six dedicated databases, three used by apps: the same reads.
            List<Row> databases = new ArrayList<>(List.of(shop));
            List<Boolean> used = new ArrayList<>(List.of(true));
            for (int i = 1; i < 6; i++) {
                Row database = dedicated(cleanup, "d" + i);
                if (i % 2 == 0) {
                    app(cleanup, "app" + i, database);
                }
                databases.add(database);
                used.add(i % 2 == 0);
            }
            assertDatabaseReads("step 4", databases, used);
            assertThat(DeleteImpact.databaseInUse(databases.get(1))).as("step 4: an unused database is deletable")
                .isNull();

            // 5. A mixed Move to shared page: two free dedicated databases, one whose logical name a record on its
            //    host's shared engine already holds, and that shared record itself. The host's engine is the first
            //    mysql engine on the local host, the one moveRefusals finds.
            Row hostEngine = Objects.requireNonNull(Models.get(DatabaseEngineModel.class).find().noCache()
                .where(DatabaseEngineModel.SERVER_ID.eq(ServerModel.localServerId()))
                .where(DatabaseEngineModel.ENGINE.eq("mysql"))
                .orderBy(DatabaseEngineModel.ID, SortOrder.ASC).first(), "the host's shared engine");
            Row taken = dedicated(cleanup, "mvtaken");
            Row holder = database(cleanup, "mv-taken", hostEngine);
            assertThat((String) holder.get(DatabaseModel.DB_NAME)).as("step 5: the holder has the same logical name")
                .isEqualTo(taken.get(DatabaseModel.DB_NAME));
            List<Row> mixed = List.of(databases.get(1), taken, holder, databases.get(3));
            Measured moves = measure(DatabaseParts.MOVE_TO_SHARED, Models.get(DatabaseModel.class), DatabaseModel.ID,
                mixed, "managed_databases");
            assertThat(moves.offers()).as("step 5: Move to shared shows on the free databases only")
                .extracting(offer -> offer instanceof Offer.Available)
                .containsExactly(true, false, false, true);
            assertThat(moves.offers().get(1)).as("step 5: the taken name hides the move")
                .isInstanceOf(Offer.Hidden.class);
            List<Microcopy> refusals = DatabaseService.moveRefusals(mixed);
            assertThat(refusals.get(0)).as("step 5: a free database moves").isNull();
            assertThat(refusals.get(3)).as("step 5: so does the other").isNull();
            Microcopy nameTaken = Objects.requireNonNull(refusals.get(1), "step 5: the taken name is refused");
            assertThat(nameTaken.key()).as("step 5: the refusal names the taken logical name")
                .isEqualTo(HohenheimMicrocopy.VIOLATIONS.of("database_logical_name_taken").key());
            assertThat(argsOf(nameTaken, "database", "engine", "record")).as("step 5: its arguments")
                .containsExactly(String.valueOf((Object) taken.get(DatabaseModel.DB_NAME)),
                    String.valueOf((Object) hostEngine.get(DatabaseEngineModel.NAME)), PREFIX + "mv-taken");
            Microcopy shared = Objects.requireNonNull(refusals.get(2), "step 5: the shared record is refused");
            assertThat(shared.key()).as("step 5: a shared record is already shared")
                .isEqualTo(HohenheimMicrocopy.VIOLATIONS.of("database_already_shared").key());
            assertThat(argsOf(shared, "name")).as("step 5: naming itself").containsExactly(PREFIX + "mv-taken");
        } finally {
            runAll(cleanup);
        }
    }

    @Test
    void theEnvironmentImageAndPeerDeletesReadOncePerPage() {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            Row project = row(cleanup, Models.get(ProjectModel.class), Map.of(ProjectModel.NAME.getName(),
                PREFIX + "project"));

            // 1. One environment holding one variable: dead, one read per holding table.
            List<Row> environments = new ArrayList<>();
            environments.add(environment(cleanup, project, "env0", 1));
            Map<String, Integer> environmentReads = Map.of("instances", 1, "instance_variables", 1);
            Measured one = measure(EnvironmentParts.DELETE, Models.get(EnvironmentModel.class), EnvironmentModel.ID,
                environments, "instances", "instance_variables");
            assertThat(one.statements()).as("step 1: one environment").isEqualTo(environmentReads);
            assertThat(one.offers()).as("step 1: a held environment's delete is dead")
                .allMatch(Offer.Unavailable.class::isInstance);

            // 2. Five environments holding zero to four variables: the same reads.
            for (int i = 1; i < 5; i++) {
                environments.add(environment(cleanup, project, "env" + i, i - 1));
            }
            Measured page = measure(EnvironmentParts.DELETE, Models.get(EnvironmentModel.class), EnvironmentModel.ID,
                environments, "instances", "instance_variables");
            assertThat(page.statements()).as("step 2: five environments").isEqualTo(environmentReads);
            assertThat(page.offers().get(1)).as("step 2: the empty environment's delete is live")
                .isInstanceOf(Offer.Available.class);

            // 3. Runtime images, one then five: one grouped count per counted table.
            List<Row> images = new ArrayList<>();
            images.add(image(cleanup, "img0"));
            Map<String, Integer> imageReads = Map.of("instances", 1, "instance_templates", 1);
            assertThat(measure(RuntimeImageParts.DELETE, Models.get(RuntimeImageModel.class), RuntimeImageModel.ID,
                images, "instances", "instance_templates").statements()).as("step 3: one image")
                .isEqualTo(imageReads);
            for (int i = 1; i < 5; i++) {
                images.add(image(cleanup, "img" + i));
            }
            Measured imagePage = measure(RuntimeImageParts.DELETE, Models.get(RuntimeImageModel.class),
                RuntimeImageModel.ID, images, "instances", "instance_templates");
            assertThat(imagePage.statements()).as("step 3: five images").isEqualTo(imageReads);
            assertThat(imagePage.offers()).as("step 3: unused images are deletable")
                .allMatch(Offer.Available.class::isInstance);

            // 4. DNS peers, one then four, the first a secondary zone replicates from: one read of the zones; the
            //    replicated peer's delete is dead naming that zone, the others' live.
            List<Row> peers = new ArrayList<>();
            peers.add(peer(cleanup, "peer0"));
            String replica = PREFIX + "replica.test";
            int replicaId = DnsFixtures.createZone(replica, DnsZoneModel.ROLE_SECONDARY,
                peers.get(0).get(DnsPeerModel.ID));
            cleanup.add(() -> HardDeletes.byId(Models.get(DnsZoneModel.class), replicaId));
            Measured onePeer = measure(DnsOperations.DELETE_PEER, Models.get(DnsPeerModel.class), DnsPeerModel.ID,
                peers, "dns_zones");
            assertThat(onePeer.statements()).as("step 4: one peer").isEqualTo(Map.of("dns_zones", 1));
            assertThat(onePeer.offers()).as("step 4: a replicated peer's delete is dead")
                .allMatch(Offer.Unavailable.class::isInstance);
            for (int i = 1; i < 4; i++) {
                peers.add(peer(cleanup, "peer" + i));
            }
            Measured fourPeers = measure(DnsOperations.DELETE_PEER, Models.get(DnsPeerModel.class), DnsPeerModel.ID,
                peers, "dns_zones");
            assertThat(fourPeers.statements()).as("step 4: four peers").isEqualTo(Map.of("dns_zones", 1));
            assertThat(fourPeers.offers()).as("step 4: only the replicated peer's delete is dead")
                .extracting(offer -> offer instanceof Offer.Available)
                .containsExactly(false, true, true, true);
            Microcopy inUse = ((Offer.Unavailable) fourPeers.offers().get(0)).reason();
            assertThat(inUse.key()).as("step 4: in the peer's own words")
                .isEqualTo(HohenheimMicrocopy.DNS_PEER.of("delete_in_use").key());
            assertThat(argsOf(inUse, "zones").get(0)).as("step 4: naming the zone").contains(replica);
        } finally {
            runAll(cleanup);
        }
    }

    @Test
    void theServerAndAuthProviderDeletesReadOncePerPage() {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            Map<String, Integer> serverReads = Map.of("stacks", 1, "managed_databases", 1, "database_engines", 1,
                "instances", 2, "port_allocations", 1);
            String[] serverTables = serverReads.keySet().toArray(String[]::new);

            // 1. One remote host carrying one stack: dead, one grouped count per referencing table plus the
            //    migration read.
            List<Row> servers = new ArrayList<>();
            servers.add(server(cleanup, "host0", 1));
            Measured one = measure(ServerParts.DELETE, Models.get(ServerModel.class), ServerModel.ID, servers,
                serverTables);
            assertThat(one.statements()).as("step 1: one host").isEqualTo(serverReads);
            assertThat(one.offers()).as("step 1: a host a stack names is dead")
                .allMatch(Offer.Unavailable.class::isInstance);

            // 2. Four hosts carrying zero to three stacks: the same reads.
            for (int i = 1; i < 4; i++) {
                servers.add(server(cleanup, "host" + i, i - 1));
            }
            Measured page = measure(ServerParts.DELETE, Models.get(ServerModel.class), ServerModel.ID, servers,
                serverTables);
            assertThat(page.statements()).as("step 2: four hosts").isEqualTo(serverReads);
            assertThat(page.offers().get(1)).as("step 2: an empty host is deletable")
                .isInstanceOf(Offer.Available.class);

            // 3. Auth providers, one gating one site, then four gating zero to three: one read of the sites, one of
            //    the provider rules.
            Map<String, Integer> providerReads = Map.of("sites", 1, "access_rules", 1);
            List<Row> providers = new ArrayList<>();
            providers.add(provider(cleanup, "auth0", 1));
            Measured gating = measure(AuthProviderParts.DELETE, Models.get(SiteAuthProviderModel.class),
                SiteAuthProviderModel.ID, providers, "sites", "access_rules");
            assertThat(gating.statements()).as("step 3: one provider").isEqualTo(providerReads);
            assertThat(gating.offers()).as("step 3: a gating provider is dead")
                .allMatch(Offer.Unavailable.class::isInstance);
            for (int i = 1; i < 4; i++) {
                providers.add(provider(cleanup, "auth" + i, i - 1));
            }
            Measured providerPage = measure(AuthProviderParts.DELETE, Models.get(SiteAuthProviderModel.class),
                SiteAuthProviderModel.ID, providers, "sites", "access_rules");
            assertThat(providerPage.statements()).as("step 3: four providers").isEqualTo(providerReads);
            assertThat(providerPage.offers().get(1)).as("step 3: a provider nothing names is deletable")
                .isInstanceOf(Offer.Available.class);

            // 4. The providers list's "used by" cells read the delete refusal's own batch: one read of the sites and
            //    one of the rules for one provider as for four, each cell counting what uses it.
            Cells oneUse = cells(HohenheimSlugs.AUTH_PROVIDERS, "used_by", providers.subList(0, 1), "sites",
                "access_rules");
            assertThat(oneUse.statements()).as("step 4: one provider row").isEqualTo(providerReads);
            Cells fourUses = cells(HohenheimSlugs.AUTH_PROVIDERS, "used_by", providers, "sites", "access_rules");
            assertThat(fourUses.statements()).as("step 4: four provider rows, the same reads")
                .isEqualTo(providerReads);
            assertThat(fourUses.cells()).as("step 4: each counts the sites it gates")
                .extracting(cell -> ((Microcopy) cell).key() + "/" + ((Microcopy) cell).args().get("count"))
                .containsExactly(HohenheimMicrocopy.AUTH_PROVIDER.of("used_by_count").key() + "/1",
                    HohenheimMicrocopy.AUTH_PROVIDER.of("used_by_nothing").key() + "/null",
                    HohenheimMicrocopy.AUTH_PROVIDER.of("used_by_count").key() + "/1",
                    HohenheimMicrocopy.AUTH_PROVIDER.of("used_by_count").key() + "/2");
        } finally {
            runAll(cleanup);
        }
    }

    @Test
    void theApplicabilityReadsAskOncePerPage() {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            // 1. Dyndns: one zone's address records, one then five, two of them dynamic: one credential count.
            Row zone = row(cleanup, Models.get(DnsZoneModel.class), Map.of(DnsZoneModel.ORIGIN.getName(),
                PREFIX + "zone.test.", DnsZoneModel.ROLE.getName(), DnsZoneModel.ROLE_PRIMARY,
                DnsZoneModel.ENABLED.getName(), true));
            List<Row> records = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Row record = row(cleanup, Models.get(DnsRecordModel.class), Map.of(
                    DnsRecordModel.ZONE_ID.getName(), zone.get(DnsZoneModel.ID),
                    DnsRecordModel.NAME.getName(), "host" + i, DnsRecordModel.TYPE.getName(), "A",
                    DnsRecordModel.VALUE.getName(), "203.0.113." + (10 + i), DnsRecordModel.TTL.getName(), 300,
                    DnsRecordModel.ENABLED.getName(), true));
                if (i % 2 == 0) {
                    DynamicDnsService.mintFor(record.get(DnsRecordModel.ID));
                }
                records.add(record);
            }
            cleanup.add(() -> records.forEach(record -> DynamicDnsService.revokeFor(record.get(DnsRecordModel.ID))));
            Map<String, Integer> credentialReads = Map.of("dns_dyndns_credentials", 1);
            assertThat(measure(DnsOperations.REVOKE_DYNAMIC_TOKEN, Models.get(DnsRecordModel.class),
                DnsRecordModel.ID, records.subList(0, 1), "dns_dyndns_credentials").statements())
                .as("step 1: one record").isEqualTo(credentialReads);
            Measured dyndns = measure(DnsOperations.REVOKE_DYNAMIC_TOKEN, Models.get(DnsRecordModel.class),
                DnsRecordModel.ID, records, "dns_dyndns_credentials");
            assertThat(dyndns.statements()).as("step 1: five records").isEqualTo(credentialReads);
            assertThat(dyndns.offers()).as("step 1: Revoke shows exactly on the dynamic records")
                .extracting(offer -> offer instanceof Offer.Available)
                .containsExactly(true, false, true, false, true);

            // 2. Stacks, one then four, two holding a rollback target: one grouped count of the deployments.
            List<Row> stacks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Row stack = row(cleanup, Models.get(StackModel.class), Map.of(StackModel.NAME.getName(),
                    PREFIX + "stack" + i, StackModel.SERVER_ID.getName(), ServerModel.localServerId(),
                    StackModel.STATUS.getName(), StackModel.STATUS_ACTIVE, StackModel.ENABLED.getName(), false));
                if (i % 2 == 0) {
                    row(cleanup, Models.get(StackDeploymentModel.class), Map.of(
                        StackDeploymentModel.STACK_ID.getName(), stack.get(StackModel.ID),
                        StackDeploymentModel.STATUS.getName(), "success",
                        StackDeploymentModel.SPEC.getName(), "{}"));
                }
                stacks.add(stack);
            }
            Map<String, Integer> deploymentReads = Map.of("stack_deployments", 1);
            assertThat(measure(StackOperations.ROLLBACK, Models.get(StackModel.class), StackModel.ID,
                stacks.subList(0, 1), "stack_deployments").statements()).as("step 2: one stack")
                .isEqualTo(deploymentReads);
            Measured rollback = measure(StackOperations.ROLLBACK, Models.get(StackModel.class), StackModel.ID, stacks,
                "stack_deployments");
            assertThat(rollback.statements()).as("step 2: four stacks").isEqualTo(deploymentReads);
            assertThat(rollback.offers()).as("step 2: Roll back shows exactly on the stacks with a target")
                .extracting(offer -> offer instanceof Offer.Available)
                .containsExactly(true, false, true, false);

            // 3. Sites forcing HTTPS on a name no certificate covers, one then four: one read of their names for
            //    Stop forcing HTTPS, one read of the sites for each domain's Get a certificate.
            List<Row> sites = new ArrayList<>();
            List<Row> domains = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Row site = site(cleanup, "site" + i, null);
                sites.add(site);
                domains.add(row(cleanup, Models.get(SiteDomainModel.class), Map.of(
                    SiteDomainModel.SITE_ID.getName(), site.get(SiteModel.ID),
                    SiteDomainModel.HOSTNAME.getName(), PREFIX + "site" + i + ".example.test",
                    SiteDomainModel.MATCH_TYPE.getName(), SiteDomainModel.MATCH_EXACT,
                    SiteDomainModel.FORCE_SSL.getName(), true)));
            }
            Map<String, Integer> nameReads = Map.of("site_domains", 1);
            assertThat(measure(SiteOperations.STOP_FORCING_HTTPS, Models.get(SiteModel.class), SiteModel.ID,
                sites.subList(0, 1), "site_domains").statements()).as("step 3: one site")
                .isEqualTo(nameReads);
            Measured forcing = measure(SiteOperations.STOP_FORCING_HTTPS, Models.get(SiteModel.class), SiteModel.ID,
                sites, "site_domains");
            assertThat(forcing.statements()).as("step 3: four sites").isEqualTo(nameReads);
            assertThat(forcing.offers()).as("step 3: every site forcing an uncovered name offers the way back")
                .allMatch(Offer.Available.class::isInstance);
            // Get a certificate: one exact name per site, plus one name excluded from Let's Encrypt (another server
            // holds its certificate), which is never offered and never read.
            List<Row> certifiable = new ArrayList<>(domains);
            certifiable.add(row(cleanup, Models.get(SiteDomainModel.class), Map.of(
                SiteDomainModel.SITE_ID.getName(), sites.get(0).get(SiteModel.ID),
                SiteDomainModel.HOSTNAME.getName(), PREFIX + "elsewhere.example.test",
                SiteDomainModel.MATCH_TYPE.getName(), SiteDomainModel.MATCH_EXACT,
                SiteDomainModel.FORCE_SSL.getName(), false,
                SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName(), true)));
            // And one exact name of a TLS passthrough site, whose backend holds its own certificate: never offered.
            Row passthrough = row(cleanup, Models.get(SiteModel.class), new LinkedHashMap<>(Map.of(
                SiteModel.NAME.getName(), PREFIX + "passthrough", SiteModel.SLUG.getName(), PREFIX + "passthrough",
                SiteModel.UPSTREAM_KIND.getName(), SiteModel.UPSTREAM_TLS_PASSTHROUGH,
                SiteModel.SETTINGS.getName(), Map.of("forward_host", "127.0.0.1", "forward_port", 8443),
                SiteModel.ENABLED.getName(), true)));
            List<Row> requestable = new ArrayList<>(certifiable);
            requestable.add(address(cleanup, passthrough, "passthrough.example.test"));
            Map<String, Integer> siteReads = Map.of("sites", 1);
            Measured oneDomain = measure(CertificateOperations.REQUEST_FOR_DOMAIN, Models.get(SiteDomainModel.class),
                SiteDomainModel.ID, certifiable.subList(0, 1), "sites");
            assertThat(oneDomain.statements()).as("step 3: one domain").isEqualTo(siteReads);
            assertThat(oneDomain.offers()).as("step 3: an uncovered exact name is offered Get a certificate")
                .noneMatch(Offer.Hidden.class::isInstance);
            Measured sixDomains = measure(CertificateOperations.REQUEST_FOR_DOMAIN,
                Models.get(SiteDomainModel.class), SiteDomainModel.ID, requestable, "sites");
            assertThat(sixDomains.statements()).as("step 3: six domains").isEqualTo(siteReads);
            assertThat(sixDomains.offers()).as("step 3: offered on every name but the excluded and passthrough ones")
                .extracting(offer -> offer instanceof Offer.Hidden)
                .containsExactly(false, false, false, false, true, true);

            // 3b. The domains list hides Get a certificate on a name a working certificate covers: the working
            //     names are read once per render, for one domain as for five, and the action is drawn on every
            //     uncovered name and not on the covered one.
            Row covered = row(cleanup, Models.get(SiteDomainModel.class), Map.of(
                SiteDomainModel.SITE_ID.getName(), sites.get(1).get(SiteModel.ID),
                SiteDomainModel.HOSTNAME.getName(), PREFIX + "covered.example.test",
                SiteDomainModel.MATCH_TYPE.getName(), SiteDomainModel.MATCH_EXACT,
                SiteDomainModel.FORCE_SSL.getName(), false));
            Row covering = row(cleanup, Models.get(CertificateModel.class), Map.of(CertificateModel.NICE_NAME.getName(),
                PREFIX + "covering", CertificateModel.PROVIDER.getName(), CertificateModel.PROVIDER_CUSTOM,
                CertificateModel.STATUS.getName(), CertificateModel.STATUS_ACTIVE,
                CertificateModel.DOMAIN_NAMES_TEXT.getName(), PREFIX + "covered.example.test"));
            List<Row> listed = new ArrayList<>(domains);
            listed.add(covered);
            Map<String, Integer> coverageReads = Map.of("certificates", 1);
            Rendered oneRow = render(HohenheimSlugs.DOMAINS, domains.subList(0, 1), "certificates");
            assertThat(oneRow.statements()).as("step 3b: one domain row").isEqualTo(coverageReads);
            assertThat(oneRow.drawn(CertificateOperations.REQUEST_FOR_DOMAIN))
                .as("step 3b: drawn on the uncovered name")
                .containsExactly(true);
            Rendered fiveRows = render(HohenheimSlugs.DOMAINS, listed, "certificates");
            assertThat(fiveRows.statements()).as("step 3b: five domain rows").isEqualTo(coverageReads);
            assertThat(fiveRows.drawn(CertificateOperations.REQUEST_FOR_DOMAIN))
                .as("step 3b: drawn on every uncovered name, never on the covered one")
                .containsExactly(true, true, true, true, false);

            // 3c. The domains list's HTTPS cells: the sites, the working names and the certificate rows are read once
            //     for the page, for one row as for six; a forced uncovered name is broken, an unforced one says
            //     another server holds its certificate, and the covered one works and links its certificate.
            Map<String, Integer> cellReads = Map.of("sites", 1, "certificates", 2);
            assertThat(cells(HohenheimSlugs.DOMAINS, "certificate", certifiable.subList(0, 1), "sites",
                "certificates").statements()).as("step 3c: one domain row").isEqualTo(cellReads);
            List<Row> httpsPage = new ArrayList<>(certifiable);
            httpsPage.add(covered);
            Cells https = cells(HohenheimSlugs.DOMAINS, "certificate", httpsPage, "sites", "certificates");
            assertThat(https.statements()).as("step 3c: six domain rows, the same reads").isEqualTo(cellReads);
            assertThat(https.cells()).as("step 3c: what each row's HTTPS shows")
                .extracting(cell -> ((StateLineCell) cell).state())
                .containsExactly(CertCoverage.ERROR.token(), CertCoverage.ERROR.token(), CertCoverage.ERROR.token(),
                    CertCoverage.ERROR.token(), CertCoverage.NONE.token(), CertCoverage.ACTIVE.token());
            assertThat(((StateLineCell) https.cells().get(4)).detail()).as("step 3c: and why")
                .extracting(Microcopy::key)
                .isEqualTo(HohenheimMicrocopy.SITE_DOMAINS.of("https_uncovered_excluded").key());
            StateLineCell coveredCell = (StateLineCell) https.cells().get(5);
            assertThat(coveredCell.link()).as("step 3c: the covered name names its certificate")
                .isEqualTo(PREFIX + "covering");
            assertThat(coveredCell.url()).as("step 3c: and links it")
                .endsWith("/" + HohenheimSlugs.CERTIFICATES + "/" + covering.get(CertificateModel.ID));

            // 4. Instances, one then four, the first with an install medium in its drive: one read of their drives
            //    for Detach install media, which shows on that one only.
            List<Row> instances = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                instances.add(instance(cleanup, "vm" + i));
            }
            row(cleanup, Models.get(InstanceDeviceModel.class), Map.of(
                InstanceDeviceModel.INSTANCE_ID.getName(), instances.get(0).get(InstanceModel.ID),
                InstanceDeviceModel.TYPE.getName(), InstanceDeviceModel.TYPE_CDROM,
                InstanceDeviceModel.NAME.getName(), "install0",
                InstanceDeviceModel.SOURCE_MEDIA.getName(), PREFIX + "installer.iso"));
            Map<String, Integer> driveReads = Map.of("instance_devices", 1);
            Measured oneDrive = measure(InstanceOperations.DETACH_INSTALL_MEDIA, Models.get(InstanceModel.class),
                InstanceModel.ID, instances.subList(0, 1), "instance_devices");
            assertThat(oneDrive.statements()).as("step 4: one instance").isEqualTo(driveReads);
            assertThat(oneDrive.offers()).as("step 4: a drive holding a medium offers Detach")
                .allMatch(Offer.Available.class::isInstance);
            Measured drives = measure(InstanceOperations.DETACH_INSTALL_MEDIA, Models.get(InstanceModel.class),
                InstanceModel.ID, instances, "instance_devices");
            assertThat(drives.statements()).as("step 4: four instances").isEqualTo(driveReads);
            assertThat(drives.offers()).as("step 4: Detach shows exactly where a drive holds a medium")
                .extracting(offer -> offer instanceof Offer.Available)
                .containsExactly(true, false, false, false);
        } finally {
            runAll(cleanup);
        }
    }

    @Test
    void theOpenSiteLinkAndTheDatabaseVerdictsReadOncePerPage() {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            // 1. Websites visitors reach (switched on, one exact address each, no certificate), one then four, and a
            //    fifth forcing HTTPS on its only name, which no certificate covers: the Open site link reads their
            //    addresses, their protected paths, the working certificate names and the certificate rows once, the
            //    batched read alone decides that the fifth reaches nothing, and each drawn row's target is its own
            //    address, read from that same read.
            List<Row> sites = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Row site = site(cleanup, "open" + i, null);
                address(cleanup, site, "open" + i + ".example.test");
                sites.add(site);
            }
            Row forced = site(cleanup, "open-forced", null);
            row(cleanup, Models.get(SiteDomainModel.class), Map.of(
                SiteDomainModel.SITE_ID.getName(), forced.get(SiteModel.ID),
                SiteDomainModel.HOSTNAME.getName(), PREFIX + "open-forced.example.test",
                SiteDomainModel.MATCH_TYPE.getName(), SiteDomainModel.MATCH_EXACT,
                SiteDomainModel.FORCE_SSL.getName(), true));
            sites.add(forced);
            PanelAction<Row> openSite = SiteActions.operator().stream()
                .filter(action -> action.id().equals(SiteActions.OPEN_SITE)).findFirst().orElseThrow();
            Map<String, Integer> siteReads = Map.of("site_domains", 1, "protected_paths", 1, "certificates", 2);
            Drawn oneSite = draw(openSite, sites.subList(0, 1), "site_domains", "protected_paths", "certificates");
            assertThat(oneSite.statements()).as("step 1: one site").isEqualTo(siteReads);
            assertThat(oneSite.targets()).as("step 1: it opens its own address")
                .containsExactly("http://" + PREFIX + "open0.example.test");
            Drawn fiveSites = draw(openSite, sites, "site_domains", "protected_paths", "certificates");
            assertThat(fiveSites.statements()).as("step 1: five sites, the same reads").isEqualTo(siteReads);
            assertThat(fiveSites.shown()).as("step 1: every reached site offers Open site, the forced one does not")
                .containsExactly(true, true, true, true, false);
            assertThat(fiveSites.targets()).as("step 1: each drawn row opens its own address")
                .containsExactly("http://" + PREFIX + "open0.example.test", "http://" + PREFIX + "open1.example.test",
                    "http://" + PREFIX + "open2.example.test", "http://" + PREFIX + "open3.example.test", null);

            // 2. Instances, each served by a site with an address, one then four, only the first running on a host
            //    that takes it: the instances list's Open site reads the sites serving them, their addresses and their
            //    paths once, and offers itself on the running one only, since a stopped app serves no visitor.
            List<Row> instances = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Row instance = instance(cleanup, "served" + i);
                Map<String, Object> values = new LinkedHashMap<>(Map.of(SiteModel.NAME.getName(),
                    PREFIX + "served-site" + i, SiteModel.SLUG.getName(), PREFIX + "served-site" + i,
                    SiteModel.UPSTREAM_KIND.getName(), "hohenheim:instance", SiteModel.SETTINGS.getName(), Map.of(),
                    SiteModel.ENABLED.getName(), true, SiteModel.INSTANCE_ID.getName(), instance.get(InstanceModel.ID)));
                address(cleanup, row(cleanup, Models.get(SiteModel.class), values), "served" + i + ".example.test");
                instances.add(instance);
            }
            HostFixtures.LocalHostState local = HostFixtures.captureLocal();
            cleanup.add(local::restore);
            HostFixtures.makeLocalPlaceable(16L * 1024);
            Row runningApp = instances.get(0);
            runningApp.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
            Models.get(InstanceModel.class).save(runningApp);
            PanelAction<Row> openInstance = InstanceActions.placedOperator().stream()
                .filter(action -> action.id().equals(InstanceActions.OPEN_SITE)).findFirst().orElseThrow();
            Map<String, Integer> instanceReads = Map.of("sites", 1, "site_domains", 1, "protected_paths", 1);
            Drawn oneInstance = draw(openInstance, instances.subList(0, 1), "sites", "site_domains",
                "protected_paths");
            assertThat(oneInstance.statements()).as("step 2: one instance").isEqualTo(instanceReads);
            assertThat(oneInstance.shown()).as("step 2: a running, served app offers Open site")
                .containsExactly(true);
            assertThat(oneInstance.targets()).as("step 2: it opens the address of the site serving it")
                .containsExactly("http://" + PREFIX + "served0.example.test");
            Drawn fourInstances = draw(openInstance, instances, "sites", "site_domains", "protected_paths");
            assertThat(fourInstances.statements()).as("step 2: four instances, the same reads")
                .isEqualTo(instanceReads);
            assertThat(fourInstances.shown()).as("step 2: only the running app offers Open site")
                .containsExactly(true, false, false, false);
            assertThat(fourInstances.targets()).as("step 2: the running app's own address, nothing elsewhere")
                .containsExactly("http://" + PREFIX + "served0.example.test", null, null, null);
            // The running app and the host's capacity go back before the database steps plant engine instances.
            runningApp.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
            Models.get(InstanceModel.class).save(runningApp);
            local.restore();

            // 3. The databases list's health cells over one running dedicated database, then three running and one
            //    stopped: the serving instances, then the records' hosts and those instances' hosts together, are
            //    read once for the page, and each glyph says what its engine does.
            List<Row> page = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                page.add(dedicated(cleanup, "cell" + i));
            }
            page.add(dedicated(cleanup, "cell3", InstanceModel.STATUS_STOPPED));
            Map<String, Integer> cellReads = Map.of("instances", 1, "servers", 1);
            String running = DatabaseVerdict.State.RUNNING.tone().token();
            String notRunning = DatabaseVerdict.State.NOT_RUNNING.tone().token();
            Cells oneCell = healthTones(HohenheimSlugs.DATABASES, page.subList(0, 1), "instances", "servers");
            assertThat(oneCell.statements()).as("step 3: one database row").isEqualTo(cellReads);
            assertThat(oneCell.cells()).as("step 3: its engine runs").containsExactly(running);
            Cells fourCells = healthTones(HohenheimSlugs.DATABASES, page, "instances", "servers");
            assertThat(fourCells.statements()).as("step 3: four database rows, the same reads").isEqualTo(cellReads);
            assertThat(fourCells.cells()).as("step 3: the stopped engine's row says so")
                .containsExactly(running, running, running, notRunning);

            // 3b. A record whose host no longer resolves (its engine token unknown) has no owner, and neither has a
            //     shared record naming no engine; judged alone or in a page: no engine, though an instance the first
            //     once owned still exists.
            int oddId = dedicated(cleanup, "cell-odd").get(DatabaseModel.ID);
            Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.eq(oddId))
                .assign(DatabaseModel.ENGINE, "grp2h-unknown").updateAll();
            Row odd = Objects.requireNonNull(Models.get(DatabaseModel.class).find().noCache()
                .where(DatabaseModel.ID.eq(oddId)).first());
            int orphanId = database(cleanup, "cell-orphan", engine(cleanup, "cell-engine")).get(DatabaseModel.ID);
            Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.eq(orphanId))
                .assign(DatabaseModel.ENGINE_ID, (Integer) null).updateAll();
            Row orphan = Objects.requireNonNull(Models.get(DatabaseModel.class).find().noCache()
                .where(DatabaseModel.ID.eq(orphanId)).first());
            assertThat(DatabaseInstances.owned(oddId)).as("step 3b: nothing serves the odd record").isNull();
            assertThat(DatabaseInstances.owned(orphanId)).as("step 3b: nor the orphan").isNull();
            List<DatabaseVerdict> judged = List.of(DatabaseVerdict.ofDatabase(odd), DatabaseVerdict.ofDatabase(orphan),
                DatabaseVerdict.ofDatabases(List.of(page.get(0), odd, orphan)).apply(odd),
                DatabaseVerdict.ofDatabases(List.of(page.get(0), odd, orphan)).apply(orphan));
            for (DatabaseVerdict verdict : judged) {
                assertThat(verdict.state()).as("step 3b: it does not run").isEqualTo(DatabaseVerdict.State.NOT_RUNNING);
                assertThat(Objects.requireNonNull(verdict.reason()).key()).as("step 3b: for want of an engine")
                    .isEqualTo(HohenheimMicrocopy.ATTENTION_DETAIL.of("database_no_engine").key());
            }

            // 3c. A shared engine whose own instance is planted running, with a record on it, whose engine token
            //     becomes unknown: its placement does not resolve, so nothing serves the engine nor the record on it:
            //     no engine, alone and in a page beside a running one, for the record and for the engine itself.
            Row oddEngine = engine(cleanup, "cell-odd-engine");
            int oddEngineId = oddEngine.get(DatabaseEngineModel.ID);
            EngineHandles.plantEngine(oddEngineId, PREFIX + "cell-odd-engine", "mysql", InstanceModel.STATUS_RUNNING);
            Row oddPlanted = Objects.requireNonNull(
                OwnedInstances.soleOwnedBy(DatabaseEngineModel.MODEL_ID, oddEngineId));
            cleanup.add(() -> OwnedInstances.inScopeUnchecked(DatabaseEngines.SOURCE, DatabaseEngineModel.MODEL_ID,
                oddEngineId, () -> HardDeletes.row(Models.get(InstanceModel.class), oddPlanted)));
            int onOddId = database(cleanup, "cell-on-odd", oddEngine).get(DatabaseModel.ID);
            Models.get(DatabaseEngineModel.class).find().where(DatabaseEngineModel.ID.eq(oddEngineId))
                .assign(DatabaseEngineModel.ENGINE, "grp2h-unknown").updateAll();
            Row onOdd = Objects.requireNonNull(Models.get(DatabaseModel.class).find().noCache()
                .where(DatabaseModel.ID.eq(onOddId)).first());
            Row oddEngineRow = Objects.requireNonNull(Models.get(DatabaseEngineModel.class).find().noCache()
                .where(DatabaseEngineModel.ID.eq(oddEngineId)).first());
            assertThat(DatabaseInstances.owned(onOddId)).as("step 3c: nothing serves the record on the odd engine")
                .isNull();
            Function<Row, DatabaseVerdict> oddPage = DatabaseVerdict.ofDatabases(List.of(page.get(0), onOdd));
            assertThat(oddPage.apply(page.get(0)).state()).as("step 3c: the running record beside it runs")
                .isEqualTo(DatabaseVerdict.State.RUNNING);
            List<DatabaseVerdict> oddJudged = List.of(DatabaseVerdict.ofDatabase(onOdd), oddPage.apply(onOdd),
                DatabaseVerdict.ofEngine(oddEngineRow),
                DatabaseVerdict.ofEngines(List.of(oddEngineRow)).apply(oddEngineRow));
            for (DatabaseVerdict verdict : oddJudged) {
                assertThat(verdict.state()).as("step 3c: it does not run").isEqualTo(DatabaseVerdict.State.NOT_RUNNING);
                assertThat(Objects.requireNonNull(verdict.reason()).key()).as("step 3c: for want of an engine")
                    .isEqualTo(HohenheimMicrocopy.ATTENTION_DETAIL.of("database_no_engine").key());
            }

            // 4. The dashboard's database items over one stopped dedicated and one engine-less shared database, each
            //    used by an app (beside a running one an app uses), then four of each: every database and every link
            //    is judged in the same reads, each down database is one root item in its verdict's words, and each
            //    app it holds back is an item caused by it.
            String[] tables = {"instance_databases", "instances", "managed_databases", "database_engines", "servers"};
            Row engine = engine(cleanup, "attention-engine");
            Row up = dedicated(cleanup, "up-d0");
            app(cleanup, "up-app-d0", up);
            List<Row> stopped = new ArrayList<>(List.of(dedicated(cleanup, "down-d0", InstanceModel.STATUS_STOPPED)));
            List<Row> engineless = new ArrayList<>(List.of(database(cleanup, "down-s0", engine)));
            app(cleanup, "down-app-d0", stopped.get(0));
            app(cleanup, "down-app-s0", engineless.get(0));
            Map<String, Integer> attentionReads = Map.of("instance_databases", 2, "instances", 7,
                "managed_databases", 4, "database_engines", 3, "servers", 2);
            Attention two = attention(tables);
            assertThat(two.statements()).as("step 4: two databases down").isEqualTo(attentionReads);
            assertDatabaseItems("step 4", two.items(), up, stopped, engineless);
            for (int i = 1; i < 4; i++) {
                stopped.add(dedicated(cleanup, "down-d" + i, InstanceModel.STATUS_STOPPED));
                engineless.add(database(cleanup, "down-s" + i, engine));
                app(cleanup, "down-app-d" + i, stopped.get(i));
                app(cleanup, "down-app-s" + i, engineless.get(i));
            }
            Attention eight = attention(tables);
            assertThat(eight.statements()).as("step 4: eight databases down, the same reads")
                .isEqualTo(attentionReads);
            assertDatabaseItems("step 4", eight.items(), up, stopped, engineless);
        } finally {
            runAll(cleanup);
        }
    }

    @Test
    void theCertificateAndEngineCellsReadOncePerPage() {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            // 1. The Apps list's HTTPS column over one website with an exact address no certificate covers, then
            //    four, then a fifth whose main name a working certificate covers: the certificate rows are read once
            //    for the whole list (its column and its verdicts share them), never once per main name.
            List<Row> sites = new ArrayList<>(List.of(site(cleanup, "https0", null)));
            address(cleanup, sites.get(0), "https0.example.test");
            Apps oneApp = apps(sites, "certificates");
            assertThat(oneApp.https()).as("step 1: its main name has no certificate")
                .extracting(StateLineCell::state).containsExactly(CertCoverage.NONE.token());
            for (int i = 1; i < 4; i++) {
                Row site = site(cleanup, "https" + i, null);
                address(cleanup, site, "https" + i + ".example.test");
                sites.add(site);
            }
            Row coveredSite = site(cleanup, "https-covered", null);
            address(cleanup, coveredSite, "https-covered.example.test");
            Row covering = row(cleanup, Models.get(CertificateModel.class), Map.of(
                CertificateModel.NICE_NAME.getName(), PREFIX + "https-covering",
                CertificateModel.PROVIDER.getName(), CertificateModel.PROVIDER_CUSTOM,
                CertificateModel.STATUS.getName(), CertificateModel.STATUS_ACTIVE,
                CertificateModel.DOMAIN_NAMES_TEXT.getName(), PREFIX + "https-covered.example.test"));
            sites.add(coveredSite);
            Apps fiveApps = apps(sites, "certificates");
            assertThat(fiveApps.statements()).as("step 1: four uncovered main names and a covered one, the same reads")
                .isEqualTo(oneApp.statements());
            assertThat(fiveApps.https()).as("step 1: the uncovered names have no certificate, the covered one works")
                .extracting(StateLineCell::state)
                .containsExactly(CertCoverage.NONE.token(), CertCoverage.NONE.token(), CertCoverage.NONE.token(),
                    CertCoverage.NONE.token(), CertCoverage.ACTIVE.token());
            StateLineCell coveredCell = fiveApps.https().get(4);
            assertThat(coveredCell.link()).as("step 1: the covered name names its certificate")
                .isEqualTo(PREFIX + "https-covering");
            assertThat(coveredCell.url()).as("step 1: and links it").isEqualTo(CmsRoutes.detail(HohenheimSlugs.ADMIN,
                HohenheimSlugs.CERTIFICATES, covering.get(CertificateModel.ID)).toUrl());
            assertThat(fiveApps.candidateReads()).as("step 1: the certificate rows are read once for the list")
                .isEqualTo(1);

            // 1b. A running workload served by a site whose name no certificate covers joins the list: its HTTPS
            //     cell, its own verdict and the websites' verdicts each ask the certificate rows, and they share the
            //     request's one read (AppHealth.certificateRows).
            Row workload = instance(cleanup, "https-workload");
            Map<String, Object> served = new LinkedHashMap<>(Map.of(SiteModel.NAME.getName(),
                PREFIX + "https-workload-site", SiteModel.SLUG.getName(), PREFIX + "https-workload-site",
                SiteModel.UPSTREAM_KIND.getName(), "hohenheim:instance", SiteModel.SETTINGS.getName(), Map.of(),
                SiteModel.ENABLED.getName(), true, SiteModel.INSTANCE_ID.getName(), workload.get(InstanceModel.ID)));
            address(cleanup, row(cleanup, Models.get(SiteModel.class), served), "https-workload.example.test");
            HostFixtures.LocalHostState local = HostFixtures.captureLocal();
            cleanup.add(local::restore);
            HostFixtures.makeLocalPlaceable(16L * 1024);
            workload.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
            Models.get(InstanceModel.class).save(workload);
            Apps shared = apps(sites, "certificates");
            assertThat(shared.candidateReads()).as("step 1b: the column and both kinds of verdict share one read")
                .isEqualTo(1);
            assertThat(shared.https()).as("step 1b: the websites' HTTPS cells are unchanged")
                .extracting(StateLineCell::state)
                .containsExactly(CertCoverage.NONE.token(), CertCoverage.NONE.token(), CertCoverage.NONE.token(),
                    CertCoverage.NONE.token(), CertCoverage.ACTIVE.token());
            // The running workload and the host's capacity go back before the engine step plants an instance.
            workload.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
            Models.get(InstanceModel.class).save(workload);
            local.restore();

            // 2. The Engines list's state cells over one running engine, then that one and three without an
            //    instance: the instances, then the engines' hosts and those instances' hosts together, are read once.
            List<Row> engines = new ArrayList<>(List.of(engine(cleanup, "cell-e0")));
            int runningId = engines.get(0).get(DatabaseEngineModel.ID);
            EngineHandles.plantEngine(runningId, PREFIX + "cell-e0", "mysql", InstanceModel.STATUS_RUNNING);
            Row planted = Objects.requireNonNull(OwnedInstances.soleOwnedBy(DatabaseEngineModel.MODEL_ID, runningId));
            cleanup.add(() -> OwnedInstances.inScopeUnchecked(DatabaseEngines.SOURCE, DatabaseEngineModel.MODEL_ID,
                runningId, () -> HardDeletes.row(Models.get(InstanceModel.class), planted)));
            Map<String, Integer> engineReads = Map.of("instances", 1, "servers", 1);
            Cells oneEngine = cells(HohenheimSlugs.DATABASE_ENGINES, "state", engines, "instances", "servers");
            assertThat(oneEngine.statements()).as("step 2: one engine row").isEqualTo(engineReads);
            assertThat(oneEngine.cells()).as("step 2: it runs")
                .extracting(cell -> ((StateLineCell) cell).state())
                .containsExactly(DatabaseVerdict.State.RUNNING.token());
            for (int i = 1; i < 4; i++) {
                engines.add(engine(cleanup, "cell-e" + i));
            }
            Cells fourEngines = cells(HohenheimSlugs.DATABASE_ENGINES, "state", engines, "instances", "servers");
            assertThat(fourEngines.statements()).as("step 2: four engine rows, the same reads")
                .isEqualTo(engineReads);
            assertThat(fourEngines.cells()).as("step 2: an engine without an instance does not run")
                .extracting(cell -> ((StateLineCell) cell).state())
                .containsExactly(DatabaseVerdict.State.RUNNING.token(), DatabaseVerdict.State.NOT_RUNNING.token(),
                    DatabaseVerdict.State.NOT_RUNNING.token(), DatabaseVerdict.State.NOT_RUNNING.token());
        } finally {
            runAll(cleanup);
        }
    }

    // -- measuring ----------------------------------------------------------------------------------------------------

    /** The offers of one operation over a page, and the statements naming each measured table. */
    private record Measured(List<Offer> offers, Map<String, Integer> statements) {
    }

    /** Each rendered row's row actions, and the statements naming each measured table. */
    private record Rendered(List<List<RowOffer>> offers, Map<String, Integer> statements) {

        /** @return per row, whether the operation is drawn among its actions */
        List<Boolean> drawn(Operation<?, ?, ?> operation) {
            return this.offers.stream().map(row -> row.stream().anyMatch(offer -> offer.invoke() != null
                && offer.invoke().id().equals(operation.id()))).toList();
        }
    }

    /** Renders one admin list's row actions over {@code rows}, as the list page does, counting each table's reads. */
    private static Rendered render(String slug, List<Row> rows, String... tables) {
        AccessContext operator = TenantConduits.operator();
        Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN), "the admin panel");
        PanelRequest request = new PanelRequest(admin,
            new EndpointConduit().withAttribute(ConduitAttributes.PRINCIPAL, operator.principal()), operator, null);
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
        List<List<RowOffer>> rendered = new ArrayList<>();
        try (DatasourceProbe.Armed ignored = probe.recordStatements()) {
            probe.reset();
            Function<Row, List<RowOffer>> offers = PanelResourceCalls.inspect(
                PanelEntryViews.of(HohenheimSlugs.ADMIN, slug), request).rowsForRender(rows, operator);
            rows.forEach(row -> rendered.add(offers.apply(row)));
        }
        return new Rendered(rendered, tableCounts(probe.preparedStatements(), tables));
    }

    /** Offers {@code operation} over {@code rows} through one admitted batch, as a list render asks it. */
    private static Measured measure(Operation<Row, ?, ?> operation, Model model, IntegerField id, List<Row> rows,
                                    String... tables) {
        AccessContext operator = TenantConduits.operator();
        RecordSource<?> source = RecordSource.of(model).project(id).permission(HohenheimPanel.ACCESS).build();
        List<String> keys = rows.stream().map(row -> String.valueOf((Object) row.get(id))).toList();
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
        List<Offer> offers = new ArrayList<>();
        try (DatasourceProbe.Armed ignored = probe.recordStatements()) {
            probe.reset();
            SubjectReadPath.Batch<Row> batch = SubjectReadPath.records(source).batch(operator, keys);
            for (Row row : rows) {
                offers.add(batch.offer(operation, row));
            }
        }
        return new Measured(offers, tableCounts(probe.preparedStatements(), tables));
    }

    /**
     * Draws a ROW link over {@code rows} as a list render does: its condition once, then each drawn row's target.
     *
     * @return per row whether it is drawn, and the URL a drawn row opens (null for a row not drawn)
     */
    private static Drawn draw(PanelAction<Row> link, List<Row> rows, String... tables) {
        AccessContext operator = TenantConduits.operator();
        Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN), "the admin panel");
        PanelRequest request = new PanelRequest(admin, Objects.requireNonNull(operator.conduit()), operator, null);
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
        List<Boolean> shown = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        try (DatasourceProbe.Armed ignored = probe.recordStatements()) {
            probe.reset();
            Predicate<Row> drawn = link.shownOn(rows, operator);
            for (Row row : rows) {
                boolean show = drawn.test(row);
                shown.add(show);
                targets.add(show ? ((UrlTarget) Objects.requireNonNull(link.routeFor(row, request),
                    "a drawn link has its target")).url() : null);
            }
        }
        return new Drawn(shown, targets, tableCounts(probe.preparedStatements(), tables));
    }

    /** Which rows a link is drawn on, where each drawn one goes, and the statements naming each measured table. */
    private record Drawn(List<Boolean> shown, List<String> targets, Map<String, Integer> statements) {
    }

    /** One list page's cells of one column, and the statements naming each measured table. */
    private record Cells(List<Object> cells, Map<String, Integer> statements) {
    }

    /** Reads one list's page of cells as the list page does, counting each table's reads. */
    private static Cells cells(String slug, String column, List<Row> rows, String... tables) {
        AccessContext operator = TenantConduits.operator();
        var entry = PanelEntryViews.of(HohenheimSlugs.ADMIN, slug);
        ColumnSpec spec = Objects.requireNonNull(Objects.requireNonNull(entry.list()).table().column(column));
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
        List<Object> answered = new ArrayList<>();
        try (DatasourceProbe.Armed ignored = probe.recordStatements()) {
            probe.reset();
            CellMapper<Row> cells = Objects.requireNonNull(entry.reads().readPage(rows, operator), "a page batch");
            for (Row row : rows) {
                answered.add(Objects.requireNonNull(cells.cell(row, spec), "the page answers every row's " + column));
            }
        }
        return new Cells(answered, tableCounts(probe.preparedStatements(), tables));
    }

    /** Reads one list's page of health verdicts as the list page does (one read), counting each table's reads. */
    private static Cells healthTones(String slug, List<Row> rows, String... tables) {
        AccessContext operator = TenantConduits.operator();
        ResourceHealth<Row> health = Objects.requireNonNull(PanelEntryViews.of(HohenheimSlugs.ADMIN, slug).health(),
            "the list declares its health");
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
        List<Object> answered = new ArrayList<>();
        try (DatasourceProbe.Armed ignored = probe.recordStatements()) {
            probe.reset();
            Function<Row, RecordHealth> verdicts = health.read(rows, operator);
            for (Row row : rows) {
                answered.add(verdicts.apply(row).tone().token());
            }
        }
        return new Cells(answered, tableCounts(probe.preparedStatements(), tables));
    }

    /** The dashboard's database items, and the statements naming each measured table. */
    private record Attention(List<AttentionItem> items, Map<String, Integer> statements) {
    }

    /** Collects the dashboard's database items, counting each table's reads. */
    private static Attention attention(String... tables) {
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
        List<AttentionItem> items = new ArrayList<>();
        try (DatasourceProbe.Armed ignored = probe.recordStatements()) {
            probe.reset();
            DatabaseAttention.failedDatabases(items);
            DatabaseAttention.unavailableAttachedDatabases(items);
        }
        return new Attention(items, tableCounts(probe.preparedStatements(), tables));
    }

    /**
     * Each down database is one root item titled "is not running" with its verdict's reason, each app it holds back an
     * item caused by it; the running database raises none.
     */
    private static void assertDatabaseItems(String step, List<AttentionItem> items, Row up, List<Row> stopped,
                                            List<Row> engineless) {
        String notRunning = HohenheimMicrocopy.ATTENTION_TITLE.of("database_not_running").key();
        String appDown = HohenheimMicrocopy.ATTENTION_TITLE.of("app_database_down").key();
        String engineStopped = HohenheimMicrocopy.ATTENTION_DETAIL.of("database_engine_stopped").key();
        String noEngine = HohenheimMicrocopy.ATTENTION_DETAIL.of("database_no_engine").key();
        List<Map.Entry<Row, String>> reasons = new ArrayList<>();
        stopped.forEach(row -> reasons.add(Map.entry(row, engineStopped)));
        engineless.forEach(row -> reasons.add(Map.entry(row, noEngine)));
        for (Map.Entry<Row, String> expected : reasons) {
            Row database = expected.getKey();
            String reason = expected.getValue();
            AttentionSubject subject = AttentionSubject.database(database.get(DatabaseModel.ID));
            List<AttentionItem> roots = items.stream().filter(item -> subject.equals(item.about())).toList();
            assertThat(roots).as(step + ": one root item for " + database.get(DatabaseModel.NAME)).hasSize(1);
            AttentionItem root = roots.get(0);
            assertThat(root.title().key()).as(step + ": titled not running").isEqualTo(notRunning);
            assertThat(argsOf(root.title(), "name")).as(step + ": naming it")
                .containsExactly(String.valueOf((Object) database.get(DatabaseModel.NAME)));
            assertThat(Objects.requireNonNull(root.detail()).key()).as(step + ": with its verdict's reason")
                .isEqualTo(reason);
            assertThat(items).as(step + ": the app it holds back is caused by it")
                .filteredOn(item -> subject.equals(item.causedBy()))
                .extracting(item -> item.title().key()).containsExactly(appDown);
        }
        AttentionSubject running = AttentionSubject.database(up.get(DatabaseModel.ID));
        assertThat(items).as(step + ": the running database raises nothing")
            .noneMatch(item -> running.equals(item.about()) || running.equals(item.causedBy()));
    }

    /**
     * The Apps list's HTTPS cells of some websites, in their order, the statements naming each table, and how many
     * statements read the certificate rows a covering certificate is looked up in (CertificateCoverage.candidates,
     * the only certificates read filtering on the provider being null).
     */
    private record Apps(List<StateLineCell> https, Map<String, Integer> statements, int candidateReads) {
    }

    /** Reads the operator's Apps list as its page does, counting each table's reads. */
    private static Apps apps(List<Row> sites, String... tables) {
        AccessContext operator = TenantConduits.operator();
        Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN), "the admin panel");
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
        List<AppDirectory.App> apps;
        try (DatasourceProbe.Armed ignored = probe.recordStatements()) {
            probe.reset();
            apps = AppDirectory.read(admin, operator);
        }
        List<StateLineCell> https = new ArrayList<>();
        for (Row site : sites) {
            AppDirectory.App app = apps.stream().filter(listed -> listed.source() == AppDirectory.Source.WEBSITE
                && listed.id() == (int) site.get(SiteModel.ID)).findFirst().orElseThrow();
            https.add(Objects.requireNonNull(app.https(), "a named website has an HTTPS cell"));
        }
        Pattern candidates = Pattern.compile("\\bcertificates\\b[\\s\\S]*\\bprovider\"? IS NULL");
        int candidateReads = (int) probe.preparedStatements().stream()
            .filter(sql -> candidates.matcher(sql).find()).count();
        return new Apps(https, tableCounts(probe.preparedStatements(), tables), candidateReads);
    }

    /** @return these arguments of a worded copy, as text */
    private static List<String> argsOf(Microcopy copy, String... names) {
        List<String> args = new ArrayList<>();
        for (String name : names) {
            args.add(String.valueOf(copy.args().get(name)));
        }
        return args;
    }

    /** @return per table, how many of these statements name it */
    private static Map<String, Integer> tableCounts(List<String> statements, String... tables) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : tables) {
            Pattern named = Pattern.compile("\\b" + table + "\\b");
            counts.put(table, (int) statements.stream().filter(sql -> named.matcher(sql).find()).count());
        }
        return counts;
    }

    /** Back up now, Move to shared and the delete over these dedicated databases: each rule's reads, flat. */
    private static void assertDatabaseReads(String step, List<Row> databases, List<Boolean> used) {
        Model model = Models.get(DatabaseModel.class);
        Measured delete = measure(DatabaseParts.DELETE, model, DatabaseModel.ID, databases, "instance_databases",
            "instances");
        assertThat(delete.statements()).as(step + ": the delete reads the links and their apps once")
            .isEqualTo(Map.of("instance_databases", 1, "instances", 1));
        assertThat(delete.offers()).as(step + ": a database an app uses cannot be deleted, an unused one can")
            .extracting(offer -> offer instanceof Offer.Unavailable)
            .containsExactlyElementsOf(used);
        // The verdicts behind Back up now read the serving instances, then the hosts the records name and those
        // instances' hosts in one read (DatabaseInstances.servingAmong).
        Measured backUp = measure(DatabaseParts.BACK_UP_NOW, model, DatabaseModel.ID, databases, "instances",
            "servers", "database_engines");
        assertThat(backUp.statements()).as(step + ": Back up now reads the engines and their hosts once")
            .isEqualTo(Map.of("instances", 1, "servers", 1, "database_engines", 0));
        assertThat(backUp.offers()).as(step + ": every running database can be backed up")
            .allMatch(Offer.Available.class::isInstance);
        // Its applicability (DatabaseService.moveRefusals) resolves the hosts, their shared engines and the records on
        // those engines once for the page; the managed_databases count includes the subject read itself.
        Measured move = measure(DatabaseParts.MOVE_TO_SHARED, model, DatabaseModel.ID, databases, "instances",
            "servers", "database_engines", "managed_databases");
        assertThat(move.statements())
            .as(step + ": Move to shared reads the hosts, engines, their records and serving instances once")
            .isEqualTo(Map.of("instances", 1, "servers", 2, "database_engines", 1, "managed_databases", 2));
        assertThat(move.offers()).as(step + ": every free running database can move")
            .allMatch(Offer.Available.class::isInstance);
    }

    // -- fixtures -----------------------------------------------------------------------------------------------------

    private static Row engine(List<Runnable> cleanup, String name) {
        return row(cleanup, Models.get(DatabaseEngineModel.class), Map.of(
            DatabaseEngineModel.NAME.getName(), PREFIX + name, DatabaseEngineModel.ENGINE.getName(), "mysql",
            DatabaseEngineModel.ROOT_USER.getName(), "root", DatabaseEngineModel.ROOT_PASSWORD.getName(), "rootpw",
            DatabaseEngineModel.SERVER_ID.getName(), ServerModel.localServerId(),
            DatabaseEngineModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
    }

    private static Row database(List<Runnable> cleanup, String name, Row engine) {
        return row(cleanup, Models.get(DatabaseModel.class), Map.of(DatabaseModel.NAME.getName(), PREFIX + name,
            DatabaseModel.ENGINE.getName(), "mysql", DatabaseModel.PLACEMENT.getName(), DatabaseModel.PLACEMENT_SHARED,
            DatabaseModel.ENGINE_ID.getName(), engine.get(DatabaseEngineModel.ID),
            DatabaseModel.DB_NAME.getName(), name.replace("-", "") + "db",
            DatabaseModel.DB_USER.getName(), name.replace("-", "") + "user",
            DatabaseModel.DB_PASSWORD.getName(), name + "-secret-password",
            DatabaseModel.SERVER_ID.getName(), ServerModel.localServerId(),
            DatabaseModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
    }

    /** A dedicated, persistent database whose own engine instance is planted RUNNING. */
    private static Row dedicated(List<Runnable> cleanup, String name) {
        return dedicated(cleanup, name, InstanceModel.STATUS_RUNNING);
    }

    /** A dedicated, persistent database whose own engine instance is planted in this status. */
    private static Row dedicated(List<Runnable> cleanup, String name, String status) {
        Row database = row(cleanup, Models.get(DatabaseModel.class), Map.of(DatabaseModel.NAME.getName(),
            PREFIX + name, DatabaseModel.ENGINE.getName(), "mysql", DatabaseModel.PLACEMENT.getName(),
            DatabaseModel.PLACEMENT_DEDICATED, DatabaseModel.DB_NAME.getName(), name + "db",
            DatabaseModel.DB_USER.getName(), name + "user", DatabaseModel.DB_PASSWORD.getName(),
            name + "-secret-password", DatabaseModel.EPHEMERAL.getName(), false,
            DatabaseModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
        Integer id = database.get(DatabaseModel.ID);
        EngineHandles.plant(id, PREFIX + name, "mysql", status);
        Row planted = DatabaseInstances.owned(id);
        cleanup.add(() -> OwnedInstances.inScopeUnchecked(DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, id,
            () -> HardDeletes.row(Models.get(InstanceModel.class), planted)));
        return database;
    }

    /** An app instance the database is attached to. */
    private static void app(List<Runnable> cleanup, String name, Row database) {
        Row app = instance(cleanup, name);
        row(cleanup, Models.get(InstanceDatabaseModel.class), Map.of(
            InstanceDatabaseModel.INSTANCE_ID.getName(), app.get(InstanceModel.ID),
            InstanceDatabaseModel.DATABASE_ID.getName(), database.get(DatabaseModel.ID),
            InstanceDatabaseModel.ENV_PREFIX.getName(), "DB"));
    }

    /** A domain answering {@code hostname} exactly for this site, plain HTTP allowed. */
    private static Row address(List<Runnable> cleanup, Row site, String hostname) {
        return row(cleanup, Models.get(SiteDomainModel.class), Map.of(
            SiteDomainModel.SITE_ID.getName(), site.get(SiteModel.ID),
            SiteDomainModel.HOSTNAME.getName(), PREFIX + hostname,
            SiteDomainModel.MATCH_TYPE.getName(), SiteDomainModel.MATCH_EXACT,
            SiteDomainModel.FORCE_SSL.getName(), false));
    }

    private static Row instance(List<Runnable> cleanup, String name) {
        return row(cleanup, Models.get(InstanceModel.class), Map.of(InstanceModel.NAME.getName(), PREFIX + name,
            InstanceModel.KIND.getName(), "hohenheim:docker_container",
            InstanceModel.SETTINGS.getName(), new LinkedHashMap<>(Map.of("image", "alpine")),
            InstanceModel.STATUS.getName(), InstanceModel.STATUS_STOPPED,
            InstanceModel.SERVER_ID.getName(), ServerModel.localServerId()));
    }

    private static Row environment(List<Runnable> cleanup, Row project, String name, int variables) {
        Row environment = row(cleanup, Models.get(EnvironmentModel.class), Map.of(
            EnvironmentModel.PROJECT_ID.getName(), project.get(ProjectModel.ID),
            EnvironmentModel.NAME.getName(), PREFIX + name));
        for (int i = 0; i < variables; i++) {
            row(cleanup, Models.get(InstanceVariableModel.class), Map.of(
                InstanceVariableModel.ENVIRONMENT_ID.getName(), environment.get(EnvironmentModel.ID),
                InstanceVariableModel.KEY.getName(), "GRP2H_" + i,
                InstanceVariableModel.KIND.getName(), InstanceVariableModel.KIND_PLAIN,
                InstanceVariableModel.PLAIN_VALUE.getName(), "held"));
        }
        return environment;
    }

    private static Row image(List<Runnable> cleanup, String name) {
        return row(cleanup, Models.get(RuntimeImageModel.class), Map.of(RuntimeImageModel.NAME.getName(),
            PREFIX + name, RuntimeImageModel.DOCKER_IMAGE.getName(), "alpine:latest",
            RuntimeImageModel.ENABLED.getName(), true));
    }

    private static Row peer(List<Runnable> cleanup, String name) {
        return row(cleanup, Models.get(DnsPeerModel.class), Map.of(DnsPeerModel.NAME.getName(), PREFIX + name,
            DnsPeerModel.TRANSFER_HOST.getName(), "192.0.2.10"));
    }

    /** A remote host carrying {@code stacks} stacks. */
    private static Row server(List<Runnable> cleanup, String name, int stacks) {
        int id = HostFixtures.admittedDockerHost(PREFIX + name, 4096);
        Row server = Models.get(ServerModel.class).findById(id);
        cleanup.add(() -> HardDeletes.byId(Models.get(ServerModel.class), id));
        for (int i = 0; i < stacks; i++) {
            row(cleanup, Models.get(StackModel.class), Map.of(StackModel.NAME.getName(), PREFIX + name + "-stack" + i,
                StackModel.SERVER_ID.getName(), id, StackModel.STATUS.getName(), StackModel.STATUS_STOPPED,
                StackModel.ENABLED.getName(), false));
        }
        return server;
    }

    /** A basic-auth provider gating {@code sites} sites. */
    private static Row provider(List<Runnable> cleanup, String name, int sites) {
        Row provider = row(cleanup, Models.get(SiteAuthProviderModel.class), Map.of(
            SiteAuthProviderModel.NAME.getName(), PREFIX + name,
            SiteAuthProviderModel.PROVIDER_TYPE.getName(), "hohenheim:basic",
            SiteAuthProviderModel.CONFIG.getName(), new BasicAuthProviderType()
                .normalizeConfigForSave(Map.of("credentials", Map.of("alice", "s3cret")), null)));
        for (int i = 0; i < sites; i++) {
            site(cleanup, name + "-site" + i, provider.get(SiteAuthProviderModel.ID));
        }
        return provider;
    }

    private static Row site(List<Runnable> cleanup, String name, Integer providerId) {
        Map<String, Object> values = new LinkedHashMap<>(Map.of(SiteModel.NAME.getName(), PREFIX + name,
            SiteModel.SLUG.getName(), PREFIX + name, SiteModel.UPSTREAM_KIND.getName(), "hohenheim:static",
            SiteModel.SETTINGS.getName(), Map.of("root_path", "/tmp"), SiteModel.ENABLED.getName(), true));
        if (providerId != null) {
            values.put(SiteModel.AUTH_PROVIDER_ID.getName(), providerId);
        }
        return row(cleanup, Models.get(SiteModel.class), values);
    }

    private static Row row(List<Runnable> cleanup, Model model, Map<String, Object> values) {
        Row row = model.createEmptyRow();
        values.forEach(row::set);
        model.save(row);
        cleanup.add(() -> HardDeletes.row(model, row));
        return row;
    }

    /** Undoes the fixtures, newest first. */
    private static void runAll(List<Runnable> cleanup) {
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            cleanup.get(i).run();
        }
    }
}
