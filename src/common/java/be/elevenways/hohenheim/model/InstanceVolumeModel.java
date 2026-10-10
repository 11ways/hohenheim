package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.behaviour.OptimisticLockingBehaviour;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * One Hohenheim-owned host directory mounted into an instance -- THE volume mechanism,
 * replacing Docker named volumes for every tier.
 *
 * AIDEV-NOTE: {@link #HOST_PATH} is DERIVED ({@code <data_path>/volumes/<instance>/<name>})
 * and stored anyway, deliberately. It is evidence: an operator reading the row must see the
 * directory a reclaim would delete without re-running the derivation in their head, and a
 * changed data_path setting must be visible as a MISMATCH rather than silently re-pointing
 * every mount. Nothing reads it as the authority -- the deploy path re-derives.
 *
 * AIDEV-NOTE: names and container paths are OPERATOR IDENTIFIERS and are never localized
 * (phase-0 design section 5); only the field labels and refusals are microcopy.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public class InstanceVolumeModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("instance_volume");
    public static final Schema SCHEMA = new Schema();

    /** The volume every workspace carries: its {@code /home/site} data directory. */
    public static final String HOME_VOLUME = "home";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("instance")).build());

    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("volume_name"))
        .help(HohenheimMicrocopy.HELP.of("volume_name"))
        .build());

    public static final StringField CONTAINER_PATH = SCHEMA.addField(
        StringField.builder().name("container_path")
            .required()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("container_path"))
            .help(HohenheimMicrocopy.HELP.of("container_path"))
            .build());

    /** Enforced size cap; null = no cap (only legal on a backend that cannot enforce one). */
    public static final LongField QUOTA_BYTES = SCHEMA.addField(
        LongField.builder().name("quota_bytes")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("quota_bytes"))
            .help(HohenheimMicrocopy.HELP.of("quota_bytes"))
            .build());

    /** A volume no two releases may hold at once: its instance stops before the next starts. */
    public static final BooleanField EXCLUSIVE = SCHEMA.addField(
        BooleanField.builder("exclusive").defaultValue(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("exclusive_volume"))
            .help(HohenheimMicrocopy.HELP.of("exclusive_volume"))
            .build());

    public static final StringField HOST_PATH = SCHEMA.addField(
        StringField.builder().name("host_path")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("host_path"))
            .build());

    public static final LongField USED_BYTES = SCHEMA.addField(
        LongField.builder().name("used_bytes").build());

    public static final DateTimeField OBSERVED_AT = SCHEMA.addField(
        DateTimeField.builder().name("observed_at").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    /** Usage observations are bookkeeping; only declaration edits move the reviewed version. */
    public static final OptimisticLockingBehaviour LOCKING = SCHEMA.addBehaviour(
        OptimisticLockingBehaviour.create().outsideVersion(USED_BYTES, OBSERVED_AT));
    public static final IntegerField VERSION = LOCKING.versionField();

    /** @return this instance's volumes, name-ordered */
    public List<Row> findByInstanceId(int instanceId) {
        return find().where(INSTANCE_ID.eq(instanceId)).orderBy(NAME, SortOrder.ASC).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "InstanceVolume"; }

    @Override
    public String getTableName() { return "instance_volumes"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
