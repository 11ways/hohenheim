package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.test.application.FakeApps;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The host, project and stack tools through the real MCP endpoint, as an administrator's key: a host added, changed
 * and its risk accepted, its SSH host key confirmed and a changed one adopted by fingerprint, its client key reset, a
 * host paused with its apps moved off and admitted again, install media and leftovers refused where they do not apply,
 * the image cleanup started, a project and an environment made and changed, a stack and a service made and changed,
 * the stack's move refused while it deploys and its volumes purged, describe naming each call, every delete, and a
 * customer's key refused what the panel refuses him.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimHostToolsTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-host-";

    /** The SSH host key a fixture host is pinned to, and one it presents after a change. */
    private static final String PINNED_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String CHANGED_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    /** A record's code as an answer names it. */
    private static final Pattern CODE = Pattern.compile("\\b(SR|PR|EN|ST|SS)\\d+\\b");

    @Test
    void anAdministratorRunsHostsProjectsAndStacksByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        AccessContext operator = TenantConduits.operator();
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        HostFixtures.LocalHostState local = HostFixtures.captureLocal();
        try (FakeApps apps = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            UUID customerId = ApiSupport.user(PREFIX + tag + "@surface.test", "Mcp Host Customer");
            McpHttpSession customer = HohenheimMcpTest.presetSession(server, endpoint, customerId,
                "Mcp Host Customer");

            // 1. A host is added by name; a create without one is refused in the spec's words.
            assertThat(admin.call("host_set", Map.of("ssh_target", "root@192.0.2.7")).text())
                .as("step 1: a create without its name").contains("To create a host, give name.");
            String gammaName = PREFIX + "gamma-" + tag;
            McpHttpSession.Answer added = admin.call("host_set", Map.of("name", gammaName, "runtime", "docker",
                "ssh_target", "root@192.0.2.7", "posture", HostModel.POSTURE_SHARED_CONTAINER));
            assertThat(added.error()).as("step 1: " + added.text()).isFalse();
            Row gammaRow = Objects.requireNonNull(Models.get(HostModel.class).findByName(gammaName));
            int gammaId = gammaRow.get(HostModel.ID);
            cleanUp.push(() -> HardDeletes.byId(Models.get(HostModel.class), gammaId));
            String gamma = HohenheimMcpTest.code(HostModel.MODEL_ID, gammaId, operator);
            assertThat(added.text()).as("step 1: the answer names the code").contains(gamma);

            // 2. A change writes what it sends; a hostname where an address belongs is refused by its argument.
            McpHttpSession.Answer badAddress = admin.call("host_set", Map.of("code", gamma,
                "public_ipv4", "example.org"));
            assertThat(badAddress.error()).as("step 2: " + badAddress.text()).isTrue();
            assertThat(badAddress.text()).as("step 2: the argument is named").contains("public_ipv4: ");
            McpHttpSession.Answer addressed = admin.call("host_set", Map.of("code", gamma,
                "public_ipv4", "203.0.113.5"));
            assertThat(addressed.error()).as("step 2: " + addressed.text()).isFalse();
            assertThat((String) Models.get(HostModel.class).findById(gammaId).get(HostModel.PUBLIC_IPV4))
                .as("step 2: stored").isEqualTo("203.0.113.5");

            // 3. Its shared containers' risk is accepted by typing its name; another word is refused by its argument,
            //    a call whose other setting is refused accepts nothing, and a customer accepts nothing at all.
            McpHttpSession.Answer mistyped = admin.call("host_set", Map.of("code", gamma, "accept_posture", "nope"));
            assertThat(mistyped.error()).as("step 3: " + mistyped.text()).isTrue();
            assertThat(mistyped.text()).as("step 3: the argument is named").contains("accept_posture: ");
            McpHttpSession.Answer halfRefused = admin.call("host_set", Map.of("code", gamma,
                "accept_posture", gammaName, "public_ipv4", "example.org"));
            assertThat(halfRefused.error()).as("step 3: " + halfRefused.text()).isTrue();
            assertThat(customer.call("host_set", Map.of("code", gamma, "accept_posture", gammaName)).error())
                .as("step 3: a customer accepts no host's risk").isTrue();
            assertThat(HostModel.postureAcknowledged(Models.get(HostModel.class).findById(gammaId)))
                .as("step 3: nothing accepted yet").isFalse();
            McpHttpSession.Answer accepted = admin.call("host_set", Map.of("code", gamma,
                "accept_posture", gammaName));
            assertThat(accepted.error()).as("step 3: " + accepted.text()).isFalse();
            assertThat(HostModel.postureAcknowledged(Models.get(HostModel.class).findById(gammaId)))
                .as("step 3: accepted").isTrue();

            // 4. describe names the host's calls; its SSH host key is pinned, unconfirmed.
            assertThat(admin.call("describe", Map.of("code", gamma)).text()).as("step 4: describe names them")
                .contains("host_set code=" + gamma).contains("host_trust code=" + gamma)
                .contains("host_client_key_reset code=" + gamma);
            HostPins.apply(Models.get(HostModel.class).findById(gammaId), HostTrustSlot.SSH, PINNED_KEY,
                "sha256:fixture");

            // 5. A kind the host lacks, and a fingerprint that is neither pinned nor presented, are refused; nothing
            //    was trusted.
            assertThat(admin.call("host_trust", Map.of("code", gamma, "kind", "incus")).error())
                .as("step 5: no Incus daemon").isTrue();
            McpHttpSession.Answer wrong = admin.call("host_trust",
                Map.of("code", gamma, "kind", "ssh", "fingerprint", "sha256:other"));
            assertThat(wrong.error()).as("step 5: " + wrong.text()).isTrue();
            assertThat(wrong.text()).as("step 5: the argument is named").contains("fingerprint: ")
                .contains("nothing was trusted");
            assertThat(verified(gammaId)).as("step 5: still unconfirmed").isFalse();

            // 6. The pinned fingerprint confirms the pin.
            McpHttpSession.Answer confirmed = admin.call("host_trust",
                Map.of("code", gamma, "kind", "ssh", "fingerprint", "sha256:fixture"));
            assertThat(confirmed.error()).as("step 6: " + confirmed.text()).isFalse();
            assertThat(verified(gammaId)).as("step 6: confirmed").isTrue();

            // 7. The host presents another key: its fingerprint adopts it, unconfirmed again.
            Row presented = Models.get(HostModel.class).findById(gammaId);
            presented.set(HostModel.HOST_KEY_OFFERED, CHANGED_KEY);
            Models.get(HostModel.class).save(presented);
            String changed = HostKeys.fingerprintOf(CHANGED_KEY);
            McpHttpSession.Answer adopted = admin.call("host_trust",
                Map.of("code", gamma, "kind", "ssh", "fingerprint", changed));
            assertThat(adopted.error()).as("step 7: " + adopted.text()).isFalse();
            Row repinned = Models.get(HostModel.class).findById(gammaId);
            assertThat((String) repinned.get(HostModel.HOST_KEY_FINGERPRINT)).as("step 7: the new pin")
                .isEqualTo(changed);
            assertThat(verified(gammaId)).as("step 7: unconfirmed").isFalse();

            // 8. A new SSH client key; the Incus kind is refused on a Docker host.
            String before = repinned.get(HostModel.IDENTITY_PUBLIC_KEY);
            McpHttpSession.Answer reset = admin.call("host_client_key_reset", Map.of("code", gamma, "kind", "ssh"));
            assertThat(reset.error()).as("step 8: " + reset.text()).isFalse();
            assertThat((String) Models.get(HostModel.class).findById(gammaId).get(HostModel.IDENTITY_PUBLIC_KEY))
                .as("step 8: a new public key").isNotBlank().isNotEqualTo(before);
            assertThat(admin.call("host_client_key_reset", Map.of("code", gamma, "kind", "incus")).error())
                .as("step 8: no Incus certificate").isTrue();

            // 9. Leftovers and install media are an Incus host's: a Docker host is refused. On an Incus host a
            //    private URL is refused by its argument before any daemon is asked, and a removal takes no url.
            assertThat(admin.call("host_leftover_cleanup", Map.of("code", gamma)).error())
                .as("step 9: leftovers of a Docker host").isTrue();
            assertThat(admin.call("host_media_set", Map.of("host", gamma, "name", "debian",
                "url", "https://example.org/debian.iso")).error()).as("step 9: media on a Docker host").isTrue();
            int incusId = HostFixtures.admittedIncusHost(PREFIX + "incus-" + tag);
            cleanUp.push(() -> HardDeletes.byId(Models.get(HostModel.class), incusId));
            String incus = HohenheimMcpTest.code(HostModel.MODEL_ID, incusId, operator);
            McpHttpSession.Answer privateUrl = admin.call("host_media_set", Map.of("host", incus, "name", "debian",
                "url", "http://10.1.2.3/debian.iso"));
            assertThat(privateUrl.error()).as("step 9: " + privateUrl.text()).isTrue();
            assertThat(privateUrl.text()).as("step 9: the argument is named").contains("url: ");
            McpHttpSession.Answer removeWithUrl = admin.call("host_media_set", Map.of("host", incus, "name", "debian",
                "remove", true, "url", "https://example.org/debian.iso"));
            assertThat(removeWithUrl.error()).as("step 9: " + removeWithUrl.text()).isTrue();
            assertThat(removeWithUrl.text()).as("step 9: the argument is named").contains("url");

            // 10. An admitted host: moving apps off belongs to a pause, so with state=admitted it is refused by name;
            //     paused with its apps moved off in one call, then admitted again.
            int betaId = HostFixtures.admittedDockerHost(PREFIX + "beta-" + tag, 16L * 1024);
            cleanUp.push(() -> HardDeletes.byId(Models.get(HostModel.class), betaId));
            String beta = HohenheimMcpTest.code(HostModel.MODEL_ID, betaId, operator);
            McpHttpSession.Answer mixed = admin.call("host_admission_set",
                Map.of("code", beta, "state", "admitted", "move_apps_off", true));
            assertThat(mixed.error()).as("step 10: " + mixed.text()).isTrue();
            assertThat(mixed.text()).as("step 10: the argument is named").contains("move_apps_off");
            McpHttpSession.Answer drained = admin.call("host_admission_set",
                Map.of("code", beta, "state", "paused", "move_apps_off", true));
            assertThat(drained.error()).as("step 10: " + drained.text()).isFalse();
            assertThat(drained.text()).as("step 10: what moved").contains("drained");
            assertThat(admission(betaId)).as("step 10: paused").isEqualTo(HostModel.ADMISSION_CORDONED);
            assertThat(admin.call("host_admission_set", Map.of("code", beta, "state", "paused")).error())
                .as("step 10: already paused").isTrue();
            McpHttpSession.Answer resumed = admin.call("host_admission_set",
                Map.of("code", beta, "state", "admitted"));
            assertThat(resumed.error()).as("step 10: " + resumed.text()).isFalse();
            assertThat(admission(betaId)).as("step 10: admitted").isEqualTo(HostModel.ADMISSION_ADMITTED);

            // 11. The image cleanup starts at once.
            McpHttpSession.Answer images = admin.call("image_cleanup", Map.of());
            assertThat(images.error()).as("step 11: " + images.text()).isFalse();
            assertThat(images.text()).as("step 11: started").contains("started");

            // 12. A project and an environment of it, each made and changed by its code.
            String project = made(admin, "project_set", Map.of("name", PREFIX + "project-" + tag), "PR", "step 12");
            int projectId = idOf(ProjectModel.MODEL_ID, project);
            cleanUp.push(() -> HardDeletes.byId(Models.get(ProjectModel.class), projectId));
            assertThat(admin.call("project_set", Map.of("code", project, "description", "for " + tag)).error())
                .as("step 12: described").isFalse();
            assertThat((String) Models.get(ProjectModel.class).findById(projectId).get(ProjectModel.DESCRIPTION))
                .as("step 12: stored").isEqualTo("for " + tag);
            assertThat(admin.call("environment_set", Map.of("name", "staging")).text())
                .as("step 12: an environment needs its project")
                .contains("To create an environment, give project and name.");
            String environment = made(admin, "environment_set", Map.of("project", project, "name", "staging"), "EN",
                "step 12");
            int environmentId = idOf(EnvironmentModel.MODEL_ID, environment);
            cleanUp.push(() -> HardDeletes.byId(Models.get(EnvironmentModel.class), environmentId));
            assertThat(admin.call("environment_set", Map.of("code", environment, "name", "production")).error())
                .as("step 12: renamed").isFalse();
            assertThat((String) Models.get(EnvironmentModel.class).findById(environmentId)
                .get(EnvironmentModel.NAME)).as("step 12: stored").isEqualTo("production");
            assertThat(admin.call("describe", Map.of("code", environment)).text())
                .as("step 12: describe names the change").contains("environment_set code=" + environment);

            // 13. A stack on this machine (no host given) and a service of it, each made and changed by its code.
            String stack = made(admin, "stack_set", Map.of("name", PREFIX + "stack-" + tag, "enabled", false), "ST",
                "step 13");
            int stackId = idOf(StackModel.MODEL_ID, stack);
            cleanUp.push(() -> HardDeletes.byId(Models.get(StackModel.class), stackId));
            assertThat((Integer) Models.get(StackModel.class).findById(stackId).get(StackModel.HOST_ID))
                .as("step 13: this machine").isEqualTo(HostModel.localHostId());
            assertThat(admin.call("stack_service_set", Map.of("stack", stack, "name", "web")).text())
                .as("step 13: a service needs its image").contains("To create a stack service, give");
            String service = made(admin, "stack_service_set", Map.of("stack", stack, "name", "web",
                "image", "nginx:1.27.3", "command", List.of("nginx", "-g", "daemon off;")), "SS", "step 13");
            int serviceId = idOf(StackServiceModel.MODEL_ID, service);
            cleanUp.push(() -> HardDeletes.byId(Models.get(StackServiceModel.class), serviceId));
            assertThat(admin.call("stack_service_set", Map.of("code", service, "image", "nginx:1.27.4")).error())
                .as("step 13: a new image").isFalse();
            assertThat((String) Models.get(StackServiceModel.class).findById(serviceId)
                .get(StackServiceModel.IMAGE)).as("step 13: stored").isEqualTo("nginx:1.27.4");
            assertThat(admin.call("stack_set", Map.of("code", stack, "description", "web front")).error())
                .as("step 13: described").isFalse();

            // 14. While the stack deploys, a new host is refused by its argument: it would orphan the old host's
            //     containers.
            Row deploying = Models.get(StackModel.class).findById(stackId);
            deploying.set(StackModel.STATUS, StackModel.STATUS_DEPLOYING);
            Models.get(StackModel.class).save(deploying);
            McpHttpSession.Answer moved = admin.call("stack_set", Map.of("code", stack, "host", beta));
            assertThat(moved.error()).as("step 14: " + moved.text()).isTrue();
            assertThat(moved.text()).as("step 14: the argument is named").contains("host: ")
                .contains("another host");
            assertThat((Integer) Models.get(StackModel.class).findById(stackId).get(StackModel.HOST_ID))
                .as("step 14: it stayed").isEqualTo(HostModel.localHostId());
            Row settled = Models.get(StackModel.class).findById(stackId);
            settled.set(StackModel.STATUS, StackModel.STATUS_INACTIVE);
            Models.get(StackModel.class).save(settled);

            // 15. describe names the stack's and the service's calls; the stack's own volumes are purged.
            assertThat(admin.call("describe", Map.of("code", stack)).text()).as("step 15: the stack's calls")
                .contains("stack_set code=" + stack).contains("stack_volumes_purge code=" + stack);
            assertThat(admin.call("describe", Map.of("code", service)).text()).as("step 15: the service's calls")
                .contains("stack_service_set code=" + service);
            McpHttpSession.Answer purged = admin.call("stack_volumes_purge", Map.of("code", stack));
            assertThat(purged.error()).as("step 15: " + purged.text()).isFalse();
            assertThat(purged.text()).as("step 15: queued").contains("queued");

            // 16. A customer's key is refused what the panel refuses him: a host's admission, a new host or project.
            assertThat(customer.call("host_admission_set", Map.of("code", beta, "state", "paused")).error())
                .as("step 16: a customer pauses no host").isTrue();
            assertThat(admission(betaId)).as("step 16: nothing changed").isEqualTo(HostModel.ADMISSION_ADMITTED);
            assertThat(customer.call("host_set", Map.of("name", PREFIX + "theirs-" + tag,
                "ssh_target", "root@192.0.2.8")).error()).as("step 16: a customer adds no host").isTrue();
            assertThat(Models.get(HostModel.class).findByName(PREFIX + "theirs-" + tag)).as("step 16: none")
                .isNull();
            assertThat(customer.call("project_set", Map.of("name", PREFIX + "theirs-" + tag)).error())
                .as("step 16: a customer creates no project").isTrue();

            // 17. Every delete, by code: the service, the stack, the environment, the project, the host.
            for (String code : List.of(service, stack, environment, project, gamma)) {
                McpHttpSession.Answer deleted = admin.call("delete", Map.of("code", code));
                assertThat(deleted.error()).as("step 17: " + code + ": " + deleted.text()).isFalse();
            }
            assertThat(Models.get(StackModel.class).findById(stackId)).as("step 17: the stack is gone").isNull();
            assertThat(Models.get(ProjectModel.class).findById(projectId)).as("step 17: the project is gone")
                .isNull();
            assertThat(Models.get(HostModel.class).findById(gammaId)).as("step 17: the host is gone").isNull();
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
            local.restore();
        }
    }

    /** @return the code a create answered, of the kind expected */
    private static String made(McpHttpSession session, String tool, Map<String, Object> arguments, String prefix,
                               String step) {
        McpHttpSession.Answer made = session.call(tool, arguments);
        assertThat(made.error()).as(step + ": " + tool + ": " + made.text()).isFalse();
        Matcher code = CODE.matcher(made.text());
        while (code.find()) {
            if (code.group(1).equals(prefix)) return code.group();
        }
        throw new AssertionError(step + ": " + tool + " answered no " + prefix + " code: " + made.text());
    }

    /** @return the key of the record a code names */
    private static int idOf(Identifier model, String code) {
        return Integer.parseInt(Objects.requireNonNull(
            RecordCodes.keyOfCode(model, code), code));
    }

    /** @return the host's stored admission */
    private static String admission(int hostId) {
        return Models.get(HostModel.class).findById(hostId).get(HostModel.ADMISSION);
    }

    /** @return whether the host's SSH host key pin is confirmed */
    private static boolean verified(int hostId) {
        return Boolean.TRUE.equals(Models.get(HostModel.class).findById(hostId).get(HostModel.HOST_KEY_VERIFIED));
    }
}
