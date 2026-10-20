package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.WordedState;
import be.elevenways.hohenheim.host.HostState;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.EngineHost;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What a managed database or a shared engine is doing, in one verdict: the record's own lifecycle while it is not
 * active, then the stored state of the engine instance serving it. The Databases list's state cell, the Engines card,
 * an app's Databases tab, the connection card, the host page and the attention items all read it.
 *
 * AIDEV-NOTE: the record's "active" only says provisioning finished, never that anything serves. The
 * list once read "Active" for shop while its attention items said its engine was not running; whether it serves is the
 * engine instance's status column, which InstanceStatusReconciler stores from what the daemon answered, so this reads
 * STORED state only and dials nothing per render. A host whose last probe failed makes that status unverified.
 *
 * @param state  what the record does
 * @param reason why it does not serve, in words; null when it serves or the state says it all
 * @author Jelle De Loecker
 * @since  0.10.0
 */
record DatabaseVerdict(@NonNull State state, @Nullable Microcopy reason) {

    /**
     * The states a database (or engine) record is read in, each with its words, badge and attention title.
     *
     * AIDEV-NOTE: the three lifecycle members carry the record's own status token, so their words are the
     * {@code database_status} labels the STATUS field already declares; the serving members word themselves in that
     * same scope. A member declares its attention title and severity together or not at all; one without raises no
     * item of its own: RUNNING serves, and PROVISIONING is passing, so an app waiting on it keeps its own item (a fold
     * needs a shown root).
     */
    enum State implements WordedState {

        /** Its engine runs. */
        RUNNING("running", BadgeVariant.SUCCESS, "circle-check", true),

        /** Still being set up. */
        PROVISIONING(DatabaseModel.STATUS_PROVISIONING, BadgeVariant.WARNING, "rotate", false),

        /** Setting it up failed. */
        SETUP_FAILED(DatabaseModel.STATUS_FAILED, BadgeVariant.DESTRUCTIVE, "circle-xmark", false,
            "database_setup_failed", AttentionSeverity.ERROR),

        /** Removing it failed, so the record stays. */
        REMOVE_FAILED(DatabaseModel.STATUS_DESTROY_FAILED, BadgeVariant.DESTRUCTIVE, "triangle-exclamation", false,
            "database_remove_failed", AttentionSeverity.ERROR),

        /** Set up, but no engine runs for it. */
        NOT_RUNNING("not_running", BadgeVariant.WARNING, "circle-stop", false, "database_not_running",
            AttentionSeverity.WARNING),

        /** Its engine stopped after an error. */
        STOPPED_AFTER_ERROR("stopped_after_error", BadgeVariant.DESTRUCTIVE, "circle-exclamation", false,
            "database_stopped_after_error", AttentionSeverity.ERROR),

        /** Its engine's container runs, but the engine inside it was killed for running out of memory. */
        OUT_OF_MEMORY("out_of_memory", BadgeVariant.DESTRUCTIVE, "memory", false, "database_out_of_memory",
            AttentionSeverity.ERROR),

        /** Its host failed its last check, so nobody knows whether the engine runs. */
        UNREACHABLE("unreachable", BadgeVariant.WARNING, "link-slash", false, "database_unreachable",
            AttentionSeverity.WARNING);

        private final String token;
        private final BadgeVariant variant;
        private final String icon;
        private final boolean serves;
        private final @Nullable String attentionTitle;
        private final @Nullable AttentionSeverity severity;

        State(@NonNull String token, @NonNull BadgeVariant variant, @NonNull String icon, boolean serves) {
            this(token, variant, icon, serves, null, null);
        }

        State(@NonNull String token, @NonNull BadgeVariant variant, @NonNull String icon, boolean serves,
              @Nullable String attentionTitle, @Nullable AttentionSeverity severity) {
            this.token = token;
            this.variant = variant;
            this.icon = icon;
            this.serves = serves;
            this.attentionTitle = attentionTitle;
            this.severity = severity;
        }

        /** @return the stable token a state cell renders */
        @Override
        public @NonNull String token() {
            return this.token;
        }

        @Override
        public @NonNull BadgeVariant variant() {
            return this.variant;
        }

        /** @return the state in words ("Not running"); its sentence variant reads "not running" */
        @Override
        public @NonNull Microcopy label() {
            return HohenheimMicrocopy.DATABASE_STATUS.of(this.token);
        }

        /** @return whether an app holding its credentials reaches it */
        boolean serves() {
            return this.serves;
        }

        /**
         * @param name the database's name
         * @return its attention item's title ("Database shop is not running"), null when this state raises none
         */
        @Nullable Microcopy attentionTitle(@NonNull String name) {
            return this.attentionTitle == null ? null
                : HohenheimMicrocopy.ATTENTION_TITLE.of(this.attentionTitle).withArg("name", name);
        }

