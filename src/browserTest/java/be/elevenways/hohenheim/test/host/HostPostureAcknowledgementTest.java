package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.ServerResource;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostPostureAcknowledgement;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.cms.common.action.ActionContext;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.microcopy.Translation;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The shared-container risk acknowledgement, end to end: declaring the posture grants
 * nothing, an operator's named act does, and three independent things take it away again.
 *
 * WHY IT EXISTS: the instance-tier plan makes this a Phase 3 ENTRY blocker -- "the
 * acknowledgement records actor, timestamp and warning version; a boolean hidden in
 * settings is not sufficient" -- and Phases 3 through 8 shipped past it. Until this
 * landed, setting {@code shared_container} was one ordinary dropdown on an admin form,
 * after which two mutually hostile tenants shared one kernel with no record of who
 * decided that.
 *
 * DELIBERATE DIVERGENCE from the plan's gate wording, stated out loud: the gate step says
 * "two hostile tenant fixtures are refused co-location on an unacknowledged container
 * host", but the refusal here fires on the FIRST hostile workload, not the second.
 * Refusing only the second would assert that the first one is safe, which contradicts the
 * clause -- the acknowledgement is about the POSTURE, not about a count. The honest shape
 * is what steps 3 to 5 walk: the first placement is refused, the acknowledgement opens the
 * host, and both fixtures then co-locate on it.
 *
 * No daemon is contacted: every gate here decides over stored record state.
 */
class HostPostureAcknowledgementTest {

    private static final String PREFIX = "ack-";
    private static final String BUCKET_A = "ack-tenant-a";
    private static final String BUCKET_B = "ack-tenant-b";
    private static final String DOCKER_KIND = "hohenheim:docker_container";

    /**
     * The sha256 of the shipped {@code server.acknowledge_body} message per locale, pinned
     * against {@link ServerModel#POSTURE_WARNING_VERSION}. See step 1 for what to do when
     * this fails.
     */
    private static final Map<String, String> WARNING_DIGESTS = Map.of(
        "en", "b7bfede449daaeb17174de074be2216c1c146a55bb53e2ebdd54fc8d3742ebe4",
        // Re-pinned 2026-08-27, SPELLING ONLY: the shouted numeral in the nl warning
        // gained the acute accents Dutch spells it with. The meaning is unchanged, so
        // POSTURE_WARNING_VERSION deliberately stays where it is.
        "nl", "4e3ad200060fa7ac8e1530af935e6ec086369f084b7ca08a90c3bfe2da46906a");

    private static SqlDatasource datasource;

