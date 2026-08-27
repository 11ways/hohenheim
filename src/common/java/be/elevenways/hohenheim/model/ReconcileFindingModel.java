package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * One persisted Docker-reconciler finding: how a container, volume or network on one
 * server was attributed. The scheduled reconciler REPLACES a server's findings each
 * sweep; the dashboard attention list reads the stored rows so no render ever probes
 * a daemon.
 */
public class ReconcileFindingModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "reconcile_finding");
    public static final Schema SCHEMA = new Schema();

    /** {@link #BUCKET}: owner resolves to a live record. */
    public static final String BUCKET_OWNED = "owned";

    /** {@link #BUCKET}: attributed to us, but the record is gone or soft-deleted. */
    public static final String BUCKET_ORPHANED = "orphaned";

    /** {@link #BUCKET}: no owner claim, but a recognised third-party convention. */
    public static final String BUCKET_FOREIGN_KNOWN = "foreign_known";

    /** {@link #BUCKET}: unrecognised, and its name collides with our naming schemes. */
    public static final String BUCKET_FOREIGN_COLLIDING = "foreign_colliding";

    /** {@link #BUCKET}: unrecognised and unrelated to us. */
    public static final String BUCKET_FOREIGN_UNRELATED = "foreign_unrelated";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField SERVER_NAME = SCHEMA.addField(
        StringField.builder().name("server_name").build());
    public static final EnumField KIND = SCHEMA.addField(EnumField.builder("kind")
        .value("container", v -> v.displayName("Container")
            .label(kindLabel("container")).icon("cube").color("blue"))
        .value("volume", v -> v.displayName("Volume")
            .label(kindLabel("volume")).icon("database").color("purple"))
        .value("network", v -> v.displayName("Network")
            .label(kindLabel("network")).icon("diagram-project").color("teal"))
        .build());

    /** The translation token for a found resource kind; the key IS the stored value. */
    private static Microcopy kindLabel(String kind) {
        return Microcopy.of(kind).withFilter("scope", "reconcile_kind");
    }
    public static final StringField RESOURCE_NAME = SCHEMA.addField(
        StringField.builder().name("resource_name").build());
    public static final EnumField BUCKET = SCHEMA.addField(EnumField.builder("bucket")
        .value(BUCKET_OWNED, v -> v.displayName("Owned")
            .label(bucketLabel(BUCKET_OWNED)).icon("circle-check").color("green"))
        .value(BUCKET_ORPHANED, v -> v.displayName("Orphaned")
            .label(bucketLabel(BUCKET_ORPHANED)).icon("circle-exclamation").color("red"))
        .value(BUCKET_FOREIGN_KNOWN, v -> v.displayName("Foreign (known)")
            .label(bucketLabel(BUCKET_FOREIGN_KNOWN)).icon("circle-info").color("gray"))
        .value(BUCKET_FOREIGN_COLLIDING, v -> v.displayName("Foreign (colliding)")
            .label(bucketLabel(BUCKET_FOREIGN_COLLIDING)).icon("triangle-exclamation").color("orange"))
        .value(BUCKET_FOREIGN_UNRELATED, v -> v.displayName("Foreign (unrelated)")
            .label(bucketLabel(BUCKET_FOREIGN_UNRELATED)).icon("circle").color("gray"))
        .build());

    /** The translation token for an ownership bucket; the key IS the stored value. */
    private static Microcopy bucketLabel(String bucket) {
        return Microcopy.of(bucket).withFilter("scope", "reconcile_bucket");
    }
    /** How the attribution was made: owner_label, stack_label, name, foreign_label or none. */
    public static final StringField EVIDENCE = SCHEMA.addField(
        StringField.builder().name("evidence").build());
    public static final StringField OWNER_MODEL = SCHEMA.addField(
        StringField.builder().name("owner_model").nullable(true).build());
    public static final StringField OWNER_ID = SCHEMA.addField(
        StringField.builder().name("owner_id").nullable(true).build());
    public static final StringField DETAIL = SCHEMA.addField(
        StringField.builder().name("detail").nullable(true).build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    static {
        // A finding is about ONE named resource on a host; the kind and bucket are badges.
        SCHEMA.setDisplayFields(RESOURCE_NAME);
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "ReconcileFinding"; }

    @Override
    public String getTableName() { return "reconcile_findings"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
