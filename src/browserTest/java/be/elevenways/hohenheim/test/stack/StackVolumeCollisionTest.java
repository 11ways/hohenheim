package be.elevenways.hohenheim.test.stack;

import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.server.stack.StackSpec;
import be.elevenways.hohenheim.server.stack.StackVolumes;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ambiguous stack volume name {@code <stack handle>-<mount>}, closed on both sides
 * without renaming a single deployed volume: a NEW declaration whose materialized name
 * another stack already uses is refused at the write funnel, and a purge removes exactly
 * this stack's declared volumes -- never a sibling whose name merely shares the prefix.
 *
 * Hermetic: records in a fresh SQLite, the daemon is {@link FakeDockerDaemon}.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class StackVolumeCollisionTest {

    private static SqlDatasource datasource;
    private static FakeDockerDaemon daemon;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        daemon = new FakeDockerDaemon();
        daemon.install();
    }

    @AfterAll
    static void tearDown() {
        FakeDockerDaemon.restore();
        if (daemon != null) {
            daemon.close();
            daemon = null;
        }
    }

    @Test
    void aNewDeclarationThatWouldShareAnotherStacksVolumeIsRefused() {
        Db.run(datasource, () -> {
            // 1. Stack "va" mounts "b-c": it materializes <handle of va>-b-c.
            int va = stack("va");
            service(va, "web", mount("b-c", null));

            // 2. Stack "va-b" mounting "c" would materialize the very same name -- refused on
            //    the mount list, naming the volume, before anything is stored.
            int vab = stack("va-b");
            assertThatThrownBy(() -> service(vab, "db", mount("c", null)))
                .as("step 2: a mount whose volume another stack owns is refused")
                .isInstanceOfSatisfying(Violations.class, refused ->
                    assertThat(hasViolation(refused, "mounts", "stack_volume_name_taken"))
                        .as("step 2: on the mounts, with the collision key").isTrue());
            assertThat(Models.get(StackServiceModel.class).findByStackId(vab))
                .as("step 2: nothing was stored").isEmpty();

            // 3. A name nobody uses passes on the same stack.
            assertThatCode(() -> service(vab, "db", mount("d", null)))
                .as("step 3: a free volume name is accepted").doesNotThrowAnyException();

            // 4. Adopting another stack's MANAGED volume by its external name is the same
            //    collision from the other side, and is refused too.
            int vx = stack("vx");
            String managed = StackInstances.volumeName("va",
                new StackSpec.MountSpec(StackServiceModel.MOUNT_VOLUME, "b-c", "/data", null));
            assertThatThrownBy(() -> service(vx, "app", mount("any", managed)))
                .as("step 4: adopting another stack's managed volume is refused")
                .isInstanceOf(Violations.class);

            // 5. Two stacks adopting the SAME external volume is a deliberate share.
            int vy = stack("vy");
            assertThatCode(() -> {
                service(vx, "app", mount("shared", "operator-shared-volume"));
                service(vy, "app", mount("shared", "operator-shared-volume"));
            }).as("step 5: a shared external volume passes").doesNotThrowAnyException();

            // 6. A RENAME re-derives every materialized name: "vd" owns <handle of vd>-e-f,
            //    and renaming "vr" (mounting "f") to the free name "vd-e" would land on
            //    exactly that volume. Refused on the name.
            int vd = stack("vd");
            service(vd, "web", mount("e-f", null));
            int vr = stack("vr");
            service(vr, "web", mount("f", null));
            assertThatThrownBy(() -> rename(vr, "vd-e"))
                .as("step 6: a rename that collides is refused")
                .isInstanceOfSatisfying(Violations.class, refused ->
                    assertThat(hasViolation(refused, "name", "stack_volume_name_taken"))
                        .as("step 6: on the stack name").isTrue());

            // 7. Re-saving a service whose mount list is unchanged judges nothing new.
            Row web = Models.get(StackServiceModel.class).findByStackId(va).get(0);
            web.set(StackServiceModel.IMAGE, "nginx:1.27");
            assertThatCode(() -> Models.get(StackServiceModel.class).save(web))
                .as("step 7: an unrelated edit passes").doesNotThrowAnyException();
        });
    }

    @Test
    void aPurgeRemovesExactlyThisStacksVolumesAndNeverAPrefixSibling() {
        Db.run(datasource, () -> {
            // 1. Stack "pa" mounts "data" and "logs"; stack "pa-data" mounts "x". The OLD
            //    sweep of "pa" removed every volume starting with <handle of pa>- -- which
            //    includes <handle of pa-data>-x.
            int pa = stack("pa");
            service(pa, "web", mount("data", null), mount("logs", null));
            int sibling = stack("pa-data");
            service(sibling, "web", mount("x", null));

            String paData = name("pa", "data");
            String paLogs = name("pa", "logs");
            String siblingX = name("pa-data", "x");
            String paStray = name("pa", "undeclared");
            daemon.seedVolume(paData, OwnerLabels.of(StackModel.MODEL_ID, pa));
            // An older build's colliding volume: declared by "pa" but labelled to the sibling.
            daemon.seedVolume(paLogs, OwnerLabels.of(StackModel.MODEL_ID, sibling));
            daemon.seedVolume(siblingX, OwnerLabels.of(StackModel.MODEL_ID, sibling));
            daemon.seedVolume(paStray, Map.of());

            // 2. The declared set is exactly the two mounts of "pa".
            assertThat(StackVolumes.declaredBy(pa, "pa", List.of()))
                .as("step 2: the purge set is the declared names, not a prefix")
                .containsExactlyInAnyOrder(paData, paLogs);

            // 3. The purge removes the one volume that is both declared AND attributed.
            List<String> removed;
            try {
                removed = StackVolumes.purge(new DockerClient(), pa, "pa",
                    StackVolumes.declaredBy(pa, "pa", List.of()));
            } catch (IOException failed) {
                throw new AssertionError(failed);
            }
            assertThat(removed).as("step 3: only the attributed declared volume goes")
                .containsExactly(paData);
            assertThat(daemon.hasVolume(paData)).as("step 3: removed").isFalse();

            // 4. Everything else survives: the prefix sibling, the mislabelled collision,
            //    and a prefix-matching volume nobody declares.
            assertThat(daemon.hasVolume(siblingX))
                .as("step 4: another stack's volume sharing the prefix survives").isTrue();
            assertThat(daemon.hasVolume(paLogs))
                .as("step 4: a declared name labelled to another stack survives").isTrue();
            assertThat(daemon.hasVolume(paStray))
                .as("step 4: an undeclared prefix match survives").isTrue();
        });
    }

    // -- fixtures ----------------------------------------------------------------

    private static String name(String stack, String mount) {
        return StackInstances.volumeName(stack,
            new StackSpec.MountSpec(StackServiceModel.MOUNT_VOLUME, mount, "/" + mount, null));
    }

    private static int stack(String name) {
        StackModel model = Models.get(StackModel.class);
        Row stack = model.createEmptyRow();
        stack.set(StackModel.NAME, name);
        stack.set(StackModel.ENABLED, false);
        model.save(stack);
        return stack.get(StackModel.ID);
    }

    private static void rename(int stackId, String name) {
        Row stack = Models.get(StackModel.class).findById(stackId);
        stack.set(StackModel.NAME, name);
        Models.get(StackModel.class).save(stack);
    }

    private static Row mount(String name, @Nullable String external) {
        Row row = new Row();
        row.set(StackServiceModel.MOUNT_TYPE, StackServiceModel.MOUNT_VOLUME);
        row.set(StackServiceModel.MOUNT_NAME, name);
        row.set(StackServiceModel.MOUNT_PATH, "/" + name);
        if (external != null) {
            row.set(StackServiceModel.MOUNT_EXTERNAL, external);
        }
        return row;
    }

    private static void service(int stackId, String name, Row... mounts) {
        StackServiceModel model = Models.get(StackServiceModel.class);
        Row service = model.createEmptyRow();
        service.set(StackServiceModel.STACK_ID, stackId);
        service.set(StackServiceModel.NAME, name);
        service.set(StackServiceModel.ENABLED, true);
        service.set(StackServiceModel.IMAGE, "alpine:latest");
        service.setRecords(StackServiceModel.MOUNTS, new ArrayList<>(List.of(mounts)));
        model.save(service);
    }

    private static boolean hasViolation(Violations violations, String field, String key) {
        for (Violation violation : violations.all()) {
            if (field.equals(violation.fieldName()) && key.equals(violation.message().key())) {
                return true;
            }
        }
        return false;
    }
}