        /** @return how loud its attention item is, null when it raises none */
        @Nullable AttentionSeverity severity() {
            return this.severity;
        }

        /** @return the health tone this state reads as on the database's health surfaces */
        @NonNull HealthTone tone() {
            return switch (this) {
                case RUNNING -> HealthTone.OK;
                // Pending (being set up) or untold (the host did not answer): nothing to act on yet.
                case PROVISIONING, UNREACHABLE -> HealthTone.UNKNOWN;
                case NOT_RUNNING -> HealthTone.ATTENTION;
                case SETUP_FAILED, REMOVE_FAILED, STOPPED_AFTER_ERROR, OUT_OF_MEMORY -> HealthTone.BROKEN;
            };
        }
    }

    /** @return this database record's verdict: its lifecycle, then the engine instance serving it */
    static @NonNull DatabaseVerdict ofDatabase(@NonNull Row database) {
        return ofDatabases(List.of(database)).apply(database);
    }

    /**
     * {@link #ofDatabase} for a page of database records, judged together: the serving instances of the ACTIVE ones and
     * every host involved are each read once for the page, never once per row.
     *
     * @return the verdict of each of these database records (asking it reads nothing more); an ACTIVE record that was
     *         not among them is judged on its own ({@link #ofDatabase}), never answered "no engine" unread
     */
    static @NonNull Function<Row, DatabaseVerdict> ofDatabases(@NonNull List<? extends Row> databases) {
        List<Row> active = activeAmong(databases, DatabaseModel.STATUS);
        DatabaseInstances.Serving serving = DatabaseInstances.servingAmong(
            active.isEmpty() ? Map.of() : EngineHost.placementsOf(active));
        Set<Integer> judged = idsOf(active, DatabaseModel.ID);
        return database -> {
            Integer id = database.get(DatabaseModel.ID);
            String status = database.get(DatabaseModel.STATUS);
            if (id != null && DatabaseModel.STATUS_ACTIVE.equals(status) && !judged.contains(id)) {
                return ofDatabase(database);
            }
            return of(status, database.get(DatabaseModel.FAILURE_REASON),
                id == null ? null : serving.instances().get(id), serving);
        };
    }

    /** @return this shared engine's verdict: its lifecycle, then its own instance */
    static @NonNull DatabaseVerdict ofEngine(@NonNull Row engine) {
        return ofEngines(List.of(engine)).apply(engine);
    }

    /**
     * {@link #ofEngine} for a page of shared engine rows, judged together: the ACTIVE engines' instances and every host
     * involved are each read once for the page.
     *
     * @return the verdict of each of these engine rows (asking it reads nothing more); an ACTIVE engine that was not
     *         among them is judged on its own ({@link #ofEngine})
     */
    static @NonNull Function<Row, DatabaseVerdict> ofEngines(@NonNull List<? extends Row> engines) {
        List<Row> active = activeAmong(engines, DatabaseEngineModel.STATUS);
        DatabaseInstances.Serving serving = DatabaseInstances.servingAmong(
            active.isEmpty() ? Map.of() : EngineHost.placementsOfEngines(active));
        Set<Integer> judged = idsOf(active, DatabaseEngineModel.ID);
        return engine -> {
            Integer id = engine.get(DatabaseEngineModel.ID);
            String status = engine.get(DatabaseEngineModel.STATUS);
            if (id != null && DatabaseModel.STATUS_ACTIVE.equals(status) && !judged.contains(id)) {
                return ofEngine(engine);
            }
            return of(status, engine.get(DatabaseEngineModel.FAILURE_REASON),
                id == null ? null : serving.instances().get(id), serving);
        };
    }

    /** @return the rows whose lifecycle token is active: the only ones a serving instance is read for */
    private static @NonNull List<Row> activeAmong(@NonNull List<? extends Row> rows,
                                                  @NonNull Field<String, ?> status) {
        List<Row> active = new ArrayList<>();
        for (Row row : rows) {
            if (DatabaseModel.STATUS_ACTIVE.equals(row.get(status))) {
                active.add(row);
            }
        }
        return active;
    }

    /** @return the ids of these rows */
    private static @NonNull Set<Integer> idsOf(@NonNull List<Row> rows, @NonNull Field<Integer, ?> id) {
        Set<Integer> ids = new HashSet<>();
        for (Row row : rows) {
            if (row.get(id) != null) {
                ids.add(row.get(id));
            }
        }
        return ids;
    }

    /** @return whether an app holding its credentials reaches it */
    boolean serves() {
        return this.state.serves();
    }

