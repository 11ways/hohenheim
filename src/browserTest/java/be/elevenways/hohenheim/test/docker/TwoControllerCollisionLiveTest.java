package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerReconciler;
import be.elevenways.hohenheim.server.docker.DockerReconciler.Bucket;
import be.elevenways.hohenheim.server.docker.DockerReconciler.Finding;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE regression test for the hazard the whole namespace exists for: TWO controllers
 * against ONE real Docker daemon, each holding a record with the SAME id.
 *
 * AIDEV-NOTE: this is the shape that was found in the wild, twice. Parallel test forks
 * (each a fresh SQLite, so each numbering from 1) minted the same
 * {@code hohenheim-instance-1}, and because ownership was decided by (model, id) alone,
 * one fork's guard attributed the OTHER fork's RUNNING container to its own record and
 * force-removed it. The same two-controller shape is a native hohenheim plus a remote
 * workstation suite driving one daemon (docs/deploy-native.md). Every assertion here is
 * about STATE on a real daemon and about the IDENTITY named in a refusal, never a status.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class TwoControllerCollisionLiveTest {

    private static final Path SOCKET = Path.of("/var/run/docker.sock");
    private static final String IMAGE = "alpine:latest";

    /** The record id BOTH controllers allocate: the collision's whole premise. */
    private static final int SHARED_RECORD_ID = 1;

    private static SqliteDatasource controllerA;
    private static SqliteDatasource controllerB;
    private static DockerClient docker;

    @BeforeAll
    static void setUp() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        docker = new DockerClient();
        LiveLane.requireImage(new DockerClient(), IMAGE);

        controllerA = freshController("a");
        controllerB = freshController("b");
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterAll
    static void tearDown() {
        if (docker == null) {
            return;
        }
        for (SqliteDatasource controller : List.of(controllerA, controllerB)) {
            if (controller == null) {
                continue;
            }
            String handle = Db.supply(controller,
                () -> ControllerScope.handle(ControllerScope.KIND_INSTANCE, SHARED_RECORD_ID));
            try {
                docker.removeContainer(handle, true);
            } catch (IOException ignored) {
                // Already gone: the journey's own removals are the expected outcome.
            }
        }
    }

    /**
     * Two controllers, one daemon, record #1 on both sides: distinct workloads, and each
     * guard able to refuse the other's.
     */
    @Test
    void twoControllersWithTheSameRecordIdNeverTouchEachOthersWorkload() throws Exception {
        String tokenA = Db.supply(controllerA, ControllerIdentity::token);
        String tokenB = Db.supply(controllerB, ControllerIdentity::token);

        // 1. Two databases, two identities. Nothing else in this test means anything
        //    if these are equal.
        assertThat(tokenA)
            .as("step 1: each control-plane database mints its OWN controller identity")
            .isNotEqualTo(tokenB);

        // 2. Both controllers hold a record numbered 1, and the handles they derive from
        //    it are DIFFERENT names. This is the collision, gone.
        int idA = Db.supply(controllerA, () -> record("workload-a"));
        int idB = Db.supply(controllerB, () -> record("workload-b"));
        assertThat(idA).as("step 2: controller A's record is #1").isEqualTo(SHARED_RECORD_ID);
        assertThat(idB).as("step 2: controller B's record is #1 too -- the same id")
            .isEqualTo(SHARED_RECORD_ID);

        String handleA = Db.supply(controllerA,
            () -> ControllerScope.handle(ControllerScope.KIND_INSTANCE, idA));
        String handleB = Db.supply(controllerB,
            () -> ControllerScope.handle(ControllerScope.KIND_INSTANCE, idB));
        assertThat(handleA)
            .as("step 2: the same record id under two controllers is two daemon names")
            .isNotEqualTo(handleB);
        assertThat(handleA).as("step 2: A's handle carries A's token").contains(tokenA);
        assertThat(handleB).as("step 2: B's handle carries B's token").contains(tokenB);

        // 3. Both workloads RUN on the one daemon at the same time. Before the namespace
        //    the second create could not even land: the name was taken.
        Map<String, String> labelsA = Db.supply(controllerA,
            () -> OwnerLabels.of(InstanceModel.MODEL_ID, idA));
        Map<String, String> labelsB = Db.supply(controllerB,
            () -> OwnerLabels.of(InstanceModel.MODEL_ID, idB));
        run(handleA, labelsA);
        run(handleB, labelsB);
        assertThat(running(handleA)).as("step 3: A's workload is running").isTrue();
        assertThat(running(handleB))
            .as("step 3: B's workload runs alongside it on the SAME daemon").isTrue();
        assertThat(labelsA.get(OwnerLabels.CONTROLLER))
            .as("step 3: the owner labels carry the minting controller")
            .isEqualTo(tokenA);

        // 4. THE GUARD. Controller B asks to remove the name A minted, for ITS record #1
        //    -- byte-identical model and id. It must REFUSE, and name A as the owner.
        //    This is the exact call that used to force-remove a running foreign container.
        String refusal = Db.supply(controllerB, () -> {
            try {
                OwnerLabels.removeIfOwnedBy(docker, handleA, InstanceModel.MODEL_ID, idB);
                return null;   // it removed a foreign controller's container
            } catch (IOException e) {
                return e.getMessage();
            }
        });
        assertThat(running(handleA))
            .as("step 4: A's workload is STILL RUNNING -- B never force-removed it")
            .isTrue();
        assertThat(refusal)
            .as("step 4: B's guard REFUSED A's container even though the record ids match")
            .isNotNull()
            .contains("REFUSED to remove container '" + handleA + "'")
            .contains("of controller '" + tokenA + "'")
            .contains("not this controller '" + tokenB + "'");

        // 5. Each controller's sweep sees only its own: B classifies its own container
        //    OWNED and A's as a collision that names A.
        Db.run(controllerB, () -> {
            Finding mine = classify(handleB);
            assertThat(mine.bucket())
                .as("step 5: B's own container is OWNED in B's sweep").isEqualTo(Bucket.OWNED);
            Finding theirs = classify(handleA);
            assertThat(theirs.bucket())
                .as("step 5: A's container is a COLLISION in B's sweep, never OWNED")
                .isEqualTo(Bucket.FOREIGN_COLLIDING);
            assertThat(theirs.detail())
                .as("step 5: and the finding names the controller that owns it")
                .contains(tokenA);
        });

        // 6. The removal that IS legitimate still works, and takes exactly one container:
        //    B removes B's, A's keeps running.
        boolean removed = Db.supply(controllerB, () -> {
            try {
                return OwnerLabels.removeIfOwnedBy(docker, handleB, InstanceModel.MODEL_ID, idB);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(removed).as("step 6: B removes its OWN workload").isTrue();
        assertThat(exists(handleB)).as("step 6: B's container is gone").isFalse();
        assertThat(running(handleA))
            .as("step 6: A's workload survived B's whole lifecycle").isTrue();
    }

    // -- helpers --------------------------------------------------------------

    private static SqliteDatasource freshController(String suffix) throws Exception {
        File db = File.createTempFile("hohenheim-two-controller-" + suffix, ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        return datasource;
    }

    private static int record(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static Finding classify(String handle) {
        Map<?, ?> labels = null;
        try {
            Map<String, Object> inspect = docker.inspectContainer(handle);
            if (inspect.get("Config") instanceof Map<?, ?> config
                && config.get("Labels") instanceof Map<?, ?> found) {
                labels = found;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return DockerReconciler.classify(DockerReconciler.KIND_CONTAINER, handle, labels,
            new DockerReconciler.ModelRecords());
    }

    private static void run(String handle, Map<String, String> labels) throws IOException {
        try {
            docker.removeContainer(handle, true);
        } catch (IOException ignored) {
            // Nothing to clean: a leftover from an interrupted run is the only case.
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", IMAGE);
        spec.put("Cmd", List.of("sleep", "120"));
        spec.put("Labels", labels);
        String id = docker.createContainer(handle, spec, ContainerHardening.STRICT);
        docker.startContainer(id);
    }

    private static boolean running(String handle) throws IOException {
        if (!exists(handle)) {
            return false;   // removed, which is a state answer, not an error
        }
        Map<String, Object> inspect = docker.inspectContainer(handle);
        return inspect.get("State") instanceof Map<?, ?> state
            && Boolean.TRUE.equals(state.get("Running"));
    }

    private static boolean exists(String handle) {
        try {
            docker.inspectContainer(handle);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