    /**
     * Its OWN database: the chooser walks EVERY host row, so a neighbouring class's
     * leftover admitted host would silently change what "refused" means here.
     */
    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void aSharedContainerHostTakesNoTenantWorkloadUntilANamedOperatorAcceptsTheRisk()
            throws Exception {
        // 1. THE FORGOT-TO-BUMP GUARD, before anything else: the warning an operator is
        //    shown and the version their acceptance is recorded against are pinned to each
        //    other. Editing the text without bumping the version would leave every stored
        //    acknowledgement claiming to answer for a warning nobody ever read.
        for (Map.Entry<String, String> pinned : WARNING_DIGESTS.entrySet()) {
            assertThat(sha256Of(shippedWarning(pinned.getKey())))
                .as("step 1: the shipped %s risk warning no longer matches the digest pinned"
                    + " against POSTURE_WARNING_VERSION %s. If the MEANING changed, bump"
                    + " ServerModel.POSTURE_WARNING_VERSION (every stored acknowledgement"
                    + " goes stale and operators re-accept). If only the WORDING changed,"
                    + " re-pin the digest here.", pinned.getKey(),
                    ServerModel.POSTURE_WARNING_VERSION)
                .isEqualTo(pinned.getValue());
        }

        Db.run(datasource, () -> {
            int hostId = admittedSharedHost("edge-1");
            ServerModel servers = Models.get(ServerModel.class);

            // 2. Declaring the posture stores the posture AND NOTHING ELSE. This is the
            //    "a boolean hidden in settings is not sufficient" assertion: the write
            //    every path funnels through leaves no actor, no timestamp and no version.
            Row declared = servers.findById(hostId);
            assertThat((String) declared.get(ServerModel.POSTURE))
                .as("step 2: the host really does declare shared containers")
                .isEqualTo(ServerModel.POSTURE_SHARED_CONTAINER);
            assertThat(ServerModel.postureAcknowledged(declared))
                .as("step 2: and declaring it acknowledged NOTHING")
                .isFalse();
            assertThat(Map.of(
                    "posture", String.valueOf((Object) declared.get(ServerModel.ACKNOWLEDGED_POSTURE)),
                    "by", String.valueOf((Object) declared.get(ServerModel.ACKNOWLEDGED_BY)),
                    "at", String.valueOf((Object) declared.get(ServerModel.ACKNOWLEDGED_AT))))
                .as("step 2: no acknowledgement column was written by the plain save")
                .isEqualTo(Map.of("posture", "null", "by", "null", "at", "null"));

            // 3. So the FIRST hostile-tenant container is refused BY NAME, and nothing
            //    lands: placement drops the host from the eligible set, the deploy gate
            //    names the reason, and the host holds no workload afterwards.
            Throwable refusedByGate = catchThrowable(() ->
                HostAdmission.requireInstancePlacement(hostId,
                    WorkloadIsolation.SHARED_KERNEL, BUCKET_A));
            assertThat(keyOf(refusedByGate))
                .as("step 3: the deploy gate refuses an unacknowledged shared-container"
                    + " host by name")
                .isEqualTo("host_posture_unacknowledged");
            assertThat(keyOf(catchThrowable(() -> InstancePlacement.chooseForBucket(
                    BUCKET_A, workload(256), null))))
                .as("step 3: and the chooser will not offer it either, so there is nowhere"
                    + " for the workload to go")
                .isEqualTo("no_placement_available");
            assertThat(liveInstancesOn(hostId))
                .as("step 3: HOST STATE, not just the API refusal -- nothing was placed"
                    + " on the host")
                .isZero();

            // 4. The operator's act: through the ADMIN SURFACE's own row action, which is
            //    the only lane that exists. All five columns land, and the activity row
            //    beside them carries a real actor.
            Row unacknowledged = servers.findById(hostId);
            RowAction.Invoke<Row> action = acknowledgeAction();
            assertThat(action.isVisibleFor(unacknowledged, AccessContext.anonymous()))
                .as("step 4: the action offers itself on a host that needs it")
                .isTrue();
            Accountability.runAs(new Accountability("user:7", "Ada Operator",
                    "203.0.113.9", "test-agent", Accountability.ORIGIN_WEB),
                () -> action.handler().apply(unacknowledged,
                    ActionContext.of(AccessContext.anonymous())));

            Row acknowledged = servers.findById(hostId);
            assertThat(Map.of(
                    "posture", String.valueOf((Object) acknowledged.get(ServerModel.ACKNOWLEDGED_POSTURE)),
                    "version", String.valueOf((Object) acknowledged.get(ServerModel.ACKNOWLEDGED_WARNING_VERSION)),
                    "by", String.valueOf((Object) acknowledged.get(ServerModel.ACKNOWLEDGED_BY)),
                    "label", String.valueOf((Object) acknowledged.get(ServerModel.ACKNOWLEDGED_BY_LABEL))))
                .as("step 4: actor, warning version and the posture accepted are all on"
                    + " the RECORD -- the authority a gate can still read in a year")
                .isEqualTo(Map.of("posture", ServerModel.POSTURE_SHARED_CONTAINER,
                    "version", String.valueOf(ServerModel.POSTURE_WARNING_VERSION),
                    "by", "user:7", "label", "Ada Operator"));
            assertThat((Instant) acknowledged.get(ServerModel.ACKNOWLEDGED_AT))
                .as("step 4: with a timestamp").isNotNull();
            assertThat(action.isVisibleFor(acknowledged, AccessContext.anonymous()))
                .as("step 4: and the action stops offering itself once it is done")
                .isFalse();

            Row activity = latestAcknowledgementActivity();
            assertThat(activity)
                .as("step 4: the activity row is written BESIDE the columns, as history --"
                    + " CleanOldActivity prunes at 90 days, so it can never be the"
                    + " authority a gate reads")
                .isNotNull();
            assertThat(String.valueOf((Object) activity.get(ActivityModel.ACTOR)))
                .as("step 4: and it names a real actor, not system work")
                .isEqualTo("user:7");

            // 5. Now BOTH hostile-tenant fixtures place, and they CO-LOCATE: this is the
            //    plan's gate step, with the divergence stated in the class docblock -- the
            //    refusal was on the first workload, not on the second.
            assertThat(InstancePlacement.chooseForBucket(BUCKET_A, workload(256), null))
                .as("step 5: tenant A's container places on the acknowledged host")
                .isEqualTo(hostId);
            assertThat(InstancePlacement.chooseForBucket(BUCKET_B, workload(256), null))
                .as("step 5: and so does a DIFFERENT tenant's -- they share one kernel"
                    + " because an operator accepted exactly that, by name")
                .isEqualTo(hostId);

            // 6. INVALIDATOR ONE, and it is a schema hook rather than a branch in the
            //    resource: moving the posture anywhere else erases the acknowledgement,
            //    so coming back to shared_container needs a fresh human act.
            Row moving = servers.findById(hostId);
            moving.set(ServerModel.POSTURE, ServerModel.POSTURE_TRUSTED_ONLY);
            servers.save(moving);
            Row moved = servers.findById(hostId);
            assertThat((String) moved.get(ServerModel.ACKNOWLEDGED_POSTURE))
                .as("step 6: the acknowledgement was ERASED by the posture change, not"
                    + " left lying around to be resurrected")
                .isNull();
            moved.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
            servers.save(moved);
            Row returned = servers.findById(hostId);
            assertThat(ServerModel.postureAcknowledged(returned))
                .as("step 6: so going away and coming back does NOT restore it")
                .isFalse();
            assertThat(keyOf(catchThrowable(() ->
                    HostAdmission.requireInstancePlacement(hostId,
                        WorkloadIsolation.SHARED_KERNEL, BUCKET_A))))
                .as("step 6: and placement refuses again, by the same name")
                .isEqualTo("host_posture_unacknowledged");

            // 6b. THE PARTIAL SAVE, which is the shape every real posture change has. A CMS
            //     update carries only the keys it changed, so a save staging posture ALONE
            //     reached the eraser with no acknowledged_posture on the row at all: it read
            //     null, returned early, and silently did nothing on exactly the write it
            //     exists for. (The step-6 full-row save masked it -- findById loads every
            //     column.) The gate still refused the mismatched pair, so nothing was ever
            //     wrongly granted; what was open is the away-and-back resurrection.
            Accountability.runAs(new Accountability("user:7", "Ada Operator",
                    "203.0.113.9", "test-agent", Accountability.ORIGIN_WEB),
                () -> HostPostureAcknowledgement.record(servers.findById(hostId)));
            assertThat(ServerModel.postureAcknowledged(servers.findById(hostId)))
                .as("step 6b precondition: the host is acknowledged again")
                .isTrue();

            Row partial = servers.createEmptyRow();
            partial.set(ServerModel.ID, hostId);
            partial.set(ServerModel.POSTURE, ServerModel.POSTURE_DEDICATED);
            servers.save(partial);
            assertThat((String) servers.findById(hostId).get(ServerModel.ACKNOWLEDGED_POSTURE))
                .as("step 6b: a save staging ONLY the posture must still erase the"
                    + " acknowledgement")
                .isNull();
            assertThat((String) servers.findById(hostId).get(ServerModel.ACKNOWLEDGED_BY))
                .as("step 6b: and erase the whole record of it, not just the posture column")
                .isNull();

            Row back = servers.createEmptyRow();
            back.set(ServerModel.ID, hostId);
            back.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
            servers.save(back);
            assertThat(ServerModel.postureAcknowledged(servers.findById(hostId)))
                .as("step 6b: so going away and coming back through partial saves does not"
                    + " resurrect it either")
                .isFalse();

            // 6c. And the hook does NOT fire on a save that never touches the posture: an
            //     eraser that ran on every write would wipe acknowledgements at random.
            Accountability.runAs(new Accountability("user:7", "Ada Operator",
                    "203.0.113.9", "test-agent", Accountability.ORIGIN_WEB),
                () -> HostPostureAcknowledgement.record(servers.findById(hostId)));
            Row unrelated = servers.createEmptyRow();
            unrelated.set(ServerModel.ID, hostId);
            unrelated.set(ServerModel.LAST_SEEN_AT, Instant.now());
            servers.save(unrelated);
            assertThat(ServerModel.postureAcknowledged(servers.findById(hostId)))
                .as("step 6c: a partial save that never stages the posture leaves the"
                    + " acknowledgement alone")
                .isTrue();

            // Hand step 7 the state step 6 used to leave it: unacknowledged, on the
            // shared-container posture. record() refuses an already-acknowledged host.
            Row clear = servers.createEmptyRow();
            clear.set(ServerModel.ID, hostId);
            clear.set(ServerModel.POSTURE, ServerModel.POSTURE_DEDICATED);
            servers.save(clear);
            Row shared = servers.createEmptyRow();
            shared.set(ServerModel.ID, hostId);
            shared.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
            servers.save(shared);

            // 7. INVALIDATOR TWO: a warning-version bump goes stale WITHOUT touching the
            //    row. Simulated by storing an older version -- the arithmetic is the same
            //    one a real bump performs, and it needs no write to invalidate.
            Accountability.runAs(new Accountability("user:7", "Ada Operator",
                    "203.0.113.9", "test-agent", Accountability.ORIGIN_WEB),
                () -> HostPostureAcknowledgement.record(servers.findById(hostId)));
            Row current = servers.findById(hostId);
            assertThat(ServerModel.postureAcknowledged(current))
                .as("step 7 precondition: a fresh acknowledgement is current again")
                .isTrue();
            current.set(ServerModel.ACKNOWLEDGED_WARNING_VERSION,
                ServerModel.POSTURE_WARNING_VERSION - 1);
            servers.save(current);
            Row stale = servers.findById(hostId);
            assertThat(ServerModel.postureAcknowledged(stale))
                .as("step 7: an acknowledgement of an OLDER warning does not answer for"
                    + " the current one")
                .isFalse();
            assertThat((String) stale.get(ServerModel.ACKNOWLEDGED_BY))
                .as("step 7: and going stale wrote nothing -- the record of who accepted"
                    + " what is still there to read")
                .isEqualTo("user:7");
            assertThat(keyOf(catchThrowable(() ->
                    HostAdmission.requireInstancePlacement(hostId,
                        WorkloadIsolation.SHARED_KERNEL, BUCKET_A))))
                .as("step 7: the host takes no new tenant container until the new warning"
                    + " is accepted")
                .isEqualTo("host_posture_unacknowledged");

            // 8. And the act itself refuses everything that is not a human accepting a
            //    stated risk: unattended work has no actor to record.
            Row systemAttempt = servers.findById(hostId);
            assertThat(keyOf(catchThrowable(() ->
                    HostPostureAcknowledgement.record(systemAttempt))))
                .as("step 8: system-originated work cannot acknowledge a risk on an"
                    + " operator's behalf -- an actorless record is the forgery the"
                    + " clause exists to prevent")
                .isEqualTo("posture_acknowledgement_needs_actor");
        });
    }

