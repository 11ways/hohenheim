package be.elevenways.hohenheim.cluster;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.text.Texts;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Where a linked server stands as the Servers list, its page, the dashboard and the alerts say it: one verdict per
 * row, read from the state the probe wrote and this server's own version.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum LinkState {

    /** It answered the last call, running this server's version. */
    REACHABLE("reachable", HealthTone.OK),

    /** Nothing was asked of it yet. */
    NOT_CHECKED("not_checked", HealthTone.UNKNOWN),

    /** The calls fail: nothing came back, or nothing readable. */
    NOT_ANSWERING("not_answering", HealthTone.ATTENTION),

    /** It answers, refusing the key this server presents. */
    REFUSING("refusing", HealthTone.ATTENTION),

    /** Another database answers at its address; nothing more is sent there. */
    OTHER_SERVER("other_server", HealthTone.BROKEN),

    /** It answers, running another version than this server. */
    OTHER_VERSION("other_version", HealthTone.ATTENTION),

    /**
     * Its database was restored to an earlier state: a kind's generation went below what this server keeps, and
     * nothing of that kind is taken until a person decides.
     */
    RESTORED("restored", HealthTone.ATTENTION);

    private final String token;
    private final HealthTone tone;

    LinkState(@NonNull String token, @NonNull HealthTone tone) {
        this.token = token;
        this.tone = tone;
    }

    /** @return the stable token a check answers and the words' key ends in */
    public @NonNull String token() {
        return this.token;
    }

    /** @return the verdict's tone, which colours the state's pill */
    public @NonNull HealthTone tone() {
        return this.tone;
    }

    /** @return whether the dashboard raises an attention item for a server in this state */
    public boolean needsAttention() {
        return this.tone.needsAttention();
    }

    /**
     * @param since when the calls started failing, read by the states that say it
     * @return the state in words ("Does not answer since 14:02")
     */
    public @NonNull Microcopy label(@Nullable Instant since) {
        Microcopy label = HohenheimMicrocopy.LINKED_SERVER.of("linked_server_state_" + this.token);
        return switch (this) {
            case NOT_ANSWERING, REFUSING -> label.withArg("since", Objects.requireNonNullElse(since, Instant.EPOCH));
            case REACHABLE, NOT_CHECKED, OTHER_SERVER, OTHER_VERSION, RESTORED -> label;
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
        if (Texts.trimmedOrNull(row.get(LinkedServerModel.WENT_BACK)) != null) {
            return RESTORED;
        }
        if (row.get(LinkedServerModel.LAST_SEEN_AT) == null) {
            return NOT_CHECKED;
        }
        return ownVersion.equals(row.get(LinkedServerModel.BUILD)) ? REACHABLE : OTHER_VERSION;
    }

    /**
     * @param row        a linked server
     * @param ownVersion this server's version, what an answering server is compared with
     * @return the verdict of that server's row: its state in words, and why the calls fail
     */
    public static @NonNull RecordHealth verdict(@NonNull Row row, @NonNull String ownVersion) {
        LinkState state = of(row, ownVersion);
        LinkFailure failure = LinkFailure.byToken(row.get(LinkedServerModel.LAST_FAILURE));
        return new RecordHealth(state.tone, state.label(row.get(LinkedServerModel.FAILING_SINCE)),
            failure == null ? null : failure.label(), List.of(), null);
    }

    /** @return the verdict of this server's own row, which answers by being the one asked */
    public static @NonNull RecordHealth ownVerdict() {
        return new RecordHealth(REACHABLE.tone, REACHABLE.label(null), null, List.of(), null);
    }
}
