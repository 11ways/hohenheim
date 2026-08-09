package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.ServerResource;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.task.VerifyIncusIsolation;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.common.edit.Computed;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The host record as EVIDENCE: what a preflight is allowed to destroy, how old a stored
 * answer may be before a gate stops believing it, and whether an operator can read either
 * of those off the admin surface.
 *
 * AIDEV-NOTE: the defects this pins, all four of the "observes host truth honestly and
 * then destroys or ignores it" shape.
 *
 * ONE: {@code HostPreflight.store} replaced {@code capabilities} wholesale, so a preflight
 * that could not reach the daemon erased every fact a previous run had measured --
 * {@code mem_total} included. The host stayed admitted while placement skipped it as
 * UNMEASURED, which means running a diagnostic action removed a production host from the
 * pool and told the operator to run the action that had just done it.
 *
 * TWO: nothing read {@code last_seen_at} against a clock. A host that stopped answering
 * kept receiving placements.
 *
 * THREE: {@code VerifyIncusIsolation} swept {@code running} only, so a workload in
 * {@code starting} -- the status deploy stamps whenever a readiness line exists -- was on
 * the bridge and never kernel-verified.
 *
 * FOUR: a quarantine was invisible in the host LIST, because the cell branched on
 * {@code last_error_kind} and a later successful probe clears that column.
 *
 * No daemon is contacted: every assertion here is a decision over stored record state,
 * which is the property that lets these gates run on the create path.
 */
class HostEvidenceTest {

