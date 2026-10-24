package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.ByteUnit;

import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
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
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * build lane deliberately does not decide that the answer is forever "a site".
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
     * A static site: a generated multi-stage Dockerfile runs the site's build command, then serves its output folder
     * with the pinned static server, through the identical sandbox and record as {@link #KIND_DOCKERFILE}.
     */
    public static final String KIND_STATIC = "static_site";

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
        IntegerField.builder().name("id").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("id")).build());

    public static final EnumField BUILDER_KIND = SCHEMA.addField(EnumField.builder("builder_kind")
        .value(KIND_DOCKERFILE, v -> v.displayName("Dockerfile")
            .label(HohenheimMicrocopy.BUILDER_KIND.of(KIND_DOCKERFILE)).icon("file-code").color(BadgeVariant.INFO))
        .value(KIND_NIXPACKS, v -> v.displayName("Nixpacks")
            .label(HohenheimMicrocopy.BUILDER_KIND.of(KIND_NIXPACKS)).icon("box").color(BadgeVariant.SECONDARY))
        .value(KIND_STATIC, v -> v.displayName("Static site")
            .label(HohenheimMicrocopy.BUILDER_KIND.of(KIND_STATIC)).icon("file-lines").color(ColorHue.TEAL))
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
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("memory_limit")).byteSize(ByteUnit.MEGABYTE).build());
    public static final IntegerField DISK_LIMIT_MB = SCHEMA.addField(
        IntegerField.builder().name("disk_limit_mb").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("disk_limit_mb")).byteSize(ByteUnit.MEGABYTE).build());
    public static final IntegerField PIDS_LIMIT = SCHEMA.addField(
        IntegerField.builder().name("pids_limit").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("pids_limit")).build());
    public static final IntegerField TIMEOUT_SECONDS = SCHEMA.addField(
        IntegerField.builder().name("timeout_seconds").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("timeout_seconds")).suffix("s").build());

    /** Largest writable-layer size the disk watchdog OBSERVED, not a promise. */
    public static final LongField PEAK_DISK_BYTES = SCHEMA.addField(
        LongField.builder("peak_disk_bytes").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("peak_disk_bytes")).byteSize(ByteUnit.BYTE).build());
    public static final LongField ARTIFACT_BYTES = SCHEMA.addField(
        LongField.builder("artifact_bytes").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("artifact_bytes")).byteSize(ByteUnit.BYTE).build());

    public static final DateTimeField STARTED_AT = SCHEMA.addField(
        DateTimeField.builder().name("started_at").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("started_at")).build());
    public static final DateTimeField FINISHED_AT = SCHEMA.addField(
        DateTimeField.builder().name("finished_at").build());
    public static final IntegerField DURATION_MS = SCHEMA.addField(
        IntegerField.builder().name("duration_ms")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("duration_ms")).suffix("ms").build());
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

    /** The last-finished succeeded build of each of several owning records of one model, in one grouped read. */
    public Map<Integer, Row> lastSucceededAmong(String forModel, Collection<Integer> forIds) {
        return OwnedOperations.lastFinishedAmong(this, FOR_MODEL, FOR_ID, FINISHED_AT, STATUS,
            LIFECYCLE.stored(OperationStatus.SUCCEEDED), forModel, forIds);
    }

    /** @return which of several owning records of one model have a build still running, in one read */
    public Set<Integer> buildingAmong(String forModel, Collection<Integer> forIds) {
        Set<Integer> building = new LinkedHashSet<>();
        for (Row build : find().where(FOR_MODEL.eq(forModel)).where(FOR_ID.in(List.copyOf(forIds)))
                .where(STATUS.eq(LIFECYCLE.stored(OperationStatus.RUNNING))).all()) {
            building.add(build.get(FOR_ID));
        }
        return building;
    }

    static {
        // What a build is searched by: what it PRODUCED, else the commit it started from. Its title is composed
        // (resolveDisplayTitles), because these are hashes too long to name a build in a sentence.
        SCHEMA.setDisplayFields(TAG, IMAGE_ID, SOURCE_REF);
    }

    /** The words naming a build after the record it built for, argument {@code app}: "Staging API deploy". */
    private static final Microcopy DEPLOY_TITLE = HohenheimMicrocopy.BUILD_OPERATION.of("deploy_title");

    /**
     * A build is named as a deploy of the record it built for ("Staging API deploy"), in the reader's words.
     *
     * AIDEV-NOTE: the commit, image and tag stay the build's own columns. As its title they turned every activity
     * line about a build into "Jelle started e590c03e1417f0ad..." (a hash that overflowed the dashboard's recent
     * list), and its id is an installation-wide number no app counts its deploys by. The owners of a page of builds
     * are titled in one read per owner model, through that model's record source as the reader may read it
     * (referencedTitles); a build whose owner the reader may not read or that is gone, and every build titled without
     * a reader (a background lane, a retained title of a deleted build), keeps its display fields.
     *
     * @return per row, null when neither a readable owner nor a display field names it, so the pk fallback applies
     */
    @Override
    public @NonNull List<@Nullable String> resolveDisplayTitles(@NonNull List<Row> rows, @Nullable LocaleChain chain,
                                                                @Nullable AccessContext reader) {
        Map<Identifier, Map<String, String>> owners = new HashMap<>();
        if (reader != null) {
            Map<Identifier, Set<Integer>> asked = new LinkedHashMap<>();
            for (Row row : rows) {
                Identifier ownerModel = Identifier.tryParse(row.get(FOR_MODEL));
                Integer ownerId = row.get(FOR_ID);
                if (ownerModel != null && ownerId != null) {
                    asked.computeIfAbsent(ownerModel, model -> new LinkedHashSet<>()).add(ownerId);
                }
            }
            asked.forEach((model, ids) -> {
                RecordSource<?> source = RecordSourceRegistry.INSTANCE.byId(model);
                owners.put(model, source == null || !source.authorizes(reader) ? Map.of()
                    : source.referencedTitles(ids, chain, reader));
            });
        }
        List<@Nullable String> titles = new ArrayList<>(rows.size());
        for (Row row : rows) {
            Identifier ownerModel = Identifier.tryParse(row.get(FOR_MODEL));
            String owner = ownerModel == null ? null
                : owners.getOrDefault(ownerModel, Map.of()).get(String.valueOf((Object) row.get(FOR_ID)));
            titles.add(owner == null || owner.isBlank() || reader == null ? super.resolveDisplayTitle(row, chain)
                : this.deployOf(owner, chain, reader));
        }
        return titles;
    }

    /** @return "{app} deploy" in the title's chain, else the reader's languages */
    private @NonNull String deployOf(@NonNull String owner, @Nullable LocaleChain chain,
                                     @NonNull AccessContext reader) {
        MessageResolver resolver = reader.messageResolver();
        return DEPLOY_TITLE.withArg("app", owner).resolve(chain != null ? chain : reader.locales(),
            resolver != null ? resolver : MessageResolvers.getDefault());
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

    public static final HistoryBehaviour HISTORY = SCHEMA.addBehaviour(HistoryBehaviour.optional().changes());
}
