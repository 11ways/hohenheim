package be.elevenways.hohenheim.model;

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
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

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

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "build_operation");
    public static final Schema SCHEMA = new Schema();

    /** A Dockerfile build inside the sandbox (the shipped builder). */
    public static final String KIND_DOCKERFILE = "dockerfile";

    /**
     * A buildpack/Nixpacks-style build: DECLARED, not implemented. The named path is a
     * plan phase that emits a Dockerfile into the context, after which the identical
     * sandbox and the identical record run the identical Dockerfile build -- see
     * {@code be.elevenways.hohenheim.server.build.Builders}.
     */
    public static final String KIND_NIXPACKS = "nixpacks";

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    /** The wall-clock quota bound and the sandbox killed the build. */
    public static final String STATUS_TIMED_OUT = "timed_out";
    /** A resource quota other than time bound (disk today). */
    public static final String STATUS_QUOTA_EXCEEDED = "quota_exceeded";
    /** The build never started: admission, an unenforceable host, an oversized context. */
    public static final String STATUS_REFUSED = "refused";

    public static final IntegerField ID = SCHEMA.addField(
        IntegerField.builder().name("id").build());

    public static final EnumField BUILDER_KIND = SCHEMA.addField(EnumField.builder("builder_kind")
        .value(KIND_DOCKERFILE, v -> v.displayName("Dockerfile").icon("file-code").color("info"))
        .value(KIND_NIXPACKS, v -> v.displayName("Nixpacks").icon("box").color("secondary"))
        .build());

    public static final StringField FOR_MODEL = SCHEMA.addField(
        StringField.builder().name("for_model").build());

    public static final IntegerField FOR_ID = SCHEMA.addField(
        IntegerField.builder().name("for_id").build());

    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_RUNNING, v -> v.displayName("Running").icon("rotate").color("info"))
        .value(STATUS_SUCCEEDED, v -> v.displayName("Succeeded").icon("check").color("success"))
        .value(STATUS_FAILED, v -> v.displayName("Failed").icon("circle-xmark").color("destructive"))
        .value(STATUS_TIMED_OUT, v -> v.displayName("Timed out").icon("clock").color("warning"))
        .value(STATUS_QUOTA_EXCEEDED,
            v -> v.displayName("Quota exceeded").icon("gauge-high").color("warning"))
        .value(STATUS_REFUSED, v -> v.displayName("Refused").icon("ban").color("destructive"))
        .build());

    /** Commit sha (git-sourced) or another caller-supplied source identity. */
    public static final StringField SOURCE_REF = SCHEMA.addField(
        StringField.builder().name("source_ref").build());

    /** The content-addressed artifact identity; THE thing a release pins. */
    public static final StringField IMAGE_ID = SCHEMA.addField(
        StringField.builder().name("image_id").build());

    /** Human-findable name of the artifact; never an identity (a tag is mutable). */
    public static final StringField TAG = SCHEMA.addField(
        StringField.builder().name("tag").build());

    public static final IntegerField EXIT_CODE = SCHEMA.addField(
        IntegerField.builder().name("exit_code").build());

    public static final StringField FAILURE_REASON = SCHEMA.addField(
        StringField.builder().name("failure_reason").build());

    public static final TextField LOG = SCHEMA.addField(
        TextField.builder().name("log").build());

    // -- the quota that was in force, recorded so a failure is explainable ------

    public static final DoubleField CPU_LIMIT = SCHEMA.addField(
        DoubleField.builder().name("cpu_limit").filterable(false).build());
    public static final IntegerField MEMORY_LIMIT_MB = SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb").filterable(false).build());
    public static final IntegerField DISK_LIMIT_MB = SCHEMA.addField(
        IntegerField.builder().name("disk_limit_mb").filterable(false).build());
    public static final IntegerField PIDS_LIMIT = SCHEMA.addField(
        IntegerField.builder().name("pids_limit").filterable(false).build());
    public static final IntegerField TIMEOUT_SECONDS = SCHEMA.addField(
        IntegerField.builder().name("timeout_seconds").filterable(false).build());

    /** Largest writable-layer size the disk watchdog OBSERVED, not a promise. */
    public static final LongField PEAK_DISK_BYTES = SCHEMA.addField(
        LongField.builder("peak_disk_bytes").filterable(false).build());
    public static final LongField ARTIFACT_BYTES = SCHEMA.addField(
        LongField.builder("artifact_bytes").filterable(false).build());

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

    /** Newest-first build history of one owning record. */
    public List<Row> findForOwner(String forModel, int forId, int limit) {
        return find()
            .where(FOR_MODEL.eq(forModel))
            .where(FOR_ID.eq(forId))
            .orderBy(ID, SortOrder.DESC)
            .limit(limit)
            .all();
    }

    /** The newest SUCCEEDED build of one owning record, or null. */
    public Row latestSuccess(String forModel, int forId) {
        return find()
            .where(FOR_MODEL.eq(forModel))
            .where(FOR_ID.eq(forId))
            .where(STATUS.eq(STATUS_SUCCEEDED))
            .orderBy(ID, SortOrder.DESC)
            .first();
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
