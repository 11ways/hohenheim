package be.elevenways.hohenheim.test.game;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.hohenheim.server.game.GameTemplateSeeder;
import be.elevenways.hohenheim.server.game.VelocityConfigs;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.api.ResponseCarrier;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.routing.BodyDefinition;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.orm.seed.Seeds;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The game surface against a REAL daemon, one journey: Velocity from the starter
 * template (readiness detected from its console, graceful stop via its console
 * command), a stand-in backend rendered from the Minecraft template's REAL config
 * files, the game-domain mapping materializing forced-hosts config INTO the running
 * proxy and SRV rows into the DNS tier, the link network as the only reachability
 * lane, and nothing dangling after destroy.
 *
 * AIDEV-NOTE: the backend runs nginx:alpine, NOT a real Minecraft server -- the
 * itzg/minecraft-server image is hundreds of MB and boots for minutes. The template
 * FILES it renders are the seeded Minecraft template's real ones, so the config path
 * is exercised verbatim; only the workload binary is a stand-in, and the report says
 * so.
 */
class GameDomainLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String VELOCITY_IMAGE = "dockcenter/velocity:latest";
    private static final String BACKEND_IMAGE = "nginx:alpine";

    private static final String ZONE = "gamelive.test";
    private static final String HOST_ONE = "mc.gamelive.test";
    private static final String HOST_TWO = "lobby.gamelive.test";

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;
    private static Path workRoot;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-game-live-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
        // The keyring is installed BEFORE the boot: this class's database is now THE
        // default, so anything the boot encrypts into it must be encrypted with the
        // keyring the class will later read it back with.
        workRoot = Files.createTempDirectory("hohenheim-game-live-test");
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            workRoot.resolve("test-keyring.keys")));
        HohenheimAccess.declareGrantableModels();
        HohenheimTestRuntime.ensureBooted();
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
        FieldEncryption.installKeyring(null);
    }

    @Test
    void velocityFrontsABackendThroughAMappingAndNothingDangles() throws Exception {
        Assumptions.assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        Assumptions.assumeTrue(imagePresent(docker, VELOCITY_IMAGE),
            VELOCITY_IMAGE + " not present locally");
        Assumptions.assumeTrue(imagePresent(docker, BACKEND_IMAGE),
            BACKEND_IMAGE + " not present locally");
        Assumptions.assumeTrue(imagePresent(docker, "alpine:latest"),
            "alpine:latest not present locally");
        Assumptions.assumeTrue(netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            Seeds.run(datasource, new GameTemplateSeeder());

            // Zone + site + two exact domain rows.
            zone();
            int siteId = site();
            int domainOne = domain(siteId, HOST_ONE);
            int domainTwo = domain(siteId, HOST_TWO);

            // 1. The proxy comes from the SEEDED Velocity template (real image), the
            //    backend from a stand-in template carrying the seeded Minecraft
            //    template's REAL config files over nginx:alpine.
            // Seeded templates land UNAPPROVED by design; the operator act that makes
            // them selectable is the approval stamp.
            Row velocityTemplate = approve(templateNamed("Velocity proxy"));
            InstanceTemplates templates = new InstanceTemplates();
            int proxyId = templates.createFromTemplate(velocityTemplate,
                "gamelive-proxy", null, Map.of(), null);
            int backendId = templates.createFromTemplate(approve(standInBackendTemplate()),
                "gamelive-backend", null, Map.of(), null);
            int strangerId = strangerInstance();
            String proxyHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, proxyId);
            String backendHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, backendId);
            String strangerHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, strangerId);
            String proxySecret = new InstanceVariables().valuesFor(proxyId)
                .get(GameDomains.PROXY_SECRET_KEY);
            assertThat(proxySecret)
                .as("step 1: the Velocity template MINTED a forwarding secret")
                .isNotBlank();

            int tenantId = tenant();
            RecordGrants.grant("user", tenantId, SiteModel.MODEL_ID, siteId,
                HohenheimAccess.MANAGE, true);
            RecordGrants.grant("user", tenantId, InstanceModel.MODEL_ID, proxyId,
                HohenheimAccess.MANAGE, true);
            RecordGrants.grant("user", tenantId, InstanceModel.MODEL_ID, backendId,
                HohenheimAccess.MANAGE, true);
            AccessContext tenant = contextFor(new UserPrincipal(tenantId, "Game Tenant"));

            InstanceService service = new InstanceService();
            String linkHandle = null;
            try {
                // 2. Deploy the proxy: REAL Velocity boots and its readiness line
                //    ("Done (") flips starting -> Running through the console matcher.
                service.deploy(proxyId);
                boolean proxyRunning = await(60_000, () -> InstanceModel.STATUS_RUNNING.equals(
                    Models.get(InstanceModel.class).findById(proxyId)
                        .get(InstanceModel.STATUS)));
                if (!proxyRunning) {
                    System.out.println("PROXY TAIL: " + tail(service, proxyId));
                }
                assertThat(proxyRunning)
                    .as("step 2: readiness detected from Velocity's console output")
                    .isTrue();

                // 3. Create the mapping (authority held over BOTH records): the
                //    forced-hosts config lands INSIDE the running proxy container.
                Row mapping = GameDomains.applyAuthorized(tenant,
                    mappingRow(domainOne, backendId, proxyId, 80));
                int mappingId = mapping.get(GameDomainModel.ID);
                String rendered = exec(docker, proxyHandle, "cat", "/data/velocity.toml");
                assertThat(rendered)
                    .as("step 3: the RENDERED config inside the container maps the host"
                        + " onto the backend's link-network address")
                    .contains("\"" + HOST_ONE + "\" = [\"backend-" + backendId + "\"]")
                    .contains("backend-" + backendId + " = \"" + backendHandle + ":80\"");
                assertThat(exec(docker, proxyHandle, "cat", "/data/forwarding.secret"))
                    .as("step 3: the forwarding secret arrives RENDERED in the proxy config")
                    .isEqualTo(proxySecret);

                // 4. The generated SRV row rides the proxy's OBSERVED published port.
                int publishedPort = docker.publishedPort(proxyHandle, 25577);
                Row srv = srvRow();
                assertThat(srv).as("step 4: the generated SRV row exists").isNotNull();
                assertThat((String) srv.get(DnsRecordModel.NAME))
                    .isEqualTo("_minecraft._tcp.mc");
                assertThat((Integer) srv.get(DnsRecordModel.PORT))
                    .as("step 4: SRV rides the observed published port")
                    .isEqualTo(publishedPort);

                // 5. Deploy the backend: link networks attach BEFORE start, the
                //    Minecraft template's real config file renders the SAME secret.
                service.deploy(backendId);
                assertThat(await(30_000, () -> InstanceModel.STATUS_RUNNING.equals(
                    Models.get(InstanceModel.class).findById(backendId)
                        .get(InstanceModel.STATUS))))
                    .as("step 5: the backend deployed and runs")
                    .isTrue();
                String backendConf = exec(docker, backendHandle, "cat",
                    "/data/config/paper-global.yml");
                assertThat(backendConf)
                    .as("step 5: the forwarding secret arrives RENDERED in the backend"
                        + " config -- the SAME value in BOTH configs")
                    .contains("secret: \"" + proxySecret + "\"");

                // 6. Reachability is the LINK NETWORK: the proxy resolves and reaches
                //    the backend by container name; the backend publishes NO host port
                //    (Velocity fronts everything); a third, unmapped instance cannot
                //    reach the backend at all.
                linkHandle = ControllerScope.handle(ControllerScope.KIND_GAMELINK, proxyId) + "-" + backendId;
                DockerClient.ExecResult reach = docker.exec(proxyHandle, List.of(
                    "wget", "-T", "3", "-qO-", "http://" + backendHandle + ":80/"));
                assertThat(reach.exitCode())
                    .as("step 6: the proxy reaches its backend over the link network")
                    .isZero();
                assertThat(reach.stdout())
                    .as("step 6: the backend actually answered")
                    .contains("nginx");
                Map<String, Object> backendInspect = docker.inspectContainer(backendHandle);
                assertThat(String.valueOf(networkPorts(backendInspect)))
                    .as("step 6: the backend publishes NO host port -- no direct lane"
                        + " from outside exists")
                    .doesNotContain("HostPort");
                service.deploy(strangerId);
                String backendLinkIp = memberIp(docker, linkHandle + "-net", backendHandle);
                DockerClient.ExecResult crossTenant = docker.exec(strangerHandle, List.of(
                    "wget", "-T", "3", "-qO-", "http://" + backendLinkIp + ":80/"));
                assertThat(crossTenant.exitCode())
                    .as("step 6: an unmapped instance CANNOT reach the backend over"
                        + " the link subnet")
                    .isNotZero();

                // 7. Materialization ON CHANGE: repoint the mapping at the second
                //    domain -- the file INSIDE the container and the DNS row both move.
                Row change = Models.get(GameDomainModel.class).findById(mappingId);
                change.set(GameDomainModel.SITE_DOMAIN_ID, domainTwo);
                GameDomains.applyAuthorized(tenant, change);
                String changed = exec(docker, proxyHandle, "cat", "/data/velocity.toml");
                assertThat(changed)
                    .as("step 7: the rendered file INSIDE the running container changed"
                        + " with the mapping")
                    .contains("\"" + HOST_TWO + "\"")
                    .doesNotContain("\"" + HOST_ONE + "\"");
                assertThat((String) srvRow().get(DnsRecordModel.NAME))
                    .as("step 7: the generated DNS row changed with the mapping")
                    .isEqualTo("_minecraft._tcp.lobby");

                // 8. Graceful stop VIA THE CONSOLE: send Velocity's own stop command
                //    over the attached console; the observed stop suppresses crash
                //    restart and the record settles STOPPED.
                InstanceConsoles.sendCommand(proxyId, "shutdown");
                assertThat(await(30_000, () -> InstanceModel.STATUS_STOPPED.equals(
                    Models.get(InstanceModel.class).findById(proxyId)
                        .get(InstanceModel.STATUS))))
                    .as("step 8: the console stop command settled the proxy STOPPED")
                    .isTrue();
                sleep(3_000);
                assertThat((String) Models.get(InstanceModel.class).findById(proxyId)
                    .get(InstanceModel.STATUS))
                    .as("step 8: the OBSERVED stop suppressed the crash-restart policy")
                    .isEqualTo(InstanceModel.STATUS_STOPPED);

                // 9. NOTHING DANGLES: destroying the backend takes the mapping, the
                //    generated SRV row, the generated config row and the link network
                //    with it.
                service.destroy(backendId);
                assertThat(Models.get(GameDomainModel.class).find().count())
                    .as("step 9: the mapping died with its backend")
                    .isZero();
                assertThat(srvRow())
                    .as("step 9: the generated SRV row died with the mapping")
                    .isNull();
                assertThat(generatedFileRow(proxyId))
                    .as("step 9: the generated config row died with the last mapping")
                    .isNull();
                assertThat(docker.findNetworkByName(linkHandle + "-net"))
                    .as("step 9: the link network died with the mapping")
                    .isNull();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                quietDestroy(service, backendId);
                quietDestroy(service, strangerId);
                quietDestroy(service, proxyId);
                quietly(() -> docker.removeVolume(proxyHandle + "-vol-data", true));
                quietly(() -> docker.removeVolume(backendHandle + "-vol-data", true));
                if (linkHandle != null) {
                    String network = linkHandle + "-net";
                    quietly(() -> docker.removeNetwork(network));
                }
            }
        });
    }

    // -- fixtures -------------------------------------------------------------

    private static void zone() {
        var zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, ZONE);
        zone.set(DnsZoneModel.ENABLED, true);
        zone.set(DnsZoneModel.DEFAULT_TTL, 3600);
        zone.set(DnsZoneModel.NEGATIVE_TTL, 300);
        zone.set(DnsZoneModel.SOA_REFRESH, 7200);
        zone.set(DnsZoneModel.SOA_RETRY, 3600);
        zone.set(DnsZoneModel.SOA_EXPIRE, 1209600);
        zones.save(zone);
    }

    private static int site() {
        var sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "Game live site");
        site.set(SiteModel.SLUG, "game-live-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        return site.get(SiteModel.ID);
    }

    private static int domain(int siteId, String hostname) {
        var domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domains.save(domain);
        return domain.get(SiteDomainModel.ID);
    }

    private static int tenant() {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "game-live@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Game Live Tenant");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static Row approve(Row template) {
        template.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
        Models.get(InstanceTemplateModel.class).save(template);
        return template;
    }

    private static Row templateNamed(String name) {
        Row template = Models.get(InstanceTemplateModel.class).find()
            .where(InstanceTemplateModel.NAME.eq(name)).first();
        assertThat(template).as("seeded template '" + name + "' exists").isNotNull();
        return template;
    }

    /**
     * A stand-in backend template: the SEEDED Minecraft template's variable + config
     * file, verbatim, over a small image that actually listens on a TCP port.
     */
    private static Row standInBackendTemplate() {
        Row minecraft = templateNamed("Minecraft server (Paper)");
        int minecraftId = minecraft.get(InstanceTemplateModel.ID);

        var templates = Models.get(InstanceTemplateModel.class);
        Row standIn = templates.createEmptyRow();
        standIn.set(InstanceTemplateModel.NAME, "Minecraft stand-in (live test)");
        standIn.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "nginx");
        settings.put("tag", "alpine");
        standIn.set(InstanceTemplateModel.SETTINGS, settings);
        templates.save(standIn);
        int standInId = standIn.get(InstanceTemplateModel.ID);

        for (Row variable : Models.get(InstanceTemplateVariableModel.class)
                .findByTemplateId(minecraftId)) {
            Row copy = Models.get(InstanceTemplateVariableModel.class).createEmptyRow();
            copy.set(InstanceTemplateVariableModel.TEMPLATE_ID, standInId);
            copy.set(InstanceTemplateVariableModel.KEY,
                variable.get(InstanceTemplateVariableModel.KEY));
            copy.set(InstanceTemplateVariableModel.TYPE,
                variable.get(InstanceTemplateVariableModel.TYPE));
            copy.set(InstanceTemplateVariableModel.SETTINGS,
                variable.get(InstanceTemplateVariableModel.SETTINGS));
            copy.set(InstanceTemplateVariableModel.REQUIRED,
                variable.get(InstanceTemplateVariableModel.REQUIRED));
            Models.get(InstanceTemplateVariableModel.class).save(copy);
        }
        for (Row file : Models.get(InstanceTemplateFileModel.class)
                .findByTemplateId(minecraftId)) {
            Row copy = Models.get(InstanceTemplateFileModel.class).createEmptyRow();
            copy.set(InstanceTemplateFileModel.TEMPLATE_ID, standInId);
            copy.set(InstanceTemplateFileModel.CONTAINER_PATH,
                file.get(InstanceTemplateFileModel.CONTAINER_PATH));
            copy.set(InstanceTemplateFileModel.CONTENT,
                file.get(InstanceTemplateFileModel.CONTENT));
            copy.set(InstanceTemplateFileModel.MODE,
                file.get(InstanceTemplateFileModel.MODE));
            Models.get(InstanceTemplateFileModel.class).save(copy);
        }
        return templates.findById(standInId);
    }

    private static int strangerInstance() {
        var model = Models.get(InstanceModel.class);
        Row row = model.createEmptyRow();
        row.set(InstanceModel.NAME, "gamelive-stranger");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put("tag", "latest");
        settings.put("command", "sleep 999999");
        row.set(InstanceModel.SETTINGS, settings);
        model.save(row);
        return row.get(InstanceModel.ID);
    }

    private static Row mappingRow(int domain, int backend, int proxy, int port) {
        Row row = Models.get(GameDomainModel.class).createEmptyRow();
        row.set(GameDomainModel.SITE_DOMAIN_ID, domain);
        row.set(GameDomainModel.BACKEND_INSTANCE_ID, backend);
        row.set(GameDomainModel.PROXY_INSTANCE_ID, proxy);
        row.set(GameDomainModel.BACKEND_PORT, port);
        row.set(GameDomainModel.ENABLED, true);
        return row;
    }

    // -- readers --------------------------------------------------------------

    private static Row srvRow() {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.GENERATED_BY.eq(GameDomains.SOURCE))
            .and(DnsRecordModel.GENERATED_FOR_MODEL.eq(GameDomainModel.MODEL_ID.toString()))
            .first();
    }

    private static Row generatedFileRow(int proxyId) {
        return Models.get(InstanceFileModel.class).find()
            .where(InstanceFileModel.INSTANCE_ID.eq(proxyId))
            .and(InstanceFileModel.CONTAINER_PATH.eq(VelocityConfigs.CONFIG_PATH))
            .first();
    }

    private static Object networkPorts(Map<String, Object> inspect) {
        return inspect.get("NetworkSettings") instanceof Map<?, ?> settings
            ? settings.get("Ports") : null;
    }

    /** The plain IPv4 of a network member found by container name. */
    private static String memberIp(DockerClient docker, String network, String containerName)
            throws IOException {
        Map<String, Object> inspect = docker.inspectNetwork(network);
        if (inspect.get("Containers") instanceof Map<?, ?> members) {
            for (Object value : members.values()) {
                if (value instanceof Map<?, ?> member
                        && containerName.equals(member.get("Name"))
                        && member.get("IPv4Address") instanceof String address) {
                    return address.contains("/")
                        ? address.substring(0, address.indexOf('/')) : address;
                }
            }
        }
        throw new IOException(containerName + " is not a member of " + network);
    }

    // -- plumbing -------------------------------------------------------------

    private static String tail(InstanceService service, int instanceId) {
        try {
            InstanceService.Resolved resolved = service.resolve(instanceId);
            if (resolved.runtime() instanceof
                    be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime docker) {
                return docker.consoleTail(resolved.spec().handle(), 40);
            }
        } catch (Exception e) {
            return "tail unavailable: " + e;
        }
        return "";
    }

    private static String exec(DockerClient docker, String container, String... command) {
        try {
            return docker.exec(container, List.of(command)).output().trim();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean imagePresent(DockerClient docker, String tag) {
        try {
            for (Object image : docker.listImages()) {
                Object repoTags = ((Map<?, ?>) image).get("RepoTags");
                if (repoTags instanceof List<?> tags && tags.contains(tag)) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private static boolean await(long timeoutMs, java.util.function.Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return true;
            }
            sleep(150);
        }
        return Boolean.TRUE.equals(condition.get());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void quietDestroy(InstanceService service, int instanceId) {
        try {
            service.destroy(instanceId);
        } catch (RuntimeException ignored) {
            // best-effort cleanup; the assertions above are the test
        }
    }

    @FunctionalInterface
    private interface QuietBody {
        void run() throws Exception;
    }

    private static void quietly(QuietBody body) {
        try {
            body.run();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    /** Production-shaped context for a bare principal (the CapabilityWalkTest idiom). */
    private static AccessContext contextFor(Principal principal) {
        StubConduit conduit = new StubConduit();
        conduit.setAttribute(ConduitAttributes.PRINCIPAL, principal);
        return AccessContext.of(conduit);
    }

    /** Attribute-only Conduit; every request-flavored method throws. */
    private static final class StubConduit implements Conduit {

        private final Map<IdentifierKey<?>, Object> attributes = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getAttribute(IdentifierKey<T> key) {
            return (T) this.attributes.get(key);
        }

        @Override
        public <T> void setAttribute(IdentifierKey<T> key, T value) {
            if (value == null) {
                this.attributes.remove(key);
            } else {
                this.attributes.put(key, value);
            }
        }

        @Override
        public ResponseCarrier getResponseCarrier() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public <T> T getParameter(ParameterDefinition<T> parameter) {
            throw new UnsupportedOperationException("stub carries no request");
        }

        @Override
        public <T> T getBody(BodyDefinition<T> definition) {
            throw new UnsupportedOperationException("stub carries no request");
        }

        @Override
        public boolean isHawkeyeRequest() {
            return false;
        }

        @Override
        public void enableStreamingResponse() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public void notFound() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public void forbidden() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public void badRequest() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public void badRequest(String message) {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public <T> ActionResult<T> softRedirect(String url) {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public <T> ActionResult<T> hardRedirect(String url) {
            throw new UnsupportedOperationException("stub carries no response");
        }
    }
}
