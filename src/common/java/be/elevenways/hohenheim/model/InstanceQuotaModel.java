package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.validation.Violations;

/**
 * Per-owner instance-count override: one row per packed manage-grant subject set
 * (the DatabaseModel.MEMORY_LIMIT_MB precedent -- a real nullable column, never a
 * settings-map key), consulted by the reserve hook ahead of the
 * {@code hohenheim.quota.max_instances_per_owner} default.
 *
 * @author Jelle De Loecker
 */
public class InstanceQuotaModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_quota");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    /**
     * The owner: the packed manage-grant subject set (HohenheimAccess.packSubjects --
     * sorted, newline-joined), "" for the operator. One override per owner, enforced
     * by the unique index the migration declares.
     */
    public static final StringField SUBJECTS = SCHEMA.addField(
        StringField.builder().name("subjects")
            .label(HohenheimFormCopy.label("quota_subjects"))
            .help(HohenheimFormCopy.help("quota_subjects"))
            .build());

    /** The override cap; 0 refuses every new instance for this owner, null = no override. */
    public static final IntegerField MAX_INSTANCES = SCHEMA.addField(
        IntegerField.builder().name("max_instances")
            .label(HohenheimFormCopy.label("max_instances"))
            .help(HohenheimFormCopy.help("max_instances"))
            .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        SCHEMA.setDisplayFields(SUBJECTS);
        // A negative cap has no meaning; refusing it here keeps "0 = nothing allowed"
        // unambiguous instead of another silent non-positive-means-unlimited lane.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(MAX_INSTANCES.getName())) {
                return;
            }
            Object value = row.get(MAX_INSTANCES.getName());
            if (value instanceof Integer max && max < 0) {
                throw Violations.ofField("max_instances", max,
                    Microcopy.of("quota_negative").withFilter("scope", "violations"));
            }
        });
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "InstanceQuota"; }
    @Override public String getTableName() { return "instance_quotas"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
