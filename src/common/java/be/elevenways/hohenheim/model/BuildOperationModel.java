package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.LongField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.common.ui.ColorHue;

import java.util.List;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * THE build-operation record: one row per sandboxed build attempt, shared by every
 * builder kind. It carries the durable state of the operation (an interrupted run
 * leaves {@code running} behind as visible evidence, never a clean-looking record),
 * the quota that was actually in force, what the sandbox observed while enforcing it,
 * and the DIGEST of the artifact -- which is the only thing a release may be pinned to.
 *
 * AIDEV-NOTE: {@link #IMAGE_ID} is the content-addressed image identity (sha256 of the
 * image config), not a tag. A tag is a mutable pointer: retagging it changes what
 * {@code image:tag} means without changing any record, which is exactly why a release
 * pins this column instead. {@link #TAG} exists only so a human can find the artifact.
 *
 * AIDEV-NOTE: the owner is the ATTRIBUTION pair the rest of the product already uses
 * ({@code for_model}/{@code for_id}, the OwnerLabels/GeneratedRows spelling), not a
 * site_id column -- a build belongs to whichever product record asked for it, and the
 * builders wave deliberately does not decide that the answer is forever "a site".
 */
public class BuildOperationModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("build_operation");
    public static final Schema SCHEMA = new Schema();

    /** A Dockerfile build inside the sandbox (the shipped builder). */
    public static final String KIND_DOCKERFILE = "dockerfile";

    /**
     * A buildpack/Nixpacks-style build: a sandboxed detection phase emits a Dockerfile
     * into the context, after which the identical sandbox and the identical record run
     * the identical Dockerfile build -- see
     * {@code be.elevenways.hohenheim.server.build.NixpacksBuilder}. What was detected is
     * recorded in {@link #DETECTION}.
     */
    public static final String KIND_NIXPACKS = "nixpacks";

    /**
     * A workspace SOURCE deploy: a checkout and a build run INSIDE the workspace's own
     * container, as its own uid -- no sandbox, no artifact, no image identity. It shares
     * this record because "what did my last deploy do, and what did it print" is one
     * question whatever ran it; {@code Builders.forKind} has no plan for it and refuses
     * it by name, which is the fail-closed half.
     */
    public static final String KIND_WORKSPACE = "workspace";

    /**
     * The builder kind a site's stored {@code builder} setting names, defaulting to
     * {@link #KIND_DOCKERFILE} for the blank/legacy value. An unknown kind passes
     * through unmapped so {@code Builders.forKind} refuses it by name.
     */
    public static String kindOrDefault(Object value) {
        String kind = trimmed(value);
        return kind.isEmpty() ? KIND_DOCKERFILE : kind;
    }

    /** The statuses a build stores in {@link #STATUS}. */
    public static final OperationLifecycle LIFECYCLE = OperationLifecycle.of(OperationStatus.RUNNING,
        OperationStatus.SUCCEEDED, OperationStatus.FAILED, OperationStatus.TIMED_OUT, OperationStatus.QUOTA_EXCEEDED,
        OperationStatus.REFUSED);

    public static final IntegerField ID = SCHEMA.addField(
        IntegerField.builder().name("id").build());

    public static final EnumField BUILDER_KIND = SCHEMA.addField(EnumField.builder("builder_kind")
        .value(KIND_DOCKERFILE, v -> v.displayName("Dockerfile")
            .label(HohenheimMicrocopy.BUILDER_KIND.of(KIND_DOCKERFILE)).icon("file-code").color(BadgeVariant.INFO))
        .value(KIND_NIXPACKS, v -> v.displayName("Nixpacks")
            .label(HohenheimMicrocopy.BUILDER_KIND.of(KIND_NIXPACKS)).icon("box").color(BadgeVariant.SECONDARY))
        .value(KIND_WORKSPACE, v -> v.displayName("Workspace")
            .label(HohenheimMicrocopy.BUILDER_KIND.of(KIND_WORKSPACE)).icon("code").color(ColorHue.VIOLET))
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("builder"))
        .build());

    public static final StringField FOR_MODEL = SCHEMA.addField(
        StringField.builder().name("for_model")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("for_model")).build());

    public static final IntegerField FOR_ID = SCHEMA.addField(
        IntegerField.builder().name("for_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("for_id")).build());

    public static final EnumField STATUS = SCHEMA.addField(LIFECYCLE.field("status"));

    /** Commit sha (git-sourced) or another caller-supplied source identity. */
    public static final StringField SOURCE_REF = SCHEMA.addField(
        StringField.builder().name("source_ref")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("source_ref")).build());

    /** The content-addressed artifact identity; THE thing a release pins. */
    public static final StringField IMAGE_ID = SCHEMA.addField(
        StringField.builder().name("image_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("image")).build());

    /** Human-findable name of the artifact; never an identity (a tag is mutable). */
    public static final StringField TAG = SCHEMA.addField(
        StringField.builder().name("tag")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("image_tag")).build());

    public static final IntegerField EXIT_CODE = SCHEMA.addField(
        IntegerField.builder().name("exit_code")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("exit_code")).build());

    public static final StringField FAILURE_REASON = SCHEMA.addField(
        StringField.builder().name("failure_reason")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("failure_reason")).build());

    public static final TextField LOG = SCHEMA.addField(
        TextField.builder().name("log")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("build_log")).build());

    /**
     * The INSPECTABLE detection record of a detecting builder kind (nixpacks): tool +
     * pinned version, the providers it named, the full plan it derived and the Dockerfile
     * it emitted, as JSON. Written even when the build was REFUSED, so "what did the
     * detector see" is a column, never log archaeology. Null for the dockerfile kind.
     */
    public static final TextField DETECTION = SCHEMA.addField(
        TextField.builder().name("detection").build());

    // -- the quota that was in force, recorded so a failure is explainable ------

    public static final DoubleField CPU_LIMIT = SCHEMA.addField(
        DoubleField.builder().name(InstanceKindFields.CPU_LIMIT).filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cpu_limit")).build());
    public static final IntegerField MEMORY_LIMIT_MB = SCHEMA.addField(
        IntegerField.builder().name(InstanceKindFields.MEMORY_LIMIT_MB).filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("memory_limit")).build());
    public static final IntegerField DISK_LIMIT_MB = SCHEMA.addField(
        IntegerField.builder().name("disk_limit_mb").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("disk_limit_mb")).build());
    public static final IntegerField PIDS_LIMIT = SCHEMA.addField(
        IntegerField.builder().name("pids_limit").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("pids_limit")).build());
    public static final IntegerField TIMEOUT_SECONDS = SCHEMA.addField(
        IntegerField.builder().name("timeout_seconds").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("timeout_seconds")).build());

    /** Largest writable-layer size the disk watchdog OBSERVED, not a promise. */
    public static final LongField PEAK_DISK_BYTES = SCHEMA.addField(
        LongField.builder("peak_disk_bytes").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("peak_disk_bytes")).build());
    public static final LongField ARTIFACT_BYTES = SCHEMA.addField(
        LongField.builder("artifact_bytes").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("artifact_bytes")).build());

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

    /** Newest-first build history of one owning record. */
    public List<Row> findForOwner(String forModel, int forId, int limit) {
        return this.history(forModel, forId).limit(limit).all();
    }

    /**
     * Keep the newest {@code keep} operations of one owning record and delete the rest.
     *
     * @param keep history depth; a non-positive value falls back to 50
     */
    public void pruneHistory(String forModel, int forId, int keep) {
        Retention.keepNewest(this, this.history(forModel, forId), ID, keep > 0 ? keep : 50);
    }

    /** The newest SUCCEEDED build of one owning record, or null. */
    public Row latestSuccess(String forModel, int forId) {
        return this.history(forModel, forId).where(STATUS.eq(LIFECYCLE.stored(OperationStatus.SUCCEEDED))).first();
    }

    static {
        // What the build PRODUCED names it best; a build that never got that far is
        // still traceable by the commit it started from.
        SCHEMA.setDisplayFields(TAG, IMAGE_ID, SOURCE_REF);
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }
    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override
    public String getModelName() { return "BuildOperation"; }
    @Override
    public String getTableName() { return "build_operations"; }
    @Override
    public Schema getSchema() { return SCHEMA; }
}
