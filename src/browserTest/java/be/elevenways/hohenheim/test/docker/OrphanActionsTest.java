package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OrphanActions;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The EXPLICIT orphan authority against a real daemon: removal re-verifies live truth,
 * refuses what live classification disagrees about, refuses volumes always, and the
 * host is asserted directly (container gone or survived at the daemon).
 */
class OrphanActionsTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-orphan-actions-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void theOrphanAuthorityRemovesReVerifiedOrphansAndRefusesEverythingElse() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker), "alpine:latest not present locally");

        Db.run(datasource, () -> {
            ServerModel.localServerId();
            String orphanName = "hohenheim-instance-987654";
            String ownedName = "hohenheim-instance-owned-probe";
            try {
                // 1. A container owner-labelled to a record that does NOT exist: the
                //    exact debris a stale controller leaves behind.
                create(docker, orphanName, OwnerLabels.of(InstanceModel.MODEL_ID, 987654));
                Row orphanFinding = finding("container", orphanName,
                    ReconcileFindingModel.BUCKET_ORPHANED);

                // 2. The explicit authority removes it -- and the DAEMON confirms.
                OrphanActions.removeOrphan(orphanFinding);
                assertThat(catchThrowable(() -> docker.inspectContainer(orphanName)))
                    .as("step 2: the orphan is ABSENT at the daemon, not merely delisted")
                    .isInstanceOfSatisfying(DockerClient.ApiException.class,
                        e -> assertThat(e.isNotFound()).isTrue());
                assertThat(Models.get(ReconcileFindingModel.class)
                        .findById(orphanFinding.get(ReconcileFindingModel.ID)))
                    .as("step 2: and the acted-on finding row is gone").isNull();

                // 3. A VOLUME finding is refused categorically -- the one unrecoverable
                //    resource never gets a delete button.
                Row volumeFinding = finding("volume", "hohenheim-instance-987654-vol-data",
                    ReconcileFindingModel.BUCKET_ORPHANED);
                assertThat(catchThrowable(() -> OrphanActions.removeOrphan(volumeFinding)))
                    .as("step 3: volumes are refused")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key())
                                .isEqualTo("orphan_volume_refused")));

                // 4. A STALE finding claiming a LIVE record's container is an orphan is
                //    refused by live re-classification, and the container SURVIVES.
                Row record = Models.get(InstanceModel.class).createEmptyRow();
                record.set(InstanceModel.NAME, "orphan-probe-live");
                record.set(InstanceModel.KIND, "hohenheim:docker_container");
                record.set(InstanceModel.SETTINGS, Map.of("image", "alpine"));
                Models.get(InstanceModel.class).save(record);
                create(docker, ownedName,
                    OwnerLabels.of(InstanceModel.MODEL_ID, record.get(InstanceModel.ID)));
                Row staleFinding = finding("container", ownedName,
                    ReconcileFindingModel.BUCKET_ORPHANED);
                assertThat(catchThrowable(() -> OrphanActions.removeOrphan(staleFinding)))
                    .as("step 4: live re-classification vetoes the stale report")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key())
                                .isEqualTo("orphan_reclassified")));
                assertThat(catchThrowable(() -> docker.inspectContainer(ownedName)))
                    .as("step 4: the live record's container SURVIVES at the daemon")
                    .isNull();

                // 5. A finding whose bucket is not orphaned is refused outright.
                Row ownedFinding = finding("container", ownedName,
                    ReconcileFindingModel.BUCKET_OWNED);
                assertThat(catchThrowable(() -> OrphanActions.removeOrphan(ownedFinding)))
                    .as("step 5: only orphans are removable")
                    .isInstanceOf(Violations.class);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                remove(docker, orphanName);
                remove(docker, ownedName);
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    private static void create(DockerClient docker, String name, Map<String, String> labels)
            throws IOException {
        docker.createContainer(name, Map.of(
            "Image", "alpine:latest",
            "Cmd", List.of("sleep", "120"),
            "Labels", labels), ContainerHardening.STRICT);
    }

    private static Row finding(String kind, String name, String bucket) {
        Row row = Models.get(ReconcileFindingModel.class).createEmptyRow();
        row.set(ReconcileFindingModel.SERVER_NAME, "local");
        row.set(ReconcileFindingModel.KIND, kind);
        row.set(ReconcileFindingModel.RESOURCE_NAME, name);
        row.set(ReconcileFindingModel.BUCKET, bucket);
        row.set(ReconcileFindingModel.EVIDENCE, "owner_label");
        Models.get(ReconcileFindingModel.class).save(row);
        return row;
    }

    private static void remove(DockerClient docker, String name) {
        try {
            docker.removeContainer(name, true);
        } catch (IOException ignored) {
            // already gone
        }
    }

    private static boolean imagePresent(DockerClient docker) throws IOException {
        for (Object image : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) image).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains("alpine:latest")) {
                return true;
            }
        }
        return false;
    }
}
