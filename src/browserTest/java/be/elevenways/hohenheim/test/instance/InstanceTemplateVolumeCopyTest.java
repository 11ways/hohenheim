package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVolumeModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The phase-0 contract of 4.6: a template's declared volumes are COPIED onto the instance
 * at create time, the {@code instance_template_variables} to {@code instance_variables}
 * precedent.
 *
 * AIDEV-NOTE: the defect this closes. {@code instance_template_volumes} existed, was
 * cascade-deleted with its template, and was read by NOTHING -- an instance created from a
 * template that declares volumes came up with an empty Volumes tab, no quota and no bind,
 * and every surface reported the create as a success. The copy is DECLARATIONS only: the
 * host directories behind them are minted by the first deploy on the backend that host
 * actually has, and a template carries no volume CONTENT to bring along (its authored
 * content is {@code instance_template_files}, which {@code copyFiles} already carried).
 *
 * <p>Hermetic: a create reaches no daemon and no filesystem, so both halves -- the copy
 * and every refusal -- are proven without a host.</p>
 */
class InstanceTemplateVolumeCopyTest extends HohenheimTestBase {

    private static final String PREFIX = "tpl-volume-";

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            int instanceId = row.get(InstanceModel.ID);
            Models.get(InstanceVolumeModel.class).find()
                .where(InstanceVolumeModel.INSTANCE_ID.eq(instanceId)).delete();
            instances.delete(instanceId);
        }
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find().where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            int templateId = row.get(InstanceTemplateModel.ID);
            Models.get(InstanceTemplateVolumeModel.class).find()
                .where(InstanceTemplateVolumeModel.TEMPLATE_ID.eq(templateId)).delete();
            templates.delete(templateId);
        }
    }

    /**
     * Every declared volume of the template becomes a declaration of the instance, with the
     * facts that decide what gets mounted intact -- and a template declaring none creates
     * an instance declaring none.
     */
    @Test
    void aTemplatesDeclaredVolumesBecomeTheInstancesOwnDeclarations() {

        // 1. A template that declares two volumes: one quota'd and exclusive, one plain.
        int templateId = template(PREFIX + "carrier", ApplicationKind.ID.toString());
        volume(templateId, "data", "/var/lib/app", 256L * 1024L * 1024L, true);
        volume(templateId, "cache", "/var/cache/app", null, false);

        // 2. Creating from it copies BOTH declarations onto the new instance. Before this
        //    contract was implemented the create succeeded with an empty Volumes tab.
        int instanceId = create(templateId, PREFIX + "carried");
        List<Row> declared = InstanceVolumes.declaredFor(instanceId);
        assertThat(declared)
            .as("step 2: both template volumes were copied onto the instance")
            .hasSize(2);

        // 3. And the facts that decide what the deploy mounts travel with them: the
        //    container path (without it the volume is never bound), the quota (without it
        //    the directory is uncapped) and the exclusivity (which decides whether two
        //    workloads may hold it at once).
        Row data = byName(declared, "data");
        assertThat((String) data.get(InstanceVolumeModel.CONTAINER_PATH))
            .as("step 3: the container path rides the copy").isEqualTo("/var/lib/app");
        assertThat((Long) data.get(InstanceVolumeModel.QUOTA_BYTES))
            .as("step 3: so does the quota").isEqualTo(256L * 1024L * 1024L);
        assertThat((Boolean) data.get(InstanceVolumeModel.EXCLUSIVE))
            .as("step 3: and the exclusivity").isTrue();
        assertThat(InstanceVolumes.hasExclusive(instanceId))
            .as("step 3: which the exclusivity question answers off the copied row")
            .isTrue();
        Row cache = byName(declared, "cache");
        assertThat((Long) cache.get(InstanceVolumeModel.QUOTA_BYTES))
            .as("step 3: an unquota'd template volume stays unquota'd").isNull();

        // 4. The copy funnels through InstanceVolumes.declare, so the host path is derived
        //    and stamped as evidence rather than left for the deploy to invent -- which is
        //    what makes the mount set the deploy derives the copied set.
        assertThat((String) data.get(InstanceVolumeModel.HOST_PATH))
            .as("step 4: the host directory a reclaim would remove is recorded")
            .endsWith("/volumes/" + instanceId + "/data");
        assertThat(InstanceVolumes.declaredMounts(instanceId))
            .as("step 4: and the mounts the deploy will hand the daemon are the copied set")
            .containsEntry(InstanceVolumes.hostPathFor(instanceId, "data"), "/var/lib/app")
            .containsEntry(InstanceVolumes.hostPathFor(instanceId, "cache"), "/var/cache/app")
            .hasSize(2);

        // 5. FALSIFIED: a template of the same kind declaring NO volumes creates an
        //    instance declaring none, so step 2 is the declarations being carried rather
        //    than a create that mints volumes of its own.
        int bareId = template(PREFIX + "bare", ApplicationKind.ID.toString());
        assertThat(InstanceVolumes.declaredFor(create(bareId, PREFIX + "bare-instance")))
            .as("step 5: a template declaring no volumes creates an instance with none")
            .isEmpty();
    }

    /**
     * A template volume the create could not honour REFUSES the create by name, instead of
     * producing an instance whose volumes are silently missing.
     */
    @Test
    void aTemplateVolumeThatCannotBeHonouredRefusesTheCreateAndPersistsNothing() {

        // 1. A kind that mounts no volumes at all: the declaration would be dead data on
        //    every instance ever created from this template, so the create refuses by name
        //    rather than dropping it.
        int wrongKind = template(PREFIX + "wrong-kind", "hohenheim:docker_container");
        volume(wrongKind, "data", "/var/lib/app", null, false);
        assertThat(violationKeys(refusedCreate(wrongKind, PREFIX + "wrong-kind-instance")))
            .as("step 1: a volume beside a kind that mounts none is refused, named")
            .contains("template_volume_kind_unsupported");
        assertNothingPersisted(PREFIX + "wrong-kind-instance");

        // 2. THE SILENT-EMPTY SHAPE, closed at the AUTHORING layer: a declaration with no
        //    container path is skipped by the mount derivation, so it would copy cleanly,
        //    appear in the Volumes tab and never be mounted by anything. The template
        //    model refuses to store one at all. The create checks it again anyway
        //    (template_volume_container_path_required) -- a row that reached the table by
        //    any other route must not become an instance volume nothing mounts.
        int noPath = template(PREFIX + "no-path", ApplicationKind.ID.toString());
        assertThat(catchThrowable(() -> volume(noPath, "data", "   ", null, false)))
            .as("step 2: a template volume with no container path cannot be authored")
            .isInstanceOf(Violations.class);

        // 3. Two template volumes at one container path would hand the daemon two binds at
        //    one path. The refusal is the volume tier's OWN collision rule, asked before
        //    the instance row exists rather than at the first deploy.
        int clash = template(PREFIX + "clash", ApplicationKind.ID.toString());
        volume(clash, "data", "/var/lib/app", null, false);
        volume(clash, "cache", "/var/lib/app", null, false);
        Throwable collision = refusedCreate(clash, PREFIX + "clash-instance");
        assertThat(violationKeys(collision))
            .as("step 3: two volumes at one path are refused, named")
            .contains("volume_container_path_conflict");
        assertThat(String.valueOf(collision.getMessage()))
            .as("step 3: and both offending volumes are named, so it is actionable")
            .contains("data").contains("cache");
        assertNothingPersisted(PREFIX + "clash-instance");

        // 4. A name that is not a plain directory name is the containment guarantee, and
        //    it is the volume tier's rule here too -- a template must not be a way to
        //    declare a path that climbs out of the volume root.
        int escaping = template(PREFIX + "escape", ApplicationKind.ID.toString());
        volume(escaping, "../etc", "/var/lib/app", null, false);
        assertThat(violationKeys(refusedCreate(escaping, PREFIX + "escape-instance")))
            .as("step 4: a traversing volume name is refused, named")
            .contains("volume_name_invalid");
        assertNothingPersisted(PREFIX + "escape-instance");

        // 5. A quota no backend could apply is refused too: zero would be stored, shown as
        //    a limit and never applied (mountsFor only sets a positive one).
        int badQuota = template(PREFIX + "bad-quota", ApplicationKind.ID.toString());
        volume(badQuota, "data", "/var/lib/app", 0L, false);
        assertThat(violationKeys(refusedCreate(badQuota, PREFIX + "bad-quota-instance")))
            .as("step 5: a non-positive quota is refused, named")
            .contains("volume_quota_invalid");
        assertNothingPersisted(PREFIX + "bad-quota-instance");

        // 6. FALSIFIED: correcting the one offending fact makes the very same template
        //    create, so every refusal above is about the declaration and not about a
        //    template that declares volumes at all.
        Row fixed = Models.get(InstanceTemplateVolumeModel.class).find()
            .where(InstanceTemplateVolumeModel.TEMPLATE_ID.eq(badQuota)).first();
        fixed.set(InstanceTemplateVolumeModel.QUOTA_BYTES, 1024L);
        Models.get(InstanceTemplateVolumeModel.class).save(fixed);
        assertThat(InstanceVolumes.declaredFor(create(badQuota, PREFIX + "bad-quota-fixed")))
            .as("step 6: the corrected template creates and carries its volume")
            .hasSize(1);
    }

    // -- fixtures ---------------------------------------------------------------

    /** An approved template of one kind; settings are the create's, not this test's, business. */
    private static int template(String name, String kind) {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row row = templates.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.KIND, kind);
        row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<String, Object>());
        row.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
        row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, 1L);
        templates.save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static void volume(int templateId, String name, String containerPath,
                               Long quotaBytes, boolean exclusive) {
        Model volumes = Models.get(InstanceTemplateVolumeModel.class);
        Row row = volumes.createEmptyRow();
        row.set(InstanceTemplateVolumeModel.TEMPLATE_ID, templateId);
        row.set(InstanceTemplateVolumeModel.NAME, name);
        row.set(InstanceTemplateVolumeModel.CONTAINER_PATH, containerPath);
        row.set(InstanceTemplateVolumeModel.QUOTA_BYTES, quotaBytes);
        row.set(InstanceTemplateVolumeModel.EXCLUSIVE, exclusive);
        volumes.save(row);
    }

    /** The production create funnel, as an in-process caller reaches it. */
    private static int create(int templateId, String name) {
        return new InstanceTemplates().createFromTemplate(
            Models.get(InstanceTemplateModel.class).findById(templateId),
            name, null, Map.of(), null);
    }

    private static Throwable refusedCreate(int templateId, String name) {
        return catchThrowable(() -> create(templateId, name));
    }

    /** No instance by that name exists at all -- a refused create leaves no record. */
    private static void assertNothingPersisted(String name) {
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(name)).count())
            .as("the refused create persisted NOTHING (%s)", name)
            .isZero();
    }

    private static Row byName(List<Row> volumes, String name) {
        for (Row volume : volumes) {
            if (name.equals(volume.get(InstanceVolumeModel.NAME))) {
                return volume;
            }
        }
        throw new AssertionError("no copied volume named " + name);
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
}
