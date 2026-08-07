package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.host.HostPreflight;
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
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
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

    private static SqliteDatasource datasource;

    private final List<Integer> hosts = new ArrayList<>();
    private final List<Integer> instances = new ArrayList<>();

    /**
     * Its OWN database: the chooser walks EVERY host row, so a shared fixture where a
     * neighbouring class left one admitted incus host behind would silently change
     * every answer here.
     */
    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-placement-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
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
        int id = row.get(ServerModel.ID);
        this.hosts.add(id);
        storeReport(PREFIX + name, memoryMb, null);
        return id;
    }

    /** An INCUS host: the runtime whose kernel-truth gate placement must consult. */
    private int incusHost(String name, Long memoryMb, String kernelLaneStatus) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, PREFIX + name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.INCUS_URL, "https://192.0.2.41:8443");
        row.set(ServerModel.SSH_TARGET, "root@192.0.2.41");
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
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
     * carrying the least booked memory wins; a tie goes to the lowest id; a workload that
     * fits nowhere is a capacity refusal that says so; and an empty set is a NAMED refusal
     * rather than a silent fall back to the local daemon.
     */
    @Test
    void theChooserScoresBookedMemoryAndRefusesByNameWithTheReasonThatApplies() {
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

            // 4. THE SCORE IS BOOKED MEMORY, NOT A COUNT OF ROWS. Alpha now carries THREE
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
                .as("step 4: the host with the least BOOKED MEMORY wins, even though it"
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
            storeReport(PREFIX + "beta", null, null);
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
            storeReport(PREFIX + "alpha", null, null);
            assertThat(keyOf(catchThrowable(() -> InstancePlacement.chooseForBucket(
                    BUCKET, workload(256), null))))
                .as("step 8b: eligible-but-unmeasured is its own named refusal")
                .isEqualTo("host_capacity_unproven");
            storeReport(PREFIX + "alpha", 4096L, null);

            // 9. A runtime the fleet does not offer is a refusal, not a wrong-runtime pick.
            InstancePlacement.Workload incus = InstancePlacement.Workload.of(
                InstanceKinds.getHandler("hohenheim:incus_container"), Map.of());
            assertThat(keyOf(catchThrowable(() ->
                    InstancePlacement.chooseForBucket(BUCKET, incus, null))))
                .as("step 9: every host here is a docker host, so an incus workload"
                    + " refuses by name rather than landing on the wrong daemon")
                .isEqualTo("no_placement_available");
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
                InstanceKinds.getHandler("hohenheim:incus_container"),
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
     * {@code IncusVmKind.requirePlaceableOn} has when a prepared alias is not published
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
        public String getDescription() {
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
        public int defaultFootprintMb() {
            return this.delegate.defaultFootprintMb();
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
        });
    }
}
