package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Prepared templates are a DECLARED image origin through the SAME Incus driver, never a
 * Windows-shaped branch: {@link ImageOrigin}, {@code secure_boot} and {@code guest_agent}
 * are the capabilities that carry what a prepared image needs that a cloud-init Linux
 * guest does not. This journey walks the source-map shape, the alias preflight refusal,
 * the managed secure-boot key, the guest-agent exec refusal, the Docker driver's refusal,
 * and the image-authorisation gate's origin comparison -- no live daemon needed
 * ({@link FakeIncusTransport} is an in-memory echo of the handful of endpoints the
 * driver touches).
 */
class PreparedImageTest extends HohenheimTestBase {

    private static final String PREFIX = "prep-img-";

    private static Integer tenantId;
    private static UserPrincipal tenantPrincipal;

    @BeforeAll
    static void seedActor() {
        Row tenant = AuthModels.users().createEmptyRow();
        tenant.set(UserModel.EMAIL, "prep-img-tenant@hohenheim.local");
        tenant.set(UserModel.DISPLAY_NAME, "Prepared Image Tenant");
        tenant.set(UserModel.ENABLED, true);
        tenant.set(UserModel.CREATED_AT, Instant.now());
        tenant.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(tenant);
        tenantId = tenant.get(UserModel.ID);
        tenantPrincipal = new UserPrincipal(tenantId, "Prepared Image Tenant");
    }

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            instances.delete(row.get(InstanceModel.ID));
        }
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find().where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            templates.delete(row.get(InstanceTemplateModel.ID));
        }
    }

    @Test
    void preparedOriginIsADeclaredCapabilityNeverAWindowsBranch() throws Exception {
        // 1. The no-regression anchor: a CATALOG spec still produces exactly the
        //    simplestreams source map, unchanged from before this feature existed.
        FakeIncusTransport catalogDaemon = new FakeIncusTransport();
        IncusInstanceRuntime catalogRuntime = new IncusInstanceRuntime(
            new IncusClient(catalogDaemon), Egress.OPEN, IncusWorkloadType.CONTAINER, null);
        InstanceSpec catalogSpec = spec("prep-img-catalog", "alpine/3.22", ImageOrigin.CATALOG,
            false, true, null);
        catalogRuntime.create(catalogSpec);
        Map<String, Object> catalogSource = sourceOf(catalogDaemon);
        assertThat(catalogSource)
            .as("step 1: a catalog spec's source map is unchanged: simplestreams + alias")
            .containsEntry("type", "image")
            .containsEntry("protocol", "simplestreams")
            .containsEntry("server", IncusInstanceRuntime.IMAGE_SERVER)
            .containsEntry("alias", "alpine/3.22")
            .doesNotContainKey("fingerprint");

        // 2. A PREPARED spec whose alias EXISTS on the daemon produces a local source
        //    map with NO protocol and NO server key -- the daemon resolves it itself.
        FakeIncusTransport preparedDaemon = new FakeIncusTransport();
        preparedDaemon.imageAliases.put("windows-2022-rdp", "fp-resolved-000");
        IncusInstanceRuntime preparedRuntime = new IncusInstanceRuntime(
            new IncusClient(preparedDaemon), Egress.OPEN, IncusWorkloadType.CONTAINER, null);
        InstanceSpec preparedSpec = spec("prep-img-prepared", "windows-2022-rdp",
            ImageOrigin.PREPARED, false, true, null);
        preparedRuntime.create(preparedSpec);
        Map<String, Object> preparedSource = sourceOf(preparedDaemon);
        assertThat(preparedSource)
            .as("step 2: a prepared spec's source map carries NO protocol and NO server")
            .containsEntry("type", "image")
            .containsEntry("alias", "windows-2022-rdp")
            .doesNotContainKey("protocol")
            .doesNotContainKey("server")
            .doesNotContainKey("fingerprint");

        // 3. A PREPARED spec whose alias is ABSENT on the daemon is refused before the
        //    daemon ever sees a create -- naming the alias and the server.
        FakeIncusTransport absentDaemon = new FakeIncusTransport();
        IncusInstanceRuntime absentRuntime = new IncusInstanceRuntime(
            new IncusClient(absentDaemon), Egress.OPEN, IncusWorkloadType.CONTAINER,
            "vm-host-7");
        InstanceSpec absentSpec = spec("prep-img-absent", "missing-alias",
            ImageOrigin.PREPARED, false, true, null);
        // AIDEV-NOTE: catchThrowable, not assertThatThrownBy: AssertJ DROPS the .as()
        // description when no throwable is raised at all, which is exactly the
        // counterfactual case, and an anonymous "Expecting code to raise a throwable"
        // does not name the enforcer that went missing.
        assertThat(catchThrowable(() -> absentRuntime.create(absentSpec)))
            .as("step 3: an absent prepared alias is refused, naming the alias and server")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("missing-alias")
            .hasMessageContaining("vm-host-7")
            .hasMessageContaining("never fetched");
        assertThat(absentDaemon.lastCreateBody)
            .as("step 3: the refusal happened BEFORE any create reached the daemon")
            .isNull();

        // 4. A PREPARED spec with a PINNED fingerprint uses the fingerprint, not the
        //    alias, and skips the alias preflight entirely (no lookup needed for a pin).
        FakeIncusTransport pinnedDaemon = new FakeIncusTransport();
        IncusInstanceRuntime pinnedRuntime = new IncusInstanceRuntime(
            new IncusClient(pinnedDaemon), Egress.OPEN, IncusWorkloadType.CONTAINER, null);
        InstanceSpec pinnedSpec = spec("prep-img-pinned", "windows-2022-rdp",
            ImageOrigin.PREPARED, false, true, "fp-pinned-abc");
        pinnedRuntime.create(pinnedSpec);
        Map<String, Object> pinnedSource = sourceOf(pinnedDaemon);
        assertThat(pinnedSource)
            .as("step 4: a pinned prepared spec uses the fingerprint, never the alias")
            .containsEntry("type", "image")
            .containsEntry("fingerprint", "fp-pinned-abc")
            .doesNotContainKey("alias")
            .doesNotContainKey("protocol")
            .doesNotContainKey("server");

        // 5. secure_boot rides the MANAGED config key on a VM: true lands "true", false
        //    lands "false", and the key is present (managed, re-asserted) either way.
        FakeIncusTransport secureBootTrueDaemon = new FakeIncusTransport();
        IncusInstanceRuntime vmRuntimeTrue = new IncusInstanceRuntime(
            new IncusClient(secureBootTrueDaemon), Egress.OPEN, IncusWorkloadType.VIRTUAL_MACHINE,
            null);
        InstanceSpec secureBootTrueSpec = spec("prep-img-sb-true", "windows-2022-rdp",
            ImageOrigin.PREPARED, true, true, "fp-sb-true");
        vmRuntimeTrue.create(secureBootTrueSpec);
        assertThat(configOf(secureBootTrueDaemon))
            .as("step 5a: secure_boot=true lands security.secureboot=\"true\", managed")
            .containsEntry("security.secureboot", "true");

        FakeIncusTransport secureBootFalseDaemon = new FakeIncusTransport();
        IncusInstanceRuntime vmRuntimeFalse = new IncusInstanceRuntime(
            new IncusClient(secureBootFalseDaemon), Egress.OPEN, IncusWorkloadType.VIRTUAL_MACHINE,
            null);
        InstanceSpec secureBootFalseSpec = spec("prep-img-sb-false", "alpine/3.22/cloud",
            ImageOrigin.CATALOG, false, true, null);
        vmRuntimeFalse.create(secureBootFalseSpec);
        assertThat(configOf(secureBootFalseDaemon))
            .as("step 5b: secure_boot=false lands security.secureboot=\"false\", managed")
            .containsEntry("security.secureboot", "false");

        // 6. An agent-less spec REFUSES an exec-driven install BY NAME and never
        //    attempts an exec against the daemon (no wait-out-the-timeout wall-clock
        //    dependency: the refusal fires before the readiness loop even starts).
        FakeIncusTransport agentlessDaemon = new FakeIncusTransport();
        IncusInstanceRuntime agentlessRuntime = new IncusInstanceRuntime(
            new IncusClient(agentlessDaemon), Egress.OPEN, IncusWorkloadType.CONTAINER, null);
        InstanceSpec agentlessSpec = spec("prep-img-noagent", "windows-2022-rdp",
            ImageOrigin.PREPARED, false, false, "fp-noagent");
        assertThat(catchThrowable(() -> agentlessRuntime.runInstall(
                agentlessSpec, agentlessSpec.image(), "echo hi", Map.of(), 5_000)))
            .as("step 6: an agent-less spec refuses the exec-driven install by name")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("guest agent")
            .hasMessageContaining("prep-img-noagent");
        assertThat(agentlessDaemon.execAttempted)
            .as("step 6: no exec was ever attempted against the daemon").isFalse();

        // 7. DockerInstanceRuntime refuses a PREPARED spec by name, beside the existing
        //    cloud-init refusal, before touching the (unopened) Docker daemon at all.
        WorkloadNetworkPolicy neverRunNft = new WorkloadNetworkPolicy(
            (args, stdin) -> {
                throw new AssertionError("nft must never run for a refused-before-daemon spec");
            },
            () -> true);
        DockerInstanceRuntime dockerRuntime = new DockerInstanceRuntime(
            new DockerClient(), neverRunNft);
        InstanceSpec dockerPreparedSpec = spec("prep-img-docker", "windows-2022-rdp",
            ImageOrigin.PREPARED, false, true, null);
        assertThat(catchThrowable(() -> dockerRuntime.create(dockerPreparedSpec)))
            .as("step 7: the docker driver refuses a prepared-origin spec, named")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("prepared-template image store")
            .hasMessageContaining("prep-img-docker");

        // 8. InstanceImagePolicy compares image_origin too: an approved CATALOG
        //    template must never authorise a same-named PREPARED alias, and the
        //    matching PREPARED template authorises it.
        String sharedAlias = "shared-namespace-alias";
        int catalogTemplateId = template(PREFIX + "catalog-tpl", true,
            settingsOf(sharedAlias, "catalog"));
        int preparedTemplateId = template(PREFIX + "prepared-tpl", true,
            settingsOf(sharedAlias, "prepared"));
        Model instances = Models.get(InstanceModel.class);

        Throwable mismatchedOrigin = catchThrowable(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + "via-catalog-template");
            row.set(InstanceModel.KIND, "hohenheim:incus_vm");
            row.set(InstanceModel.SETTINGS, settingsOf(sharedAlias, "prepared"));
            row.set(InstanceModel.TEMPLATE_ID, catalogTemplateId);
            instances.save(row);
        }));
        assertThat(violationKeys(mismatchedOrigin))
            .as("step 8a: a catalog template never authorises the same alias as prepared")
            .contains("image_requires_capability");
        assertThat(instances.find()
                .where(InstanceModel.NAME.eq(PREFIX + "via-catalog-template")).count())
            .as("step 8a: the refused create persisted NOTHING").isZero();

        int[] createdId = new int[1];
        TenantConduits.as(tenantPrincipal, () -> {
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + "via-prepared-template");
            row.set(InstanceModel.KIND, "hohenheim:incus_vm");
            row.set(InstanceModel.SETTINGS, settingsOf(sharedAlias, "prepared"));
            row.set(InstanceModel.TEMPLATE_ID, preparedTemplateId);
            instances.save(row);
            createdId[0] = row.get(InstanceModel.ID);
        });
        assertThat(instances.findById(createdId[0]))
            .as("step 8b: the matching prepared template authorises the same alias")
            .isNotNull();
    }

    // -- fixtures ---------------------------------------------------------------

    private static InstanceSpec spec(String handle, String image, ImageOrigin origin,
                                     boolean secureBoot, boolean guestAgent,
                                     String fingerprint) {
        return InstanceSpec.builder(handle, image, ResourceLimits.none(),
                new ContainerHardening.Profile("fake", List.of()),
                OwnerLabels.of(InstanceModel.MODEL_ID, handle.hashCode()))
            .imageFingerprint(fingerprint)
            .imageOrigin(origin)
            .secureBoot(secureBoot)
            .guestAgent(guestAgent)
            .build();
    }

    private static Map<String, Object> settingsOf(String image, String origin) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", image);
        settings.put("image_origin", origin);
        return settings;
    }

    private static int template(String name, boolean approved, Map<String, Object> settings) {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row row = templates.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.KIND, "hohenheim:incus_vm");
        row.set(InstanceTemplateModel.SETTINGS, settings);
        if (approved) {
            row.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
            row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, 1L);
        }
        templates.save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static String violationKeys(Throwable thrown) {
        assertThat(thrown)
            .as("the write was refused with Violations (a write that SUCCEEDS here means"
                + " the image-authorisation gate let it through)")
            .isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.fieldName()).append('=')
                .append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sourceOf(FakeIncusTransport daemon) {
        assertThat(daemon.lastCreateBody).as("a create reached the daemon").isNotNull();
        return (Map<String, Object>) daemon.lastCreateBody.get("source");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configOf(FakeIncusTransport daemon) {
        assertThat(daemon.lastCreateBody).as("a create reached the daemon").isNotNull();
        return (Map<String, Object>) daemon.lastCreateBody.get("config");
    }

}
