package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.DatabaseResource;
import be.elevenways.hohenheim.server.cms.ManageDatabaseResource;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.EngineHost;
import be.elevenways.hohenheim.server.database.TenantDatabases;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.quota.DatabaseQuota;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Tenant database allocation: what a delegated tenant may allocate, see, read the
 * credentials of, back up, attach and destroy -- and, the load-bearing half, what a
 * second tenant and a read-only teammate may not.
 *
 * No daemon is needed. Every refusal proven here fires before any driver call, and the
 * allocation itself is a record write plus an instance-row reservation; the container
 * work is scheduled to run after the transaction commits and is irrelevant to the
 * boundary being tested.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantDatabaseSurfaceTest extends HohenheimTestBase {

    private static final String PREFIX = "tenant-db-";

    private static Integer tenantAId;
    private static Integer tenantBId;
    private static Integer viewerId;
    private static UserPrincipal principalA;
    private static UserPrincipal principalB;
    private static String sessionA;
    private static String csrfA;
    private static String sessionB;
    private static String csrfB;
    private static String sessionViewer;

    private static Integer siteAId;
    private static Integer siteBId;
    private static Integer applicationAId;
    private static Integer applicationBId;
    private static Integer admittedHostId;
    private static Integer capRowId;

    private static Integer databaseAId;
    private static String databaseAName;
    /** The operator's shared engine every tenant record of this class lands on. */
    private static Integer sharedEngineId;

    @BeforeAll
    static void seed() {
        tenantAId = tenant("tenant-a@" + PREFIX + "test", "Database Tenant A");
        tenantBId = tenant("tenant-b@" + PREFIX + "test", "Database Tenant B");
        viewerId = tenant("viewer@" + PREFIX + "test", "Database Viewer");
        principalA = new UserPrincipal(tenantAId, "Database Tenant A");
        principalB = new UserPrincipal(tenantBId, "Database Tenant B");

        sessionA = session(tenantAId);
        csrfA = lastCsrf;
        sessionB = session(tenantBId);
        csrfB = lastCsrf;
        sessionViewer = session(viewerId);

        siteAId = site(PREFIX + "site-a");
        siteBId = site(PREFIX + "site-b");
        RecordGrants.grant(GrantSubjectType.USER, tenantAId, SiteModel.MODEL_ID, siteAId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, tenantBId, SiteModel.MODEL_ID, siteBId,
            HohenheimAccess.MANAGE, true);

        // A database attaches to the WORKLOAD that consumes it, which since brief 7 is an
        // application instance rather than a site.
        applicationAId = application(PREFIX + "app-a");
        applicationBId = application(PREFIX + "app-b");
        RecordGrants.grant(GrantSubjectType.USER, tenantAId, InstanceModel.MODEL_ID,
            applicationAId, HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, tenantBId, InstanceModel.MODEL_ID,
            applicationBId, HohenheimAccess.MANAGE, true);

        admittedHostId = admittedHost();
    }

    @AfterAll
    static void cleanUp() {
        if (capRowId != null) {
            Models.get(InstanceQuotaModel.class).delete(capRowId);
        }
        // Everything this class allocated landed on its OWN admitted host, so that is
        // the honest sweep key -- the stored names are per-owner namespaced and no longer
        // carry the class prefix.
        Model databases = Models.get(DatabaseModel.class);
        Model instances = Models.get(InstanceModel.class);
        Model engines = Models.get(DatabaseEngineModel.class);
        if (admittedHostId != null) {
            // Instance rows carry generated attribution: read-only, and undeletable,
            // outside their owning tier's system scope. Soft-deleted ones count too --
            // a host refuses removal while any row still points at it. The shared
            // engine's own instance rides the same sweep: it sits on this host too.
            GeneratedRows.sweeping("database", () -> {
                for (Row instance : instances.find()
                        .where(InstanceModel.SERVER_ID.eq(admittedHostId)).all()) {
                    instances.delete(instance.get(InstanceModel.ID));
                }
            });
            InstanceDatabaseModel links = Models.get(InstanceDatabaseModel.class);
            for (Row row : databases.find()
                    .where(DatabaseModel.SERVER_ID.eq(admittedHostId)).all()) {
                Integer id = row.get(DatabaseModel.ID);
                for (Row link : links.findByDatabaseId(id)) {
                    links.delete(link.get(InstanceDatabaseModel.ID));
                }
                databases.delete(id);
            }
            // Engines go LAST: an engine refuses to go while a record still names it.
            for (Row engine : engines.find()
                    .where(DatabaseEngineModel.SERVER_ID.eq(admittedHostId)).all()) {
                engines.delete(engine.get(DatabaseEngineModel.ID));
            }
        }
        Model sites = Models.get(SiteModel.class);
        for (Row row : sites.find().where(SiteModel.NAME.startsWith(PREFIX)).all()) {
            sites.delete(row.get(SiteModel.ID));
        }
        if (admittedHostId != null) {
            Models.get(ServerModel.class).delete(admittedHostId);
        }
    }

    // -- fixtures -------------------------------------------------------------

    private static String lastCsrf;

    private static int tenant(String email, String name) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, name);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static String session(int userId) {
        Session session = Zenit.getSessionStore().create();
        session.set(be.elevenways.zenit.auth.AuthKeys.USER_ID, (long) userId);
        lastCsrf = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, lastCsrf);
        Zenit.getSessionStore().save(session);
        return session.token().secret();
    }

    private static int site(String name) {
        Model sites = Models.get(SiteModel.class);
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, name);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.ENABLED, false);
        sites.save(row);
        return row.get(SiteModel.ID);
    }

    /** An application instance: the record a database link now hangs off. */
    private static int application(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine"));
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int admittedHost() {
        Model servers = Models.get(ServerModel.class);
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, PREFIX + "host");
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.PREFLIGHT_OK, true);
        servers.save(row);
        HostFixtures.acknowledgePosture(row);
        HostPreflight.store(PREFIX + "host", new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return row.get(ServerModel.ID);
    }

    private HttpResponse<String> get(String session, String path) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String session, String csrf, String path, String body)
            throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
                .header("X-Csrf-Token", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Row databaseNamed(String name) {
        return Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq(name)).first();
    }

    private static String violationKeys(Throwable thrown) {
        assertThat(thrown).as("a refusal was raised at all").isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }

    private static String packedOf(int userId) {
        return HohenheimAccess.packSubjects(Set.of("user:" + userId));
    }

    /** The tenant's INSTANCE (workload) bucket, which a shared database never spends. */
    private static String instanceBucketOf(int userId) {
        return InstanceQuota.bucketKeyOf(packedOf(userId));
    }

    /** The tenant's DATABASE bucket, the one a shared record is charged to. */
    private static String databaseBucketOf(int userId) {
        return DatabaseQuota.bucketKeyOf(packedOf(userId));
    }

    private static boolean offersAction(List<RowAction<Row>> actions, String actionName) {
        for (RowAction<Row> action : actions) {
            if (action.id().getPath().equals(actionName)) {
                return true;
            }
        }
        return false;
    }

    // -- the journeys ---------------------------------------------------------

    /**
     * The allocation funnel end to end: authority, placement, per-owner NAMING, ownership,
     * and the quota bucket the engine is charged to.
     */
    @Test
    @Order(1)
    void allocationDerivesNamePlacementOwnershipAndChargesTheTenantsOwnQuota() throws Exception {
        String createUrl = "/manage/databases/new";

        // 1. Without hohenheim.databases.create the tenant is not even eligible, and the
        //    submit persists nothing. (The /manage panel itself is reachable: tenant A
        //    holds a site grant, so this is the CREATE gate answering, not the panel.)
        HttpResponse<String> unauthorized = post(sessionA, csrfA, createUrl,
            "name=blog&engine=postgres");
        assertThat(databaseNamed("u" + tenantAId + "-blog"))
            .as("step 1: a tenant without allocation authority persisted NOTHING").isNull();
        assertThat(unauthorized.statusCode())
            .as("step 1: and was refused rather than redirected to a created record")
            .isNotIn(302, 303);

        // 2. With the permission the allocation lands.
        GrantService.createDirectGrant(GrantSubjectType.USER, tenantAId,
            TenantDatabases.DATABASES_CREATE.value(), true);
        GrantService.createDirectGrant(GrantSubjectType.USER, tenantBId,
            TenantDatabases.DATABASES_CREATE.value(), true);
        HttpResponse<String> created = post(sessionA, csrfA, createUrl,
            "name=blog&engine=postgres");
        assertThat(created.statusCode()).as("step 2: the allocation lands").isIn(302, 303);

        // 3. THE NAME IS NAMESPACED. The tenant asked for "blog"; the stored name -- which
        //    is also the container handle, the data volume and the backup directory -- is
        //    private to this owner.
        databaseAName = "u" + tenantAId + "-blog";
        Row database = databaseNamed(databaseAName);
        assertThat(database).as("step 3: the record is stored under the namespaced name")
            .isNotNull();
        assertThat(databaseNamed("blog"))
            .as("step 3: and the bare label was NOT taken installation-wide").isNull();
        databaseAId = database.get(DatabaseModel.ID);

        // 4. PLACEMENT chose the admitted host; the tenant named none and could not.
        assertThat((Integer) database.get(DatabaseModel.SERVER_ID))
            .as("step 4: the engine landed on the admitted host placement chose")
            .isEqualTo(admittedHostId);

        // 5. OWNERSHIP: the creator holds manage, which is what makes the record reachable
        //    on the very surface that created it.
        assertThat(HohenheimAccess.manageSubjectsOf(DatabaseModel.MODEL_ID, databaseAId))
            .as("step 5: the creator owns what it allocated")
            .containsExactly("user:" + tenantAId);
        assertThat(get(sessionA, "/manage/databases").body())
            .as("step 5: and it shows up in the tenant's own list")
            .contains(databaseAName);

        // 6. THE PLACEMENT AND THE QUOTA. A tenant's postgres is a LOGICAL database on the
        //    operator's shared engine: the record owns no instance of its own, it names the
        //    engine row, and that row's instance is booked ONCE against the OPERATOR's
        //    bucket (the operator chose to share one process). The tenant is charged in
        //    the DATABASE dimension only; its INSTANCE quota does not move.
        assertThat((String) database.get(DatabaseModel.PLACEMENT))
            .as("step 6: a persistent postgres allocation lands shared")
            .isEqualTo(DatabaseModel.PLACEMENT_SHARED);
        assertThat(OwnedInstances.soleOwnedBy(DatabaseModel.MODEL_ID, databaseAId))
            .as("step 6: a shared record owns NO instance row of its own").isNull();
        sharedEngineId = database.get(DatabaseModel.ENGINE_ID);
        assertThat(sharedEngineId).as("step 6: it names its engine").isNotNull();
        Row engine = Models.get(DatabaseEngineModel.class).findById(sharedEngineId);
        assertThat(engine).as("step 6: and that engine row exists").isNotNull();
        assertThat((Integer) engine.get(DatabaseEngineModel.SERVER_ID))
            .as("step 6: on the same admitted host").isEqualTo(admittedHostId);
        Row engineInstance = DatabaseInstances.ownedBy(EngineHost.ofEngine(engine));
        assertThat(engineInstance).as("step 6: the engine owns the instance row").isNotNull();
        assertThat((String) engineInstance.get(InstanceModel.QUOTA_BUCKET))
            .as("step 6: booked against the OPERATOR bucket, never the tenant's")
            .isEqualTo(InstanceQuota.bucketKeyOf(""))
            .isNotEqualTo(instanceBucketOf(tenantAId));
        assertThat((String) database.get(DatabaseModel.QUOTA_BUCKET))
            .as("step 6: while the record itself is charged to the tenant's DATABASE bucket")
            .isEqualTo(databaseBucketOf(tenantAId));
        assertThat(Quotas.usedOf(instanceBucketOf(tenantAId)))
            .as("step 6: and the tenant's instance quota was not spent").isZero();
        assertThat(Quotas.usedOf(databaseBucketOf(tenantAId)))
            .as("step 6: one database slot is").isEqualTo(1);

        // 7. A SECOND TENANT may use the SAME label: namespacing is what keeps one
        //    tenant's choice from denying it to everyone else (and from answering
        //    "taken", which is an oracle over another tenant's names). It lands on the
        //    SAME engine: one process per host is the entire point of sharing.
        HttpResponse<String> other = post(sessionB, csrfB, createUrl, "name=blog&engine=postgres");
        assertThat(other.statusCode())
            .as("step 7: tenant B allocates the same label successfully").isIn(302, 303);
        Row databaseB = databaseNamed("u" + tenantBId + "-blog");
        assertThat(databaseB).as("step 7: under ITS OWN namespaced name").isNotNull();
        assertThat((Integer) databaseB.get(DatabaseModel.ENGINE_ID))
            .as("step 7: on the very same shared engine").isEqualTo(sharedEngineId);
        assertThat((String) databaseB.get(DatabaseModel.QUOTA_BUCKET))
            .as("step 7: charged to tenant B's own database bucket")
            .isEqualTo(databaseBucketOf(tenantBId));
    }

    /** Cross-tenant reads: absence and denial are one answer, on every surface. */
    @Test
    @Order(2)
    void anotherTenantsDatabaseIsIndistinguishableFromOneThatDoesNotExist() throws Exception {
        int absentId = 900_000_000;
        assertThat(Models.get(DatabaseModel.class).findById(absentId))
            .as("step 1: the control id really does not exist").isNull();

        // 1. The list is the assertion, not the status: a leak here is a NAME in the body.
        HttpResponse<String> list = get(sessionB, "/manage/databases");
        assertThat(list.statusCode()).as("step 1: the scoped list renders").isEqualTo(200);
        assertThat(list.body())
            .as("step 1: tenant B sees its own database")
            .contains("u" + tenantBId + "-blog");
        assertThat(list.body())
            .as("step 1: and NEVER tenant A's")
            .doesNotContain(databaseAName);

        // 2. Detail: same status AND same body for unowned and nonexistent.
        HttpResponse<String> foreign = get(sessionB, "/manage/databases/" + databaseAId);
        HttpResponse<String> absent = get(sessionB, "/manage/databases/" + absentId);
        assertThat(foreign.statusCode())
            .as("step 2: another tenant's database reads as MISSING, not forbidden")
            .isEqualTo(404);
        assertThat(foreign.body())
            .as("step 2: and the two answers are byte-identical -- no existence oracle")
            .isEqualTo(absent.body());

        // 3. The admin panel stays shut.
        assertThat(get(sessionB, "/admin/databases").statusCode())
            .as("step 3: the operator surface refuses the tenant").isEqualTo(403);
    }

    /**
     * Credentials are their OWN capability: a view-only teammate sees the record and not
     * the password.
     */
    @Test
    @Order(3)
    void aViewOnlyTeammateSeesTheRecordButNeverTheCredentials() throws Exception {
        String credentialsUrl = "/manage/databases/" + databaseAId + "/page/credentials";
        String password = String.valueOf(
            (Object) Models.get(DatabaseModel.class).findById(databaseAId)
                .get(DatabaseModel.DB_PASSWORD));
        assertThat(password).as("step 0: the fixture really has a stored password")
            .isNotBlank().isNotEqualTo("null");

        // 1. The teammate is granted VIEW and nothing else. They reach the record.
        RecordGrants.grant(GrantSubjectType.USER, viewerId, DatabaseModel.MODEL_ID, databaseAId,
            HohenheimAccess.VIEW, true);
        HttpResponse<String> detail = get(sessionViewer, "/manage/databases/" + databaseAId);
        assertThat(detail.statusCode()).as("step 1: a view grant opens the record")
            .isEqualTo(200);
        assertThat(detail.body())
            .as("step 1: the record form NEVER carries the plaintext password")
            .doesNotContain(password);

        // 2. THE ATTACK: the credentials tab, reached by URL rather than by clicking a tab
        //    that is not offered. Absence of the tab is UX; this is the gate.
        HttpResponse<String> denied = get(sessionViewer, credentialsUrl);
        assertThat(denied.statusCode())
            .as("step 2: a view-only holder cannot open the credentials tab")
            .isEqualTo(404);
        assertThat(denied.body())
            .as("step 2: and no part of the answer carries the password")
            .doesNotContain(password);
        assertThat(detail.body())
            .as("step 2: the tab is not even offered to them")
            .doesNotContain("/page/credentials");

        // 3. The OWNER reads them -- manage implies credentials, so the positive anchor is
        //    not a second grant, it is ownership.
        HttpResponse<String> owner = get(sessionA, credentialsUrl);
        assertThat(owner.statusCode()).as("step 3: the owner opens the tab").isEqualTo(200);
        assertThat(owner.body()).as("step 3: and reads the credential").contains(password);

        // 4. Granting the teammate CREDENTIALS explicitly opens exactly that one door.
        RecordGrants.grant(GrantSubjectType.USER, viewerId, DatabaseModel.MODEL_ID, databaseAId,
            HohenheimAccess.CREDENTIALS, true);
        assertThat(get(sessionViewer, credentialsUrl).body())
            .as("step 4: an explicit credentials grant is what opens it")
            .contains(password);
        RecordGrants.revoke(GrantSubjectType.USER, viewerId, DatabaseModel.MODEL_ID, databaseAId,
            HohenheimAccess.CREDENTIALS);
        assertThat(get(sessionViewer, credentialsUrl).statusCode())
            .as("step 4: and revoking it closes the door again, same request")
            .isEqualTo(404);
    }

    /** The backup download answers to the capability, and never says which name exists. */
    @Test
    @Order(4)
    void theBackupDownloadAnswersToItsCapabilityAndLeaksNoName() throws Exception {
        String url = "/databases/" + databaseAName + "/backup";

        // 1. THE ATTACK: tenant B knows (or guesses) the name and asks for the dump. The
        //    endpoint is requiresLogin -- there is no /admin baseline to refuse first, so
        //    this really is the capability gate answering.
        HttpResponse<String> stolen = get(sessionB, url);
        assertThat(stolen.statusCode())
            .as("step 1: another tenant's dump is MISSING, not forbidden")
            .isEqualTo(404);
        // The BODY is the assertion too: a 404 that still echoes the requested name
        // confirms it exists, which is the oracle this refusal exists to close.
        assertThat(stolen.body())
            .as("step 1: and the refusal never echoes the database name back")
            .doesNotContain(databaseAName);

        // 2. And a name that does not exist answers identically, so the URL is not an
        //    oracle over other tenants' database names.
        assertThat(get(sessionB, "/databases/" + PREFIX + "no-such-db/backup").statusCode())
            .as("step 2: a nonexistent name gets the SAME answer")
            .isEqualTo(404);

        // 3. The OWNER passes the gate. No engine container exists in this suite, so the
        //    dump itself cannot succeed -- but the answer is the post-gate failure
        //    redirect, NOT the pre-gate 404, which is what separates "refused" from
        //    "allowed and then the daemon said no".
        assertThat(get(sessionA, url).statusCode())
            .as("step 3: the owner is past the capability gate")
            .isNotEqualTo(404);

        // 4. The row action follows the same capability: it is rendered for the owner and
        //    absent for a viewer, so no control promises authority the service refuses.
        assertThat(get(sessionA, "/manage/databases").body())
            .as("step 4: the owner is offered the backup action")
            .contains(url);
        assertThat(get(sessionViewer, "/manage/databases").body())
            .as("step 4: a view-only teammate is not")
            .doesNotContain(url);
    }

    /** Every load-bearing column is frozen for a tenant, on the WRITE PIPELINE. */
    @Test
    @Order(5)
    void aTenantCannotWriteThePlacementTheImageTheCapsOrTheEphemeralFlag() {
        Model databases = Models.get(DatabaseModel.class);
        Row stored = databases.findById(databaseAId);
        int storedServer = stored.get(DatabaseModel.SERVER_ID);

        // 1..6 THE ATTACKS, one per column, each straight at the MODEL -- no form, no
        //      resource, exactly the shape a direct POST or a revision restore takes.
        //      The placement pair is the shared-tier addition: flipping to dedicated or
        //      repointing at another engine would each trip a placement invariant, and
        //      the refusal a tenant sees must still be the FROZEN one (hook order), never
        //      an invariant that reads as "your engine choice is wrong".
        for (Map.Entry<String, Object> attack : new java.util.LinkedHashMap<String, Object>() {{
                put(DatabaseModel.SERVER_ID.getName(), ServerModel.localServerId());
                put(DatabaseModel.IMAGE.getName(), "attacker/postgres:evil");
                put(DatabaseModel.MEMORY_LIMIT_MB.getName(), 65536);
                put(DatabaseModel.EPHEMERAL.getName(), true);
                put(DatabaseModel.PLACEMENT.getName(), DatabaseModel.PLACEMENT_DEDICATED);
                put(DatabaseModel.ENGINE_ID.getName(), 900_000_000);
            }}.entrySet()) {
            Throwable refused = catchThrowable(() -> TenantConduits.as(principalA, () -> {
                Row write = databases.findById(databaseAId);
                write.set(attack.getKey(), attack.getValue());
                databases.save(write);
            }));
            assertThat(violationKeys(refused))
                .as("step 1: writing " + attack.getKey() + " is refused by name")
                .contains("tenant_field_frozen");
            assertThat(databases.findById(databaseAId).get(attack.getKey()))
                .as("step 1: and " + attack.getKey() + " kept its stored value")
                .isEqualTo(stored.get(attack.getKey()));
        }
        assertThat((Integer) databases.findById(databaseAId).get(DatabaseModel.SERVER_ID))
            .as("step 2: placement is intact after every attempt").isEqualTo(storedServer);

        // 3. And a tenant cannot INSERT a database record at all outside the funnel: the
        //    funnel is what derives the name, the placement and the credentials, so a
        //    hand-made row would be a database with none of them decided.
        Throwable smuggled = catchThrowable(() -> TenantConduits.as(principalA, () -> {
            Row row = databases.createEmptyRow();
            row.set(DatabaseModel.NAME, PREFIX + "smuggled");
            row.set(DatabaseModel.ENGINE, "postgres");
            row.set(DatabaseModel.DB_NAME, "smuggled");
            row.set(DatabaseModel.SERVER_ID, ServerModel.localServerId());
            databases.save(row);
        }));
        assertThat(violationKeys(smuggled))
            .as("step 3: a hand-made tenant create is refused by name")
            .contains("tenant_database_not_allocatable");
        assertThat(databaseNamed(PREFIX + "smuggled"))
            .as("step 3: and nothing landed").isNull();

        // 4. The operator's "move to shared engine" action is NOT a tenant verb: a tenant
        //    never sees, let alone drives, where the operator's data lives. The positive
        //    anchor is the operator resource declaring it, so this is not a typo test.
        assertThat(offersAction(new DatabaseResource().rowActions(), "move_database_shared"))
            .as("step 4 anchor: the operator resource offers the move").isTrue();
        assertThat(offersAction(new ManageDatabaseResource().rowActions(), "move_database_shared"))
            .as("step 4: the tenant resource does not").isFalse();
    }

    /** Attaching a database to an application needs authority over BOTH sides. */
    @Test
    @Order(6)
    void attachingADatabaseToAnApplicationNeedsAuthorityOverBothRecords() {
        InstanceDatabaseModel links = Models.get(InstanceDatabaseModel.class);

        // 1. THE ATTACK: tenant B points THEIR OWN application at tenant A's database,
        //    which would inject A's credentials into B's runtime.
        Throwable stolenCredential = catchThrowable(() -> TenantConduits.as(principalB, () -> {
            Row link = links.createEmptyRow();
            link.set(InstanceDatabaseModel.INSTANCE_ID, applicationBId);
            link.set(InstanceDatabaseModel.DATABASE_ID, databaseAId);
            link.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
            links.save(link);
        }));
        assertThat(violationKeys(stolenCredential))
            .as("step 1: the database half refuses")
            .contains("database_not_permitted");
        assertThat(links.findByDatabaseId(databaseAId))
            .as("step 1: and no link row landed").isEmpty();

        // 2. THE MIRROR ATTACK: tenant A points an application they do NOT manage at
        //    their own database, handing their credentials to somebody else's workload.
        Throwable foreignWorkload = catchThrowable(() -> TenantConduits.as(principalA, () -> {
            Row link = links.createEmptyRow();
            link.set(InstanceDatabaseModel.INSTANCE_ID, applicationBId);
            link.set(InstanceDatabaseModel.DATABASE_ID, databaseAId);
            link.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
            links.save(link);
        }));
        assertThat(violationKeys(foreignWorkload))
            .as("step 2: the workload half refuses")
            .contains("tenant_instance_not_managed");
        assertThat(links.findByDatabaseId(databaseAId))
            .as("step 2: still no link row").isEmpty();

        // 3. THE POSITIVE ANCHOR: both sides theirs, and the link lands.
        TenantConduits.as(principalA, () -> {
            Row link = links.createEmptyRow();
            link.set(InstanceDatabaseModel.INSTANCE_ID, applicationAId);
            link.set(InstanceDatabaseModel.DATABASE_ID, databaseAId);
            link.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
            links.save(link);
        });
        assertThat(links.findByDatabaseId(databaseAId))
            .as("step 3: authority over both sides attaches").hasSize(1);

        // 4. And tenant B cannot detach what they could not attach.
        Throwable detach = catchThrowable(() -> TenantConduits.as(principalB, () ->
            links.find().where(InstanceDatabaseModel.DATABASE_ID.eq(databaseAId)).delete()));
        // The WORKLOAD half answers first here (the link hangs off tenant A's application),
        // which is the honest first refusal -- both halves are asked, and either one
        // refusing is what keeps a link from being detached by someone who owns neither.
        assertThat(violationKeys(detach))
            .as("step 4: removing the link asks the same two-sided question")
            .contains("tenant_instance_not_managed");
        assertThat(links.findByDatabaseId(databaseAId))
            .as("step 4: the link survived the attempt").hasSize(1);
    }

    /**
     * The cap binds under CONCURRENCY, because the reservation rides the record write.
     * It is the DATABASE cap: a shared record spends no instance slot, so the instance
     * cap could not bind a tenant's database count even in principle.
     */
    @Test
    @Order(7)
    void racingAllocationsCannotOverspendTheTenantsLastSlot() throws Exception {
        String packed = packedOf(tenantAId);
        long used = Quotas.usedOf(databaseBucketOf(tenantAId));
        long instancesUsed = Quotas.usedOf(instanceBucketOf(tenantAId));
        capTenantAt(tenantAId, (int) used + 1);   // EXACTLY one remaining slot

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Thread> threads = new ArrayList<>();
        Throwable[] failures = new Throwable[2];
        for (int i = 0; i < 2; i++) {
            int slot = i;
            Thread worker = new Thread(() -> {
                try {
                    barrier.await();
                    post(sessionA, csrfA, "/manage/databases/new",
                        "name=race" + slot + "&engine=postgres");
                } catch (Throwable error) {
                    failures[slot] = error;
                }
            });
            worker.start();
            threads.add(worker);
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(failures).as("step 1: both submits completed").containsOnlyNulls();

        // 2. STATE first: exactly ONE of the two raced allocations exists. Two would mean
        //    both spent the same last slot -- the defect a form-render check cannot catch.
        List<Row> raced = new ArrayList<>();
        for (Row row : Models.get(DatabaseModel.class).find().all()) {
            String name = String.valueOf((Object) row.get(DatabaseModel.NAME));
            if (name.startsWith("u" + tenantAId + "-race")) {
                raced.add(row);
            }
        }
        assertThat(raced).as("step 2: exactly one racing allocation landed").hasSize(1);
        assertThat(Quotas.usedOf(databaseBucketOf(tenantAId)))
            .as("step 2: used == limit, not limit + 1")
            .isEqualTo(used + 1);
        assertThat(DatabaseQuota.limitFor(packed))
            .as("step 2: against the tenant's OWN cap, not a shared one")
            .isEqualTo((int) used + 1);
        assertThat(Quotas.usedOf(instanceBucketOf(tenantAId)))
            .as("step 2: and the tenant's INSTANCE quota did not move -- a shared "
                + "database is not a workload slot")
            .isEqualTo(instancesUsed);

        // 3. The loser left nothing behind: no orphan "provisioning" record, and the
        //    winner joined the existing engine rather than minting a second one.
        assertThat(Models.get(DatabaseModel.class).find().all().stream()
                .filter(row -> String.valueOf((Object) row.get(DatabaseModel.NAME))
                    .startsWith("u" + tenantAId + "-race"))
                .count())
            .as("step 3: the refused allocation rolled its record back").isEqualTo(1);
        assertThat((Integer) raced.get(0).get(DatabaseModel.ENGINE_ID))
            .as("step 3: the winner lives on the host's one shared engine")
            .isEqualTo(sharedEngineId);
    }

    /** Destroy is the tenant's own verb, and only over their own database. */
    @Test
    @Order(8)
    void onlyTheOwnerDestroysAndTheSlotComesBack() throws Exception {
        // The link from journey 6 blocks a destroy by design (a live workload depends on
        // the injected credentials); remove it as the owner first.
        InstanceDatabaseModel links = Models.get(InstanceDatabaseModel.class);
        TenantConduits.as(principalA, () ->
            links.find().where(InstanceDatabaseModel.DATABASE_ID.eq(databaseAId)).delete());

        // 1. THE ATTACK: tenant B destroys tenant A's database through the service every
        //    surface funnels into.
        Throwable stolen = catchThrowable(() -> TenantConduits.as(principalB, () -> {
            try {
                new DatabaseService().destroy(databaseAName, true);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }));
        assertThat(violationKeys(stolen))
            .as("step 1: another tenant's destroy is refused by name")
            .contains("database_not_permitted");
        assertThat(databaseNamed(databaseAName))
            .as("step 1: and the record is still there -- a refusal that still deleted "
                + "would pass a status-only test").isNotNull();

        // 2. THE POSITIVE ANCHOR: the owner destroys their own, the record is gone, and
        //    the quota slot comes back so the cap is not a one-way ratchet. No engine
        //    container ever ran in this suite, so the logical drop has nothing to drop
        //    and the destroy confirms "observed absent" exactly like a dedicated one.
        long before = Quotas.usedOf(databaseBucketOf(tenantAId));
        TenantConduits.as(principalA, () -> {
            try {
                new DatabaseService().destroy(databaseAName, true);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(databaseNamed(databaseAName)).as("step 2: the owner's destroy lands")
            .isNull();
        assertThat(Quotas.usedOf(databaseBucketOf(tenantAId)))
            .as("step 2: and hands the tenant's own slot back")
            .isEqualTo(before - 1);

        // 3. THE ENGINE SURVIVES its tenant: it is the operator's process, still serving
        //    tenant B's database, and even the last database's destroy leaves it standing
        //    (deleting an engine is an explicit operator act, by design).
        Row engine = Models.get(DatabaseEngineModel.class).findById(sharedEngineId);
        assertThat(engine).as("step 3: the shared engine row outlives the record").isNotNull();
        assertThat(DatabaseInstances.ownedBy(EngineHost.ofEngine(engine)))
            .as("step 3: and so does its instance row").isNotNull();
    }

    private static void capTenantAt(int userId, int maximum) {
        Model quotas = Models.get(InstanceQuotaModel.class);
        String packed = HohenheimAccess.packSubjects(Set.of("user:" + userId));
        Row row = quotas.find().where(InstanceQuotaModel.SUBJECTS.eq(packed)).first();
        if (row == null) {
            row = quotas.createEmptyRow();
            row.set(InstanceQuotaModel.SUBJECTS, packed);
        }
        row.set(InstanceQuotaModel.MAX_DATABASES, maximum);
        quotas.save(row);
        capRowId = row.get(InstanceQuotaModel.ID);
    }
}
