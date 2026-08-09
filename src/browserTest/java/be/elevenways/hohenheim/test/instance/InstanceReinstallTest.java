package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.instance.InstanceInstalls;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * {@code InstanceInstalls.reinstall} with NO daemon anywhere: the DESTRUCTIVE lane of the
 * template lifecycle, driven over {@link FakeNativeDaemons}. Reinstall is the explicit
 * wipe verb -- deliberately distinct from create, which CONVERGES onto an existing
 * instance so a redeploy can never destroy a system container's rootfs.
 *
 * AIDEV-NOTE: until this class, the only coverage of {@code reinstall} anywhere was
 * {@code InstanceTemplateInstallLiveTest}, which skips on any host without a Docker socket
 * and a pre-pulled alpine -- so the wipe lane was, in practice, unproven. What is asserted
 * here is the SEMANTIC, never merely that the call returns: what is gone afterwards, what
 * survives it, and that a caller lacking the wipe authority is refused BEFORE anything is
 * destroyed.
 *
 * What this cannot prove, so nothing reads its green as total coverage: the real driver's
 * owner-verified volume removal and the one-shot install sibling. The fake's "volumes" are
 * a map that dies with the workload -- the rootfs-stateful (incus) shape, where destroying
 * the workload IS the wipe -- so {@code removeVolumesForRestore} is not exercised here.
 */
class InstanceReinstallTest extends HohenheimTestBase {

    private static final String PREFIX = "reinstall-";
    private static final String SCRIPT = "echo install >> /data/runs";

    private static int hostId;
    private static int configOnlyUserId;
    private static UserPrincipal configOnlyPrincipal;

