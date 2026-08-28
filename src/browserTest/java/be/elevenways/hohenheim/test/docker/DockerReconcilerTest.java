package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerReconciler;
import be.elevenways.hohenheim.server.docker.DockerReconciler.Bucket;
import be.elevenways.hohenheim.server.docker.DockerReconciler.Evidence;
import be.elevenways.hohenheim.server.docker.DockerReconciler.Finding;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.task.ReapIncusControllers;
import be.elevenways.hohenheim.server.stack.StackInstances;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconciler: the pure classification matrix (runs WITHOUT a daemon, so the
 * decision logic is covered on machines where every Docker test skips), the
 * replace-per-server persistence + attention projection against a real database,
 * and a report-only sweep against a live daemon where one is present.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
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
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
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

        @Override
        public String controllerToken() {
            return ControllerIdentity.token();
        }
    }

    /** Another controller's identity token: same daemon, different id space. */
    private static final String OTHER_CONTROLLER = "zq7m4x1b";

    /** A resource name minted by THIS controller. */
    private static String ours(String rest) {
        return ControllerScope.PREFIX + "-" + ControllerIdentity.token() + "-" + rest;
    }

    /** The same resource name, minted by ANOTHER controller against the same daemon. */
    private static String theirs(String rest) {
        return ControllerScope.PREFIX + "-" + OTHER_CONTROLLER + "-" + rest;
    }

    /** Owner labels as another controller writes them for ITS record. */
    private static Map<String, String> foreignOwner(Identifier model, Object recordId) {
        return Map.of(OwnerLabels.MODEL, model.toString(), OwnerLabels.ID,
            String.valueOf(recordId), OwnerLabels.CONTROLLER, OTHER_CONTROLLER);
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
        Finding orphan = classify("volume", ours("site-999-vol-data"),
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
        Finding labelBeatsName = classify("container", ours("site-999"),
            mapOf(OwnerLabels.of(SiteModel.MODEL_ID, 7)));
        assertThat(labelBeatsName.bucket()).isEqualTo(Bucket.OWNED);
        assertThat(labelBeatsName.evidence()).isEqualTo(Evidence.OWNER_LABEL);
    }

    @Test
    void legacyStackLabelsAndNamingSchemesStillAttribute() {
        // 1. Pre-owner-label stack resources carry only the stack-name label.
        Finding stackOwned = classify("container", ours("stack-shop-web"),
            Map.of(StackInstances.LEGACY_LABEL_STACK, "shop"));
        assertThat(stackOwned.bucket()).as("live stack label is OWNED").isEqualTo(Bucket.OWNED);
        assertThat(stackOwned.evidence()).isEqualTo(Evidence.STACK_LABEL);

        Finding stackOrphan = classify("volume", ours("stack-dead-data"),
            Map.of(StackInstances.LEGACY_LABEL_STACK, "dead"));
        assertThat(stackOrphan.bucket()).as("dead stack label is ORPHANED")
            .isEqualTo(Bucket.ORPHANED);

        // 2. Unlabelled resources named by OUR schemes resolve through the records:
        //    a live site's volume is owned-by-name (relabels on next recreate)...
        Finding siteVolume = classify("volume", ours("site-7-vol-uploads"), Map.of());
        assertThat(siteVolume.bucket()).as("live site's unlabelled volume is OWNED")
            .isEqualTo(Bucket.OWNED);
        assertThat(siteVolume.evidence()).isEqualTo(Evidence.NAME);

        //    ...and a DEAD site's volume is the orphan this whole feature exists for.
        Finding deadSiteVolume = classify("volume", ours("site-41-vol-data"), Map.of());
        assertThat(deadSiteVolume.bucket())
            .as("unlabelled volume of a deleted site surfaces as ORPHANED")
            .isEqualTo(Bucket.ORPHANED);
        assertThat(deadSiteVolume.evidence()).isEqualTo(Evidence.NAME);

        // 3. Database naming: container by name, volume by name + -data suffix.
        assertThat(classify("container", ours("db-appdb"), Map.of()).bucket())
            .as("live db container by name").isEqualTo(Bucket.OWNED);
        assertThat(classify("volume", ours("db-appdb-data"), Map.of()).bucket())
            .as("live db volume by name").isEqualTo(Bucket.OWNED);
        assertThat(classify("volume", ours("db-gone-data"), Map.of()).bucket())
            .as("dead db volume is ORPHANED").isEqualTo(Bucket.ORPHANED);

        // 4. Stack naming with dashes in the stack name: the longest live candidate
        //    wins ("my-shop" before "my").
        assertThat(classify("container", ours("stack-my-shop-web"), Map.of()).bucket())
            .as("dashed stack name resolves").isEqualTo(Bucket.OWNED);
        assertThat(classify("network", ours("stack-shop"), Map.of()).bucket())
            .as("stack network by name").isEqualTo(Bucket.OWNED);
        assertThat(classify("volume", ours("stack-vanished-data"), Map.of()).bucket())
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
        Finding badSiteShape = classify("container", ours("site-notanumber"), Map.of());
        assertThat(badSiteShape.bucket()).as("site scheme with non-numeric id collides")
            .isEqualTo(Bucket.FOREIGN_COLLIDING);
        assertThat(classify("container", "qq-postgres", Map.of()).bucket())
            .as("stopped third-party container is unrelated").isEqualTo(Bucket.FOREIGN_UNRELATED);
        assertThat(classify("container", "mongodb",
            Map.of("org.opencontainers.image.version", "24.04")).bucket())
            .isEqualTo(Bucket.FOREIGN_UNRELATED);
    }

    /**
     * Two controllers, one daemon, the SAME record id: the classifier must answer for
     * the OTHER controller's resources without ever consulting our records, because a
     * record id only means something inside the database that allocated it.
     */
    @Test
    void anotherControllersResourcesNeverResolveAgainstOurRecords() {
        // 1. Control: our own labelled resource for live site #7 is OWNED.
        Finding mine = classify("container", ours("site-7"),
            mapOf(OwnerLabels.of(SiteModel.MODEL_ID, 7)));
        assertThat(mine.bucket())
            .as("step 1: our own controller's labelled container for a live record is OWNED")
            .isEqualTo(Bucket.OWNED);

        // 2. ANOTHER controller's labels naming ITS site #7 -- byte-identical model and
        //    id -- must NOT come back OWNED. This is the exact shape that made
        //    removeIfOwnedBy unable to refuse another controller's running container.
        Finding foreign = classify("container", theirs("site-7"),
            foreignOwner(SiteModel.MODEL_ID, 7));
        assertThat(foreign.bucket())
            .as("step 2: another controller's site #7 is a COLLISION, not our record")
            .isEqualTo(Bucket.FOREIGN_COLLIDING);
        assertThat(foreign.detail())
            .as("step 2: the refusal names the controller that actually owns it")
            .contains(OTHER_CONTROLLER);
        assertThat(foreign.owner().controller())
            .as("step 2: the parsed owner carries the foreign controller, not ours")
            .isEqualTo(OTHER_CONTROLLER);

        // 3. Their NAME alone (unlabelled) must not resolve through our records either:
        //    site 7 is live HERE, and name attribution would have called it ours.
        Finding foreignName = classify("volume", theirs("site-7-vol-uploads"), Map.of());
        assertThat(foreignName.bucket())
            .as("step 3: another controller's name never resolves against our records")
            .isEqualTo(Bucket.FOREIGN_COLLIDING);
        assertThat(foreignName.detail()).contains(OTHER_CONTROLLER);

        // 4. A PRE-NAMESPACE resource (owner labels, no controller label) is attributable
        //    to no controller at all: reported loudly, never silently adopted or ignored.
        Finding preNamespace = classify("container", "hohenheim-site-7",
            Map.of(OwnerLabels.MODEL, SiteModel.MODEL_ID.toString(), OwnerLabels.ID, "7"));
        assertThat(preNamespace.bucket())
            .as("step 4: a pre-namespace resource is a collision, not an orphan or ours")
            .isEqualTo(Bucket.FOREIGN_COLLIDING);
        assertThat(preNamespace.detail())
            .as("step 4: the detail says WHY it cannot be attributed")
            .contains("pre-namespace");

        // 5. And a pre-namespace name with no labels at all says the same thing.
        Finding preNamespaceName = classify("volume", "hohenheim-site-7-vol-uploads", Map.of());
        assertThat(preNamespaceName.bucket())
            .as("step 5: a pre-namespace unlabelled name is a collision")
            .isEqualTo(Bucket.FOREIGN_COLLIDING);
        assertThat(preNamespaceName.detail())
            .as("step 5: named as carrying no controller namespace")
            .contains("no controller namespace");
    }

    @Test
    void classifyAllReadsRealListingShapes() {
        // Shapes exactly as /containers/json, /volumes and /networks come back.
        List<Object> containers = List.of(Map.of(
            "Names", List.of("/" + ours("site-7")),
            "Labels", mapOf(OwnerLabels.of(SiteModel.MODEL_ID, 7))));
        List<Object> volumes = List.of(Map.of("Name", ours("site-41-vol-data")));
        List<Object> networks = List.of(Map.of("Name", "bridge"));

        List<Finding> findings = DockerReconciler.classifyAll(containers, volumes, networks, RECORDS);
        assertThat(findings).hasSize(3);
        assertThat(findings.get(0).name()).as("leading slash stripped").isEqualTo(ours("site-7"));
        assertThat(findings.get(0).bucket()).isEqualTo(Bucket.OWNED);
        assertThat(findings.get(1).bucket()).as("label-less volume classified by name")
            .isEqualTo(Bucket.ORPHANED);
        assertThat(findings.get(2).bucket()).isEqualTo(Bucket.FOREIGN_KNOWN);
    }

    /**
     * The REAL record resolver knows the instance tier (C7): a live instance's
     * labelled container is OWNED, a soft-deleted one's is ORPHANED. This runs
     * through {@code ModelRecords} on purpose -- the fake above would keep passing
     * with the resolver branch missing, and a missing branch means EVERY live
     * instance lands on the attention list as a false orphan.
     */
    @Test
    void modelRecordsResolveInstancesHonouringSoftDelete() {
        Db.run(datasource, () -> {
            // 1. A live instance record: its labelled container attributes as OWNED.
            Row instance = Models.get(InstanceModel.class)
                .createEmptyRow();
            instance.set(InstanceModel.NAME, "reconciled");
            instance.set(InstanceModel.KIND,
                "hohenheim:docker_container");
            Models.get(InstanceModel.class).save(instance);
            int id = instance.get(InstanceModel.ID);
            DockerReconciler.Records records = new DockerReconciler.ModelRecords();

            Finding live = DockerReconciler.classify("container", ours("instance-" + id),
                mapOf(OwnerLabels.of(InstanceModel.MODEL_ID, id)),
                records);
            assertThat(live.bucket())
                .as("step 1: a live instance's labelled container is OWNED, not a false alarm")
                .isEqualTo(Bucket.OWNED);
            assertThat(live.evidence()).isEqualTo(Evidence.OWNER_LABEL);

            // 2. Soft-delete the record: the same container is now the orphan the
            //    attention list exists for (soft-deleted = not live).
            instance.set(InstanceModel.DELETED_AT,
                Instant.now());
            Models.get(InstanceModel.class).save(instance);
            Finding trashed = DockerReconciler.classify("container", ours("instance-" + id),
                mapOf(OwnerLabels.of(InstanceModel.MODEL_ID, id)),
                records);
            assertThat(trashed.bucket())
                .as("step 2: a soft-deleted instance's container is ORPHANED")
                .isEqualTo(Bucket.ORPHANED);

            // 3. A label-less hohenheim-instance-* name is DELIBERATELY not attributed by
            //    name: the tier was born after the owner labels, so an unlabelled
            //    lookalike is a collision, never adopted-by-name.
            Finding lookalike = DockerReconciler.classify("container",
                ours("instance-" + id), Map.of(), records);
            assertThat(lookalike.bucket())
                .as("step 3: an unlabelled instance-named container is FOREIGN_COLLIDING")
                .isEqualTo(Bucket.FOREIGN_COLLIDING);

            Models.get(InstanceModel.class).delete(id);
        });
    }

    // -- persistence + attention ----------------------------------------------

    @Test
    void storeReplacesPerServerAndAttentionSurfacesOnlyAlarms() {
        Db.run(datasource, () -> {
            // 1. First sweep of "local": an orphan, a collision, and quiet buckets.
            DockerReconciler.store("local", List.of(
                new Finding("volume", ours("site-41-vol-data"), Bucket.ORPHANED,
                    Evidence.NAME, null, "no live record"),
                new Finding("container", "hohenheim-imposter", Bucket.FOREIGN_COLLIDING,
                    Evidence.NAME, null, null),
                new Finding("container", "serene_poincare", Bucket.FOREIGN_KNOWN,
                    Evidence.FOREIGN_LABEL, null, "org.testcontainers"),
                new Finding("container", ours("site-7"), Bucket.OWNED,
                    Evidence.OWNER_LABEL,
                    new OwnerLabels.Owner(SiteModel.MODEL_ID, "7", ControllerIdentity.token()), null)));
            // A second server's findings live independently.
            DockerReconciler.store("edge-1", List.of(
                new Finding("volume", ours("db-gone-data"), Bucket.ORPHANED,
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

            // 2b. The foreign rows are not alarms, but they are not invisible either: the
            //     dashboard carries ONE informational row per host that has them, counting
            //     them and linking to the findings list, so a host full of unmanaged
            //     resources never sits under an "All clear".
            List<AttentionItem> foreign = new ArrayList<>();
            AttentionCollector.dockerForeignResources(foreign);
            assertThat(foreign).as("one informational row per host with foreign resources").hasSize(1);
            assertThat(foreign.get(0).severity()).as("foreign resources inform, never warn").isEqualTo("info");
            assertThat(foreign.get(0).title().key()).isEqualTo("docker_foreign");
            assertThat(foreign.get(0).target()).as("the row leads to the findings list").isNotNull();

            // 3. Re-sweeping local REPLACES its findings and clears its attention,
            //    while edge-1's stored truth is untouched.
            DockerReconciler.store("local", List.of(
                new Finding("container", ours("site-7"), Bucket.OWNED,
                    Evidence.OWNER_LABEL,
                    new OwnerLabels.Owner(SiteModel.MODEL_ID, "7", ControllerIdentity.token()), null)));
            List<Row> after = Models.get(ReconcileFindingModel.class).find().all();
            assertThat(after).hasSize(2);
            assertThat(after).extracting(row -> row.get(ReconcileFindingModel.SERVER_NAME))
                .containsExactlyInAnyOrder("local", "edge-1");
            List<AttentionItem> remaining = DockerReconciler.attentionItems();
            assertThat(remaining).hasSize(1);
            assertThat(remaining.get(0).title().key()).isEqualTo("docker_orphans");
        });
    }

    // -- the releasing-claim observer (daemon-free) ---------------------------

    /**
     * The reserved-until-observed loop, closed by the reconciler: a releasing claim is
     * deleted ONLY when neither the daemon listing nor (on the local host) the OS probe
     * sees the port bound; every other releasing claim is retained, and held claims are
     * never touched. Runs entirely on fake listings and a fake probe -- this coverage
     * exists even where every daemon test skips.
     */
    @Test
    void releasingClaimsAreFreedOnlyWhenObservedFreeAndOnlyByTheObserver() {
        Db.run(datasource, () -> {
            int localId = ServerModel.localServerId();
            Row edge = Models.get(ServerModel.class).createEmptyRow();
            edge.set(ServerModel.NAME, "sweep-edge");
            edge.set(ServerModel.MODE, ServerModel.MODE_SSH);
            Models.get(ServerModel.class).save(edge);
            Integer edgeId = edge.get(ServerModel.ID);

            // 1. Two owning records, so the claims can be PARKED through the real funnel
            //    (releaseOwner = the unverified release): three local claims + two remote
            //    ones end up releasing; an unowned local claim stays held.
            Integer localOwner = sweepOwnerRecord("sweepa");
            Integer remoteOwner = sweepOwnerRecord("sweepc");
            PortLedger.claim(localId, "127.0.0.1", 9411, "tcp",
                DatabaseModel.MODEL_ID, localOwner, "published-still");
            PortLedger.claim(localId, "", 9412, "tcp",
                DatabaseModel.MODEL_ID, localOwner, "probe-bound");
            PortLedger.claim(localId, "127.0.0.1", 9413, "tcp",
                DatabaseModel.MODEL_ID, localOwner, "observed-free");
            PortLedger.claim(localId, "", 9414, "udp", null, null, "held-stays");
            PortLedger.claim(edgeId, "127.0.0.1", 9415, "tcp",
                DatabaseModel.MODEL_ID, remoteOwner, "remote-published");
            PortLedger.claim(edgeId, "127.0.0.1", 9416, "tcp",
                DatabaseModel.MODEL_ID, remoteOwner, "remote-free");
            PortLedger.releaseOwner(DatabaseModel.MODEL_ID, localOwner);
            PortLedger.releaseOwner(DatabaseModel.MODEL_ID, remoteOwner);

            // 2. The local daemon still publishes 9411 (whole-host binding overlaps the
            //    loopback claim); the OS probe says 9412 is bound and 9413 free.
            List<Object> localContainers = List.of(Map.of(
                "Names", List.of("/whatever"),
                "Ports", List.of(
                    Map.of("IP", "0.0.0.0", "PrivatePort", 80, "PublicPort", 9411, "Type", "tcp"))));
            DockerReconciler.ReleasingSweep local = DockerReconciler.sweepReleasingClaims(
                "local", localContainers,
                (address, port, protocol) -> port != 9412);
            assertThat(local.released()).as("step 2: exactly one local claim observed free")
                .isEqualTo(1);
            assertThat(local.retained()).as("step 2: two local claims still bound").isEqualTo(2);
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(localId, "127.0.0.1", 9413, "tcp")))
                .as("step 2: the observed-free claim was deleted").isNull();
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(localId, "127.0.0.1", 9411, "tcp")))
                .as("step 2: the still-published claim was kept").isNotNull();
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 9412, "tcp")))
                .as("step 2: the probe-bound claim was kept").isNotNull();
            Row held = PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 9414, "udp"));
            assertThat(held != null && !PortLedger.isReleasing(held))
                .as("step 2: a held claim is never the sweep's business").isTrue();

            // 3. On a REMOTE host the daemon listing is the only witness: the probe must
            //    never be consulted (it would answer about the controller), so a probe
            //    that would claim everything bound still frees the unpublished claim.
            List<Object> remoteContainers = List.of(Map.of(
                "Names", List.of("/remote"),
                "Ports", List.of(
                    Map.of("IP", "127.0.0.1", "PrivatePort", 80, "PublicPort", 9415, "Type", "tcp"))));
            DockerReconciler.ReleasingSweep remote = DockerReconciler.sweepReleasingClaims(
                "sweep-edge", remoteContainers,
                (address, port, protocol) -> false);
            assertThat(remote.released())
                .as("step 3: the unpublished remote claim was freed on daemon evidence")
                .isEqualTo(1);
            assertThat(remote.retained())
                .as("step 3: the still-published remote claim was kept").isEqualTo(1);

            // 4. The attention projection: the surviving releasing claims surface as one
            //    warning per server once past the age threshold (threshold in the future
            //    makes every parked row "old" without forging timestamps).
            List<AttentionItem> items = new ArrayList<>();
            AttentionCollector.stuckReleasingPorts(items, Instant.now().plusSeconds(60));
            assertThat(items)
                .as("step 4: local and edge each raise one stuck-releasing warning")
                .hasSize(2);
            assertThat(items).allSatisfy(item -> {
                assertThat(item.severity()).isEqualTo("warning");
                assertThat(item.title().key()).isEqualTo("ports_releasing");
            });

            // Cleanup so other tests' ledgers stay unpolluted.
            for (Row claim : Models.get(PortAllocationModel.class).find()
                    .where(PortAllocationModel.PORT.gte(9411)).all()) {
                PortLedger.releaseObserved(claim);
            }
        });
    }

    private static Integer sweepOwnerRecord(String name) {
        Row db = Models.get(DatabaseModel.class).createEmptyRow();
        db.set(DatabaseModel.NAME, name);
        db.set(DatabaseModel.ENGINE, "postgres");
        db.set(DatabaseModel.DB_USER, "u");
        db.set(DatabaseModel.DB_PASSWORD, "p");
        db.set(DatabaseModel.DB_NAME, "d");
        Models.get(DatabaseModel.class).save(db);
        return db.get(DatabaseModel.ID);
    }

    /** The listing parser reads real /containers/json shapes; stopped containers bind nothing. */
    @Test
    void publishedPortsReadRealListingShapes() {
        List<DockerReconciler.PublishedPort> published = DockerReconciler.publishedPorts(List.of(
            Map.of("Names", List.of("/running"),
                "Ports", List.of(
                    Map.of("IP", "0.0.0.0", "PrivatePort", 5432, "PublicPort", 32768, "Type", "tcp"),
                    Map.of("PrivatePort", 9000))),   // unpublished exposed port: no PublicPort
            Map.of("Names", List.of("/stopped"), "Ports", List.of())));
        assertThat(published).as("only real publications are bindings").hasSize(1);
        assertThat(published.get(0).address()).as("0.0.0.0 folds to the whole-host spelling")
            .isEmpty();
        assertThat(published.get(0).port()).isEqualTo(32768);
        assertThat(DockerReconciler.stillPublished("127.0.0.1", 32768, "tcp", published))
            .as("a whole-host publication overlaps a loopback claim").isTrue();
        assertThat(DockerReconciler.stillPublished("", 32768, "udp", published))
            .as("protocol is a first-class discriminator").isFalse();
    }

    /**
     * The Docker-tier sweep doubles as that tier's host heartbeat, so it may only report
     * on hosts it can actually address. An INCUS host has no Docker daemon by
     * construction; sweeping it turned that structural refusal into a probe FAILURE every
     * hour, overwriting the host's real last_error -- and its sticky HOST_KEY_CHANGED
     * quarantine verdict, which only an explicit repin is allowed to clear.
     */
    @Test
    void theDockerSweepNeverProbesOrRestampsAnIncusHost() {
        Db.run(datasource, () -> {
            Row incus = Models.get(ServerModel.class).createEmptyRow();
            incus.set(ServerModel.NAME, "sweep-incus");
            incus.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
            incus.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
            Models.get(ServerModel.class).save(incus);
            Integer incusId = incus.get(ServerModel.ID);
            // The verdict a contradicted identity leaves behind, written through the
            // PRODUCTION path so the quarantine stamp lands the way it really does.
            HostProbe.recordFailure("sweep-incus", HostProbe.Outcome.failure(
                HostProbe.FailureKind.HOST_KEY_CHANGED, "incus tls identity changed"));

            // 1. The Docker host inventory names the docker hosts and ONLY those --
            //    a fix that simply swept nothing would pass everything below.
            ServerService servers = new ServerService(datasource);
            assertThat(servers.dockerNames())
                .as("step 1: the docker host inventory keeps the docker hosts")
                .contains(ServerService.LOCAL)
                .doesNotContain("sweep-incus");

            // 2. A full sweep leaves the incus host's stored verdict exactly as it was.
            Map<String, List<Finding>> swept = DockerReconciler.sweepAll(servers);
            assertThat(swept.keySet())
                .as("step 2: the sweep visited the docker host and skipped the incus one")
                .doesNotContain("sweep-incus");

            Row after = Models.get(ServerModel.class).findById(incusId);
            assertThat((String) after.get(ServerModel.LAST_ERROR_KIND))
                .as("step 2: the docker sweep must not restamp an incus host's typed"
                    + " verdict (this is the quarantine token; only a repin clears it)")
                .isEqualTo(HostProbe.FailureKind.HOST_KEY_CHANGED.token);
            assertThat((String) after.get(ServerModel.LAST_ERROR))
                .as("step 2: the incus host's real last error survives the docker sweep")
                .isEqualTo("incus tls identity changed");
            assertThat((Object) after.get(ServerModel.LAST_SEEN_AT))
                .as("step 2: the docker sweep never claims to have SEEN an incus host")
                .isNull();

            // 3. The quarantine itself is still in force, and it lives in its OWN column
            //    now -- so even a sweep that did restamp the transient verdict could no
            //    longer erase it.
            assertThat((Object) after.get(ServerModel.QUARANTINED_AT))
                .as("step 3: the quarantine stamp survives the sweep").isNotNull();
            assertThat(HostPins.isQuarantined(after, HostTrustSlot.transportOf(after)))
                .as("step 3: the incus host is still quarantined after the sweep")
                .isTrue();

            // 4. The OTHER half of the same story: an incus host is not simply
            //    unheartbeated. Its own 15-minute sweep probes it and records the verdict,
            //    so "no docker sweep touches it" does not mean "nothing watches it". This
            //    host addresses no daemon, so the probe FAILS -- and a failing probe must
            //    still land, or an unreachable incus host would stay silently green.
            ReapIncusControllers.HostOutcome outcome = ReapIncusControllers.sweepHost(after);
            assertThat(outcome.reachable())
                .as("step 4: there is no daemon behind this record, so the probe fails")
                .isFalse();
            Row probed = Models.get(ServerModel.class).findById(incusId);
            // Asserted as a CHANGE, not as "not null": step 2 left a verdict on this row,
            // so a not-null assertion here passed with the recording removed entirely --
            // it was vacuous, and this is the strengthened form.
            assertThat((String) probed.get(ServerModel.LAST_ERROR_KIND))
                .as("step 4: the incus sweep records its OWN typed verdict, replacing the"
                    + " stale one -- this is a real probe of this host, unlike the docker"
                    + " sweep's structural refusal")
                .isNotNull()
                .isNotEqualTo(HostProbe.FailureKind.HOST_KEY_CHANGED.token);
            assertThat((String) probed.get(ServerModel.LAST_ERROR))
                .as("step 4: and the stored detail is the probe's, not the stale text")
                .isNotEqualTo("incus tls identity changed");
            assertThat((Object) probed.get(ServerModel.LAST_SEEN_AT))
                .as("step 4: and it never claims to have SEEN a host that did not answer")
                .isNull();
            assertThat(HostPins.isQuarantined(probed, HostTrustSlot.transportOf(probed)))
                .as("step 4: the quarantine lives in its own column and survives this"
                    + " probe too -- only a repin clears it")
                .isTrue();
        });
    }

    // -- the live daemon, report-only -----------------------------------------

    @Test
    void liveSweepFindsAPlantedOrphanAndRemovesNothing() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");

        // 1. Plant a container claiming a site record that does not exist.
        String name = "hohenheim-reconciler-orphan-" + Long.toHexString(System.nanoTime());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", "alpine:latest");
        spec.put("Cmd", List.of("sleep", "60"));
        // AIDEV-NOTE: minted INSIDE this class's datasource scope on purpose. The owner
        // labels now carry the controller identity, and this class has two databases in
        // play (its own plus the runtime's default), so labelling outside the scope the
        // sweep runs in would compare two different controllers' tokens.
        spec.put("Labels", Db.supply(datasource, () -> OwnerLabels.of(SiteModel.MODEL_ID, 987_654)));
        docker.createContainer(name, spec, ContainerHardening.STRICT);

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
}
