package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerReconciler;
import be.elevenways.hohenheim.server.docker.DockerReconciler.Bucket;
import be.elevenways.hohenheim.server.docker.DockerReconciler.Evidence;
import be.elevenways.hohenheim.server.docker.DockerReconciler.Finding;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.stack.StackDeployer;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The reconciler: the pure classification matrix (runs WITHOUT a daemon, so the
 * decision logic is covered on machines where every Docker test skips), the
 * replace-per-server persistence + attention projection against a real database,
 * and a report-only sweep against a live daemon where one is present.
 */
class DockerReconcilerTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-reconciler-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        HohenheimTestRuntime.ensureBooted();
    }

    /** A map-backed {@link DockerReconciler.Records}: pure inputs, no database. */
    private record FakeRecords(Set<String> liveIds, Set<String> liveNames)
        implements DockerReconciler.Records {

        @Override
        public boolean liveById(Identifier model, String id) {
            return liveIds.contains(model + "#" + id);
        }

        @Override
        public boolean liveByName(Identifier model, String name) {
            return liveNames.contains(model + "#" + name);
        }
    }

    private static final FakeRecords RECORDS = new FakeRecords(
        Set.of("hohenheim:site#7", "hohenheim:database#3", "hohenheim:stack#9"),
        Set.of("hohenheim:database#appdb", "hohenheim:stack#shop", "hohenheim:stack#my-shop"));

    private static Finding classify(String kind, String name, Map<String, String> labels) {
        return DockerReconciler.classify(kind, name, labels, RECORDS);
    }

    // -- the pure matrix ------------------------------------------------------

    @Test
    void ownerLabelsDecideFirstAndResolveAgainstLiveRecords() {
        // 1. Owner labels naming a live record -> OWNED, evidence owner_label.
        Finding owned = classify("container", "whatever",
            mapOf(OwnerLabels.of(SiteModel.MODEL_ID, 7)));
        assertThat(owned.bucket()).as("live owner-labelled resource is OWNED").isEqualTo(Bucket.OWNED);
        assertThat(owned.evidence()).isEqualTo(Evidence.OWNER_LABEL);
        assertThat(owned.owner().model()).isEqualTo(SiteModel.MODEL_ID);
        assertThat(owned.owner().id()).isEqualTo("7");

        // 2. Owner labels whose record is gone -> ORPHANED, still carrying WHO it was.
        Finding orphan = classify("volume", "hohenheim-site-999-vol-data",
            mapOf(OwnerLabels.of(SiteModel.MODEL_ID, 999)));
        assertThat(orphan.bucket()).as("dead owner-labelled resource is ORPHANED")
            .isEqualTo(Bucket.ORPHANED);
        assertThat(orphan.evidence()).isEqualTo(Evidence.OWNER_LABEL);
        assertThat(orphan.owner().id()).isEqualTo("999");

        // 3. An owner label for a model the resolver cannot answer for is an alarm,
        //    never an assumption of ownership.
        Finding unknownModel = classify("container", "x",
            mapOf(OwnerLabels.of(Identifier.of("hohenheim", "does_not_exist"), 1)));
        assertThat(unknownModel.bucket()).as("unresolvable model is ORPHANED")
            .isEqualTo(Bucket.ORPHANED);

        // 4. The owner label WINS over a colliding-looking name.
        Finding labelBeatsName = classify("container", "hohenheim-site-999",
            mapOf(OwnerLabels.of(SiteModel.MODEL_ID, 7)));
        assertThat(labelBeatsName.bucket()).isEqualTo(Bucket.OWNED);
        assertThat(labelBeatsName.evidence()).isEqualTo(Evidence.OWNER_LABEL);
    }

    @Test
    void legacyStackLabelsAndNamingSchemesStillAttribute() {
        // 1. Pre-owner-label stack resources carry only the stack-name label.
        Finding stackOwned = classify("container", "hohenheim-stack-shop-web",
            Map.of(StackDeployer.LABEL_STACK, "shop"));
        assertThat(stackOwned.bucket()).as("live stack label is OWNED").isEqualTo(Bucket.OWNED);
        assertThat(stackOwned.evidence()).isEqualTo(Evidence.STACK_LABEL);

        Finding stackOrphan = classify("volume", "hohenheim-stack-dead-data",
            Map.of(StackDeployer.LABEL_STACK, "dead"));
        assertThat(stackOrphan.bucket()).as("dead stack label is ORPHANED")
            .isEqualTo(Bucket.ORPHANED);

        // 2. Unlabelled resources named by OUR schemes resolve through the records:
        //    a live site's volume is owned-by-name (relabels on next recreate)...
        Finding siteVolume = classify("volume", "hohenheim-site-7-vol-uploads", Map.of());
        assertThat(siteVolume.bucket()).as("live site's unlabelled volume is OWNED")
            .isEqualTo(Bucket.OWNED);
        assertThat(siteVolume.evidence()).isEqualTo(Evidence.NAME);

        //    ...and a DEAD site's volume is the orphan this whole feature exists for.
        Finding deadSiteVolume = classify("volume", "hohenheim-site-41-vol-data", Map.of());
        assertThat(deadSiteVolume.bucket())
            .as("unlabelled volume of a deleted site surfaces as ORPHANED")
            .isEqualTo(Bucket.ORPHANED);
        assertThat(deadSiteVolume.evidence()).isEqualTo(Evidence.NAME);

        // 3. Database naming: container by name, volume by name + -data suffix.
        assertThat(classify("container", "hohenheim-db-appdb", Map.of()).bucket())
            .as("live db container by name").isEqualTo(Bucket.OWNED);
        assertThat(classify("volume", "hohenheim-db-appdb-data", Map.of()).bucket())
            .as("live db volume by name").isEqualTo(Bucket.OWNED);
        assertThat(classify("volume", "hohenheim-db-gone-data", Map.of()).bucket())
            .as("dead db volume is ORPHANED").isEqualTo(Bucket.ORPHANED);

        // 4. Stack naming with dashes in the stack name: the longest live candidate
        //    wins ("my-shop" before "my").
        assertThat(classify("container", "hohenheim-stack-my-shop-web", Map.of()).bucket())
            .as("dashed stack name resolves").isEqualTo(Bucket.OWNED);
        assertThat(classify("network", "hohenheim-stack-shop", Map.of()).bucket())
            .as("stack network by name").isEqualTo(Bucket.OWNED);
        assertThat(classify("volume", "hohenheim-stack-vanished-data", Map.of()).bucket())
            .as("stack-scheme resource with no live stack is ORPHANED")
            .isEqualTo(Bucket.ORPHANED);
    }

    @Test
    void foreignResourcesNeverAlarmWhenRecognisedAndSplitWhenNot() {
        // 1. The org's testcontainers convention MUST be foreign-known, never an
        //    alarm -- otherwise every dev machine opens with six false alarms.
        Finding testcontainer = classify("container", "serene_poincare", Map.of(
            "be.elevenways.zenit.testdatasources", "skerit@archdev",
            "be.elevenways.zenit.testdatasources.backend", "postgres",
            "org.testcontainers", "true"));
        assertThat(testcontainer.bucket()).as("testcontainers are foreign-known")
            .isEqualTo(Bucket.FOREIGN_KNOWN);
        assertThat(testcontainer.evidence()).isEqualTo(Evidence.FOREIGN_LABEL);

        // 2. Compose projects and docker's anonymous volumes are recognised too.
        assertThat(classify("container", "someapp-web-1",
            Map.of("com.docker.compose.project", "someapp")).bucket())
            .isEqualTo(Bucket.FOREIGN_KNOWN);
        assertThat(classify("volume", "7d6b22849621",
            Map.of("com.docker.volume.anonymous", "")).bucket())
            .isEqualTo(Bucket.FOREIGN_KNOWN);
        assertThat(classify("network", "bridge", Map.of()).bucket())
            .as("docker built-in networks are foreign-known").isEqualTo(Bucket.FOREIGN_KNOWN);

        // 3. Unrecognised + hohenheim-prefixed = colliding (the legacy replace paths
        //    would force-remove exactly these); unrecognised otherwise = unrelated.
        Finding colliding = classify("container", "hohenheim-imposter", Map.of());
        assertThat(colliding.bucket()).as("hohenheim-* junk collides")
            .isEqualTo(Bucket.FOREIGN_COLLIDING);
        Finding badSiteShape = classify("container", "hohenheim-site-notanumber", Map.of());
        assertThat(badSiteShape.bucket()).as("site scheme with non-numeric id collides")
            .isEqualTo(Bucket.FOREIGN_COLLIDING);
        assertThat(classify("container", "qq-postgres", Map.of()).bucket())
            .as("stopped third-party container is unrelated").isEqualTo(Bucket.FOREIGN_UNRELATED);
        assertThat(classify("container", "mongodb",
            Map.of("org.opencontainers.image.version", "24.04")).bucket())
            .isEqualTo(Bucket.FOREIGN_UNRELATED);
    }

    @Test
    void classifyAllReadsRealListingShapes() {
        // Shapes exactly as /containers/json, /volumes and /networks come back.
        List<Object> containers = List.of(Map.of(
            "Names", List.of("/hohenheim-site-7"),
            "Labels", mapOf(OwnerLabels.of(SiteModel.MODEL_ID, 7))));
        List<Object> volumes = List.of(Map.of("Name", "hohenheim-site-41-vol-data"));
        List<Object> networks = List.of(Map.of("Name", "bridge"));

        List<Finding> findings = DockerReconciler.classifyAll(containers, volumes, networks, RECORDS);
        assertThat(findings).hasSize(3);
        assertThat(findings.get(0).name()).as("leading slash stripped").isEqualTo("hohenheim-site-7");
        assertThat(findings.get(0).bucket()).isEqualTo(Bucket.OWNED);
        assertThat(findings.get(1).bucket()).as("label-less volume classified by name")
            .isEqualTo(Bucket.ORPHANED);
        assertThat(findings.get(2).bucket()).isEqualTo(Bucket.FOREIGN_KNOWN);
    }

    // -- persistence + attention ----------------------------------------------

    @Test
    void storeReplacesPerServerAndAttentionSurfacesOnlyAlarms() {
        Db.run(datasource, () -> {
            // 1. First sweep of "local": an orphan, a collision, and quiet buckets.
            DockerReconciler.store("local", List.of(
                new Finding("volume", "hohenheim-site-41-vol-data", Bucket.ORPHANED,
                    Evidence.NAME, null, "no live record"),
                new Finding("container", "hohenheim-imposter", Bucket.FOREIGN_COLLIDING,
                    Evidence.NAME, null, null),
                new Finding("container", "serene_poincare", Bucket.FOREIGN_KNOWN,
                    Evidence.FOREIGN_LABEL, null, "org.testcontainers"),
                new Finding("container", "hohenheim-site-7", Bucket.OWNED,
                    Evidence.OWNER_LABEL,
                    new OwnerLabels.Owner(SiteModel.MODEL_ID, "7"), null)));
            // A second server's findings live independently.
            DockerReconciler.store("edge-1", List.of(
                new Finding("volume", "hohenheim-db-gone-data", Bucket.ORPHANED,
                    Evidence.NAME, null, "no live record")));

            List<Row> stored = Models.get(ReconcileFindingModel.class).find().all();
            assertThat(stored).hasSize(5);

            // 2. Attention: one orphan item per server, one collision item for local;
            //    owned and foreign-known rows never surface.
            List<AttentionItem> items = DockerReconciler.attentionItems();
            assertThat(items).hasSize(3);
            assertThat(items).allSatisfy(item ->
                assertThat(item.severity()).as("report-only findings warn, never error")
                    .isEqualTo("warning"));
            assertThat(items.stream().map(i -> i.title().key()))
                .containsExactlyInAnyOrder("docker_orphans", "docker_orphans", "docker_colliding");

            // 3. Re-sweeping local REPLACES its findings and clears its attention,
            //    while edge-1's stored truth is untouched.
            DockerReconciler.store("local", List.of(
                new Finding("container", "hohenheim-site-7", Bucket.OWNED,
                    Evidence.OWNER_LABEL,
                    new OwnerLabels.Owner(SiteModel.MODEL_ID, "7"), null)));
            List<Row> after = Models.get(ReconcileFindingModel.class).find().all();
            assertThat(after).hasSize(2);
            assertThat(after).extracting(row -> row.get(ReconcileFindingModel.SERVER_NAME))
                .containsExactlyInAnyOrder("local", "edge-1");
            List<AttentionItem> remaining = DockerReconciler.attentionItems();
            assertThat(remaining).hasSize(1);
            assertThat(remaining.get(0).title().key()).isEqualTo("docker_orphans");
        });
    }

    // -- the live daemon, report-only -----------------------------------------

    @Test
    void liveSweepFindsAPlantedOrphanAndRemovesNothing() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, "alpine:latest"), "alpine:latest not present locally");

        // 1. Plant a container claiming a site record that does not exist.
        String name = "hohenheim-reconciler-orphan-" + Long.toHexString(System.nanoTime());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", "alpine:latest");
        spec.put("Cmd", List.of("sleep", "60"));
        spec.put("Labels", OwnerLabels.of(SiteModel.MODEL_ID, 987_654));
        docker.createContainer(name, spec);

        try {
            Db.run(datasource, () -> {
                // 2. Sweep the real daemon: the planted orphan is found and stored.
                List<Finding> findings;
                try {
                    findings = DockerReconciler.sweepServer("local", docker);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                Finding planted = findings.stream()
                    .filter(f -> name.equals(f.name())).findFirst().orElse(null);
                assertThat(planted).as("planted container appears in the sweep").isNotNull();
                assertThat(planted.bucket()).as("dead-record claim is ORPHANED")
                    .isEqualTo(Bucket.ORPHANED);
                assertThat(planted.owner().id()).isEqualTo("987654");

                Row storedRow = Models.get(ReconcileFindingModel.class).find()
                    .where(ReconcileFindingModel.RESOURCE_NAME.eq(name))
                    .first();
                assertThat(storedRow).as("finding persisted for the dashboard").isNotNull();
                assertThat((String) storedRow.get(ReconcileFindingModel.BUCKET))
                    .isEqualTo(ReconcileFindingModel.BUCKET_ORPHANED);

                // 3. The org's live testcontainers (when present) are foreign-known,
                //    never orphaned/colliding noise.
                findings.stream()
                    .filter(f -> f.detail() != null && f.detail().contains("testdatasources"))
                    .forEach(f -> assertThat(f.bucket()).isEqualTo(Bucket.FOREIGN_KNOWN));

                // Log the sweep so a misclassification on a real machine is visible
                // in the test output, not just a wrong dashboard later.
                findings.forEach(f -> System.out.println("RECONCILE " + f.bucket()
                    + " [" + f.evidence() + "] " + f.kind() + " " + f.name()
                    + (f.detail() != null ? " (" + f.detail() + ")" : "")));
            });

            // 4. REPORT-ONLY: the sweep must not have removed or altered the orphan.
            Map<String, Object> stillThere = docker.inspectContainer(name);
            assertThat(stillThere).as("reconciler never removes a resource").isNotNull();
        } finally {
            docker.removeContainer(name, true);
        }
    }

    private static Map<String, String> mapOf(Map<String, String> owner) {
        return new LinkedHashMap<>(owner);
    }

    private static boolean imagePresent(DockerClient docker, String tag) throws IOException {
        for (Object image : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) image).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }
}