    @BeforeAll
    static void seed() {
        FakeNativeDaemons.register();
        FakeNativeDaemons.resetInstalls();
        hostId = incusHost(PREFIX + "host");

        Row tenant = AuthModels.users().createEmptyRow();
        tenant.set(UserModel.EMAIL, "reinstall-config-only@hohenheim.local");
        tenant.set(UserModel.DISPLAY_NAME, "Config Only");
        tenant.set(UserModel.ENABLED, true);
        tenant.set(UserModel.CREATED_AT, Instant.now());
        tenant.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(tenant);
        configOnlyUserId = tenant.get(UserModel.ID);
        configOnlyPrincipal = new UserPrincipal(configOnlyUserId, "Config Only");
    }

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX))
                .withTrashed().all()) {
            int id = row.get(InstanceModel.ID);
            for (Row variable : Models.get(InstanceVariableModel.class).findByInstanceId(id)) {
                Models.get(InstanceVariableModel.class)
                    .delete(variable.get(InstanceVariableModel.ID));
            }
            instances.delete(id);
        }
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find()
                .where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            templates.delete(row.get(InstanceTemplateModel.ID));
        }
        FakeNativeDaemons.resetInstalls();
    }

    /**
     * The two data policies as ONE journey over the same instance shape: preserve re-runs
     * the step over the data that is already there, clear destroys the workload (and, on a
     * rootfs-stateful driver, its data with it) and installs onto nothing. Both leave the
     * instance's VARIABLE ROWS untouched -- losing a tenant's only copy of their
     * credentials to a reinstall is the failure class InstanceInstalls exists to prevent.
     */
    @Test
    void reinstallClearsExactlyWhatThePolicyDeclaresAndNeverTheVariables() {
        int preserveTemplate = template(PREFIX + "preserve",
            InstanceTemplateModel.REINSTALL_PRESERVE);
        int clearTemplate = template(PREFIX + "clear", InstanceTemplateModel.REINSTALL_CLEAR);

        // 1. An installed instance whose workload carries data, plus a variable row that
        //    must outlive everything below.
        int instanceId = instanceRecord(PREFIX + "preserve-box", preserveTemplate);
        String handle = FakeNativeDaemons.handleOf(instanceId);
        Map<String, FakeNativeDaemons.FakeWorkload> daemon = FakeNativeDaemons.daemonOf(hostId);
        InstanceService service = new InstanceService();
        service.deploy(instanceId);
        service.stop(instanceId);
        variable(instanceId, "DB_PASSWORD", "hunter2");
        new InstanceInstalls().install(instanceId);
        daemon.get(handle).data.put("tenant-file", "the tenant's own bytes");
        assertThat(Map.of(
                "state", String.valueOf((Object) reload(instanceId)
                    .get(InstanceModel.INSTALL_STATE)),
                "runs", String.valueOf(FakeNativeDaemons.INSTALL_RUNS
                    .getOrDefault(handle, List.of()).size())))
            .as("step 1: the install step ran once and the record says installed")
            .isEqualTo(Map.of("state", InstanceModel.INSTALL_INSTALLED, "runs", "1"));

        // 2. PRESERVE: the step runs again over data that is still there. This is the
        //    positive anchor for step 3 -- a reinstall that wiped unconditionally would
        //    satisfy "clear wiped it" for free.
        new InstanceInstalls().reinstall(instanceId);
        assertThat(Map.of(
                "workload", String.valueOf(daemon.containsKey(handle)),
                "tenant-file", String.valueOf(daemon.get(handle).data.get("tenant-file")),
                "runs", String.valueOf(FakeNativeDaemons.INSTALL_RUNS.get(handle).size())))
            .as("step 2: a PRESERVE reinstall keeps the workload and its data, and re-runs"
                + " the install step over them")
            .isEqualTo(Map.of("workload", "true",
                "tenant-file", "the tenant's own bytes", "runs", "2"));

        // 3. CLEAR, on a second instance: the workload is DESTROYED before the step runs,
        //    so the data it held is gone -- the rootfs-stateful wipe.
        int clearId = instanceRecord(PREFIX + "clear-box", clearTemplate);
        String clearHandle = FakeNativeDaemons.handleOf(clearId);
        service.deploy(clearId);
        service.stop(clearId);
        variable(clearId, "DB_PASSWORD", "hunter2");
        new InstanceInstalls().install(clearId);
        daemon.get(clearHandle).data.put("tenant-file", "the tenant's own bytes");
        new InstanceInstalls().reinstall(clearId);
        assertThat(Map.of(
                "workload", String.valueOf(daemon.containsKey(clearHandle)),
                "runs", String.valueOf(FakeNativeDaemons.INSTALL_RUNS.get(clearHandle).size()),
                "state", String.valueOf((Object) reload(clearId)
                    .get(InstanceModel.INSTALL_STATE))))
            .as("step 3: a CLEAR reinstall destroyed the workload (its data with it) and"
                + " then ran the install step onto nothing")
            .isEqualTo(Map.of("workload", "false", "runs", "2",
                "state", InstanceModel.INSTALL_INSTALLED));

        // 4. What SURVIVED: the record and every variable row on it, on BOTH instances.
        //    A wipe that also took the credentials would still pass step 3.
        assertThat(Map.of(
                "preserve", variableValues(instanceId),
                "clear", variableValues(clearId)))
            .as("step 4: NO reinstall policy touches the instance's variable rows")
            .isEqualTo(Map.of("preserve", "DB_PASSWORD=hunter2",
                "clear", "DB_PASSWORD=hunter2"));
        assertThat(reload(clearId))
            .as("step 4: and the record itself survives its own wipe").isNotNull();

        // 5. The wipe is NOT conditional on the install succeeding: a clear reinstall
        //    whose script then fails leaves the data gone and says FAILED, visibly. A
        //    caller that read "failed" as "nothing happened" would be wrong.
        int failId = instanceRecord(PREFIX + "clear-fail-box", clearTemplate);
        String failHandle = FakeNativeDaemons.handleOf(failId);
        service.deploy(failId);
        service.stop(failId);
        new InstanceInstalls().install(failId);
        daemon.get(failHandle).data.put("tenant-file", "the tenant's own bytes");
        FakeNativeDaemons.INSTALL_FAILS.add(failHandle);
        Throwable failed = catchThrowable(() -> new InstanceInstalls().reinstall(failId));
        assertThat(Map.of(
                "violation", violationKeys("step 5: the failing install step refuses", failed),
                "workload", String.valueOf(daemon.containsKey(failHandle)),
                "state", String.valueOf((Object) reload(failId)
                    .get(InstanceModel.INSTALL_STATE))))
            .as("step 5: the wipe already happened when the step failed, and the record"
                + " carries the failure rather than a clean-looking installed")
            .isEqualTo(Map.of("violation", "install_failed ",
                "workload", "false", "state", InstanceModel.INSTALL_FAILED));
    }

    /**
     * The authorization the wipe lane had none of: a tenant-originated reinstall asks for
     * CONFIG, and a {@code clear} policy additionally asks for DESTROY -- BEFORE the
     * workload is touched. A refusal that arrives after the destroy would be no refusal
     * at all, which is why every assertion here reads the daemon back.
     */
    @Test
    void aCallerWithoutTheWipeAuthorityIsRefusedBeforeAnythingIsDestroyed() {
        int clearTemplate = template(PREFIX + "gated", InstanceTemplateModel.REINSTALL_CLEAR);
        int instanceId = instanceRecord(PREFIX + "gated-box", clearTemplate);
        String handle = FakeNativeDaemons.handleOf(instanceId);
        Map<String, FakeNativeDaemons.FakeWorkload> daemon = FakeNativeDaemons.daemonOf(hostId);
        InstanceService service = new InstanceService();
        service.deploy(instanceId);
        service.stop(instanceId);
        new InstanceInstalls().install(instanceId);
        daemon.get(handle).data.put("tenant-file", "the tenant's own bytes");
        int runsBefore = FakeNativeDaemons.INSTALL_RUNS.get(handle).size();

        // 1. No grant at all: refused, and the workload is untouched.
        Throwable ungranted = catchThrowable(() -> TenantConduits.as(configOnlyPrincipal,
            () -> new InstanceInstalls().reinstall(instanceId)));
        assertThat(Map.of(
                "violation", violationKeys("step 1: an ungranted tenant is refused", ungranted),
                "workload", String.valueOf(daemon.containsKey(handle)),
                "tenant-file", String.valueOf(daemon.get(handle).data.get("tenant-file"))))
            .as("step 1: a tenant holding nothing is refused and destroys nothing")
            .isEqualTo(Map.of("violation", "instance_not_permitted ",
                "workload", "true", "tenant-file", "the tenant's own bytes"));

        // 2. CONFIG alone -- the authority every other "author what the instance is" act
        //    needs -- still does not buy the WIPE. The split is the whole point: this is
        //    the assertion that separates "gated" from "gated by the right verb".
        RecordGrants.grant("user", configOnlyUserId, InstanceModel.MODEL_ID,
            instanceId, HohenheimAccess.CONFIG, true);
        Throwable configOnly = catchThrowable(() -> TenantConduits.as(configOnlyPrincipal,
            () -> new InstanceInstalls().reinstall(instanceId)));
        assertThat(Map.of(
                "violation", violationKeys("step 2: CONFIG alone does not buy the wipe", configOnly),
                "workload", String.valueOf(daemon.containsKey(handle)),
                "tenant-file", String.valueOf(daemon.get(handle).data.get("tenant-file")),
                "runs", String.valueOf(FakeNativeDaemons.INSTALL_RUNS.get(handle).size())))
            .as("step 2: CONFIG without DESTROY is refused, and NOTHING ran -- not the"
                + " destroy, not the install step")
            .isEqualTo(Map.of("violation", "instance_not_permitted ",
                "workload", "true", "tenant-file", "the tenant's own bytes",
                "runs", String.valueOf(runsBefore)));

        // 3. The POSITIVE anchor: with DESTROY added the very same call goes through, so
        //    steps 1 and 2 cannot be passing because the call was broken for other reasons.
        RecordGrants.grant("user", configOnlyUserId, InstanceModel.MODEL_ID,
            instanceId, HohenheimAccess.DESTROY, true);
        TenantConduits.as(configOnlyPrincipal,
            () -> new InstanceInstalls().reinstall(instanceId));
        assertThat(Map.of(
                "workload", String.valueOf(daemon.containsKey(handle)),
                "runs", String.valueOf(FakeNativeDaemons.INSTALL_RUNS.get(handle).size()),
                "state", String.valueOf((Object) reload(instanceId)
                    .get(InstanceModel.INSTALL_STATE))))
            .as("step 3: CONFIG plus DESTROY performs the wipe the two refusals prevented")
            .isEqualTo(Map.of("workload", "false", "runs", String.valueOf(runsBefore + 1),
                "state", InstanceModel.INSTALL_INSTALLED));
    }

    // -- fixtures -------------------------------------------------------------

    private static Row reload(int instanceId) {
        return Models.get(InstanceModel.class).findById(instanceId);
    }

    private static String variableValues(int instanceId) {
        StringBuilder text = new StringBuilder();
        for (Row row : Models.get(InstanceVariableModel.class).findByInstanceId(instanceId)) {
            text.append((String) row.get(InstanceVariableModel.KEY)).append('=')
                .append((String) row.get(InstanceVariableModel.PLAIN_VALUE));
        }
        return text.toString();
    }

    /** The refusal's violation keys, or a loud failure naming the step that expected one. */
    private static String violationKeys(String what, Throwable thrown) {
        assertThat(thrown).as(what).isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }

    private static void variable(int instanceId, String key, String value) {
        Row row = Models.get(InstanceVariableModel.class).createEmptyRow();
        row.set(InstanceVariableModel.INSTANCE_ID, instanceId);
        row.set(InstanceVariableModel.KEY, key);
        row.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_PLAIN);
        row.set(InstanceVariableModel.PLAIN_VALUE, value);
        Models.get(InstanceVariableModel.class).save(row);
    }

    private static int template(String name, String reinstallPolicy) {
        Row row = Models.get(InstanceTemplateModel.class).createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.KIND,
            FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceTemplateModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceTemplateModel.INSTALL_SCRIPT, SCRIPT);
        row.set(InstanceTemplateModel.REINSTALL_POLICY, reinstallPolicy);
        row.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
        row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, 1L);
        Models.get(InstanceTemplateModel.class).save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static int instanceRecord(String name, int templateId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "fake/image")));
        row.set(InstanceModel.SERVER_ID, hostId);
        row.set(InstanceModel.TEMPLATE_ID, templateId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int incusHost(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        HostPreflight.store(name, new HostPreflight.Report(List.of(
            new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "fake daemon"),
            new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                HostPreflight.STATUS_PASS, true, "fake kernel-truth lane")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return Models.get(ServerModel.class).findByName(name).get(ServerModel.ID);
    }
}