    private static final String SSH_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final long SIXTEEN_GB = 16L * 1024 * 1024 * 1024;

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-host-evidence-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
    }

    /**
     * A preflight that could not reach the daemon records ITS OWN failure and destroys
     * nothing it did not re-measure -- and the retained evidence carries the age of the run
     * that produced it, never the age of the run that failed.
     */
    @Test
    void aFailedProbeRecordsItsFailureWithoutErasingWhatAnEarlierRunMeasured() {
        Db.run(datasource, () -> {
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.createEmptyRow();
            host.set(ServerModel.NAME, "evidence-a");
            host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
            host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
            host.set(ServerModel.POSTURE, ServerModel.POSTURE_VM_ISOLATED);
            model.save(host);

            // 1. A full, reachable preflight: 16 GB measured, the kernel lane PROVEN.
            Instant measuredAt = Instant.now().minus(Duration.ofMinutes(20));
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put(HostPreflight.MEM_TOTAL_FACT, SIXTEEN_GB);
            facts.put("incus_version", "7.3");
            HostPreflight.store("evidence-a", new HostPreflight.Report(List.of(
                new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "reachable"),
                new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                    HostPreflight.STATUS_PASS, true, "nft transaction applied"),
                new HostPreflight.Check(IncusPreflight.USERNS_CHECK,
                    HostPreflight.STATUS_PASS, true, "uid_map reads '1000000 0 65536'")),
                facts, true, measuredAt, null));

            Row measured = model.findByName("evidence-a");
            assertThat(InstanceCapacity.budgetMbOf(measured))
                .as("step 1: a measured host has a placement budget").isNotNull();

            // 2. THE DEFECT. A preflight whose daemon was unreachable: the battery produces
            //    a failing daemon check and NO facts at all. The failure must land, and
            //    nothing this run never looked at may be erased by it.
            Instant failedAt = Instant.now();
            HostPreflight.store("evidence-a", new HostPreflight.Report(List.of(
                new HostPreflight.Check("daemon", HostPreflight.STATUS_FAIL, true,
                    "unreachable: connection refused")),
                Map.of(), false, failedAt, null));

            Row afterFailure = model.findByName("evidence-a");
            assertThat(HostPreflight.storedCheckStatus(afterFailure, "daemon"))
                .as("step 2: THIS run's own verdict is stored, fail-closed and honest")
                .isEqualTo(HostPreflight.STATUS_FAIL);
            assertThat((Boolean) afterFailure.get(ServerModel.PREFLIGHT_OK))
                .as("step 2: and the run's verdict still sinks preflight_ok").isFalse();
            assertThat(InstanceCapacity.budgetMbOf(afterFailure))
                .withFailMessage("step 2: the failed probe erased the memory reading, so"
                    + " placement now skips a host it was placing on a minute ago -- a"
                    + " diagnostic action cordoned a production host")
                .isNotNull();
            assertThat(HostPreflight.storedCheckStatus(afterFailure,
                    IncusPreflight.KERNEL_LANE_CHECK))
                .as("step 2: a question this run never asked keeps its answer")
                .isEqualTo(HostPreflight.STATUS_PASS);

            // 3. Retained evidence carries ITS OWN age, not the failed run's. This is what
            //    makes the merge honest rather than a way to look permanently fresh.
            assertThat(HostPreflight.factMeasuredAt(afterFailure,
                    HostPreflight.MEM_TOTAL_FACT))
                .as("step 3: the memory reading is stamped when it was MEASURED")
                .isEqualTo(measuredAt);
            assertThat((Instant) afterFailure.get(ServerModel.PROBED_AT))
                .as("step 3: while probed_at follows the run that just failed")
                .isAfter(measuredAt);
            assertThat(HostPreflight.storedCheckAt(afterFailure, "daemon"))
                .as("step 3: and a check this run DID produce is stamped now")
                .isEqualTo(failedAt);

            // 4. The freshness bound therefore judges the MEASUREMENT. A bound the reading
            //    is older than removes the budget even though probed_at is seconds old --
            //    proving the bound never silently rode the failed run's timestamp.
            Integer originalHours = HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Capacity.FACTS_MAX_AGE_HOURS);
            try {
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Capacity.FACTS_MAX_AGE_HOURS, 1);
                Map<String, Object> memory = new LinkedHashMap<>();
                memory.put(HostPreflight.MEM_TOTAL_FACT, SIXTEEN_GB);
                // The reading is measured NINE HOURS ago...
                HostPreflight.store("evidence-a", new HostPreflight.Report(List.of(),
                    memory, true, Instant.now().minus(Duration.ofHours(9)), null));
                assertThat(InstanceCapacity.budgetMbOf(model.findByName("evidence-a")))
                    .as("step 4: a reading older than the bound carries no budget").isNull();

                // 5. ...and a preflight that re-measured NOTHING runs right now. It stamps
                //    probed_at, so a bound reading that column would call the nine-hour-old
                //    number fresh. The bound must judge the MEASUREMENT, or the merge would
                //    have turned a destroyed reading into a permanently young one.
                HostPreflight.store("evidence-a", new HostPreflight.Report(List.of(
                    new HostPreflight.Check("daemon", HostPreflight.STATUS_FAIL, true,
                        "unreachable: connection refused")),
                    Map.of(), false, Instant.now(), null));
                assertThat(InstanceCapacity.budgetMbOf(model.findByName("evidence-a")))
                    .withFailMessage("step 5: a probe that measured nothing made a"
                        + " nine-hour-old memory reading look current, so placement is"
                        + " rationing against a number nobody has re-read")
                    .isNull();

                // 6. POSITIVE ANCHOR: an actual re-measurement restores the budget, so
                //    steps 4 and 5 were the bound answering and not the merge losing a fact.
                HostPreflight.store("evidence-a", new HostPreflight.Report(List.of(),
                    memory, true, Instant.now(), null));
                assertThat(InstanceCapacity.budgetMbOf(model.findByName("evidence-a")))
                    .as("step 6: a freshly measured reading is a budget again").isNotNull();
            } finally {
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Capacity.FACTS_MAX_AGE_HOURS,
                    originalHours != null ? originalHours : 168);
            }
        });
    }

    /**
     * A host that stopped answering takes no NEW workload, says so by name, and says it
     * where an operator reads the host list -- while every stored decision about it is left
     * exactly as the operator made it.
     */
    @Test
    void aLapsedHeartbeatRefusesPlacementByNameAndIsVisibleInTheList() {
        Db.run(datasource, () -> {
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.createEmptyRow();
            host.set(ServerModel.NAME, "evidence-b");
            host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
            host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
            host.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
            host.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
            host.set(ServerModel.PREFLIGHT_OK, true);
            host.set(ServerModel.LAST_SEEN_AT, Instant.now());
            model.save(host);
            int serverId = model.findByName("evidence-b").get(ServerModel.ID);

            Integer originalMinutes = HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Hosts.CONTACT_MAX_AGE_MINUTES);
            try {
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Hosts.CONTACT_MAX_AGE_MINUTES, 180);

                // 1. POSITIVE ANCHOR: a host that answered a moment ago places fine.
                HostAdmission.requireInstancePlacement(serverId);

                // 2. THE DEFECT. Nine hours of silence, and every stored column still says
                //    healthy: admitted, preflight_ok, no error kind. Only the clock knows.
                Row lapsing = model.findByName("evidence-b");
                lapsing.set(ServerModel.LAST_SEEN_AT, Instant.now().minus(Duration.ofHours(9)));
                model.save(lapsing);
                assertThat(catchThrowable(() ->
                        HostAdmission.requireInstancePlacement(serverId)))
                    .withFailMessage("step 2: a host nobody has heard from in nine hours"
                        + " still accepts new workloads")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key())
                                .isEqualTo("host_contact_lapsed")));

                // 3. The refusal takes NOTHING away: the operator's admission decision, the
                //    preflight verdict and the record itself are untouched, so the host
                //    resumes the moment it answers again.
                Row afterRefusal = model.findByName("evidence-b");
                assertThat((String) afterRefusal.get(ServerModel.ADMISSION))
                    .as("step 3: no gate rewrites the admission column behind the operator")
                    .isEqualTo(ServerModel.ADMISSION_ADMITTED);

                // 4. And it is READABLE: the list cell says the host is silent, where before
                //    a lapsed host rendered identically to a healthy one.
                assertThat(hostStatusCell(afterRefusal))
                    .withFailMessage("step 4: the host list shows nothing about a host that"
                        + " stopped answering: '%s'", hostStatusCell(afterRefusal))
                    .containsIgnoringCase("silent");

                // 5. POSITIVE ANCHOR for the whole bound: a fresh contact clears both the
                //    refusal and the cell, so steps 2 and 4 were the clock and not a gate
                //    that refuses everything.
                Row answering = model.findByName("evidence-b");
                answering.set(ServerModel.LAST_SEEN_AT, Instant.now());
                model.save(answering);
                HostAdmission.requireInstancePlacement(serverId);
                assertThat(hostStatusCell(model.findByName("evidence-b")))
                    .as("step 5: and the cell stops saying it").doesNotContainIgnoringCase(
                        "silent");

                // 6. Zero disables the bound outright -- the declared way out for an
                //    operator who would rather place onto an unheard-from host.
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Hosts.CONTACT_MAX_AGE_MINUTES, 0);
                Row silentAgain = model.findByName("evidence-b");
                silentAgain.set(ServerModel.LAST_SEEN_AT,
                    Instant.now().minus(Duration.ofDays(30)));
                model.save(silentAgain);
                HostAdmission.requireInstancePlacement(serverId);
            } finally {
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Hosts.CONTACT_MAX_AGE_MINUTES,
                    originalMinutes != null ? originalMinutes : 180);
            }
        });
    }

    /**
     * A quarantine survives in the LIST, and a locally-addressed Incus record says out loud
     * that its kernel verdict is about the controller's own machine.
     */
    @Test
    void theListShowsAQuarantineAndTheFormNamesTheMachineAVerdictIsAbout() {
        Db.run(datasource, () -> {
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.createEmptyRow();
            host.set(ServerModel.NAME, "evidence-c");
            host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
            host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
            host.set(ServerModel.POSTURE, ServerModel.POSTURE_VM_ISOLATED);
            host.set(ServerModel.SSH_TARGET, "root@192.0.2.44");
            model.save(host);
            HostPins.apply(model.findByName("evidence-c"), HostTrustSlot.SSH, SSH_KEY,
                HostKeys.fingerprintOf(SSH_KEY));
            HostPins.confirm(model.findByName("evidence-c"), HostTrustSlot.SSH);
            HostPreflight.store("evidence-c", new HostPreflight.Report(List.of(
                new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                    HostPreflight.STATUS_PASS, true, "nft transaction applied")),
                Map.of("incus_version", "7.3"), true, Instant.now(), null));

            // 1. THE DEFECT (item 4). The record has a blank incus_url, so it addresses the
            //    CONTROLLER's own socket -- while the operator reads a row named after some
            //    other machine and a kernel verdict that never says which machine it is
            //    about.
            String kernelState = kernelIsolationState("evidence-c");
            assertThat(kernelState)
                .as("step 1: the kernel verdict is a PASS").containsIgnoringCase("verified");
            assertThat(kernelState)
                .withFailMessage("step 1: the form reports a green kernel verdict without"
                    + " ever saying it is about the controller's own daemon: '%s'",
                    kernelState)
                .contains("unix://");
            assertThat(kernelState)
                .as("step 1: and says whose machine that is")
                .containsIgnoringCase("controller");

            // 2. POSITIVE ANCHOR: an https record addresses a real remote daemon, so the
            //    local-socket sentence must NOT appear -- step 1 is not a constant string.
            Row remote = model.findByName("evidence-c");
            remote.set(ServerModel.INCUS_URL, "https://192.0.2.44:8443");
            model.save(remote);
            assertThat(kernelIsolationState("evidence-c"))
                .as("step 2: a genuinely remote daemon carries no local-socket warning")
                .doesNotContain("unix://");

            // 3. THE DEFECT (the cheap one). A quarantined host that a later probe REACHED:
            //    the success cleared last_error_kind, which is the only thing the list cell
            //    used to look at, so a security state vanished from the list entirely.
            Row quarantined = model.findByName("evidence-c");
            quarantined.set(ServerModel.QUARANTINED_AT, Instant.now());
            quarantined.set(ServerModel.QUARANTINE_REASON, "the daemon offered another cert");
            quarantined.set(ServerModel.LAST_ERROR_KIND, (String) null);
            quarantined.set(ServerModel.LAST_SEEN_AT, Instant.now());
            model.save(quarantined);
            assertThat(hostStatusCell(model.findByName("evidence-c")))
                .withFailMessage("step 3: a quarantined host renders in the list with no"
                    + " quarantine word at all: '%s'",
                    hostStatusCell(model.findByName("evidence-c")))
                .containsIgnoringCase("quarantin");

            // 4. POSITIVE ANCHOR: lifting the stamp restores the ordinary cell, so step 3
            //    was the quarantine column and not a cell that always shouts.
            Row cleared = model.findByName("evidence-c");
            cleared.set(ServerModel.QUARANTINED_AT, (Instant) null);
            model.save(cleared);
            assertThat(hostStatusCell(model.findByName("evidence-c")))
                .as("step 4: an unquarantined host reads as its daemon again")
                .doesNotContainIgnoringCase("quarantin").contains("Incus");
        });
    }

    /**
     * The Incus isolation sweep looks at every workload that could be on the bridge, which
     * is the same set its Docker twin looks at.
     */
    @Test
    void theIncusIsolationSweepCoversEveryWorkloadThatCouldBeOnTheBridge() {
        Db.run(datasource, () -> {
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.createEmptyRow();
            host.set(ServerModel.NAME, "evidence-d");
            host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
            host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
            host.set(ServerModel.INCUS_URL, "https://192.0.2.77:8443");
            host.set(ServerModel.POSTURE, ServerModel.POSTURE_VM_ISOLATED);
            model.save(host);
            int serverId = model.findByName("evidence-d").get(ServerModel.ID);

            // 1. A workload the deploy stamped `starting`, which is what every template
            //    carrying a readiness line gets: the guest is up and on the bridge.
            int starting = instance("evidence-d-starting", serverId,
                InstanceModel.STATUS_STARTING);

            // 2. THE DEFECT. The sweep filtered `running` alone, so this host was not swept
            //    at all -- its workload's isolation was never read from the kernel. The
            //    host appearing in the outcomes is the observable: the sweep cannot reach
            //    this unreachable daemon, but it must have TRIED.
            assertThat(sweptHosts())
                .withFailMessage("step 2: a workload in `starting` is on the bridge and the"
                    + " sweep never looks at it")
                .contains("evidence-d");

            // 3. The same for `error`, which is the status a readiness timeout stamps while
            //    stopping NOTHING -- so the record claims failure and the guest keeps
            //    running. It was missing from BOTH sweeps.
            statusOf(starting, InstanceModel.STATUS_STOPPED);
            int errored = instance("evidence-d-error", serverId, InstanceModel.STATUS_ERROR);
            assertThat(sweptHosts())
                .withFailMessage("step 3: an errored record routinely names a guest that is"
                    + " still up, and the sweep never looks at it")
                .contains("evidence-d");

            // 4. POSITIVE ANCHOR: with every workload stopped, the host drops out of the
            //    sweep entirely -- so steps 2 and 3 were the status filter answering and
            //    not a sweep that walks every host regardless.
            statusOf(errored, InstanceModel.STATUS_STOPPED);
            assertThat(sweptHosts())
                .as("step 4: a host with no live workload is not swept")
                .doesNotContain("evidence-d");
        });
    }

    // -- helpers ------------------------------------------------------------------

    /** Every host the isolation sweep actually visited this run. */
    private static List<String> sweptHosts() {
        List<String> names = new ArrayList<>();
        for (VerifyIncusIsolation.HostOutcome outcome : VerifyIncusIsolation.sweep()) {
            names.add(outcome.server());
        }
        return names;
    }

    private static int instance(String name, int serverId, String status) {
        InstanceModel model = Models.get(InstanceModel.class);
        Row row = model.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.SERVER_ID, serverId);
        row.set(InstanceModel.STATUS, status);
        model.save(row);
        return model.find().where(InstanceModel.NAME.eq(name)).first().get(InstanceModel.ID);
    }

    private static void statusOf(int instanceId, String status) {
        InstanceModel model = Models.get(InstanceModel.class);
        Row row = model.findById(instanceId);
        row.set(InstanceModel.STATUS, status);
        model.save(row);
    }

    /** The host list's status cell, through the resource's own production path. */
    private static String hostStatusCell(Row row) {
        ServerResource resource = new ServerResource();
        for (ColumnSpec column : resource.tableSpec().columns()) {
            if ("host_status".equals(column.name())) {
                return String.valueOf(resource.cellValue(row, column));
            }
        }
        throw new IllegalStateException("the host list has no host_status column");
    }

    /** The host form's kernel-isolation field, computed the way the form computes it. */
    @SuppressWarnings("unchecked")
    private static String kernelIsolationState(String name) {
        for (FormEntry entry : new ServerResource().formSpec().entries()) {
            if (entry instanceof Computed<?> computed
                    && "kernel_isolation_state".equals(computed.name())) {
                return String.valueOf(((Computed<String>) computed)
                    .compute(Map.of("name", name)));
            }
        }
        throw new IllegalStateException("the host form has no kernel_isolation_state entry");
    }
}
