package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestAccessContexts;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What the chooser decides, and -- since 2026-08-07 -- what it is no longer allowed to
 * decide: the ELIGIBLE SET is the deploy path's own authority, and the SCORE is booked
 * memory rather than a count of rows.
 *
 * AIDEV-NOTE: this class was written as a CHARACTERIZATION of a count-based score and
 * said in as many words that it was the thing that must change when a declared footprint
 * landed. It has. The two properties it now pins are the ones the Proxmox-use inventory
 * named as blocking: placement must never choose a host whose deploy then refuses BY NAME
 * (the kernel-truth gate is the live instance of that), and the score must know how big a
 * host is. Steps 4 and 6 of the first journey are the ones that fail under the old
 * behaviour; do not weaken them into "some eligible host was picked".
 *
 * No daemon is contacted: every gate exercised here decides over stored record state,
 * which is the property that lets it run on the create path at all.
 */
class InstancePlacementTest {

    private static final String PREFIX = "placement-";
    private static final String BUCKET = "placement-owner";
    private static final String OTHER_BUCKET = "placement-stranger";
    private static final String DOCKER_KIND = "hohenheim:docker_container";
    private static final String SSH_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static SqlDatasource datasource;

    private final List<Integer> hosts = new ArrayList<>();
    private final List<Integer> instances = new ArrayList<>();

