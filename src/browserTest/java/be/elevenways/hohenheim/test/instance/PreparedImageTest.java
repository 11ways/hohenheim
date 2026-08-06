package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.incus.IncusTransport;
import be.elevenways.hohenheim.server.incus.IncusWebSocket;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.dry.Dry;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
        return new InstanceSpec(handle, image, null, Map.of(), Map.of(), null,
            ResourceLimits.none(), new ContainerHardening.Profile("fake", List.of()),
            OwnerLabels.of(InstanceModel.MODEL_ID, handle.hashCode()), null, fingerprint,
            origin, secureBoot, guestAgent);
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

    /**
     * An in-memory ECHO of the handful of Incus endpoints {@link IncusInstanceRuntime}
     * touches: whatever is POSTed/PUT is stored verbatim and returned on the next GET,
     * so the isolation ACL and NIC read-back verifications the real driver performs
     * pass on their own merits (the fake never hardcodes an "expected" shape) rather
     * than needing separately-maintained fixture data. An unhandled path throws loudly
     * instead of silently answering success -- a gap here must fail the test, not hide
     * behind a default.
     */
    private static final class FakeIncusTransport implements IncusTransport {

        final Map<String, Object> aclStore = new LinkedHashMap<>();
        final Map<String, String> imageAliases = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> instances = new LinkedHashMap<>();
        Map<String, Object> lastCreateBody;
        boolean execAttempted;
        private int operationCounter;

        @Override
        public Http11.Raw exchange(String method, String pathAndQuery, String jsonBody,
                                   long timeoutMs) throws IOException {
            String path = pathAndQuery.contains("?")
                ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;

            if ("GET".equals(method) && path.equals("/1.0/profiles/default")) {
                Map<String, Object> eth0 = new LinkedHashMap<>();
                eth0.put("type", "nic");
                eth0.put("network", "incusbr0");
                Map<String, Object> root = new LinkedHashMap<>();
                root.put("type", "disk");
                root.put("pool", "default-pool");
                root.put("path", "/");
                Map<String, Object> devices = new LinkedHashMap<>();
                devices.put("eth0", eth0);
                devices.put("root", root);
                return sync(Map.of("devices", devices));
            }
            if ("GET".equals(method)
                    && path.equals("/1.0/network-acls/" + IncusNetworkPolicy.aclName())) {
                Object stored = this.aclStore.get(IncusNetworkPolicy.aclName());
                return stored == null ? notFound("Network ACL not found") : sync(stored);
            }
            if ("POST".equals(method) && path.equals("/1.0/network-acls")) {
                Map<String, Object> body = parse(jsonBody);
                this.aclStore.put(String.valueOf(body.get("name")), body);
                return sync(body);
            }
            if ("PUT".equals(method)
                    && path.equals("/1.0/network-acls/" + IncusNetworkPolicy.aclName())) {
                Map<String, Object> body = parse(jsonBody);
                this.aclStore.put(IncusNetworkPolicy.aclName(), body);
                return sync(body);
            }
            if ("GET".equals(method) && path.startsWith("/1.0/images/aliases/")) {
                String alias = path.substring("/1.0/images/aliases/".length());
                String fingerprint = this.imageAliases.get(alias);
                return fingerprint == null ? notFound("Image alias not found")
                    : sync(Map.of("target", fingerprint));
            }
            if ("POST".equals(method) && path.equals("/1.0/instances")) {
                Map<String, Object> body = parse(jsonBody);
                this.lastCreateBody = body;
                Map<String, Object> stored = new LinkedHashMap<>(body);
                stored.put("architecture", "x86_64");
                stored.put("ephemeral", false);
                stored.put("description", "");
                this.instances.put(String.valueOf(body.get("name")), stored);
                return async();
            }
            if ("POST".equals(method) && path.endsWith("/exec")) {
                this.execAttempted = true;
                return async();
            }
            if ("PUT".equals(method) && path.endsWith("/state")) {
                return async();
            }
            if ("PUT".equals(method) && path.startsWith("/1.0/instances/")) {
                Map<String, Object> body = parse(jsonBody);
                String name = path.substring("/1.0/instances/".length());
                this.instances.put(name, new LinkedHashMap<>(body));
                return async();
            }
            if ("GET".equals(method) && path.startsWith("/1.0/instances/")
                    && !path.contains("/state") && !path.contains("/exec")) {
                String name = path.substring("/1.0/instances/".length());
                Map<String, Object> instance = this.instances.get(name);
                return instance == null ? notFound("Instance not found") : sync(instance);
            }
            if ("GET".equals(method) && path.matches("/1\\.0/operations/.+/wait")) {
                Map<String, Object> operationResult = new LinkedHashMap<>();
                operationResult.put("status_code", 200);
                operationResult.put("status", "Success");
                operationResult.put("description", "fake operation");
                operationResult.put("err", "");
                return sync(operationResult);
            }
            throw new IOException("FakeIncusTransport: unhandled " + method + " " + path);
        }

        private Http11.Raw async() {
            String opPath = "/1.0/operations/op-" + (++this.operationCounter);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "async");
            envelope.put("operation", opPath);
            envelope.put("metadata", Map.of());
            return raw(202, Json.stringify(envelope));
        }

        private static Http11.Raw sync(Object metadata) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "sync");
            envelope.put("metadata", metadata);
            return raw(200, Json.stringify(envelope));
        }

        private static Http11.Raw notFound(String message) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "error");
            envelope.put("error_code", 404);
            envelope.put("error", message);
            return raw(404, Json.stringify(envelope));
        }

        private static Http11.Raw raw(int status, String body) {
            return new Http11.Raw(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> parse(String json) throws IOException {
            try {
                return (Map<String, Object>) new Dry().parse(json);
            } catch (Exception malformed) {
                throw new IOException("FakeIncusTransport: bad JSON body: " + json, malformed);
            }
        }

        @Override
        public Http11.Raw exchangeUpload(String method, String pathAndQuery, Path bodyFile,
                                         String contentType, Map<String, String> extraHeaders,
                                         long timeoutMs) {
            throw new UnsupportedOperationException("not exercised by this journey");
        }

        @Override
        public Http11.Raw exchangeDownload(String method, String pathAndQuery,
                                           Path destination, long maxBytes, long timeoutMs) {
            throw new UnsupportedOperationException("not exercised by this journey");
        }

        @Override
        public IncusWebSocket openWebSocket(String pathAndQuery, long connectTimeoutMs) {
            throw new UnsupportedOperationException("not exercised by this journey");
        }

        @Override
        public String describe() {
            return "fake-incus-transport";
        }
    }
}
