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
 * Per-owner quota overrides: one row per packed manage-grant subject set (the
 * DatabaseModel.MEMORY_LIMIT_MB precedent -- real nullable columns, never
 * settings-map keys), each consulted by its reserve hook ahead of the matching
 * {@code hohenheim.quota.*_per_owner} default. SIX dimensions:
 * {@link #MAX_INSTANCES}, {@link #MAX_MEMORY_MB}, {@link #MAX_DISK_GB},
 * {@link #MAX_NICS}, {@link #MAX_SITES} and {@link #MAX_DATABASES}.
 *
 * AIDEV-NOTE: the table is still called {@code instance_quotas} and the model is still
 * called InstanceQuota, and both names are now narrower than the thing: this is THE
 * per-owner quota row for every dimension hohenheim rations -- instances, their memory,
 * their disks and NICs, SITES and managed DATABASES. One row per owner is the point -- an
 * operator sets a tenant's whole budget on one form instead of hunting three tables -- so
 * the alternative was a second override table with the same key and the same semantics,
 * which is worse than a historical name. Do not add a parallel one.
 *
 * AIDEV-NOTE: memory here is the per-OWNER budget, which is a different question from
 * {@code InstanceCapacity}'s per-HOST budget: the host asks "does this machine have the
 * RAM", the owner asks "may this tenant have it at all". Both book the SAME number -- the
 * workload's declared {@code memory_limit_mb}, else its kind's declared footprint -- which
 * is also the cgroup/VM cap the driver applies, so neither ledger is an estimate.
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

    /**
     * Workload-memory cap in MB (sum over the owner's live instances, each charged at the
     * memory its driver actually caps it to); same 0/null semantics.
     */
    public static final IntegerField MAX_MEMORY_MB = SCHEMA.addField(
        IntegerField.builder().name("max_memory_mb")
            .label(HohenheimFormCopy.label("max_memory_mb"))
            .help(HohenheimFormCopy.help("max_memory_mb"))
            .build());

    /** Attached-disk cap in GB (sum over the owner's device rows); same 0/null semantics. */
    public static final IntegerField MAX_DISK_GB = SCHEMA.addField(
        IntegerField.builder().name("max_disk_gb")
            .label(HohenheimFormCopy.label("max_disk_gb"))
            .help(HohenheimFormCopy.help("max_disk_gb"))
            .build());

    /** Extra-NIC cap (count over the owner's device rows); same 0/null semantics. */
    public static final IntegerField MAX_NICS = SCHEMA.addField(
        IntegerField.builder().name("max_nics")
            .label(HohenheimFormCopy.label("max_nics"))
            .help(HohenheimFormCopy.help("max_nics"))
            .build());

    /** Live-site cap (count over the owner's non-trashed sites); same 0/null semantics. */
    public static final IntegerField MAX_SITES = SCHEMA.addField(
        IntegerField.builder().name("max_sites")
            .label(HohenheimFormCopy.label("max_sites"))
            .help(HohenheimFormCopy.help("max_sites"))
            .build());

    /** Managed-database cap (count over the owner's database records); same 0/null semantics. */
    public static final IntegerField MAX_DATABASES = SCHEMA.addField(
        IntegerField.builder().name("max_databases")
            .label(HohenheimFormCopy.label("max_databases"))
            .help(HohenheimFormCopy.help("max_databases"))
            .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        SCHEMA.setDisplayFields(SUBJECTS);
        // A negative cap has no meaning; refusing it here keeps "0 = nothing allowed"
        // unambiguous instead of another silent non-positive-means-unlimited lane.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            for (IntegerField cap : new IntegerField[] {MAX_INSTANCES, MAX_MEMORY_MB, MAX_DISK_GB,
                    MAX_NICS, MAX_SITES, MAX_DATABASES}) {
                if (!row.has(cap.getName())) {
                    continue;
                }
                Object value = row.get(cap.getName());
                if (value instanceof Integer max && max < 0) {
                    throw Violations.ofField(cap.getName(), max,
                        Microcopy.of("quota_negative").withFilter("scope", "violations"));
                }
            }
        });
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "InstanceQuota"; }
    @Override public String getTableName() { return "instance_quotas"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
