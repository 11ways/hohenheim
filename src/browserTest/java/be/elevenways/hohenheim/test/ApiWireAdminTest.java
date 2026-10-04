package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.test.ApiWire.Caller;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.ApiSupport.form;

/**
 * The operator routes of {@code /api/v1} -- DNS zones, hosts, managed databases, engines and access lists: every
 * route's success reply and its main refusal, compared byte for byte to the java-rewrite capture through
 * {@link ApiWire}.
 *
 * AIDEV-NOTE: the class runs on a database of its own, copied from the migrated template, so every id is the same
 * on every run, and every host carries a preflight measured at {@link #T0}. The dedicated database the move queues
 * sits on an incus host, so the move's background half fails against no daemon instead of touching this machine's.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class ApiWireAdminTest extends HohenheimTestBase {

    private static final Instant T0 = Instant.parse("2026-01-02T03:04:05Z");
    private static final String MANAGE_ACCESS = "hohenheim.manage.access";

    private static List<String> savedNameservers;

    private static Caller admin;
    private static Caller tenant;
    private static Caller tenantSession;

    private static int dockerHostId;
    private static int engineId;
    private static int heldDatabaseId;
    private static int detachedDatabaseId;
    private static int dedicatedDatabaseId;
    private static int accessListId;

    @BeforeAll
    static void seed() throws Exception {
        TestDatabases.freshDatabase();
        savedNameservers = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Dns.NAMESERVERS);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Dns.NAMESERVERS, List.of("ns1.wire.test", "ns2.wire.test"));

        int operatorId = ApiWire.operatorId();
        int tenantId = ApiSupport.user("wire-admin-tenant@surface.test", "Wire Admin Tenant");

        dockerHostId = host("wire-docker-host", ServerModel.RUNTIME_DOCKER);
        int incusHostId = host("wire-incus-host", ServerModel.RUNTIME_INCUS);
        engineId = engine("wire-pg", dockerHostId);
        heldDatabaseId = sharedDatabase("wire-held-db", "helddb", dockerHostId, engineId);
        detachedDatabaseId = sharedDatabase("wire-detached-db", "detacheddb", dockerHostId, engineId);
        dedicatedDatabaseId = dedicatedDatabase("wire-dedicated-db", incusHostId);
        int workloadId = instance("wire-web", dockerHostId);
        Row link = Models.get(InstanceDatabaseModel.class).createEmptyRow();
        link.set(InstanceDatabaseModel.INSTANCE_ID, workloadId);
        link.set(InstanceDatabaseModel.DATABASE_ID, heldDatabaseId);
        link.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
        Models.get(InstanceDatabaseModel.class).save(link);

        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, "wire-staff");
        list.set(AccessListModel.SATISFY, "all");
        list.set(AccessListModel.SHARED, true);
        Models.get(AccessListModel.class).save(list);
        accessListId = list.get(AccessListModel.ID);

        GrantService.createDirectGrant(GrantSubjectType.USER, tenantId, MANAGE_ACCESS, true);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, DatabaseModel.MODEL_ID, heldDatabaseId,
            HohenheimAccess.VIEW, true);

        admin = new Caller.Key(ApiKeyService.create(operatorId, "wire-admin-admin", List.of("hohenheim.*"), null)
            .plaintext());
        tenant = new Caller.Key(ApiKeyService.create(tenantId, "wire-admin-tenant",
            List.of(MANAGE_ACCESS,
                CapabilityScopes.format(DatabaseModel.MODEL_ID, HohenheimAccess.VIEW),
                CapabilityScopes.format(AccessListModel.MODEL_ID, HohenheimAccess.MANAGE)), null).plaintext());
        tenantSession = new Caller.Session(sessionCookieHeader(sessionFor(tenantId).token()));
    }

    @AfterAll
    static void tearDown() throws Exception {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Dns.NAMESERVERS, savedNameservers);
        // Later classes of this JVM find the seeded shared database they expect.
        freshSeededDatabase();
    }

    @Test
    void theOperatorRoutesAnswerWhatJavaRewriteAnswered() {
        ApiWire wire = new ApiWire("admin", this::requestTo, HohenheimTestBase::sendRequestBytes);

        // 1. DNS zones: list, create and import, each refused (an operator surface, an undeclared field, no text).
        wire.get("list zones", admin, "/api/v1/dns/zones");
        wire.get("list zones with a tenant key", tenant, "/api/v1/dns/zones");
        ApiWire.Reply zone = wire.post("create a zone", admin, "/api/v1/dns/zones",
            form("origin", "Wire-Zone.test.", "soa_contact", "hostmaster@wire-zone.test"));
        wire.post("create a zone with an undeclared field", admin, "/api/v1/dns/zones",
            form("origin", "stranger-wire-zone.test", "colour", "red"));
        String zonePath = "/api/v1/dns/zones/" + ApiSupport.idOf(zone.text()) + "/import";
        wire.post("import a zone file", admin, zonePath, form("zone_text",
            "$ORIGIN wire-zone.test.\n$TTL 3600\n"
                + "@ IN SOA ns1.afraid.org. dnsadmin.afraid.org. 2604070003 86400 7200 2419200 3600\n"
                + "@ IN NS ns1.afraid.org.\n@ IN NS ns2.afraid.org.\n"
                + "@ IN A 192.0.2.1\nwww IN A 192.0.2.2\n@ IN MX 10 mail.wire-zone.test.\n"));
        wire.post("import an empty zone file", admin, zonePath, form("zone_text", " "));

        // 2. Hosts: the ledger list and one host with its workloads, refused to a tenant and for an absent host.
        wire.get("list hosts", admin, "/api/v1/hosts");
        wire.get("list hosts with a tenant key", tenant, "/api/v1/hosts");
        wire.get("read a host", admin, "/api/v1/hosts/" + dockerHostId);
        wire.get("read an absent host", admin, "/api/v1/hosts/999999");

        // 3. Engines: the list and one engine with its logical databases.
        wire.get("list engines", admin, "/api/v1/engines");
        wire.get("list engines with a tenant key", tenant, "/api/v1/engines");
        wire.get("read an engine", admin, "/api/v1/engines/" + engineId);
        wire.get("read an absent engine", admin, "/api/v1/engines/999999");

        // 4. Databases: the operator's list and a delegate's read of its one record.
        wire.get("list databases", admin, "/api/v1/databases");
        wire.get("list databases as a browser session", tenantSession, "/api/v1/databases");
        wire.get("read a delegated database", tenant, "/api/v1/databases/" + heldDatabaseId);
        wire.get("read an undelegated database", tenant, "/api/v1/databases/" + detachedDatabaseId);

        // 5. Database writes: a shared record does not move, a dedicated one is queued; a held record does not
        //    delete, a detached one does.
        wire.post("move a shared database", admin, "/api/v1/databases/" + heldDatabaseId + "/move-shared", "");
        wire.post("move a dedicated database", admin,
            "/api/v1/databases/" + dedicatedDatabaseId + "/move-shared", "");
        wire.post("delete a database a workload holds", admin, "/api/v1/databases/" + heldDatabaseId + "/delete", "");
        wire.post("delete a detached database", admin, "/api/v1/databases/" + detachedDatabaseId + "/delete", "");

        // 6. Access lists: list, read, create, add a rule and delete, each with its refusal.
        String listPath = "/api/v1/access-lists/" + accessListId;
        wire.get("list access lists", admin, "/api/v1/access-lists");
        wire.get("list access lists as a browser session", tenantSession, "/api/v1/access-lists");
        wire.get("read an access list", admin, listPath);
        wire.get("read an access list the key does not manage", tenant, listPath);
        ApiWire.Reply created = wire.post("create an access list", admin, "/api/v1/access-lists",
            form("name", "wire-created-list", "satisfy", "any"));
        wire.post("create an access list with an undeclared field", admin, "/api/v1/access-lists",
            form("name", "wire-stranger-list", "colour", "red"));
        wire.post("add a rule", admin, listPath + "/rules",
            form("type", AccessRuleModel.TYPE_IP_ALLOW, "data.network", "10.0.0.0/8", "enabled", "true"));
        wire.post("add a rule of an unknown type", admin, listPath + "/rules", form("type", "sudo"));
        wire.post("delete an access list", admin,
            "/api/v1/access-lists/" + ApiSupport.idOf(created.text()) + "/delete", "");
        wire.post("delete an access list the key does not manage", tenant, listPath + "/delete", "");

        // 7. Every reply is the java-rewrite one.
        wire.assertGolden();
    }

    // -- fixtures -------------------------------------------------------------------------------------------------

    /** An admitted, acknowledged host whose preflight passed at {@link #T0}. */
    private static int host(String name, String runtime) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, runtime);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.PREFLIGHT_OK, true);
        Models.get(ServerModel.class).save(row);
        HostFixtures.acknowledgePosture(row);
        HostPreflight.store(name, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of(HostPreflight.MEM_TOTAL_FACT, 16L * 1024 * 1024 * 1024), true, T0, null));
        return row.get(ServerModel.ID);
    }

    /** A shared engine row with no owned instance: the tier exists, no container does. */
    private static int engine(String name, int serverId) {
        Row row = Models.get(DatabaseEngineModel.class).createEmptyRow();
        row.set(DatabaseEngineModel.NAME, name);
        row.set(DatabaseEngineModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
        row.set(DatabaseEngineModel.IMAGE, "postgres:16");
        row.set(DatabaseEngineModel.SERVER_ID, serverId);
        row.set(DatabaseEngineModel.ROOT_USER, "root");
        row.set(DatabaseEngineModel.ROOT_PASSWORD, "wire-root-pw");
        row.set(DatabaseEngineModel.MEMORY_LIMIT_MB, 1024);
        row.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        Models.get(DatabaseEngineModel.class).save(row);
        return row.get(DatabaseEngineModel.ID);
    }

    private static int sharedDatabase(String name, String dbName, int serverId, int engineId) {
        Row row = Models.get(DatabaseModel.class).createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
        row.set(DatabaseModel.DB_NAME, dbName);
        row.set(DatabaseModel.DB_USER, dbName + "user");
        row.set(DatabaseModel.DB_PASSWORD, "wire-secret-pw");
        row.set(DatabaseModel.SERVER_ID, serverId);
        row.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_SHARED);
        row.set(DatabaseModel.ENGINE_ID, engineId);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        Models.get(DatabaseModel.class).save(row);
        return row.get(DatabaseModel.ID);
    }

    private static int dedicatedDatabase(String name, int serverId) {
        Row row = Models.get(DatabaseModel.class).createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
        row.set(DatabaseModel.DB_NAME, "dedicateddb");
        row.set(DatabaseModel.DB_USER, "dedicateduser");
        row.set(DatabaseModel.DB_PASSWORD, "wire-secret-pw");
        row.set(DatabaseModel.SERVER_ID, serverId);
        row.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_DEDICATED);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        Models.get(DatabaseModel.class).save(row);
        return row.get(DatabaseModel.ID);
    }

    private static int instance(String name, int serverId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SERVER_ID, serverId);
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "tag", "latest"));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        row.set(InstanceModel.CREATED_AT, T0);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }
}