    /**
     * Its OWN database: the chooser walks EVERY host row, so a shared fixture where a
     * neighbouring class left one admitted incus host behind would silently change
     * every answer here.
     */
    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        HohenheimTestRuntime.ensureBooted();
        // The budget arithmetic is the SUBJECT here, so the two knobs that shape it are
        // pinned rather than inherited: bookable memory == the host's measured total.
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Capacity.HOST_MEMORY_RESERVE_MB, 0);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Capacity.MEMORY_OVERCOMMIT_RATIO, 1.0);
    }

    @AfterEach
    void cleanUp() {
        Db.run(datasource, () -> {
                for (Integer id : this.instances) {
                    Models.get(InstanceModel.class).delete(id);
                }
                for (Integer id : this.hosts) {
                    Models.get(ServerModel.class).delete(id);
                }
        });
        this.instances.clear();
        this.hosts.clear();
    }

    /**
     * An admitted DOCKER host with a stored preflight report carrying {@code memoryMb} of
     * measured memory -- the fact InstanceCapacity reads back as the budget.
     *
     * AIDEV-NOTE: the report goes through HostPreflight.store, THE persistence funnel, and
     * never hand-writes the capabilities shape. That is also what stamps probed_at, which
     * the freshness bound reads.
     */
    private int host(String name, String admission, String posture, Long memoryMb) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, PREFIX + name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.ADMISSION, admission);
        row.set(ServerModel.POSTURE, posture);
        Models.get(ServerModel.class).save(row);
        // An operator who declares shared containers also accepts that risk; without the
        // act, this fixture would be asserting the acknowledgement gate rather than the
        // chooser. The gate itself has its own journey, and the helper no-ops on the
        // postures that need no acknowledgement.
        HostFixtures.acknowledgePosture(row);
        int id = row.get(ServerModel.ID);
        this.hosts.add(id);
        storeReport(PREFIX + name, memoryMb, null);
        return id;
    }

    /** An INCUS host: the runtime whose kernel-truth gate placement must consult. */
    private int incusHost(String name, Long memoryMb, String kernelLaneStatus) {
        return incusHost(name, memoryMb, kernelLaneStatus,
            ServerModel.POSTURE_SHARED_CONTAINER);
    }

    /** The same, with the posture named -- the axis the isolation pairing reads. */
    private int incusHost(String name, Long memoryMb, String kernelLaneStatus,
                          String posture) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, PREFIX + name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.INCUS_URL, "https://192.0.2.41:8443");
        row.set(ServerModel.SSH_TARGET, "root@192.0.2.41");
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, posture);
        Models.get(ServerModel.class).save(row);
        HostFixtures.acknowledgePosture(row);
        int id = row.get(ServerModel.ID);
        this.hosts.add(id);
        ServerModel model = Models.get(ServerModel.class);
        HostPins.apply(model.findById(id), HostTrustSlot.INCUS_TLS,
            "-----BEGIN CERTIFICATE-----\nMIIBdaemon\n-----END CERTIFICATE-----\n",
            "sha256:daemon");
        HostPins.confirm(model.findById(id), HostTrustSlot.INCUS_TLS);
        HostPins.apply(model.findById(id), HostTrustSlot.SSH, SSH_KEY, "sha256:ssh");
        HostPins.confirm(model.findById(id), HostTrustSlot.SSH);
        storeReport(PREFIX + name, memoryMb, kernelLaneStatus);
        return id;
    }

    /** Store a passing report with the measured memory and an optional kernel-lane verdict. */
    private static void storeReport(String name, Long memoryMb, String kernelLaneStatus) {
        List<HostPreflight.Check> checks = new ArrayList<>();
        checks.add(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true,
            "reachable"));
        if (kernelLaneStatus != null) {
            checks.add(new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                kernelLaneStatus, true, "probe verdict under test"));
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        if (memoryMb != null) {
            facts.put("mem_total", memoryMb * 1024L * 1024L);
        }
        HostPreflight.store(name, new HostPreflight.Report(List.copyOf(checks), facts,
            true, Instant.now(), null));
    }

    /**
     * Make a host UNMEASURED, the way a freshly enrolled one is: no stored memory reading
     * on the record at all.
     *
     * AIDEV-NOTE: it clears the RECORD rather than storing a report with no facts, because
     * {@link HostPreflight#store} merges -- a run that measured nothing no longer erases
     * what an earlier run measured, which is exactly the point of that merge (a preflight
     * that could not reach the daemon used to cordon a healthy host by wiping mem_total).
     * "Never measured" is a property of the record, not of one report.
     */
    private static void unmeasure(String name) {
        ServerModel model = Models.get(ServerModel.class);
        Row row = model.findByName(name);
        row.set(ServerModel.CAPABILITIES, null);
        model.save(row);
        storeReport(name, null, null);
    }

    /** A workload of the docker kind, sized by an explicit memory limit. */
    private static InstancePlacement.Workload workload(int memoryMb) {
        return InstancePlacement.Workload.of(InstanceKinds.getHandler(DOCKER_KIND),
            Map.of("image", "fake/image", "memory_limit_mb", memoryMb));
    }

    /**
     * A live workload CHARGED to {@code bucket} and BOOKED on its host.
     *
     * AIDEV-NOTE: the bucket is written in a SECOND save on purpose. InstanceQuota's
     * create hook derives and stamps QUOTA_BUCKET from the acting principal, so a
     * bucket set on the insert is silently replaced by the operator's empty-set one --
     * which would make every dedicated-posture assertion below pass for the wrong
     * reason. The read-back is asserted rather than assumed.
     */
    private int liveInstanceOn(int serverId, String bucket, int memoryMb) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, PREFIX + "w" + System.nanoTime());
        row.set(InstanceModel.KIND, DOCKER_KIND);
        row.set(InstanceModel.SETTINGS,
            Map.of("image", "fake/image", "memory_limit_mb", memoryMb));
        row.set(InstanceModel.SERVER_ID, serverId);
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        this.instances.add(id);

        row.set(InstanceModel.QUOTA_BUCKET, bucket);
        Models.get(InstanceModel.class).save(row);
        Row stored = Models.get(InstanceModel.class).findById(id);
        assertThat((String) stored.get(InstanceModel.QUOTA_BUCKET))
            .as("fixture: the workload really is charged to '%s'", bucket)
            .isEqualTo(bucket);
        assertThat((Integer) stored.get(InstanceModel.CAPACITY_MB))
            .as("fixture: and really booked %s MB on its host", memoryMb)
            .isEqualTo(memoryMb);
        return id;
    }

    private static String keyOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }

    /**
     * The eligible set and the score, in one walk: only an admitted, tenant-accepting,
     * runtime-matching host with a PROVEN memory budget qualifies; among those the one
     * with the most REMAINING HEADROOM wins; a tie goes to the lowest id; a workload that
     * fits nowhere is a capacity refusal that says so; and an empty set is a NAMED refusal
     * rather than a silent fall back to the local daemon.
     *
     * AIDEV-NOTE: every host here is measured at the SAME budget and carries no managed
     * processes, so booked-ascending and headroom-descending agree throughout -- which is
     * exactly why this journey could not catch the budget-blind score it was written
     * against. The two cases that separate them live in
     * {@link #theScoreIsRemainingHeadroomNotTheInstanceBucketAlone}; do not fold them in
     * here, the equal-budget fixture is what makes the ELIGIBILITY steps readable.
     */
    @Test
    void theChooserScoresFreeHeadroomAndRefusesByNameWithTheReasonThatApplies() {
        Db.run(datasource, () -> {
            // 1. No eligible host at all: named refusal, never a default.
            int blocked = host("blocked", ServerModel.ADMISSION_BLOCKED,
                ServerModel.POSTURE_SHARED_CONTAINER, 4096L);
            int trustedOnly = host("trusted-only", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_TRUSTED_ONLY, 4096L);
            assertThat(keyOf(catchThrowable(() -> InstancePlacement.chooseForBucket(
                    BUCKET, workload(512), null))))
                .as("step 1: a blocked host and a trusted-only host leave nothing eligible,"
                    + " and that is a refusal by name")
                .isEqualTo("no_placement_available");

            // 2. One admitted shared host: it is chosen, and neither ineligible host is.
            int alpha = host("alpha", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER, 4096L);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(512), null))
                .as("step 2: the only admitted tenant-accepting host is chosen")
                .isEqualTo(alpha);
            assertThat(List.of(blocked, trustedOnly))
                .as("step 2: and it is neither of the refused ones")
                .doesNotContain(alpha);

            // 3. A second eligible host, empty, beats the one carrying a workload.
            int beta = host("beta", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER, 4096L);
            liveInstanceOn(alpha, BUCKET, 512);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(512), null))
                .as("step 3: the emptier host wins")
                .isEqualTo(beta);

            // 4. THE SCORE IS MEGABYTES, NOT A COUNT OF ROWS. Alpha now carries THREE
            //    workloads totalling 768 MB; beta carries ONE of 1024 MB. A count-based
            //    score picks beta and is wrong about which host has room.
            liveInstanceOn(alpha, BUCKET, 128);
            liveInstanceOn(alpha, BUCKET, 128);
            liveInstanceOn(beta, BUCKET, 1024);
            assertThat(InstanceCapacity.bookedMbOn(alpha))
                .as("step 4: alpha carries three rows totalling 768 MB").isEqualTo(768);
            assertThat(InstanceCapacity.bookedMbOn(beta))
                .as("step 4: beta carries one row of 1024 MB").isEqualTo(1024);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(256), null))
                .as("step 4: the host with the most FREE MEMORY wins, even though it"
                    + " carries three times as many workloads")
                .isEqualTo(alpha);

            // 5. Equal bookings: the LOWEST ID breaks the tie, so a placement is
            //    reproducible. Beta reaches alpha's 768 by releasing 256 of its 1024.
            Row betaWorkload = Models.get(InstanceModel.class).find()
                .where(InstanceModel.SERVER_ID.eq(beta)).first();
            betaWorkload.set(InstanceModel.SETTINGS,
                Map.of("image", "fake/image", "memory_limit_mb", 768));
            Models.get(InstanceModel.class).save(betaWorkload);
            assertThat(InstanceCapacity.bookedMbOn(beta))
                .as("step 5: an edited limit re-books the DELTA, it does not double-book")
                .isEqualTo(768);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(256), null))
                .as("step 5: an even split goes to the lowest id, deterministically")
                .isEqualTo(Math.min(alpha, beta));

            // 6. A workload nobody has room for is a CAPACITY refusal, distinct by name
            //    from "nothing accepts you" -- the operator is told to free memory or
            //    admit a host, not to go looking for a posture that is already fine.
            assertThat(keyOf(catchThrowable(() -> InstancePlacement.chooseForBucket(
                    BUCKET, workload(4096), null))))
                .as("step 6: 4096 MB does not fit in either host's 3328 MB of headroom")
                .isEqualTo("no_placement_capacity");
            // POSITIVE ANCHOR: the refusal above is about SIZE, not about eligibility.
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(3328), null))
                .as("step 6: exactly the free 3328 MB still fits, on the lowest id")
                .isEqualTo(Math.min(alpha, beta));

            // 7. The excluded host (the drain lane's source) is never its own destination.
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(256),
                    Math.min(alpha, beta)))
                .as("step 7: excluding the winner picks the other one")
                .isEqualTo(Math.max(alpha, beta));

            // 8. A host whose memory was never measured is not a host with infinite room:
            //    the CHOOSER will not pick a machine whose size nobody knows, and the
            //    survivor is chosen instead.
            unmeasure(PREFIX + "beta");
            assertThat(InstanceCapacity.budgetMbOf(
                    Models.get(ServerModel.class).findById(beta)))
                .as("step 8: an unmeasured host has NO budget, not an unlimited one")
                .isNull();
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(256), null))
                .as("step 8: so placement lands on the host that can still answer")
                .isEqualTo(alpha);

            // 8b. And when NOTHING is measured, the refusal names the host to preflight
            //     rather than saying "nothing accepts this" -- the two states have
            //     different fixes and must not share a message.
            unmeasure(PREFIX + "alpha");
            assertThat(keyOf(catchThrowable(() -> InstancePlacement.chooseForBucket(
                    BUCKET, workload(256), null))))
                .as("step 8b: eligible-but-unmeasured is its own named refusal")
                .isEqualTo("host_capacity_unproven");
            storeReport(PREFIX + "alpha", 4096L, null);

            // 9. A runtime the fleet does not offer is a refusal, not a wrong-runtime pick.
            InstancePlacement.Workload incus = InstancePlacement.Workload.of(
                InstanceKinds.getHandler("hohenheim:system_container"), Map.of());
            assertThat(keyOf(catchThrowable(() ->
                    InstancePlacement.chooseForBucket(BUCKET, incus, null))))
                .as("step 9: every host here is a docker host, so an incus workload"
                    + " refuses by name rather than landing on the wrong daemon")
                .isEqualTo("no_placement_available");
        });
    }

    /**
     * WHICH host wins when two of them could both take the workload: the one with the
     * most room left, never the one holding the fewest instance megabytes.
     *
     * AIDEV-NOTE: this is the journey the previous score could not survive, and it needs
     * TWO hosts to say anything at all. Both pairs below are built so the workload fits on
     * BOTH candidates: the assertion is then purely about ranking, and a step that fails is
     * a score regression rather than an eligibility one. Steps 2 and 4 are the ones that
     * fail under `fewest booked`.
     */
    @Test
    void theScoreIsRemainingHeadroomNotTheInstanceBucketAlone() {
        Db.run(datasource, () -> {
            // 1. PAIR ONE, the class docblock's own worked example: a big host carrying
            //    something against a small host carrying nothing. Both fit a 256 MB
            //    workload, so eligibility cannot be what decides.
            int small = host("score-small", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER, 1024L);
            int large = host("score-large", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER, 8192L);
            liveInstanceOn(large, BUCKET, 512);
            assertThat(Map.of(
                    "small", InstanceCapacity.bookedMbOn(small),
                    "large", InstanceCapacity.bookedMbOn(large)))
                .as("step 1: the SMALL host is the one holding fewer booked megabytes")
                .isEqualTo(Map.of("small", 0L, "large", 512L));

            // 2. THE SCORE KNOWS HOW BIG A HOST IS. 8192 - 512 = 7680 MB of room beats
            //    1024 - 0 = 1024, so the busy big machine wins. A `fewest booked` score
            //    reads 0 against 512 and packs the fleet onto the 1 GB box.
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(256), null))
                .as("step 2: 7680 MB of headroom outranks 1024 MB, even though the winner"
                    + " is the only one of the two carrying a workload at all")
                .isEqualTo(large);

            // 3. PAIR TWO, EQUAL BUDGETS. Both are measured at 4096 MB, so budget alone
            //    cannot decide; only what each already carries can.
            int lightlyLoaded = host("score-light", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER, 4096L);
            int instanceHeavy = host("score-instances", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER, 4096L);
            liveInstanceOn(instanceHeavy, BUCKET, 512);
            assertThat(Map.of(
                    "light", InstanceCapacity.bookableMbOn(lightlyLoaded, 4096L)
                        - InstanceCapacity.bookedMbOn(lightlyLoaded),
                    "heavy", InstanceCapacity.bookableMbOn(instanceHeavy, 4096L)
                        - InstanceCapacity.bookedMbOn(instanceHeavy)))
                .as("step 3: one has 4096 MB left, the other 3584")
                .isEqualTo(Map.of("light", 4096L, "heavy", 3584L));

            // 4. So the score must read the SAME subtraction the ceiling does. Both
            //    hosts fit a 256 MB workload; the one with real room takes it.
            //    Excluding the pair-one winner keeps this about pair two.
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(256), large))
                .as("step 4: the host with real room wins, not the one that merely"
                    + " happens to hold fewer instances")
                .isEqualTo(lightlyLoaded);

            // 5. TIE-BREAK, unchanged and still the lowest id: the tie is made explicit
            //    by giving the lightly-loaded host the same 512 MB of instances.
            liveInstanceOn(lightlyLoaded, BUCKET, 512);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(256), large))
                .as("step 5: equal headroom goes to the lowest id, deterministically")
                .isEqualTo(Math.min(lightlyLoaded, instanceHeavy));
        });
    }

    /**
     * The eligible set is the DEPLOY PATH'S OWN AUTHORITY: a host whose deploy would
     * refuse by name is never chosen. The kernel-truth gate is the live instance of that
     * -- it was added to HostAdmission and placement, which re-stated a subset of the same
     * rules inline, never learned about it.
     */
    @Test
    void anIncusHostThatCannotProveKernelTruthIsNeverChosen() {
        Db.run(datasource, () -> {
            // 1. Admitted, tenant-accepting, measured, correct runtime -- and its last
            //    preflight never proved it could read its own kernel. HostAdmission
            //    refuses such a host at deploy, so placement must not offer it.
            int unproven = incusHost("incus-unproven", 4096L, HostPreflight.STATUS_FAIL);
            assertThat((String) Models.get(ServerModel.class).findById(unproven)
                    .get(ServerModel.ADMISSION))
                .as("step 1: the host really is stored as ADMITTED")
                .isEqualTo(ServerModel.ADMISSION_ADMITTED);
            InstancePlacement.Workload incus = InstancePlacement.Workload.of(
                InstanceKinds.getHandler("hohenheim:system_container"),
                Map.of("image", "images:debian/13"));
            Throwable refused = catchThrowable(() ->
                InstancePlacement.chooseForBucket(BUCKET, incus, null));
            assertThat(refused)
                .as("step 1: placement REFUSED rather than choosing host %s, whose deploy"
                    + " HostAdmission would then refuse by name -- the chooser must call"
                    + " that gate, never re-state a subset of it", unproven)
                .isNotNull();
            assertThat(keyOf(refused))
                .as("step 1: and the refusal is a named one")
                .isEqualTo("no_placement_available");

            // 2. POSITIVE ANCHOR: the same host, with the kernel lane PROVEN, is chosen.
            //    Without this the refusal above could be refusing every incus host.
            storeReport(PREFIX + "incus-unproven", 4096L, HostPreflight.STATUS_PASS);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, incus, null))
                .as("step 2: proving the lane makes the very same host eligible")
                .isEqualTo(unproven);

            // 3. And the gate is LIVE, not a stored decision: a failing probe closes it
            //    again without anything touching the admission column.
            storeReport(PREFIX + "incus-unproven", 4096L, HostPreflight.STATUS_FAIL);
            Throwable again = catchThrowable(() ->
                InstancePlacement.chooseForBucket(BUCKET, incus, null));
            assertThat(again)
                .as("step 3: the eligible set follows the EVIDENCE on every call, so host"
                    + " %s drops out again the moment its probe verdict flips", unproven)
                .isNotNull();
            assertThat(keyOf(again))
                .as("step 3: and it is the same named refusal")
                .isEqualTo("no_placement_available");
            assertThat((String) Models.get(ServerModel.class).findById(unproven)
                    .get(ServerModel.ADMISSION))
                .as("step 3: and placement never rewrites the operator's admit")
                .isEqualTo(ServerModel.ADMISSION_ADMITTED);
        });
    }

    /**
     * A kind's OWN host requirement (the prepared-image constraint is the shipping one)
     * excludes a host from the eligible set, and -- because that host passed every
     * admission gate -- its named reason is what the operator is told, instead of a
     * generic "nothing accepts this workload" they cannot act on.
     */
    @Test
    void aKindsOwnHostRequirementExcludesAHostAndIsWhatTheOperatorIsTold() {
        Db.run(datasource, () -> {
            int only = host("kind-gate", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER, 4096L);

            // 1. POSITIVE ANCHOR FIRST: with no kind requirement, this host is chosen.
            //    Everything below is therefore about the requirement, not the host.
            InstanceKindHandler docker = InstanceKinds.getHandler(DOCKER_KIND);
            assertThat(InstancePlacement.chooseForBucket(BUCKET,
                    InstancePlacement.Workload.of(docker, Map.of()), null))
                .as("step 1: the host is eligible when the kind asks nothing of it")
                .isEqualTo(only);

            // 2. A kind that refuses this host BY NAME takes it out of the eligible set,
            //    and the refusal the caller sees is the kind's own sentence -- naming the
            //    host and what is missing on it.
            Throwable refused = catchThrowable(() -> InstancePlacement.chooseForBucket(
                BUCKET, InstancePlacement.Workload.of(new RefusingKind(docker), Map.of()),
                null));
            assertThat(refused)
                .as("step 2: the host was excluded by the KIND, so placement refused")
                .isNotNull();
            assertThat(keyOf(refused))
                .as("step 2: and the operator gets the actionable reason, not the generic"
                    + " no_placement_available they could do nothing with")
                .isEqualTo("host_prepared_image_missing");
        });
    }

    /**
     * A kind that refuses every host, by name -- the shape
     * {@code VmKind.requirePlaceableOn} has when a prepared alias is not published
     * on the candidate, without needing a daemon to say so.
     */
    private record RefusingKind(InstanceKindHandler delegate) implements InstanceKindHandler {

        @Override
        public Identifier typeId() {
            return this.delegate.typeId();
        }

        @Override
        public InstanceRuntime runtimeFor(String serverName) {
            return this.delegate.runtimeFor(serverName);
        }

        @Override
        public InstanceSpec specFor(
                int instanceId, Map<String, Object> settings) {
            return this.delegate.specFor(instanceId, settings);
        }

        @Override
        public @NonNull Microcopy getDescription() {
            return this.delegate.getDescription();
        }

        @Override
        public Schema getSchema() {
            return this.delegate.getSchema();
        }

        @Override
        public @NonNull String getDisplayName() {
            return this.delegate.getDisplayName();
        }

        @Override
        public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
            return this.delegate.defaultFootprintMb(settings);
        }

        // Both trust axes follow the delegate; answering the interface default here
        // would make a pairing assertion about a wrapped VM kind pass as a container.
        @Override
        public @NonNull WorkloadIsolation isolation() {
            return this.delegate.isolation();
        }

        @Override
        public void requirePlaceableOn(String serverName, Map<String, Object> settings) {
            throw Violations.ofForm(Microcopy.of("host_prepared_image_missing")
                .withFilter("scope", "violations")
                .withArg("name", serverName)
                .withArg("image", "win2025-core"));
        }
    }

    /**
     * The dedicated posture's exclusivity claim: the host belongs to ONE owner, and the
     * chooser has to make that true rather than merely say it. A live workload charged
     * to any other bucket takes the host out of the eligible set entirely.
     */
    @Test
    void aDedicatedHostAcceptsOneBucketAndStopsAcceptingOnceAnotherLandsOnIt() {
        Db.run(datasource, () -> {
            int shared = host("ded-shared", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER, 4096L);
            int dedicated = host("ded-exclusive", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_DEDICATED, 4096L);

            // 1. Empty, a dedicated host accepts anyone -- it has made no promise yet.
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(256), shared))
                .as("step 1: an empty dedicated host is eligible")
                .isEqualTo(dedicated);

            // 2. Once OUR bucket is on it, it still accepts us: exclusivity is per owner,
            //    not one workload.
            liveInstanceOn(dedicated, BUCKET, 256);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(256), shared))
                .as("step 2: the owner it already carries may land again")
                .isEqualTo(dedicated);

            // 3. A STRANGER is refused it -- and the refusal is total, not a lower score:
            //    with the shared host excluded there is nowhere left to go.
            assertThat(keyOf(catchThrowable(() -> InstancePlacement.chooseForBucket(
                    OTHER_BUCKET, workload(256), shared))))
                .as("step 3: a dedicated host carrying another owner's workload is not"
                    + " merely deprioritised for a stranger, it is INELIGIBLE")
                .isEqualTo("no_placement_available");
            assertThat(InstancePlacement.chooseForBucket(OTHER_BUCKET, workload(256), null))
                .as("step 3: the stranger lands on the shared host instead")
                .isEqualTo(shared);

            // 4. The operator's own empty-set bucket is a stranger too -- "dedicated" is not
            //    "dedicated except for us".
            assertThat(keyOf(catchThrowable(() -> InstancePlacement.chooseForBucket(
                    "", workload(256), shared))))
                .as("step 4: the operator bucket does not get a pass onto a taken"
                    + " dedicated host")
                .isEqualTo("no_placement_available");

            // 5. THE ADMIN HOLE, and why exclusivity moved into HostAdmission on
            //    2026-08-12. forActor honours a caller-supplied server_id for an admin and
            //    returns it WITHOUT walking the chooser at all, so every assertion above
            //    was about a path the operator does not take. The chooser is still allowed
            //    to hand back the named host -- an operator may name one -- but the DEPLOY
            //    gate now refuses to run a stranger's workload there.
            assertThat(InstancePlacement.forActor(null, dedicated, workload(256)))
                .as("step 5: an admin naming a host still gets the host they named")
                .isEqualTo(dedicated);
            assertThat(keyOf(catchThrowable(() -> HostAdmission.requireInstancePlacement(
                    dedicated, WorkloadIsolation.SHARED_KERNEL, OTHER_BUCKET))))
                .as("step 5: and the gate every deploy funnels through refuses to place a"
                    + " SECOND owner's workload on a dedicated machine -- the rule used to"
                    + " live only in the chooser the admin path skips")
                .isEqualTo("host_dedicated_to_other");
            // POSITIVE ANCHOR: the owner it is dedicated to is not refused by that gate.
            HostAdmission.requireInstancePlacement(dedicated,
                WorkloadIsolation.SHARED_KERNEL, BUCKET);
        });
    }

    /**
     * THE ISOLATION PAIRING: a host that declares VM isolation promises every tenant on it
     * a hypervisor boundary, and a container cannot provide one.
     *
     * AIDEV-NOTE: this was a live hole, not a hypothetical. Both Incus kinds return
     * RUNTIME_INCUS and both are tenant-authored, and {@code acceptsTenantWorkloads} is
     * one-dimensional -- so a hostile-tenant CONTAINER placed and deployed on a
     * {@code vm_isolated} host, which is the one posture whose whole meaning is that it
     * does not happen. Nothing compared a workload's isolation to what the host declared
     * because no workload declared one.
     */
    /**
     * A workspace and an application need a host whose volume root can ENFORCE a quota, and
     * the eligible set answers to the same declaration the deploy path does.
     *
     * AIDEV-NOTE: the refusal lives on the KIND ({@code requirePlaceableOn}) rather than in
     * the chooser, for the reason that seam records: a second copy of a refusal is the
     * drift defect it exists to remove. This journey therefore asserts BOTH ends -- the
     * chooser will not offer the host, and asking the kind directly names the reason.
     */
    @Test
    void aHostWithNoVolumeQuotaTakesNoWorkspaceOrApplication() {
        Db.run(datasource, () -> {
            int plain = host("vol-none", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER, 8192L);
            setVolumeBackend(plain, VolumeBackend.NONE);

            InstanceKindHandler workspace = InstanceKinds.getHandler("hohenheim:workspace");
            InstanceKindHandler application = InstanceKinds.getHandler("hohenheim:application");
            InstancePlacement.Workload workspaceLoad =
                InstancePlacement.Workload.of(workspace, Map.of());
            InstancePlacement.Workload applicationLoad =
                InstancePlacement.Workload.of(application, Map.of());

            // 1. The host is otherwise perfect -- admitted, acknowledged, measured -- so
            //    what follows is about the filesystem and nothing else.
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workload(256), null))
                .as("step 1: an ordinary container places there")
                .isEqualTo(plain);

            // 2. Both new kinds refuse it BY NAME when asked directly.
            for (InstanceKindHandler kind : List.of(workspace, application)) {
                assertThat(keyOf(catchThrowable(() ->
                        kind.requirePlaceableOn(ServerModel.nameOf(plain), Map.of()))))
                    .as("step 2: %s names the missing quota", kind.typeId())
                    .isEqualTo("host_no_volume_quota");
            }

            // 3. And the chooser never offers it either, so no record is ever created
            //    pointing at a host the deploy would then refuse. It propagates the KIND's
            //    own reason rather than the generic "nothing accepts this workload":
            //    a host excluded only by the kind's requirement carries the one refusal an
            //    operator can act on directly (mount a quota-capable filesystem there).
            assertThat(keyOf(catchThrowable(() ->
                    InstancePlacement.chooseForBucket(BUCKET, workspaceLoad, null))))
                .as("step 3: the workspace is not placed, and is told why")
                .isEqualTo("host_no_volume_quota");
            assertThat(keyOf(catchThrowable(() ->
                    InstancePlacement.chooseForBucket(BUCKET, applicationLoad, null))))
                .as("step 3: nor the application")
                .isEqualTo("host_no_volume_quota");

            // 4. POSITIVE ANCHOR: give the SAME host a quota-capable backend and both land
            //    there. The refusal is about the filesystem, not about the kinds.
            setVolumeBackend(plain, VolumeBackend.BTRFS);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, workspaceLoad, null))
                .as("step 4: a btrfs volume root takes a workspace")
                .isEqualTo(plain);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, applicationLoad, null))
                .as("step 4: and an application")
                .isEqualTo(plain);

            // 5. XFS with project quota is a mount that CAN cap a volume and that this
            //    build has no operations for, so placement refuses it exactly like a host
            //    with no quota at all. Until 2026-08-23 it was offered here and the FIRST
            //    deploy died in VolumeOperations instead -- a promise made by placement and
            //    broken one screen later.
            setVolumeBackend(plain, VolumeBackend.XFS_PRJQUOTA);
            assertThat(VolumeBackend.XFS_PRJQUOTA.filesystemEnforcesQuota())
                .as("step 5: the mount itself could enforce a cap")
                .isTrue();
            assertThat(keyOf(catchThrowable(() ->
                    InstancePlacement.chooseForBucket(BUCKET, workspaceLoad, null))))
                .as("step 5: and placement still refuses, because nothing here can apply one")
                .isEqualTo("host_no_volume_quota");
        });
    }

    /** Stamp a detected volume backend on a host the way the preflight probe does. */
    private static void setVolumeBackend(int serverId, VolumeBackend backend) {
        ServerModel model = Models.get(ServerModel.class);
        Row row = model.findById(serverId);
        VolumeBackends.store(row, new VolumeBackends.Detection(backend,
            "/fixture/volumes", "fixture: " + backend.token()));
    }

    @Test
    void aVmIsolatedHostRefusesAContainerWorkloadAndStillTakesAVirtualMachine() {
        Db.run(datasource, () -> {
            int vmOnly = incusHost("vm-isolated", 8192L, HostPreflight.STATUS_PASS,
                ServerModel.POSTURE_VM_ISOLATED);
            InstanceKindHandler containerKind =
                InstanceKinds.getHandler("hohenheim:system_container");
            InstanceKindHandler vmKind = InstanceKinds.getHandler("hohenheim:vm");
            InstancePlacement.Workload container = InstancePlacement.Workload.of(
                containerKind, Map.of("image", "images:debian/13"));
            InstancePlacement.Workload machine = InstancePlacement.Workload.of(
                vmKind, Map.of("image", "images:debian/13"));

            // 1. The kinds DECLARE the two boundaries, which is what makes the pairing
            //    answerable at all -- and the container's answer is the conservative one.
            assertThat(containerKind.isolation())
                .as("step 1: a system container shares the host kernel")
                .isEqualTo(WorkloadIsolation.SHARED_KERNEL);
            assertThat(vmKind.isolation())
                .as("step 1: a VM does not")
                .isEqualTo(WorkloadIsolation.VIRTUAL_MACHINE);

            // 2. THE HOLE. A hostile-tenant container on a host promising VM isolation is
            //    refused by name at the deploy gate...
            assertThat(keyOf(catchThrowable(() -> HostAdmission.requireInstancePlacement(
                    vmOnly, containerKind.isolation(), BUCKET))))
                .as("step 2: a shared-kernel workload cannot satisfy a vm_isolated host")
                .isEqualTo("host_posture_requires_vm");
            // ...and never chosen, so no record is ever created pointing at it.
            assertThat(keyOf(catchThrowable(() ->
                    InstancePlacement.chooseForBucket(BUCKET, container, null))))
                .as("step 2: and the chooser will not offer the host either")
                .isEqualTo("no_placement_available");

            // 3. POSITIVE ANCHOR: the SAME host, the SAME tenant, a VM -- it places. The
            //    refusal above is about the boundary, not about the host or the tenant.
            assertThat(InstancePlacement.chooseForBucket(BUCKET, machine, null))
                .as("step 3: the workload the posture was declared for lands there")
                .isEqualTo(vmOnly);
            HostAdmission.requireInstancePlacement(vmOnly, vmKind.isolation(), BUCKET);

            // 4. And the pairing is scoped to the posture that makes the promise: on a
            //    shared_container host BOTH place, because that posture promises nothing
            //    about kernels -- it only demands that an operator accepted the risk.
            int shared = incusHost("vm-pair-shared", 8192L, HostPreflight.STATUS_PASS);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, container, vmOnly))
                .as("step 4: a container places on an acknowledged shared-container host")
                .isEqualTo(shared);
            HostAdmission.requireInstancePlacement(shared, vmKind.isolation(), BUCKET);

            // 5. A dedicated host is unaffected by the isolation axis in either direction:
            //    it rations by OWNER, and says nothing about kernels.
            int dedicated = incusHost("vm-pair-dedicated", 8192L, HostPreflight.STATUS_PASS,
                ServerModel.POSTURE_DEDICATED);
            HostAdmission.requireInstancePlacement(dedicated, containerKind.isolation(), BUCKET);
            HostAdmission.requireInstancePlacement(dedicated, vmKind.isolation(), BUCKET);
        });
    }

    /**
     * AN ACTOR WHO NAMES NO HOST GETS THE CHOOSER -- the OPERATOR included, which is the
     * lane that used to skip it.
     *
     * AIDEV-NOTE: the create form narrows its host pick to hosts that accept the workload,
     * so on a fresh installation (local enrolled but {@code blocked}) it offers nothing at
     * all. Until 2026-08-26 the submit behind that empty pick still landed on the blocked
     * local daemon, because {@code forActor} returned it unconditionally for an admin
     * whenever the workload could run on Docker -- no admission, no posture, no capacity.
     * The visible form and the authoritative write disagreed. Step 6 pins the seam the
     * fix deliberately left alone: a NULL context is in-process work with no form and no
     * picker, and it still gets the local daemon (the deploy gate is what stops it there).
     */
    @Test
    void anAdminCreateThatNamesNoHostWalksTheSameEligibilityGate() {
        Db.run(datasource, () -> {
            AccessContext admin = adminContext();
            assertThat(HohenheimAccess.isAdmin(admin))
                .as("fixture: the seeded operator really holds the panel permission")
                .isTrue();

            // The implicit local daemon, as a fresh installation carries it: enrolled,
            // never preflighted, admission `blocked`. Registered for teardown because this
            // journey ADMITS it and every other test in this class walks EVERY host row.
            int local = ServerModel.localServerId();
            this.hosts.add(local);
            assertThat((String) Models.get(ServerModel.class).findById(local)
                    .get(ServerModel.ADMISSION))
                .as("fixture: the seeded local host is blocked until an operator admits it")
                .isEqualTo(ServerModel.ADMISSION_BLOCKED);

            // 1. Nothing is eligible: the operator's create is REFUSED BY NAME. This is
            //    the step that fails under the old behaviour, which handed the blocked
            //    host back without walking a single gate.
            assertThat(keyOf(catchThrowable(() ->
                    InstancePlacement.forActor(admin, null, workload(512)))))
                .as("step 1: with nothing admitted, an admin create naming no host is"
                    + " refused -- never silently placed on the blocked local daemon")
                .isEqualTo("no_placement_available");

            // 2. The operator admits and preflights the local daemon. The SAME call now
            //    lands on it: eligibility is what changed, not the caller.
            HostFixtures.admitLocal();
            storeReport(ServerModel.MODE_LOCAL, 4096L, null);
            assertThat(InstancePlacement.forActor(admin, null, workload(512)))
                .as("step 2: admitted and measured, local is what the chooser picks")
                .isEqualTo(local);

            // 3. And the score applies to this lane as well: a bigger empty host outranks
            //    the local daemon, which the implicit default could never express.
            int roomier = host("no-host-roomier", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER, 16384L);
            assertThat(InstancePlacement.forActor(admin, null, workload(512)))
                .as("step 3: the operator lane is scored like every other placement")
                .isEqualTo(roomier);

            // 4. NAMING a host is still the operator authority it always was, and it is
            //    now the ONLY way past the chooser: cordoned, local is out of the eligible
            //    set, and an operator who names it anyway still gets it (the deploy gate
            //    is what refuses to run there -- see the dedicated-host journey).
            Row localRow = Models.get(ServerModel.class).findById(local);
            localRow.set(ServerModel.ADMISSION, ServerModel.ADMISSION_CORDONED);
            Models.get(ServerModel.class).save(localRow);
            assertThat(InstancePlacement.forActor(admin, local, workload(512)))
                .as("step 4: an operator naming a host still gets the host they named")
                .isEqualTo(local);

            // 5. THE TENANT PATH IS UNCHANGED: a submitted host is ignored outright and
            //    the chooser answers, so the cordoned daemon is not reachable by asking.
            AccessContext tenant = TestAccessContexts.contextFor(
                new UserPrincipal(987654L, "Placement Tenant"));
            assertThat(HohenheimAccess.isAdmin(tenant))
                .as("fixture: the tenant holds no operator permission")
                .isFalse();
            assertThat(InstancePlacement.forActor(tenant, local, workload(512)))
                .as("step 5: a tenant-submitted host is ignored and the chooser decides")
                .isEqualTo(roomier);

            // 6. THE SEAM: no actor at all is in-process work -- no form, no picker, no
            //    refusal to show anyone -- and it still gets the local daemon even now
            //    that local is cordoned. Deliberate, and pinned so that changing it is a
            //    decision rather than an accident.
            assertThat(InstancePlacement.forActor(null, null, workload(512)))
                .as("step 6: the actor-less lane keeps the implicit local daemon")
                .isEqualTo(local);
        });
    }

    /**
     * An operator context over THIS test's own database: an enabled user holding the
     * wildcard grant, which is the shape {@code HohenheimTestBase.seedAuthenticatedAdmin}
     * gives the harness admin -- spelled here because this class runs on a fresh
     * datasource the harness never seeded.
     */
    private static @NonNull AccessContext adminContext() {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "placement-operator@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Placement Operator");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        Row grant = AuthModels.grants().createEmptyRow();
        grant.set(GrantModel.SUBJECT_TYPE, GrantSubjectType.USER.key());
        grant.set(GrantModel.SUBJECT_ID, user.get(UserModel.ID));
        grant.set(GrantModel.PERMISSION, "*");
        grant.set(GrantModel.VALUE, true);
        AuthModels.grants().save(grant);
        return TestAccessContexts.contextFor(new UserPrincipal(
            ((Integer) user.get(UserModel.ID)).longValue(), "Placement Operator"));
    }
}
