package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
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
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.ui.BadgeVariant;

import java.util.List;
import java.util.Set;

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
 * environment map onto a derived surface: secrets live only on the record that owns them.
 *
 * AIDEV-NOTE: {@link #OWNER_FINGERPRINT} vs {@link #SPEC_FINGERPRINT} is what makes a
 * rollback SURVIVE convergence: a succeeded rollback whose site_fingerprint still equals
 * the current source identity PINS the site to the rolled-back release, because the
 * source did not change since the operator rejected it; any source change dissolves the
 * pin naturally (a genuinely new deploy wins). For a plain release both are equal.
 */
public class ReleaseOperationModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("release_operation");
    public static final Schema SCHEMA = new Schema();

    /** A forward release of a new spec. */
    public static final String KIND_RELEASE = "release";

    /** A release of the RETAINED prior spec, pinned by digest -- never a rebuild. */
    public static final String KIND_ROLLBACK = "rollback";

    /**
     * The statuses a release operation stores in {@link #STATUS}. Every reader of "still in flight" or "took traffic"
     * derives from the members' facts, never from a hand-spelled status list.
     */
    public static final OperationLifecycle LIFECYCLE = OperationLifecycle.of(OperationStatus.PENDING,
        OperationStatus.DEPLOYING, OperationStatus.PROBING, OperationStatus.SWITCHING, OperationStatus.DRAINING,
        OperationStatus.SUCCEEDED, OperationStatus.FAILED, OperationStatus.INTERRUPTED);

    /** The statuses whose candidate has been switched to: it serves, or served, the traffic. */
    public static final Set<OperationStatus> TOOK_TRAFFIC =
        Set.of(OperationStatus.SWITCHING, OperationStatus.DRAINING, OperationStatus.SUCCEEDED);

    /** Every status an operation is still in flight in, derived from {@link OperationStatus#inFlight()}. */
    public static final List<String> IN_FLIGHT_STATUSES = LIFECYCLE.stored(OperationStatus::inFlight);

    /** Every status whose candidate has taken traffic, derived from {@link #TOOK_TRAFFIC}. */
    public static final List<String> TRAFFIC_TAKEN_STATUSES = LIFECYCLE.stored(TOOK_TRAFFIC::contains);

    public static final IntegerField ID = SCHEMA.addField(
        IntegerField.builder().name("id").build());

    public static final EnumField KIND = SCHEMA.addField(EnumField.builder("kind")
        .value(KIND_RELEASE, v -> v.displayName("Release")
            .label(HohenheimMicrocopy.RELEASE_KIND.of(KIND_RELEASE)).icon("rocket").color(BadgeVariant.INFO))
        .value(KIND_ROLLBACK, v -> v.displayName("Rollback")
            .label(HohenheimMicrocopy.RELEASE_KIND.of(KIND_ROLLBACK)).icon("clock-rotate-left")
            .color(BadgeVariant.WARNING))
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("kind"))
        .build());

    public static final StringField FOR_MODEL = SCHEMA.addField(
        StringField.builder().name("for_model")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("for_model")).build());

    public static final IntegerField FOR_ID = SCHEMA.addField(
        IntegerField.builder().name("for_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("for_id")).build());

    public static final EnumField STATUS = SCHEMA.addField(LIFECYCLE.field("status"));

    /** The content-addressed image the candidate ran; THE pinned artifact identity. */
    public static final StringField IMAGE_ID = SCHEMA.addField(
        StringField.builder().name("image_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("image")).build());

    /** The instance row deployed as this operation's candidate. */
    public static final IntegerField CANDIDATE_INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("candidate_instance_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("candidate_instance")).build());

    /** The previously-serving instance this operation retired (the rollback target). */
    public static final IntegerField RETIRED_INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("retired_instance_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("retired_instance")).build());

    /** Source identity of the SITE's settings at operation time (the pin key). */
    public static final StringField OWNER_FINGERPRINT = SCHEMA.addField(
        StringField.builder().name("owner_fingerprint").filterable(false).build());

    /** Source identity of the spec the candidate DEPLOYED (differs on rollback). */
    public static final StringField SPEC_FINGERPRINT = SCHEMA.addField(
        StringField.builder().name("spec_fingerprint").filterable(false).build());

    public static final StringField FAILURE_REASON = SCHEMA.addField(
        StringField.builder().name("failure_reason")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("failure_reason")).build());

    /** Timestamped step lines; every phase of the operation is visible here. */
    public static final TextField STEP_LOG = SCHEMA.addField(
        TextField.builder().name("step_log")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("step_log")).build());

    public static final DateTimeField STARTED_AT = SCHEMA.addField(
        DateTimeField.builder().name("started_at").build());
    public static final DateTimeField FINISHED_AT = SCHEMA.addField(
        DateTimeField.builder().name("finished_at").build());
    public static final IntegerField DURATION_MS = SCHEMA.addField(
        IntegerField.builder().name("duration_ms")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("duration_ms")).build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    /** One owning record's operations, newest first. */
    private QueryBuilder<Row> history(String forModel, int forId) {
        return OwnedOperations.newestFirst(this, FOR_MODEL, FOR_ID, ID, forModel, forId);
    }

    /** Newest-first release history of one owning record. */
    public List<Row> findForOwner(String forModel, int forId, int limit) {
        return this.history(forModel, forId).limit(limit).all();
    }

    /** The newest SUCCEEDED operation of one owning record, or null. */
    public Row latestSuccess(String forModel, int forId) {
        return this.history(forModel, forId).where(STATUS.eq(LIFECYCLE.stored(OperationStatus.SUCCEEDED))).first();
    }

    /** Every operation of one owning record still claiming to be in flight. */
    public List<Row> findInFlight(String forModel, int forId) {
        return this.history(forModel, forId).where(STATUS.in(IN_FLIGHT_STATUSES)).all();
    }

    /** Keep the newest {@code keep} operations of one owning record and delete the rest. */
    public void pruneHistory(String forModel, int forId, int keep) {
        Retention.keepNewest(this, this.history(forModel, forId), ID, keep);
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
