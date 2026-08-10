package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.DatabaseResource;
import be.elevenways.hohenheim.server.cms.InstanceDatabaseResource;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.test.database.EngineHandles;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Attaching a managed database to an INSTANCE: the two-sided authority rule, the
 * reachability refusals, the credential family the workload receives, the promise that no
 * credential is ever STORED, and the two lifecycle guarantees (a database cannot be
 * destroyed under an attached workload; a destroyed workload releases its database).
 *
 * No daemon is needed. Every refusal proven here fires before any driver call, and the
 * injection half is asserted through {@link DatabaseEnvInjection}'s injectable live
 * resolver -- the same hermetic seam {@code DatabaseEnvInjectionTest} uses for the site
 * lane. What a real container sees over the real link network is the live lane's claim,
 * and it rides the same {@code LinkNetworkSupport} calls the site lane already proves.
 *
 * AIDEV-NOTE: writes are driven at the MODEL through {@link TenantConduits}, not through a
 * form. The authority rule lives on the write pipeline exactly because the revision-restore
 * endpoint, the automation API and any direct {@code model.save} never pass a resource
 * method -- proving it through a form would prove the form omits a field.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InstanceDatabaseAttachTest extends HohenheimTestBase {

    private static final String PREFIX = "inst-db-";
    private static final String PASSWORD = "s3cr3t-attach-pw";

    private static Integer tenantAId;
    private static Integer tenantBId;
    private static Integer viewerId;
    private static Integer authAdminId;
    private static Principal principalA;
    private static Principal principalB;
    private static Principal principalViewer;
    private static Principal principalAuthAdmin;

    private static Integer hostId;
    private static Integer otherHostId;
    private static Integer instanceAId;
    private static Integer instanceBId;
    private static Integer incusInstanceId;
    private static Integer remoteInstanceId;
    private static Integer databaseAId;
    private static Integer databaseBId;
    private static Integer databaseRemoteId;

    @BeforeAll
    static void seed() {
        tenantAId = tenant("a@" + PREFIX + "test", "Attach Tenant A");
        tenantBId = tenant("b@" + PREFIX + "test", "Attach Tenant B");
        viewerId = tenant("viewer@" + PREFIX + "test", "Attach Viewer");
        authAdminId = tenant("authadmin@" + PREFIX + "test", "Auth Admin Only");
        principalA = new UserPrincipal(tenantAId, "Attach Tenant A");
        principalB = new UserPrincipal(tenantBId, "Attach Tenant B");
        principalViewer = new UserPrincipal(viewerId, "Attach Viewer");
        principalAuthAdmin = new UserPrincipal(authAdminId, "Auth Admin Only");

        hostId = host(PREFIX + "host");
        otherHostId = host(PREFIX + "other");

        instanceAId = instance(PREFIX + "srv-a", "hohenheim:docker_container", hostId);
        instanceBId = instance(PREFIX + "srv-b", "hohenheim:docker_container", hostId);
        incusInstanceId = instance(PREFIX + "srv-incus", "hohenheim:incus_container", hostId);
        remoteInstanceId = instance(PREFIX + "srv-remote", "hohenheim:docker_container", otherHostId);
        databaseAId = database(PREFIX + "db-a", hostId);
        databaseBId = database(PREFIX + "db-b", hostId);
        databaseRemoteId = database(PREFIX + "db-remote", otherHostId);

        // Tenant A owns instance A, the Incus one, the remote one and database A;
        // tenant B owns instance B and database B. MANAGE is the ownership marker and
        // implies CONFIG on an instance, so A is a legitimate attacher on its own pair.
        RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, instanceAId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, incusInstanceId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, remoteInstanceId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", tenantAId, DatabaseModel.MODEL_ID, databaseAId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", tenantBId, InstanceModel.MODEL_ID, instanceBId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", tenantBId, DatabaseModel.MODEL_ID, databaseBId,
            HohenheimAccess.MANAGE, true);
        // Tenant B also owns a database on the OTHER host: the fixture whose stored
        // (owner-namespaced) name and host the pre-fix mismatch refusal leaked.
        RecordGrants.grant("user", tenantBId, DatabaseModel.MODEL_ID, databaseRemoteId,
            HohenheimAccess.MANAGE, true);
        // The read-only teammate: VIEW on both ends of a legitimate pair, and nothing more.
        RecordGrants.grant("user", viewerId, InstanceModel.MODEL_ID, instanceAId,
            HohenheimAccess.VIEW, true);
        RecordGrants.grant("user", viewerId, DatabaseModel.MODEL_ID, databaseAId,
            HohenheimAccess.VIEW, true);
        // THE trap actor: an auth administrator (it can edit users) who holds no hohenheim
        // record grant at all. Without it a refusal could be zenit-auth's /admin baseline
        // answering rather than this rule.
        GrantService.createDirectGrant("user", authAdminId, "auth.users.edit", true);
    }

    @AfterAll
    static void cleanUp() {
        Model links = Models.get(InstanceDatabaseModel.class);
        for (Integer databaseId : List.of(databaseAId, databaseBId, databaseRemoteId)) {
            for (Row link : Models.get(InstanceDatabaseModel.class).findByDatabaseId(databaseId)) {
                links.delete(link.get(InstanceDatabaseModel.ID));
            }
        }
        Model instances = Models.get(InstanceModel.class);
        GeneratedRows.sweeping("test", () -> {
            for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
                instances.delete(row.get(InstanceModel.ID));
            }
            // The planted engine instances are named after their database record.
            for (Row row : instances.find()
                    .where(InstanceModel.GENERATED_FOR_MODEL.eq(DatabaseModel.MODEL_ID.toString()))
                    .all()) {
                Integer owner = row.get(InstanceModel.GENERATED_FOR_ID);
                if (databaseAId.equals(owner) || databaseBId.equals(owner)
                        || databaseRemoteId.equals(owner)) {
                    instances.delete(row.get(InstanceModel.ID));
                }
            }
        });
        Model databases = Models.get(DatabaseModel.class);
        for (Row row : databases.find().where(DatabaseModel.NAME.startsWith(PREFIX)).all()) {
            databases.delete(row.get(DatabaseModel.ID));
        }
        Model servers = Models.get(ServerModel.class);
        for (Row row : servers.find().where(ServerModel.NAME.startsWith(PREFIX)).all()) {
            servers.delete(row.get(ServerModel.ID));
        }
    }

    // -- the journeys ---------------------------------------------------------

    /**
     * The two-sided rule: an attach needs config on the INSTANCE and manage on the
     * DATABASE. Every refusal asserts the row count, never a thrown type alone -- a
     * refusal that still wrote the row would pass a throws-only test.
     */
    @Test
    @Order(1)
    void attachingNeedsAuthorityOverBOTHEndsAndPersistsNothingWhenItDoesNot() {
        // 1. ATTACK -- a tenant attaching a database they do NOT own. Tenant A manages
        //    instance A and would receive tenant B's credentials as plain environment
        //    variables inside their own container: the one-sided check's whole failure mode.
        Throwable foreignDatabase = attachAs(principalA, instanceAId, databaseBId, "DB");
        assertThat(violationKeys(foreignDatabase))
            .as("step 1: attaching a foreign database is refused by name")
            .contains("database_not_permitted");
        assertThat(linksOf(instanceAId))
            .as("step 1: and NOTHING was written -- a refusal that still linked would leak")
            .isEmpty();

        // 2. ATTACK -- a tenant attaching to an instance they do NOT own, which hands
        //    their own database to a runtime somebody else controls.
        Throwable foreignInstance = attachAs(principalA, instanceBId, databaseAId, "DB");
        assertThat(violationKeys(foreignInstance))
            .as("step 2: attaching to a foreign instance is refused by name")
            .contains("tenant_instance_not_managed");
        assertThat(linksOf(instanceBId)).as("step 2: nothing was written").isEmpty();

        // 3. ATTACK -- both ends foreign, which must answer the same way rather than
        //    revealing which half failed first.
        assertThat(violationKeys(attachAs(principalB, instanceAId, databaseAId, "DB")))
            .as("step 3: the instance side answers first, uniformly")
            .contains("tenant_instance_not_managed");
        assertThat(linksOf(instanceAId)).isEmpty();

        // 4. ATTACK -- the read-only teammate. VIEW on both ends is explicitly not
        //    authority to wire them together: config is what authors what an instance IS.
        assertThat(violationKeys(attachAs(principalViewer, instanceAId, databaseAId, "DB")))
            .as("step 4: view on both ends is not authority to attach")
            .contains("tenant_instance_not_managed");
        assertThat(linksOf(instanceAId)).isEmpty();

        // 5. ATTACK -- an auth ADMINISTRATOR who holds no hohenheim grant. This is the
        //    layered-enforcer check: the refusal above must be THIS rule and not
        //    zenit-auth's panel baseline refusing before it ever runs.
        assertThat(violationKeys(attachAs(principalAuthAdmin, instanceAId, databaseAId, "DB")))
            .as("step 5: an auth admin is not a hohenheim record holder")
            .contains("tenant_instance_not_managed");
        assertThat(linksOf(instanceAId)).isEmpty();

        // 6. POSITIVE ANCHOR: the legitimate owner of BOTH ends attaches, so steps 1-5
        //    are a boundary rather than a mechanism that refuses everyone.
        assertThat(attachAs(principalA, instanceAId, databaseAId, "DB"))
            .as("step 6: the owner of both ends attaches").isNull();
        List<Row> links = linksOf(instanceAId);
        assertThat(links).as("step 6: exactly one attachment landed").hasSize(1);
        assertThat((Integer) links.get(0).get(InstanceDatabaseModel.DATABASE_ID))
            .isEqualTo(databaseAId);

        // 7. And DETACHING answers to the same two-sided rule: tenant B cannot remove
        //    tenant A's attachment, and the row survives the attempt.
        Integer linkId = links.get(0).get(InstanceDatabaseModel.ID);
        Throwable foreignDetach = catchThrowable(() -> TenantConduits.as(principalB,
            () -> Models.get(InstanceDatabaseModel.class).delete(linkId)));
        assertThat(violationKeys(foreignDetach))
            .as("step 7: a foreign detach is refused")
            .contains("tenant_instance_not_managed");
        assertThat(linksOf(instanceAId))
            .as("step 7: a refused detach did not delete -- status-only would have passed")
            .hasSize(1);
    }

    /**
     * What the workload actually receives, and -- the property this whole design exists to
     * keep -- what is NEVER stored. Depends on the attachment made in journey 1.
     */
    @Test
    @Order(2)
    void theWorkloadReceivesADerivedFamilyThatIsStoredNOWHERE() {
        // 1. The derived family for the attached database, resolved through the injectable
        //    live resolver (a running engine, container-network style).
        Map<String, String> derived = DatabaseEnvInjection.envForInstance(instanceAId, running());
        assertThat(derived)
            .as("step 1: the prefixed family and the primary URL are derived")
            .containsEntry("DB_PASSWORD", PASSWORD)
            .containsEntry("DB_USER", "appuser")
            .containsEntry("DB_NAME", "appdb")
            .containsKeys("DB_HOST", "DB_PORT", "DB_URL", "DATABASE_URL");
        assertThat(derived.get("DB_PORT"))
            .as("step 1: the engine's NATIVE port, never a published one")
            .isEqualTo(String.valueOf(ManagedDatabase.Engine.POSTGRES.port()));
        assertThat(derived.get("DB_HOST"))
            .as("step 1: a container hostname, never 127.0.0.1 (which is the container itself)")
            .isNotEqualTo("127.0.0.1");

        // 2. THE COUNTERFACTUAL that matters most: the credential is in the DERIVED map and
        //    in NO stored column. Not the settings map, not an instance variable row.
        Row instance = Models.get(InstanceModel.class).findById(instanceAId);
        assertThat(String.valueOf((Object) instance.get(InstanceModel.SETTINGS)))
            .as("step 2: the attach wrote no credential into instances.settings")
            .doesNotContain(PASSWORD);
        for (Row variable : Models.get(InstanceVariableModel.class).findByInstanceId(instanceAId)) {
            assertThat(String.valueOf((Object) variable.get(InstanceVariableModel.SECRET_VALUE)))
                .as("step 2: nor into the instance-variable carrier")
                .doesNotContain(PASSWORD);
            assertThat(String.valueOf((Object) variable.get(InstanceVariableModel.PLAIN_VALUE)))
                .doesNotContain(PASSWORD);
        }

        // 3. The derived values substitute into the command the workload RUNS, which is the
        //    Pterodactyl shape: a start command that names its database.
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("command", "./run --db {{DB_HOST}}:{{DB_PORT}} --pass {{DB_PASSWORD}}");
        Map<String, Object> applied = new InstanceVariables()
            .applyToSettings(settings, Map.of(), derived);
        assertThat(String.valueOf(applied.get("command")))
            .as("step 3: {{KEY}} tokens resolve from the derived family")
            .isEqualTo("./run --db " + derived.get("DB_HOST") + ":" + derived.get("DB_PORT")
                + " --pass " + PASSWORD);

        // 4. LAYERING: anything operator-authored overrides the derived baseline, the same
        //    order the site lane documents. A stale operator value must not be silently
        //    replaced by an attach, and the derived value must not be silently replaced by
        //    nothing.
        Map<String, Object> withEnv = new LinkedHashMap<>();
        withEnv.put("environment_variables", Map.of("DB_HOST", "operator-authored"));
        Map<String, Object> layered = new InstanceVariables()
            .applyToSettings(withEnv, Map.of("DB_NAME", "declared-wins"), derived);
        Object env = layered.get("environment_variables");
        assertThat(env).isInstanceOf(Map.class);
        Map<?, ?> effective = (Map<?, ?>) env;
        assertThat(effective.get("DB_HOST"))
            .as("step 4: the settings' own env overrides the derived baseline")
            .isEqualTo("operator-authored");
        assertThat(effective.get("DB_NAME"))
            .as("step 4: a declared variable overrides both")
            .isEqualTo("declared-wins");
        assertThat(effective.get("DB_PASSWORD"))
            .as("step 4: and everything nobody overrode still arrives")
            .isEqualTo(PASSWORD);

        // 5. A database that is NOT running contributes NOTHING: credentials for a dead
        //    engine are the silent-success shape, not a favour.
        assertThat(DatabaseEnvInjection.envForInstance(instanceAId, absent()))
            .as("step 5: an unreachable engine injects no variables at all")
            .isEmpty();

        // 6. And an instance with no attachment gets nothing, so step 1 is the attachment
        //    speaking and not a family everyone receives.
        assertThat(DatabaseEnvInjection.envForInstance(instanceBId, running()))
            .as("step 6: an unattached instance receives no database variables")
            .isEmpty();
    }

    /**
     * Reachability is refused at LINK time, so injection never emits an address the
     * workload cannot dial.
     */
    @Test
    @Order(3)
    void anUnreachablePairingIsRefusedWhenItIsATTACHEDRatherThanAtTheNextStart() {
        InstanceDatabaseResource resource = new InstanceDatabaseResource();
        AccessContext admin = AccessContext.anonymous();

        // 1. An Incus instance has no link networks at all, so its container could never
        //    reach the engine -- refused by name rather than at the next deploy.
        assertThat(violationKeys(catchThrowable(() -> resource.persistRow(
                Map.of("instance_id", incusInstanceId, "database_id", databaseAId,
                    "env_prefix", "DB"), admin))))
            .as("step 1: a driver without link networks cannot host an attachment")
            .contains("instance_kind_no_injection");

        // 2. A link network exists only on the daemon both workloads share.
        assertThat(violationKeys(catchThrowable(() -> resource.persistRow(
                Map.of("instance_id", remoteInstanceId, "database_id", databaseAId,
                    "env_prefix", "DB"), admin))))
            .as("step 2: a cross-host pairing is refused")
            .contains("database_instance_server_mismatch");

        // 3. The same database twice on one instance is a duplicate, refused by NAME
        //    before any anonymous constraint error.
        assertThat(violationKeys(catchThrowable(() -> resource.persistRow(
                Map.of("instance_id", instanceAId, "database_id", databaseAId,
                    "env_prefix", "OTHER"), admin))))
            .as("step 3: attaching the same database twice is refused")
            .contains("database_already_attached");

        // 4. Two databases cannot share one variable family, or the second would silently
        //    overwrite the first's credentials.
        assertThat(violationKeys(catchThrowable(() -> resource.persistRow(
                Map.of("instance_id", instanceAId, "database_id", databaseBId,
                    "env_prefix", "db"), admin))))
            .as("step 4: a taken prefix is refused, case-insensitively")
            .contains("prefix_taken");

        // 5. A prefix that is not a legal variable name is refused.
        assertThat(violationKeys(catchThrowable(() -> resource.persistRow(
                Map.of("instance_id", instanceAId, "database_id", databaseBId,
                    "env_prefix", "9-bad"), admin))))
            .as("step 5: a prefix that cannot be a variable name is refused")
            .contains("prefix_format");

        // 6. POSITIVE ANCHOR: a second database on a FREE prefix is accepted, so steps
        //    3-5 discriminate rather than forbid a second attachment.
        assertThat(catchThrowable(() -> resource.persistRow(
                Map.of("instance_id", instanceAId, "database_id", databaseBId,
                    "env_prefix", "CACHE"), admin)))
            .as("step 6: a second database on its own prefix attaches")
            .isNull();
        assertThat(linksOf(instanceAId)).as("step 6: two attachments now").hasSize(2);
        // Clean the second one back off: the later journeys reason about ONE attachment.
        Models.get(InstanceDatabaseModel.class).find()
            .where(InstanceDatabaseModel.DATABASE_ID.eq(databaseBId)).delete();
    }

    /**
     * The two lifecycle guarantees, which are each other's inverse: a database cannot be
     * destroyed while a live workload depends on it, and a destroyed workload stops
     * holding a database hostage.
     */
    @Test
    @Order(4)
    void aDatabaseCannotDieUnderALiveWorkloadAndADeadWorkloadReleasesIt() {
        assertThat(linksOf(instanceAId))
            .as("precondition: instance A is still attached to database A").hasSize(1);

        // 1. THE ATTACK: destroy the database out from under the running workload. The
        //    refusal must NAME the instance -- an in-use check that only counted sites
        //    would have let this through and left the workload unable to connect with
        //    nothing anywhere saying why.
        Row database = Models.get(DatabaseModel.class).findById(databaseAId);
        Throwable inUse = catchThrowable(() ->
            new DatabaseResource().deleteRow(database, AccessContext.anonymous()));
        assertThat(violationKeys(inUse))
            .as("step 1: a database attached to a live instance cannot be destroyed")
            .contains("database_in_use");
        assertThat(InstanceDatabaseLinks.liveInstanceNames(databaseAId))
            .as("step 1: and the refusal names the workload the operator has to detach")
            .contains(PREFIX + "srv-a");
        assertThat((Object) Models.get(DatabaseModel.class).findById(databaseAId))
            .as("step 1: the record is still there -- a refusal that still deleted"
                + " would pass a status-only test")
            .isNotNull();

        // 2. A SOFT-DELETED instance is not a live dependant. Destroy soft-deletes, so a
        //    link naming a dead record must not keep the database hostage forever.
        Model instances = Models.get(InstanceModel.class);
        Row doomed = instances.findById(instanceAId);
        doomed.set(InstanceModel.DELETED_AT, Instant.now());
        be.elevenways.hohenheim.server.auth.TenantWrites.inAuthorizedOperation(
            () -> instances.save(doomed));
        assertThat(InstanceDatabaseLinks.liveInstanceNames(databaseAId))
            .as("step 2: a soft-deleted workload names nobody")
            .isEmpty();

        // 3. And the explicit cleanup destroy performs drops the rows themselves, because
        //    a soft delete fires NO remove hooks and nothing else would ever do it.
        InstanceDatabaseLinks.deleteForInstance(instanceAId);
        assertThat(linksOf(instanceAId))
            .as("step 3: destroying the workload dropped its attachments")
            .isEmpty();

        // 4. POSITIVE ANCHOR for the gate's INPUT: database B was never attached, so it
        //    names nobody while step 1's database named a workload. Asserted on
        //    liveInstanceNames rather than by calling deleteRow again on purpose -- a
        //    second deleteRow would run past the gate and into the real daemon, which
        //    would make the assertion about Docker rather than about this rule.
        assertThat(InstanceDatabaseLinks.liveInstanceNames(databaseBId))
            .as("step 4: a never-attached database names no workload")
            .isEmpty();

        // 5. Restore the instance row so the class's own cleanup can see it.
        Row revived = instances.find().where(InstanceModel.ID.eq(instanceAId)).first();
        if (revived != null) {
            revived.set(InstanceModel.DELETED_AT, null);
            be.elevenways.hohenheim.server.auth.TenantWrites.inAuthorizedOperation(
                () -> instances.save(revived));
        }
    }

    /**
     * The FORM path must not be an existence/name/host oracle: before the fix,
     * {@code InstanceDatabaseResource.validate} ran its UNSCOPED lookups ahead of the
     * authority decision, so a tenant probing database ids from their own instance's
     * attach form got three distinguishable answers -- {@code database_missing} for an
     * absent id, {@code database_instance_server_mismatch} interpolating the stored
     * (owner-namespaced) database NAME plus both host names for a foreign cross-host id,
     * and the uniform refusal only for a foreign same-host id. That violates
     * {@code HohenheimAccess.databaseRefusal}'s contract: visibility, absence and denial
     * are one answer.
     */
    @Test
    @Order(5)
    void probingForeignDatabaseIdsThroughTheFormIsOneIndistinguishableAnswer() {
        // 0. A FRESH instance owned by tenant A. Journey 4 soft-deleted instance A, and
        //    zenit-auth's RecordGrantCleanup revokes a soft-deleted record's grants (a
        //    restore never re-grants), so this journey brings its own probe base. The
        //    precondition pins the capability so every refusal below is provably about
        //    the DATABASE side.
        int probeInstanceId = instance(PREFIX + "srv-probe", "hohenheim:docker_container",
            hostId);
        RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, probeInstanceId,
            HohenheimAccess.MANAGE, true);
        assertThat(HohenheimAccess.hasInstanceCapability(
                AccessContext.of(TenantConduits.stubFor(principalA)), probeInstanceId,
                HohenheimAccess.CONFIG))
            .as("precondition: tenant A authors the probe instance")
            .isTrue();

        // 1. THE THREE PROBES, all from tenant A's own instance: foreign-same-host,
        //    foreign-cross-host, absent. Pre-fix these carried three different keys and
        //    the cross-host one READ BACK tenant B's database name and both host names.
        Throwable sameHost = probeAs(principalA, probeInstanceId, databaseBId);
        Throwable crossHost = probeAs(principalA, probeInstanceId, databaseRemoteId);
        Throwable absent = probeAs(principalA, probeInstanceId, 999999999);
        assertThat(violationKeys(sameHost))
            .as("step 1: the same-host foreign probe answers the uniform refusal")
            .contains("database_not_permitted");
        assertThat(violationKeys(crossHost))
            .as("step 1: the cross-host foreign probe answers the SAME refusal, never"
                + " database_instance_server_mismatch")
            .contains("database_not_permitted")
            .doesNotContain("server_mismatch");
        assertThat(violationKeys(absent))
            .as("step 1: the absent-id probe answers the SAME refusal, never"
                + " database_missing")
            .contains("database_not_permitted")
            .doesNotContain("database_missing");
        assertThat(String.valueOf(crossHost.getMessage()))
            .as("step 1: and nothing about the foreign record leaks -- not its stored"
                + " name, not its host")
            .doesNotContain(PREFIX + "db-remote")
            .doesNotContain(PREFIX + "other");

        // 2. BYTE-IDENTICAL, which is the whole contract: a probing tenant cannot tell
        //    absent from cross-host from same-host-not-yours.
        assertThat(String.valueOf(crossHost.getMessage()))
            .as("step 2: cross-host and same-host probes are indistinguishable")
            .isEqualTo(String.valueOf(sameHost.getMessage()));
        assertThat(String.valueOf(absent.getMessage()))
            .as("step 2: and the absent id answers identically too")
            .isEqualTo(String.valueOf(sameHost.getMessage()));
        assertThat(linksOf(probeInstanceId))
            .as("step 2: no probe wrote anything").isEmpty();

        // 3. POSITIVE ANCHOR: the legitimate owner attaches THROUGH THE SAME FORM PATH,
        //    so the collapse above is an ordering, not a form that refuses everyone.
        TenantConduits.as(principalA, () -> new InstanceDatabaseResource().persistRow(
            Map.of("instance_id", probeInstanceId, "database_id", databaseAId,
                "env_prefix", "DB"),
            AccessContext.of(TenantConduits.stubFor(principalA))));
        List<Row> links = linksOf(probeInstanceId);
        assertThat(links).as("step 3: the owner's attach landed").hasSize(1);

        // 4. The UPDATE shape is the same oracle (re-point my link at your database):
        //    probing through updateRow answers the identical uniform refusal and moves
        //    nothing.
        Row link = links.get(0);
        Throwable repointed = catchThrowable(() -> TenantConduits.as(principalA,
            () -> new InstanceDatabaseResource().updateRow(link,
                Map.of("database_id", databaseRemoteId),
                AccessContext.of(TenantConduits.stubFor(principalA)))));
        assertThat(violationKeys(repointed))
            .as("step 4: the update probe answers the uniform refusal")
            .contains("database_not_permitted");
        assertThat(String.valueOf(repointed.getMessage()))
            .as("step 4: byte-identical to every other probe")
            .isEqualTo(String.valueOf(sameHost.getMessage()));
        assertThat((Integer) linksOf(probeInstanceId).get(0)
                .get(InstanceDatabaseModel.DATABASE_ID))
            .as("step 4: the link still names the owner's own database")
            .isEqualTo(databaseAId);

        // 5. POSITIVE ANCHOR for the diagnostic: an OPERATOR (no tenant origin) still
        //    gets the reachability message by name -- the collapse is tenant-scoped
        //    ordering, not a lobotomized validator.
        Throwable operator = catchThrowable(() -> new InstanceDatabaseResource().persistRow(
            Map.of("instance_id", probeInstanceId, "database_id", databaseRemoteId,
                "env_prefix", "OP"), AccessContext.anonymous()));
        assertThat(violationKeys(operator))
            .as("step 5: operators keep database_instance_server_mismatch")
            .contains("database_instance_server_mismatch");

        // 6. Leave the class the way journey 4 left it: no attachments on instance A.
        Models.get(InstanceDatabaseModel.class).find()
            .where(InstanceDatabaseModel.DATABASE_ID.eq(databaseAId)).delete();
    }

    /** One form-path probe as one principal; @return the refusal (never null here). */
    private static Throwable probeAs(Principal principal, Integer instanceId,
                                     Integer databaseId) {
        return catchThrowable(() -> TenantConduits.as(principal,
            () -> new InstanceDatabaseResource().persistRow(
                Map.of("instance_id", instanceId, "database_id", databaseId,
                    "env_prefix", "DB"),
                AccessContext.of(TenantConduits.stubFor(principal)))));
    }

    // -- fixtures -------------------------------------------------------------

    /** Attach as one principal; @return the refusal, or null when it landed. */
    private static Throwable attachAs(Principal principal, Integer instanceId,
                                      Integer databaseId, String prefix) {
        return catchThrowable(() -> TenantConduits.as(principal, () -> {
            Model links = Models.get(InstanceDatabaseModel.class);
            Row row = links.createEmptyRow();
            row.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
            row.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
            row.set(InstanceDatabaseModel.ENV_PREFIX, prefix);
            links.save(row);
        }));
    }

    private static List<Row> linksOf(Integer instanceId) {
        return Models.get(InstanceDatabaseModel.class).findByInstanceId(instanceId);
    }

    /** A live resolver reporting a running engine on a published port. */
    private static DatabaseEnvInjection.LiveResolver running() {
        return row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, 55432);
    }

    /** A live resolver reporting no container at all. */
    private static DatabaseEnvInjection.LiveResolver absent() {
        return row -> new ManagedDatabase.LiveStatus(ContainerState.ABSENT, null);
    }

    private static String violationKeys(Throwable thrown) {
        assertThat(thrown).as("a refusal was raised at all").isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }

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

    private static int host(String name) {
        Model servers = Models.get(ServerModel.class);
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.PREFLIGHT_OK, true);
        servers.save(row);
        HostPreflight.store(name, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return row.get(ServerModel.ID);
    }

    private static int instance(String name, String kind, Integer serverId) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.SERVER_ID, serverId);
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "tag", "latest"));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int database(String name, Integer serverId) {
        Model databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, PASSWORD);
        row.set(DatabaseModel.SERVER_ID, serverId);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(row);
        Integer id = row.get(DatabaseModel.ID);
        // The engine's address IS its owned instance's handle since the lowering, so a
        // record without one has no reachable address at all -- plant it like production.
        EngineHandles.plant(id, name, "postgres", InstanceModel.STATUS_RUNNING);
        return id;
    }
}
