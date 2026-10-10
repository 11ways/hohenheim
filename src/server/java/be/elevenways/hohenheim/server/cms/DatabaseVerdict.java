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
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.EngineHost;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.Supplier;

/**
 * What a managed database or a shared engine is doing, in one verdict: the record's own lifecycle while it is not
 * active, then the stored state of the engine instance serving it. The Databases list's state cell, the Engines card,
 * an app's Databases tab, the connection card, the host page and the attention items all read it.
 *
 * AIDEV-NOTE: the record's "active" only says provisioning finished, never that anything serves. D10b's walk had the
 * list read "Active" for shop while its attention items said its engine was not running; whether it serves is the
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
        @Nullable Microcopy attentionTitle(@NonNull Object name) {
            return this.attentionTitle == null ? null
                : HohenheimMicrocopy.ATTENTION_TITLE.of(this.attentionTitle).withArg("name", name);
        }

        /** @return how loud its attention item is, null when it raises none */
        @Nullable AttentionSeverity severity() {
            return this.severity;
        }
    }

    /** @return this database record's verdict: its lifecycle, then the engine instance serving it */
    static @NonNull DatabaseVerdict ofDatabase(@NonNull Row database) {
        Integer id = database.get(DatabaseModel.ID);
        return of(database.get(DatabaseModel.STATUS), database.get(DatabaseModel.FAILURE_REASON),
            () -> id == null ? null : DatabaseInstances.owned(id));
    }

    /** @return this shared engine's verdict: its lifecycle, then its own instance */
    static @NonNull DatabaseVerdict ofEngine(@NonNull Row engine) {
        return of(engine.get(DatabaseEngineModel.STATUS), engine.get(DatabaseEngineModel.FAILURE_REASON),
            () -> DatabaseInstances.ownedBy(EngineHost.ofEngine(engine)));
    }

    /** @return whether an app holding its credentials reaches it */
    boolean serves() {
        return this.state.serves();
    }

    /** @return the verdict as a list cell: the state's badge, and why under it */
    @NonNull StateLineCell cell() {
        return StateLineCell.of(this.state, this.reason);
    }

    /** @return the verdict as an enum-style badge (the host page's workload rows) */
    @NonNull EnumBadgeState badge() {
        return new EnumBadgeState(this.state.token(), this.state.label(), null, this.state.icon, this.state.variant,
            null, true);
    }

    /**
     * @param status  the record's stored lifecycle token
     * @param failure the record's stored failure reason, verbatim
     * @param engine  the engine instance serving it, asked only for an active record; null when there is none
     */
    private static @NonNull DatabaseVerdict of(@Nullable String status, @Nullable String failure,
                                               @NonNull Supplier<@Nullable Row> engine) {
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
        Row instance;
        try {
            instance = engine.get();
        } catch (RuntimeException unresolvable) {
            instance = null;   // a shared record whose engine row is gone serves nothing
        }
        if (instance == null) {
            return new DatabaseVerdict(State.NOT_RUNNING, HohenheimMicrocopy.ATTENTION_DETAIL.of("database_no_engine"));
        }
        // "Gone or stopped", "the host could not be asked" and "the engine failed" are different operator problems;
        // conflating the first two was the C6 status defect.
        if (hostUnanswering(instance)) {
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
                STOPPED -> new DatabaseVerdict(State.NOT_RUNNING,
                HohenheimMicrocopy.ATTENTION_DETAIL.of("database_engine_stopped"));
        };
    }

    /**
     * Whether the engine's host last FAILED its probe, so its stored instance status is unverified rather than
     * evidence. Only a recorded probe failure counts: a host that is merely silent or never probed has made no claim
     * either way.
     */
    private static boolean hostUnanswering(@NonNull Row engine) {
        Row server;
        try {
            server = Models.get(ServerModel.class).findById(
                ServerModel.canonicalServerId(engine.get(InstanceModel.SERVER_ID)));
        } catch (RuntimeException unresolvable) {
            return true;
        }
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