    // -- fixtures ----------------------------------------------------------------

    /** An admitted docker host declaring shared containers, measured and reachable. */
    private static int admittedSharedHost(String name) {
        ServerModel servers = Models.get(ServerModel.class);
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, PREFIX + name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.PREFLIGHT_OK, true);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        servers.save(row);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put(HostPreflight.MEM_TOTAL_FACT, 16L * 1024 * 1024 * 1024);
        HostPreflight.store(PREFIX + name, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            facts, true, Instant.now(), null));
        return row.get(ServerModel.ID);
    }

    private static InstancePlacement.Workload workload(int memoryMb) {
        return InstancePlacement.Workload.of(InstanceKinds.getHandler(DOCKER_KIND),
            Map.of("image", "fake/image", "memory_limit_mb", memoryMb));
    }

    private static long liveInstancesOn(int serverId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.SERVER_ID.eq(serverId))
            .where(InstanceModel.DELETED_AT.isNull()).count();
    }

    @SuppressWarnings("unchecked")
    private static RowAction.Invoke<Row> acknowledgeAction() {
        for (RowAction<Row> action : new ServerResource().rowActions()) {
            if (action instanceof RowAction.Invoke<Row> invoke
                    && Identifier.of("hohenheim", "acknowledge_posture").equals(invoke.id())) {
                return invoke;
            }
        }
        throw new AssertionError("acknowledge_posture row action not found on ServerResource");
    }

    private static Row latestAcknowledgementActivity() {
        return Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(ServerModel.MODEL_ID.toString()))
            .where(ActivityModel.DETAIL.eq(HostPostureAcknowledgement.ACTIVITY_DETAIL))
            .orderBy(ActivityModel.ID, SortOrder.DESC).first();
    }

    private static String keyOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }

    // -- the shipped warning ------------------------------------------------------

    /**
     * The raw {@code server.acknowledge_body} source THIS repo ships for a locale, read
     * through the catalog loader rather than resolved: the pin is over what an operator
     * is shown, not over one rendering of it.
     */
    private static String shippedWarning(String tag) throws Exception {
        Path resources = Path.of("src/server/resources");
        assertThat(Files.isDirectory(resources))
            .as("the check needs the hohenheim project dir as its working directory").isTrue();
        try (URLClassLoader own = new URLClassLoader(
                new URL[] {resources.toUri().toURL()}, null)) {
            DefaultCatalogLoader loader = new DefaultCatalogLoader("META-INF/microcopy/", own);
            for (Translation candidate : loader.findCandidates("acknowledge_body",
                    LocaleChain.ofTags(tag))) {
                for (Translation.Filter filter : candidate.getFilters()) {
                    if ("scope".equals(filter.getName()) && "server".equals(filter.getValue())) {
                        return String.valueOf(candidate.getSource());
                    }
                }
            }
        }
        throw new AssertionError("no server-scoped acknowledge_body shipped for " + tag);
    }

    private static String sha256Of(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
