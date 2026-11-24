package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.host.HostPostureAcknowledgement;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import be.elevenways.zenit.common.security.CallerChannel;
import be.elevenways.zenit.common.security.Principal;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-side operator decisions over the host record: the admission gate refuses
 * instance placement on any host that was not preflighted, admitted AND -- since the
 * posture acknowledgement landed -- whose shared-container risk nobody accepted, so a
 * test that deploys must first do what an operator would.
 */
public final class HostFixtures {

    private HostFixtures() {
    }

    /**
     * Make the implicit local host PLACEABLE: admitted, acknowledged AND measured.
     *
     * AIDEV-NOTE: admission alone is not placement. The chooser needs a memory budget to
     * ration against and answers {@code host_capacity_unproven} for a host nobody ever
     * measured, so a fixture that only admits leaves every create refused for a reason
     * that has nothing to do with what it is testing. Idempotent, like {@link #admitLocal},
     * and the report goes through {@link HostPreflight#store} rather than hand-writing the
     * capabilities shape.
     *
     * @param memoryMb the measured total the budget is derived from
     */
    public static void makeLocalPlaceable(long memoryMb) {
        admitLocal();
        HostPreflight.store(HostModel.MODE_LOCAL, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true,
                "fixture: reachable")),
            Map.of(HostPreflight.MEM_TOTAL_FACT, memoryMb * 1024L * 1024L),
            true, Now.instant(), null));
    }

    /** Give the implicit local host a quota-capable volume root, which a workspace and an application need. */
    public static void giveLocalVolumeQuota() {
        VolumeBackends.store(Models.get(HostModel.class).findById(HostModel.localHostId()),
            new VolumeBackends.Detection(VolumeBackend.BTRFS, "/fixture/volumes", "fixture: btrfs"));
    }

    /** Withdraw the implicit local host's admission, the state a fresh installation is in. */
    public static void blockLocal() {
        Row row = Models.get(HostModel.class).findById(HostModel.localHostId());
        row.set(HostModel.ADMISSION, HostModel.ADMISSION_BLOCKED);
        Models.get(HostModel.class).save(row);
    }

    /**
     * Capture every column of the implicit local host this fixture family writes, so a
     * class that admits, blocks or measures it can hand the shared row back unchanged.
     *
     * AIDEV-NOTE: the local host is ONE row shared by every class in a browser-test fork,
     * and Gradle hands classes to forks by timing, so a class that admits it in a
     * {@code @BeforeAll} and never restores decides what every LATER class in that fork
     * sees. TenantInstanceSurfaceTest failed exactly that way on 2026-09-24 once
     * InstanceQuotaTest happened to run before it. {@link #CAPTURED} must name every column
     * {@link #admitLocal}, {@link #makeLocalPlaceable}, {@link #giveLocalVolumeQuota}, {@link #blockLocal} and
     * {@link #acknowledgePosture} write, including what {@link HostPreflight#store} and the
     * probe it drives stamp.
     *
     * @return the captured state; {@link LocalHostState#restore} writes it back
     */
    public static @NonNull LocalHostState captureLocal() {
        Row row = Models.get(HostModel.class).findById(HostModel.localHostId());
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field<?, ?> field : CAPTURED) {
            values.put(field.getName(), row.get(field.getName()));
        }
        return new LocalHostState(values);
    }

    /** The local-host columns {@link #captureLocal} saves and {@link LocalHostState#restore} writes back. */
    private static final List<Field<?, ?>> CAPTURED = List.of(
        HostModel.ADMISSION,
        HostModel.POSTURE,
        HostModel.PREFLIGHT_OK,
        HostModel.CAPABILITIES,
        HostModel.PROBED_AT,
        HostModel.CONTROLLER_VERSION,
        HostModel.LAST_SEEN_AT,
        HostModel.LAST_ERROR_KIND,
        HostModel.LAST_ERROR,
        HostModel.ACKNOWLEDGED_POSTURE,
        HostModel.ACKNOWLEDGED_WARNING_VERSION,
        HostModel.ACKNOWLEDGED_AT,
        HostModel.ACKNOWLEDGED_BY,
        HostModel.ACKNOWLEDGED_BY_KIND,
        HostModel.ACKNOWLEDGED_BY_LABEL,
        HostModel.VOLUME_BACKEND,
        HostModel.VOLUME_ROOT,
        HostModel.VOLUME_BACKEND_DETAIL,
        HostModel.VOLUME_PROBED_AT);

    /**
     * The implicit local host's fixture-written columns at one moment.
     *
     * @param values the raw stored value per column name, nulls included
     */
    public record LocalHostState(@NonNull Map<String, Object> values) {

        /**
         * Write the captured columns back onto the local host in ONE save.
         *
         * AIDEV-NOTE: posture and acknowledgement travel together on purpose. The model's
         * before-validate hook erases an acknowledgement that does not name the row's
         * posture, and the captured pair is consistent by construction.
         */
        public void restore() {
            Row row = Models.get(HostModel.class).findById(HostModel.localHostId());
            for (Map.Entry<String, Object> value : this.values.entrySet()) {
                row.set(value.getKey(), value.getValue());
            }
            Models.get(HostModel.class).save(row);
        }
    }

    /**
     * A new admitted, acknowledged and measured incus host record whose preflight passed on
     * a fake daemon and a fake kernel-truth lane.
     *
     * AIDEV-NOTE: nine classes carried this block verbatim. mem_total is what the capacity
     * budget is read from: an admitted host always carries it in production, and placement
     * skips one that does not.
     *
     * @return the new server's id
     */
    public static int admittedIncusHost(@NonNull String name) {
        Row row = Models.get(HostModel.class).createEmptyRow();
        row.set(HostModel.NAME, name);
        row.set(HostModel.RUNTIME, HostModel.RUNTIME_INCUS);
        row.set(HostModel.ADMISSION, HostModel.ADMISSION_ADMITTED);
        row.set(HostModel.POSTURE, HostModel.POSTURE_SHARED_CONTAINER);
        Models.get(HostModel.class).save(row);
        acknowledgePosture(row);
        HostPreflight.store(name, new HostPreflight.Report(List.of(
            new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "fake daemon"),
            new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                HostPreflight.STATUS_PASS, true, "fake kernel-truth lane")),
            Map.of(HostPreflight.MEM_TOTAL_FACT, 16L * 1024 * 1024 * 1024), true, Now.instant(),
            null));
        return Models.get(HostModel.class).findByName(name).get(HostModel.ID);
    }

    /**
     * A new admitted, acknowledged and measured Docker host on the local socket, so no pinned identity is asked of it.
     *
     * @param memoryMb the measured total its budget is derived from
     * @return the new server's id
     */
    public static int admittedDockerHost(@NonNull String name, long memoryMb) {
        Row row = Models.get(HostModel.class).createEmptyRow();
        row.set(HostModel.NAME, name);
        row.set(HostModel.RUNTIME, HostModel.RUNTIME_DOCKER);
        row.set(HostModel.MODE, HostModel.MODE_LOCAL);
        Models.get(HostModel.class).save(row);
        int id = row.get(HostModel.ID);
        admitDockerHost(id, memoryMb);
        return id;
    }

    /**
     * Do for an enrolled Docker host what an operator does before it carries apps: pin and confirm its ssh identity
     * when it is remote, admit it under shared_container, acknowledge that posture and store a passing, measured
     * preflight.
     *
     * @param memoryMb the measured total its budget is derived from
     */
    public static void admitDockerHost(int serverId, long memoryMb) {
        HostModel servers = Models.get(HostModel.class);
        Row row = servers.findById(serverId);
        row.set(HostModel.ADMISSION, HostModel.ADMISSION_ADMITTED);
        row.set(HostModel.POSTURE, HostModel.POSTURE_SHARED_CONTAINER);
        servers.save(row);
        if (HostModel.requiresPinnedIdentity(servers.findById(serverId))) {
            HostPins.apply(servers.findById(serverId), HostTrustSlot.SSH, FIXTURE_SSH_KEY, "sha256:fixture");
            HostPins.confirm(servers.findById(serverId), HostTrustSlot.SSH);
        }
        acknowledgePosture(servers.findById(serverId));
        HostPreflight.store(String.valueOf((Object) row.get(HostModel.NAME)), new HostPreflight.Report(List.of(
            new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "fake daemon")),
            Map.of(HostPreflight.MEM_TOTAL_FACT, memoryMb * 1024L * 1024L), true, Now.instant(), null));
    }

    /** The ssh host key a remote fixture host is pinned to. */
    private static final String FIXTURE_SSH_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    /** Admit the implicit local host for tenant placement (posture shared_container). */
    public static void admitLocal() {
        int id = HostModel.localHostId();
        Row row = Models.get(HostModel.class).findById(id);
        row.set(HostModel.PREFLIGHT_OK, true);
        row.set(HostModel.ADMISSION, HostModel.ADMISSION_ADMITTED);
        row.set(HostModel.POSTURE, HostModel.POSTURE_SHARED_CONTAINER);
        Models.get(HostModel.class).save(row);
        acknowledgePosture(row);
    }

    /**
     * ENSURE a host carries a current risk acknowledgement, performing the operator act as
     * a named operator when it does not -- the same production entry point the row action
     * uses.
     *
     * AIDEV-NOTE: idempotent on purpose, because {@link #admitLocal} is. The implicit local
     * host is ONE row shared by every class in a browser-test JVM, so this runs many times
     * against the same record; the production act refuses a redundant acknowledgement by
     * name ({@code posture_already_acknowledged}), which is right for an operator clicking
     * twice and wrong for a fixture asserting a precondition. Ten slow-lane classes failed
     * on exactly that before the guard was added.
     *
     * AIDEV-NOTE: the posture must already be SAVED before this runs. The model's
     * before-validate hook erases an acknowledgement that does not name the row's posture,
     * so stamping one in the same save as the posture change would clear it again -- which
     * is the hook doing its job, not a bug to work around.
     */
    public static void acknowledgePosture(@NonNull Row server) {
        if (!HostModel.postureNeedsAcknowledgement(server)
                || HostModel.postureAcknowledged(server)) {
            return;
        }
        // AIDEV-NOTE: resolve the shared fixture's real account in the CURRENT datasource, not a fabricated actor
        // token or a cached id from another test database. Risk acceptance records an ACCOUNT principal.
        Principal operator = TenantConduits.operator().principal();
        Accountability attribution = Accountability.of(operator.reference(), operator.attributionLabel(),
            new CallerChannel(AccountabilityOrigin.OFFLINE, null, null));
        Accountability.runAs(attribution, () -> HostPostureAcknowledgement.record(server));
    }
}