    /** @return the verdict as a list cell: the state's badge, and why under it */
    @NonNull StateLineCell cell() {
        return StateLineCell.of(this.state, this.reason);
    }

    /** @return the verdict as the database's health: its state in words, and why */
    @NonNull RecordHealth health() {
        return new RecordHealth(this.state.tone(), this.state.label(), this.reason, List.of(), null);
    }

    /** @return the verdict as an enum-style badge (the host page's workload rows) */
    @NonNull EnumBadgeState badge() {
        return new EnumBadgeState(this.state.token(), this.state.label(), null, this.state.icon, this.state.variant,
            null, true);
    }

    /**
     * @param status   the record's stored lifecycle token
     * @param failure  the record's stored failure reason, verbatim
     * @param instance the engine instance serving an active record, null when none does or its placement does not
     *                 resolve
     * @param serving  the page's resolution, whose hosts an engine instance's host is read from
     */
    private static @NonNull DatabaseVerdict of(@Nullable String status, @Nullable String failure,
                                               @Nullable Row instance,
                                               DatabaseInstances.@NonNull Serving serving) {
        if (DatabaseModel.STATUS_PROVISIONING.equals(status)) {
            return new DatabaseVerdict(State.PROVISIONING, null);
        }
        if (DatabaseModel.STATUS_FAILED.equals(status)) {
            return new DatabaseVerdict(State.SETUP_FAILED, failure == null || failure.isBlank()
                ? HohenheimMicrocopy.ATTENTION_DETAIL.of("provisioning_failed")
                    : HohenheimMicrocopy.ATTENTION_DETAIL.of("provisioning_failed_reason").withArg("reason", failure));
        }
        if (DatabaseModel.STATUS_DESTROY_FAILED.equals(status)) {
            return new DatabaseVerdict(State.REMOVE_FAILED, failure == null || failure.isBlank() ? null
                : Microcopy.literal(failure));
        }
        if (!DatabaseModel.STATUS_ACTIVE.equals(status)) {
            // An unknown stored token claims nothing about a serving engine: fail closed.
            return new DatabaseVerdict(State.NOT_RUNNING, null);
        }
        // A record whose placement does not resolve (an unknown engine token, a missing engine row) has no instance
        // here, and serves nothing.
        if (instance == null) {
            return new DatabaseVerdict(State.NOT_RUNNING, HohenheimMicrocopy.ATTENTION_DETAIL.of("database_no_engine"));
        }
        // "Gone or stopped", "the host could not be asked" and "the engine failed" are different operator problems;
        // conflating the first two was the C6 status defect.
        if (hostUnanswering(serving.hostOf(instance))) {
            return new DatabaseVerdict(State.UNREACHABLE,
                HohenheimMicrocopy.ATTENTION_DETAIL.of("database_host_unanswering"));
        }
        InstanceStatus stored = InstanceStatus.forToken(instance.get(InstanceModel.STATUS));
        if (stored == null) {
            return new DatabaseVerdict(State.NOT_RUNNING,
                HohenheimMicrocopy.ATTENTION_DETAIL.of("database_engine_stopped"));
        }
        return switch (stored) {
            // The container runs, but the sweep saw the kernel kill the engine inside it: "runs" is exactly what the
            // status alone would wrongly vouch for.
            case STARTING, RUNNING, CAPTURING, RESTORING, MIGRATING ->
                instance.get(InstanceModel.WORKLOAD_KILLED_AT) != null
                    ? new DatabaseVerdict(State.OUT_OF_MEMORY,
                        HohenheimMicrocopy.ATTENTION_DETAIL.of("database_engine_killed"))
                    : new DatabaseVerdict(State.RUNNING, null);
            case ERROR -> new DatabaseVerdict(State.STOPPED_AFTER_ERROR,
                HohenheimMicrocopy.ATTENTION_DETAIL.of("database_engine_error"));
            case CREATED,
                STOPPED, DESTROYING -> new DatabaseVerdict(State.NOT_RUNNING,
                HohenheimMicrocopy.ATTENTION_DETAIL.of("database_engine_stopped"));
        };
    }

    /**
     * Whether the engine's host last FAILED its probe, so its stored instance status is unverified rather than
     * evidence. Only a recorded probe failure counts: a host that is merely silent or never probed has made no claim
     * either way; a host that cannot be resolved counts as failed.
     */
    private static boolean hostUnanswering(@Nullable Row server) {
        if (server == null) {
            return true;
        }
        HostState state = ServerParts.statusCellOf(server).state();
        return switch (state) {
            case ERROR -> true;
            case QUARANTINED, SILENT, NEVER_PROBED, OK -> false;
        };
    }
}
