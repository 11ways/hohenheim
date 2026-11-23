package be.elevenways.hohenheim.cluster;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.StateBadge;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Where a linked server stands as the Servers list, the dashboard and the alerts say it: one verdict per row, read
 * from the state the probe wrote and this server's own version.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum LinkState implements StateBadge {

    /** It answered the last call, running this server's version. */
    REACHABLE("reachable", BadgeVariant.SUCCESS),

    /** Nothing was asked of it yet. */
    NOT_CHECKED("not_checked", BadgeVariant.SECONDARY),

    /** The calls fail: nothing came back, or nothing readable. */
    NOT_ANSWERING("not_answering", BadgeVariant.WARNING),

    /** It answers, refusing the key this server presents. */
    REFUSING("refusing", BadgeVariant.WARNING),

    /** Another database answers at its address; nothing more is sent there. */
    OTHER_SERVER("other_server", BadgeVariant.DESTRUCTIVE),

    /** It answers, running another version than this server. */
    OTHER_VERSION("other_version", BadgeVariant.WARNING);

    private final String token;
    private final BadgeVariant variant;

    LinkState(@NonNull String token, @NonNull BadgeVariant variant) {
        this.token = token;
        this.variant = variant;
    }

    @Override
    public @NonNull String token() {
        return this.token;
    }

    @Override
    public @NonNull BadgeVariant variant() {
        return this.variant;
    }

    /** @return whether the dashboard raises an attention item for a server in this state */
    public boolean needsAttention() {
        return switch (this) {
            case NOT_ANSWERING, REFUSING, OTHER_SERVER, OTHER_VERSION -> true;
            case REACHABLE, NOT_CHECKED -> false;
        };
    }

    /**
     * @param since when the calls started failing, read by the states that say it
     * @return the state in words ("Does not answer since 14:02")
     */
    public @NonNull Microcopy label(@Nullable Instant since) {
        Microcopy label = HohenheimMicrocopy.LINKED_SERVER.of("linked_server_state_" + this.token);
        return switch (this) {
            case NOT_ANSWERING, REFUSING -> label.withArg("since", Objects.requireNonNullElse(since, Instant.EPOCH));
            case REACHABLE, NOT_CHECKED, OTHER_SERVER, OTHER_VERSION -> label;
        };
    }

    /**
     * @param row        a linked server
     * @param ownVersion this server's version, what an answering server is compared with
     * @return the verdict of that server's row
     */
    public static @NonNull LinkState of(@NonNull Row row, @NonNull String ownVersion) {
        LinkFailure failure = LinkFailure.byToken(row.get(LinkedServerModel.LAST_FAILURE));
        if (failure != null) {
            return switch (failure) {
                case UNREACHABLE, SILENT, BAD_ANSWER -> NOT_ANSWERING;
                case REFUSED -> REFUSING;
                case OTHER_SERVER -> OTHER_SERVER;
            };
        }
        if (row.get(LinkedServerModel.LAST_SEEN_AT) == null) {
            return NOT_CHECKED;
        }
        return ownVersion.equals(row.get(LinkedServerModel.BUILD)) ? REACHABLE : OTHER_VERSION;
    }

    /** @return the state cell of a linked server's row: the badge, and why the calls fail under it */
    public static @NonNull StateLineCell cell(@NonNull Row row, @NonNull String ownVersion) {
        LinkState state = of(row, ownVersion);
        LinkFailure failure = LinkFailure.byToken(row.get(LinkedServerModel.LAST_FAILURE));
        return StateLineCell.of(state, state.label(row.get(LinkedServerModel.FAILING_SINCE)),
            failure == null ? null : failure.label());
    }

    /** @return the state cell of this server's own row, which answers by being the one asked */
    public static @NonNull StateLineCell ownCell() {
        return StateLineCell.of(REACHABLE, REACHABLE.label(null), null);
    }
}
