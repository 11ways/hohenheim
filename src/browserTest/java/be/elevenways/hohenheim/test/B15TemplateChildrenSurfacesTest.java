package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.InstanceTemplateDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.InstanceTemplateVolumeModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.variable.StringVariableType;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.PlacedOperationMoves;
import be.elevenways.zenit.cms.common.render.table.SynthesizedRowActions;
import be.elevenways.hohenheim.instance.InstanceSnapshotOperations;
import be.elevenways.hohenheim.server.cms.InstanceSnapshotParts;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The additionally assigned B15 template children and snapshot offers, captured before their parts migration.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class B15TemplateChildrenSurfacesTest {
    private static final List<SurfaceCase> CASES = new ArrayList<>();
    private static final String PREFIX = "b15-children-";
    private static int templateOwner;

    @BeforeAll
    static void seed() throws Exception {
        TestDatabases.freshBootedDatasource();
        HohenheimTestBase.seedAuthenticatedAdmin();
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        AccessContext operator = access(admin.get(UserModel.ID), "Test Admin");
        int member = ApiSupport.user(PREFIX + "member@capture.test", "B15 Snapshot Member");
        AccessContext tenant = access(member, "B15 Snapshot Member");
        AccessContext outsider = access(ApiSupport.user(PREFIX + "outsider@capture.test", "B15 Outsider"), "B15 Outsider");
        Row template = Models.get(InstanceTemplateModel.class).createEmptyRow();
        template.set(InstanceTemplateModel.NAME, PREFIX + "template");
        template.set(InstanceTemplateModel.KIND, "hohenheim:application");
        template.set(InstanceTemplateModel.SETTINGS, Map.of("image", "alpine", "tag", "latest"));
        Models.get(InstanceTemplateModel.class).save(template);
        int owner = template.get(InstanceTemplateModel.ID);
        templateOwner = owner;

        Row variable = Models.get(InstanceTemplateVariableModel.class).createEmptyRow();
        variable.set(InstanceTemplateVariableModel.TEMPLATE_ID, owner);
        variable.set(InstanceTemplateVariableModel.KEY, "B15_CAPTURE");
        variable.set(InstanceTemplateVariableModel.LABEL, "Capture variable");
        variable.set(InstanceTemplateVariableModel.TYPE, StringVariableType.ID.toString());
        variable.set(InstanceTemplateVariableModel.SETTINGS, Map.of("max_length", 40));
        Models.get(InstanceTemplateVariableModel.class).save(variable);
        child("instance-template-variables", variable.get(InstanceTemplateVariableModel.ID), operator, tenant);

        Row file = Models.get(InstanceTemplateFileModel.class).createEmptyRow();
        file.set(InstanceTemplateFileModel.TEMPLATE_ID, owner);
        file.set(InstanceTemplateFileModel.CONTAINER_PATH, "/etc/b15/capture.conf");
        file.set(InstanceTemplateFileModel.CONTENT, "value={{B15_CAPTURE}}\n");
        file.set(InstanceTemplateFileModel.MODE, "0644");
        Models.get(InstanceTemplateFileModel.class).save(file);
        child("instance-template-files", file.get(InstanceTemplateFileModel.ID), operator, tenant);

        Row volume = Models.get(InstanceTemplateVolumeModel.class).createEmptyRow();
        volume.set(InstanceTemplateVolumeModel.TEMPLATE_ID, owner);
        volume.set(InstanceTemplateVolumeModel.NAME, "capture-data");
        volume.set(InstanceTemplateVolumeModel.CONTAINER_PATH, "/data");
        volume.set(InstanceTemplateVolumeModel.QUOTA_BYTES, 64L * 1024L * 1024L);
        Models.get(InstanceTemplateVolumeModel.class).save(volume);
        child("instance-template-volumes", volume.get(InstanceTemplateVolumeModel.ID), operator, tenant);

        Row database = Models.get(InstanceTemplateDatabaseModel.class).createEmptyRow();
        database.set(InstanceTemplateDatabaseModel.TEMPLATE_ID, owner);
        database.set(InstanceTemplateDatabaseModel.ENGINE, "postgres");
        database.set(InstanceTemplateDatabaseModel.ENV_PREFIX, "B15_DB");
        Models.get(InstanceTemplateDatabaseModel.class).save(database);
        child("instance-template-databases", database.get(InstanceTemplateDatabaseModel.ID), operator, tenant);

        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        instance.set(InstanceModel.NAME, PREFIX + "instance");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "tag", "latest", "command", "sleep 300"));
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(instance);
        int instanceId = instance.get(InstanceModel.ID);
        RecordGrants.grant(GrantSubjectType.USER, member, InstanceModel.MODEL_ID, instanceId, HohenheimAccess.VIEW, true);
        RecordGrants.grant(GrantSubjectType.USER, member, InstanceModel.MODEL_ID, instanceId, HohenheimAccess.SNAPSHOTS, true);
        String complete = snapshot(instanceId, InstanceSnapshotModel.STATUS_COMPLETE, "captured state");
        String failed = snapshot(instanceId, InstanceSnapshotModel.STATUS_FAILED, "failed capture");
        CASES.add(SurfaceCase.of("admin", "instance-snapshots", "operator", operator));
        CASES.add(SurfaceCase.of("admin", "instance-snapshots", "tenant", tenant).refusedFor(ZenitRefusalReason.FORBIDDEN));
        CASES.add(SurfaceCase.of("manage", "instance-snapshots", "operator", operator));
        CASES.add(SurfaceCase.of("manage", "instance-snapshots", "member", tenant));
        CASES.add(SurfaceCase.of("manage", "instance-snapshots", "outsider", outsider).refusedFor(ZenitRefusalReason.FORBIDDEN));
        for (String panel : List.of("admin", "manage")) {
            AccessContext viewer = panel.equals("admin") ? operator : tenant;
            String role = panel.equals("admin") ? "operator" : "member";
            CASES.add(SurfaceCase.of(panel, "instance-snapshots", role, viewer).onRecord(complete, "complete"));
            CASES.add(SurfaceCase.of(panel, "instance-snapshots", role, viewer).onRecord(failed, "failed"));
        }
    }

    @Test
    void compareTemplateChildrenAndSnapshotsAfterTheirMove() throws Exception {
        StringBuilder text = new StringBuilder();
        SurfaceBaselines before = SurfaceBaselines.load(getClass(), "/panel-surfaces/hohenheim-b15-children-before.txt")
            .placedOperations(PlacedOperationMoves.of(InstanceSnapshotOperations.RESTORE.id())
                .synthesized(InstanceSnapshotParts.SLUG, SynthesizedRowActions.DELETE, InstanceSnapshotOperations.DELETE.id()));
        for (SurfaceCase fixture : CASES) {
            PanelSurfaces capture = PanelSurfaces.capture(fixture);
            if (fixture.recordKey() != null) assertThat(capture.text()).as("record is loaded for " + fixture.name())
                .contains("record - found=true");
            text.append(capture.text()).append('\n');
            before.check(capture);
        }
        assertThat(CASES).as("every newly assigned legacy case is captured").hasSize(21);
        Path output = Path.of("build", "panel-surfaces", "hohenheim-b15-children-after.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, text);
        before.finish();
    }

    @Test
    void directAndPartialModelSavesShareTheTemplateAuthoringRules() {
        // 1. The template-file consumer is wired to the same save hook as instance files, including canonicalization.
        Model files = Models.get(InstanceTemplateFileModel.class);
        Row file = files.createEmptyRow();
        file.set(InstanceTemplateFileModel.TEMPLATE_ID, templateOwner);
        file.set(InstanceTemplateFileModel.CONTAINER_PATH, " /etc/b15/native.conf ");
        file.set(InstanceTemplateFileModel.CONTENT, "verbatim body\n");
        file.set(InstanceTemplateFileModel.MODE, "0640");
        files.save(file);
        assertThat(files.findById(file.get(InstanceTemplateFileModel.ID)).get(InstanceTemplateFileModel.CONTAINER_PATH))
            .as("direct save canonicalizes the staged path").isEqualTo("/etc/b15/native.conf");

        // 2. A one-column mode edit cannot bypass that model rule, and a refused edit moves nothing.
        Row mode = files.createEmptyRow();
        mode.set(InstanceTemplateFileModel.ID, file.get(InstanceTemplateFileModel.ID));
        mode.set(InstanceTemplateFileModel.MODE, "0899");
        assertThatThrownBy(() -> files.save(mode)).as("partial mode edit is validated by the shared rule")
            .isInstanceOf(Violations.class).hasMessageContaining("file_mode_format");
        assertThat(files.findById(file.get(InstanceTemplateFileModel.ID)).get(InstanceTemplateFileModel.CONTENT))
            .as("refused partial edit preserves the verbatim file body").isEqualTo("verbatim body\n");

        // 3. An absolute but climbing path is also refused on the model lane.
        Row path = files.createEmptyRow();
        path.set(InstanceTemplateFileModel.ID, file.get(InstanceTemplateFileModel.ID));
        path.set(InstanceTemplateFileModel.CONTAINER_PATH, "/etc/../escape");
        assertThatThrownBy(() -> files.save(path)).as("direct traversal edit cannot evade the form's rule")
            .isInstanceOf(Violations.class).hasMessageContaining("file_path_absolute");

        // 4. Variable identity uniqueness is the model's rule, not only the list writer's.
        Model variables = Models.get(InstanceTemplateVariableModel.class);
        Row duplicate = variables.createEmptyRow();
        duplicate.set(InstanceTemplateVariableModel.TEMPLATE_ID, templateOwner);
        duplicate.set(InstanceTemplateVariableModel.KEY, "B15_CAPTURE");
        assertThatThrownBy(() -> variables.save(duplicate)).as("direct variable collision is refused")
            .isInstanceOf(Violations.class).hasMessageContaining("variable_key_taken");

        // 5. Database prefixes retain their case-insensitive uniqueness and canonical spelling on direct saves.
        Model databases = Models.get(InstanceTemplateDatabaseModel.class);
        Row database = databases.createEmptyRow();
        database.set(InstanceTemplateDatabaseModel.TEMPLATE_ID, templateOwner);
        database.set(InstanceTemplateDatabaseModel.ENGINE, "postgres");
        database.set(InstanceTemplateDatabaseModel.ENV_PREFIX, " b15_other ");
        databases.save(database);
        assertThat(databases.findById(database.get(InstanceTemplateDatabaseModel.ID)).get(InstanceTemplateDatabaseModel.ENV_PREFIX))
            .as("database prefix is normalized before typed model validation").isEqualTo("B15_OTHER");
        Row collision = databases.createEmptyRow();
        collision.set(InstanceTemplateDatabaseModel.TEMPLATE_ID, templateOwner);
        collision.set(InstanceTemplateDatabaseModel.ENGINE, "postgres");
        collision.set(InstanceTemplateDatabaseModel.ENV_PREFIX, "b15_db");
        assertThatThrownBy(() -> databases.save(collision)).as("case-folded prefix collision is refused")
            .isInstanceOf(Violations.class).hasMessageContaining("template_database_prefix_taken");
    }

    private static void child(String slug, Object id, AccessContext operator, AccessContext tenant) {
        CASES.add(SurfaceCase.of("admin", slug, "operator", operator));
        CASES.add(SurfaceCase.of("admin", slug, "tenant", tenant).refusedFor(ZenitRefusalReason.FORBIDDEN));
        CASES.add(SurfaceCase.of("admin", slug, "operator", operator).onRecord(String.valueOf(id), "child"));
    }
    private static AccessContext access(int id, String name) {
        return AccessContext.of(TenantConduits.stubFor(new UserPrincipal(id, name)));
    }
    private static String snapshot(int owner, String status, String note) {
        Model model = Models.get(InstanceSnapshotModel.class);
        Row row = model.createEmptyRow();
        row.set(InstanceSnapshotModel.INSTANCE_ID, owner);
        row.set(InstanceSnapshotModel.STATUS, status);
        row.set(InstanceSnapshotModel.NOTE, note);
        row.set(InstanceSnapshotModel.TOTAL_BYTES, 42L);
        model.save(row);
        return String.valueOf(row.get(InstanceSnapshotModel.ID));
    }
}
