package be.elevenways.hohenheim.test.preview;

import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.source.TestSources;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.application.ApplicationDeploys;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.preview.PreviewDeployments;
import be.elevenways.hohenheim.server.preview.PreviewDomains;
import be.elevenways.hohenheim.server.preview.PreviewQuota;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.record.RecordScheduleKind;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE preview gate against a REAL daemon and a REAL proxy: a preview is created from a
 * git ref through the sandboxed builder, serves on its generated hostname through the
 * routing table, receives ONLY preview-declared variables, and -- when its stored
 * lifetime ends -- is FULLY reclaimed: instance record, container, port claims,
 * generated hostname row, DNS rows and quota charge, asserted at the daemon and in the
 * database while a hand-authored domain row survives untouched.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class PreviewDeploymentLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static boolean booted;
    private static PrivateNetns netns;
    private static ProxyServer proxy;
    private static Path upstreamRepo;
    private static Integer siteId;

    /** The APPLICATION previews and production releases are both built from. */
    private static Integer applicationId;
    private static Row handRow;

    /** Unique per run: stale checkouts of earlier runs must never collide on a digest. */
    private static final String PREVIEW_BODY = "preview-" + System.nanoTime();

    @BeforeAll
    static void boot() throws Exception {
        if (!booted) {
            booted = true;
            ProxyTestSupport.bootRuntime();
        }
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Previews.BASE_DOMAIN, "preview.test");
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Previews.LIFETIME_MINUTES, 60);
        HostFixtures.admitLocal();
        // A hosted zone covering the preview base domain, and a declared public
        // address: the preview lane materializes A records into it, attributed.
        var servers = Models.get(be.elevenways.hohenheim.model.ServerModel.class);
        Row local = servers.findById(
            be.elevenways.hohenheim.model.ServerModel.localServerId());
        local.set(be.elevenways.hohenheim.model.ServerModel.PUBLIC_IPV4, "192.0.2.10");
        servers.save(local);
        var zones = Models.get(be.elevenways.hohenheim.model.DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(be.elevenways.hohenheim.model.DnsZoneModel.ORIGIN, "preview.test");
        zone.set(be.elevenways.hohenheim.model.DnsZoneModel.ENABLED, true);
        zones.save(zone);
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }

        upstreamRepo = Files.createTempDirectory("hohenheim-preview-upstream");
        gitIn(upstreamRepo, "init", "-q", "-b", "main");
        gitIn(upstreamRepo, "config", "user.email", "test@example.com");
        gitIn(upstreamRepo, "config", "user.name", "Test");
        // main and feature-x serve DIFFERENT bodies, so their build digests differ --
        // which is what lets the reclaim assert the preview's OWN artifact died while
        // the site's production artifact (a different digest) is untouched.
        Files.writeString(upstreamRepo.resolve("answer.sh"), answerScript("main-one"));
        Files.writeString(upstreamRepo.resolve("Dockerfile"), """
            FROM alpine:latest
            COPY answer.sh /answer.sh
            RUN chmod +x /answer.sh
            CMD ["/bin/busybox","nc","-lk","-p","8080","-e","/answer.sh"]
            """);
        gitIn(upstreamRepo, "add", ".");
        gitIn(upstreamRepo, "commit", "-q", "-m", "preview fixture");
        gitIn(upstreamRepo, "checkout", "-q", "-b", "feature-x");
        Files.writeString(upstreamRepo.resolve("answer.sh"), answerScript(PREVIEW_BODY));
        gitIn(upstreamRepo, "add", ".");
        gitIn(upstreamRepo, "commit", "-q", "-m", "preview branch content");

        Row site = ProxyTestSupport.setupSite("hohenheim:static", "Preview Live Site",
            "prev-live", new LinkedHashMap<>());

        // ONE settings map: the APPLICATION carries both the source and the runtime spec
        // now, so the production environment a preview must never inherit lives there too.
        Map<String, Object> applicationSettings = new LinkedHashMap<>(dockerSettings());
        applicationSettings.put("repository_url", upstreamRepo.toString());
        applicationSettings.put("branch", "main");
        applicationSettings.put("previews_enabled", true);
        applicationSettings.put("preview_environment_variables",
            Map.of("PREVIEW_MARKER", "preview-only-value"));
        applicationId = TestSources.attachGitSource(site, applicationSettings);
        Models.get(SiteModel.class).save(site);
        siteId = site.get(SiteModel.ID);
        ProxyTestSupport.addDomain(site, "prev-live.test", "exact", null, false);

        // A hand-authored second domain: the self-scoping counter-subject.
        var domains = Models.get(SiteDomainModel.class);
        handRow = domains.createEmptyRow();
        handRow.set(SiteDomainModel.SITE_ID, siteId);
        handRow.set(SiteDomainModel.HOSTNAME, "hand-live.preview.test");
        handRow.set(SiteDomainModel.MATCH_TYPE, "exact");
        domains.save(handRow);

        proxy = ProxyTestSupport.startProxy();
        ServerMain.adoptProxyServer(proxy);
    }

    @AfterAll
    static void tearDown() {
        // Reclaim everything this class put on the daemon, THROUGH the product
        // funnels, even when a test failed mid-journey: live previews first (a failed
        // run otherwise leaves its preview container RUNNING forever), then the
        // fixture application's production release (otherwise every run leaves an exited
        // container plus its built image behind).
        if (applicationId != null) {
            try {
                PreviewDeployments.destroyForApplication(applicationId);
            } catch (RuntimeException e) {
                System.err.println("teardown: preview reclaim failed - " + e.getMessage());
            }
            try {
                ApplicationReleases.destroyFor(applicationId);
            } catch (RuntimeException e) {
                System.err.println("teardown: release reclaim failed - " + e.getMessage());
            }
        }
        ServerMain.adoptProxyServer(null);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
    }

    private static Map<String, Object> dockerSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put("tag", "latest");
        settings.put("container_port", 8080);
        // Production runtime environment a preview must NEVER inherit.
        settings.put("environment_variables",
            Map.of("PROD_DB_PASSWORD", "prod-secret-never-in-previews"));
        return settings;
    }

    @Test
    void aPreviewIsCreatedServesIsolatedAndIsFullyReclaimedOnExpiry() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the sandbox refuses to build unprotected");
        DockerClient docker = new DockerClient();

        // AIDEV-NOTE: routing no longer deploys anything (the upstream handler only
        // RESOLVES since brief 7), so the production release is this test's own explicit
        // step rather than an async side effect of proxy start. It runs FIRST and
        // SYNCHRONOUSLY: both deploys take the same host lease, and the ordering is what
        // the old awaitInitialDeployFinished() bought by waiting on a race.
        ApplicationDeploys.deploy(applicationId, "main", "preview live fixture");

        // 1. CREATE: build the feature ref through the sandbox and deploy it.
        Row preview = PreviewDeployments.deploy(applicationId, "feature-x", null, 41);
        int previewId = preview.get(PreviewDeploymentModel.ID);
        Integer instanceId = preview.get(PreviewDeploymentModel.INSTANCE_ID);
        String hostname = preview.get(PreviewDeploymentModel.HOSTNAME);
        assertThat((String) preview.get(PreviewDeploymentModel.STATUS))
            .as("step 1: the preview reports running").isEqualTo(
                PreviewDeploymentModel.STATUS_RUNNING);
        assertThat(hostname).as("step 1: the deterministic generated hostname")
            .isEqualTo("prev-live--feature-x.preview.test");
        assertThat(instanceId).as("step 1: the preview owns an instance").isNotNull();
        List<Row> armed = Models.get(RecordScheduleModel.class)
            .findForRecord(PreviewDeploymentModel.MODEL_ID, previewId);
        assertThat(armed)
            .as("step 1: deploy itself armed the one-shot expiry schedule").hasSize(1);
        assertThat((String) armed.get(0).get(RecordScheduleModel.KIND))
            .as("step 1: as a one-shot").isEqualTo(RecordScheduleKind.ONCE.storageKey());
        assertThat((Object) armed.get(0).get(RecordScheduleModel.RUN_AT)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
            .as("step 1: at the stored deadline (storage keeps millis)")
            .isEqualTo(preview.get(PreviewDeploymentModel.EXPIRES_AT)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));

        // 2. The instance and domain rows carry the preview's ATTRIBUTION, and the
        //    daemon runs a digest-pinned artifact built from the ref.
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        assertThat((String) instance.get(InstanceModel.GENERATED_BY))
            .as("step 2: the instance is attributed to the preview")
            .isEqualTo(PreviewDomains.SOURCE);
        assertThat((String) instance.get(InstanceModel.GENERATED_FOR_MODEL))
            .isEqualTo(PreviewDeploymentModel.MODEL_ID.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> stored =
            (Map<String, Object>) instance.get(InstanceModel.SETTINGS);
        assertThat(String.valueOf(stored.get("image")))
            .as("step 2: the preview runs a digest-pinned build").startsWith("sha256:");
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        assertThat(isRunning(docker, handle))
            .as("step 2: the container runs at the daemon").isTrue();
        Row generatedDomain = generatedDomainOf(previewId);
        assertThat(generatedDomain)
            .as("step 2: the hostname row is generated and attributed").isNotNull();
        List<Row> dns = generatedDnsOf(previewId);
        assertThat(dns)
            .as("step 2: an attributed A record materialized into the hosted zone")
            .isNotEmpty();
        assertThat(dns).anySatisfy(record -> {
            assertThat((String) record.get(DnsRecordModel.TYPE))
                .isEqualTo(DnsRecordModel.TYPE_A);
            assertThat((String) record.get(DnsRecordModel.VALUE)).isEqualTo("192.0.2.10");
        });

        // 3. It SERVES through the real proxy on the generated hostname.
        int port = ProxyTestSupport.httpPort(proxy);
        String response = ProxyTestSupport.rawRequest(port, hostname, "/");
        assertThat(response).as("step 3: the preview answers through the proxy")
            .contains("200").contains(PREVIEW_BODY);

        // 4. VARIABLE ISOLATION, asserted at the daemon: the preview-declared variable
        //    is in the container's environment, the production secret is NOT.
        String env = String.valueOf(docker.inspectContainer(handle));
        assertThat(env).as("step 4: the preview-declared variable arrived")
            .contains("PREVIEW_MARKER=preview-only-value");
        assertThat(env).as("step 4: the production secret has no path into a preview")
            .doesNotContain("prod-secret-never-in-previews");

        // 5. The quota charge is real and owner-bucketed.
        String bucket = preview.get(PreviewDeploymentModel.QUOTA_BUCKET);
        assertThat(bucket).startsWith("hohenheim:previews:");
        assertThat(PreviewQuota.usedBy(bucket.substring("hohenheim:previews:".length())))
            .as("step 5: the owner's preview slot is spent").isEqualTo(1);

        // 6. EXPIRY: re-arm the deploy-armed one-shot at a reached deadline (the
        //    extend-the-window call, backwards); the framework sweeper reclaims
        //    EVERYTHING.
        PreviewDeployments.armExpiry(previewId, Instant.now().minusSeconds(1));
        new RecordSchedules(Datasources.getDefault()).runDue(null);

        Row dead = awaitDeleted(previewId);
        assertThat((String) dead.get(PreviewDeploymentModel.STATUS))
            .as("step 6: stamped EXPIRED").isEqualTo(PreviewDeploymentModel.STATUS_EXPIRED);
        assertThat((Object) dead.get(PreviewDeploymentModel.DELETED_AT)).isNotNull();
        Row deadInstance = Models.get(InstanceModel.class).findById(instanceId);
        assertThat((Object) deadInstance.get(InstanceModel.DELETED_AT))
            .as("step 6: the instance record is soft-deleted").isNotNull();
        assertThat(containerExists(docker, handle))
            .as("step 6: the container is GONE at the daemon").isFalse();
        assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId))
            .as("step 6: no port claim survives the preview").isEmpty();
        assertThat(generatedDomainOf(previewId))
            .as("step 6: the generated hostname row is gone").isNull();
        assertThat(generatedDnsOf(previewId))
            .as("step 6: no generated DNS row survives").isEmpty();
        assertThat(PreviewQuota.usedBy(bucket.substring("hohenheim:previews:".length())))
            .as("step 6: the quota slot is released").isZero();
        assertThat(imagePresent(docker, String.valueOf(stored.get("image"))))
            .as("step 6: the preview's OWN build artifact was reclaimed at the daemon")
            .isFalse();

        // 7. SELF-SCOPED: the hand-authored domain row on the same site survived every
        //    sweep -- cleanup removes exactly the preview's own output.
        assertThat(Models.get(SiteDomainModel.class)
            .findById(handRow.get(SiteDomainModel.ID)))
            .as("step 7: the hand-authored row was never adopted or deleted").isNotNull();

        // 8. And the hostname no longer serves the preview.
        String afterExpiry = ProxyTestSupport.rawRequest(port, hostname, "/");
        assertThat(afterExpiry)
            .as("step 8: the expired preview's hostname no longer answers with its body")
            .doesNotContain(PREVIEW_BODY);
    }

    // -- helpers --------------------------------------------------------------

    /**
     * The ambient minute sweeper can win the lease race for the due one-shot; whoever
     * fires it, the reclaimed STATE is what the test asserts -- await it briefly.
     */
    private static Row awaitDeleted(int previewId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Row row = Models.get(PreviewDeploymentModel.class).findById(previewId);
            if (row != null && row.get(PreviewDeploymentModel.DELETED_AT) != null) {
                return row;
            }
            Thread.sleep(100);
        }
        return Models.get(PreviewDeploymentModel.class).findById(previewId);
    }

    private static Row generatedDomainOf(int previewId) {
        List<Row> rows = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.GENERATED_BY.eq(PreviewDomains.SOURCE))
            .where(SiteDomainModel.GENERATED_FOR_MODEL.eq(
                PreviewDeploymentModel.MODEL_ID.toString()))
            .where(SiteDomainModel.GENERATED_FOR_ID.eq(previewId))
            .all();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<Row> generatedDnsOf(int previewId) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.GENERATED_BY.eq(PreviewDomains.SOURCE))
            .where(DnsRecordModel.GENERATED_FOR_MODEL.eq(
                PreviewDeploymentModel.MODEL_ID.toString()))
            .where(DnsRecordModel.GENERATED_FOR_ID.eq(previewId))
            .all();
    }

    private static boolean imagePresent(DockerClient docker, String reference) {
        try {
            docker.inspectImage(reference);
            return true;
        } catch (IOException gone) {
            return false;
        }
    }

    private static boolean containerExists(DockerClient docker, String handle) {
        try {
            docker.inspectContainer(handle);
            return true;
        } catch (IOException gone) {
            return false;
        }
    }

    private static boolean isRunning(DockerClient docker, String handle) {
        try {
            Map<String, Object> inspect = docker.inspectContainer(handle);
            return inspect.get("State") instanceof Map<?, ?> state
                && Boolean.TRUE.equals(state.get("Running"));
        } catch (IOException gone) {
            return false;
        }
    }

    private static String answerScript(String body) {
        return "#!/bin/busybox sh\n"
            + "CR=$(printf '\\r')\n"
            + "while read -r line; do\n"
            + "  line=${line%$CR}\n"
            + "  [ -z \"$line\" ] && break\n"
            + "done\n"
            + "printf 'HTTP/1.1 200 OK\\r\\nContent-Length: "
            + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + "\\r\\nConnection: close\\r\\n\\r\\n" + body + "'\n";
    }

    private static String gitIn(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                || process.exitValue() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }
}
