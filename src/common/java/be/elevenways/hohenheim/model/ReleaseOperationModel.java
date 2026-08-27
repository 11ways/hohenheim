package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * THE release-operation record (the BuildOperationModel shape): one row per attempt to
 * change WHICH release serves a product record's traffic -- create candidate, probe,
 * switch, drain, retain, reclaim -- so a half-finished release is diagnosable evidence,
 * never a silently forgotten state. A rollback is the SAME operation over the retained
 * release's pinned spec; nothing is rebuilt from mutable source.
 *
 * AIDEV-NOTE: there is deliberately NO spec snapshot column. The pinned spec of every
 * release lives on its (retained) instance row's digest-pinned settings, which is what a
 * rollback deploys; copying it here would duplicate that authority AND leak the secret
 * environment map onto a derived surface (the phase 0.6 discipline).
 *
 * AIDEV-NOTE: {@link #OWNER_FINGERPRINT} vs {@link #SPEC_FINGERPRINT} is what makes a
 * rollback SURVIVE convergence: a succeeded rollback whose site_fingerprint still equals
 * the current source identity PINS the site to the rolled-back release, because the
 * source did not change since the operator rejected it; any source change dissolves the
 * pin naturally (a genuinely new deploy wins). For a plain release both are equal.
 */
public class ReleaseOperationModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "release_operation");
    public static final Schema SCHEMA = new Schema();

    /** A forward release of a new spec. */
    public static final String KIND_RELEASE = "release";

    /** A release of the RETAINED prior spec, pinned by digest -- never a rebuild. */
    public static final String KIND_ROLLBACK = "rollback";

    /** Row written, no daemon work yet. */
    public static final String STATUS_PENDING = "pending";
    /** Candidate instance created and deploying. */
    public static final String STATUS_DEPLOYING = "deploying";
    /** Candidate running; the health gate is interrogating it. */
    public static final String STATUS_PROBING = "probing";
    /** Probe passed; roles are being flipped. */
    public static final String STATUS_SWITCHING = "switching";
    /** Traffic switched; the superseded release drains before its stop + reclaim. */
    public static final String STATUS_DRAINING = "draining";
    public static final String STATUS_SUCCEEDED = "succeeded";
    /** The candidate never took traffic; the prior release kept serving. */
    public static final String STATUS_FAILED = "failed";
    /** Found in flight at boot; recovery settled the runtime state and stamped this. */
    public static final String STATUS_INTERRUPTED = "interrupted";

    public static final IntegerField ID = SCHEMA.addField(
        IntegerField.builder().name("id").build());

    public static final EnumField KIND = SCHEMA.addField(EnumField.builder("kind")
        .value(KIND_RELEASE, v -> v.displayName("Release")
            .label(kindLabel(KIND_RELEASE)).icon("rocket").color("info"))
        .value(KIND_ROLLBACK, v -> v.displayName("Rollback")
            .label(kindLabel(KIND_ROLLBACK)).icon("clock-rotate-left").color("warning"))
        .build());

    /** The translation token for a release kind; the key IS the stored value. */
    private static Microcopy kindLabel(String kind) {
        return Microcopy.of(kind).withFilter("scope", "release_kind");
    }

    public static final StringField FOR_MODEL = SCHEMA.addField(
        StringField.builder().name("for_model").build());

    public static final IntegerField FOR_ID = SCHEMA.addField(
        IntegerField.builder().name("for_id").build());

    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_PENDING, v -> v.displayName("Pending")
            .label(statusLabel(STATUS_PENDING)).icon("clock").color("secondary"))
        .value(STATUS_DEPLOYING, v -> v.displayName("Deploying")
            .label(statusLabel(STATUS_DEPLOYING)).icon("rotate").color("info"))
        .value(STATUS_PROBING, v -> v.displayName("Probing")
            .label(statusLabel(STATUS_PROBING)).icon("stethoscope").color("info"))
        .value(STATUS_SWITCHING, v -> v.displayName("Switching")
            .label(statusLabel(STATUS_SWITCHING)).icon("shuffle").color("info"))
        .value(STATUS_DRAINING, v -> v.displayName("Draining")
            .label(statusLabel(STATUS_DRAINING)).icon("hourglass-half").color("info"))
        .value(STATUS_SUCCEEDED, v -> v.displayName("Succeeded")
            .label(statusLabel(STATUS_SUCCEEDED)).icon("check").color("success"))
        .value(STATUS_FAILED, v -> v.displayName("Failed")
            .label(statusLabel(STATUS_FAILED)).icon("circle-xmark").color("destructive"))
        .value(STATUS_INTERRUPTED, v -> v.displayName("Interrupted")
            .label(statusLabel(STATUS_INTERRUPTED)).icon("power-off").color("warning"))
        .build());

    /** The translation token for a release status; the key IS the stored value. */
    private static Microcopy statusLabel(String status) {
        return Microcopy.of(status).withFilter("scope", "release_status");
    }

    /** The content-addressed image the candidate ran; THE pinned artifact identity. */
    public static final StringField IMAGE_ID = SCHEMA.addField(
        StringField.builder().name("image_id").build());

    /** The instance row deployed as this operation's candidate. */
    public static final IntegerField CANDIDATE_INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("candidate_instance_id").build());

    /** The previously-serving instance this operation retired (the rollback target). */
    public static final IntegerField RETIRED_INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("retired_instance_id").build());

    /** Source identity of the SITE's settings at operation time (the pin key). */
    public static final StringField OWNER_FINGERPRINT = SCHEMA.addField(
        StringField.builder().name("owner_fingerprint").filterable(false).build());

    /** Source identity of the spec the candidate DEPLOYED (differs on rollback). */
    public static final StringField SPEC_FINGERPRINT = SCHEMA.addField(
        StringField.builder().name("spec_fingerprint").filterable(false).build());

    public static final StringField FAILURE_REASON = SCHEMA.addField(
        StringField.builder().name("failure_reason").build());

    /** Timestamped step lines; every phase of the operation is visible here. */
    public static final TextField STEP_LOG = SCHEMA.addField(
        TextField.builder().name("step_log").build());

    public static final DateTimeField STARTED_AT = SCHEMA.addField(
        DateTimeField.builder().name("started_at").build());
    public static final DateTimeField FINISHED_AT = SCHEMA.addField(
        DateTimeField.builder().name("finished_at").build());
    public static final IntegerField DURATION_MS = SCHEMA.addField(
        IntegerField.builder().name("duration_ms").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    /** Newest-first release history of one owning record. */
    public List<Row> findForOwner(String forModel, int forId, int limit) {
        return find()
            .where(FOR_MODEL.eq(forModel))
            .where(FOR_ID.eq(forId))
            .orderBy(ID, SortOrder.DESC)
            .limit(limit)
            .all();
    }

    /** The newest SUCCEEDED operation of one owning record, or null. */
    public Row latestSuccess(String forModel, int forId) {
        return find()
            .where(FOR_MODEL.eq(forModel))
            .where(FOR_ID.eq(forId))
            .where(STATUS.eq(STATUS_SUCCEEDED))
            .orderBy(ID, SortOrder.DESC)
            .first();
    }

    /** Every operation of one owning record still claiming to be in flight. */
    public List<Row> findInFlight(String forModel, int forId) {
        return find()
            .where(FOR_MODEL.eq(forModel))
            .where(FOR_ID.eq(forId))
            .where(STATUS.in(STATUS_PENDING, STATUS_DEPLOYING, STATUS_PROBING,
                STATUS_SWITCHING, STATUS_DRAINING))
            .orderBy(ID, SortOrder.DESC)
            .all();
    }

    static {
        // The image being released is the only human-readable thing an operation carries;
        // its kind and status render as badges beside it.
        SCHEMA.setDisplayFields(IMAGE_ID);
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }
    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override
    public String getModelName() { return "ReleaseOperation"; }
    @Override
    public String getTableName() { return "release_operations"; }
    @Override
    public Schema getSchema() { return SCHEMA; }
}
