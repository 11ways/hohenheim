package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeAutoLoad;
import be.elevenways.protoblast.common.dry.BlastDrySerializers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The STORED health verdict for a host, with the two facts every surface renders it
 * through: the pl-status-dot token and the wording beside it.
 *
 * AIDEV-NOTE: this replaces a bare String state whose dot was chosen by a switch with a
 * {@code default -> "online"} arm. That arm is the whole reason this type exists: a state
 * the switch did not know -- a typo, a state added to the producer, a value revived from
 * an older payload -- rendered a GREEN dot, so "we have no idea how this host is" and
 * "this host is healthy" were the same pixel. A closed enum has no unknown member, and
 * the dot is a fact ON the member, so no future state can pick up green by omission.
 * ONLY {@link #OK} is online.
 *
 * The static initializer registers the DRY serializer/reviver pair so the state crosses
 * the web boundary as its own name; {@code @HawkeyeAutoLoad} forces TeaVM to run it.
 */
@HawkeyeAutoLoad
public enum HostState {

    /** Security verdict, read off {@code quarantined_at} and winning over everything. */
    QUARANTINED("quarantined", "error", "state_quarantined"),

    /** The last probe failed; the typed failure class travels beside it. */
    ERROR("error", "error", "state_error"),

    /** Reached once, but the last contact is older than the placement bound. */
    SILENT("silent", "warning", "state_silent"),

    /** Enrolled but never reached, so nothing about it is known yet. */
    NEVER_PROBED("never_probed", "idle", "state_never_probed"),

    /** Reached recently with no error: the ONLY state that is allowed to look green. */
    OK("ok", "online", null);

    private final String token;
    private final String dot;
    private final @Nullable String wordingKey;

    HostState(String token, String dot, @Nullable String wordingKey) {
        this.token = token;
        this.dot = dot;
        this.wordingKey = wordingKey;
    }

    /** The stable token rendered as {@code data-host-state} and branched on in templates. */
    public @NonNull String token() {
        return this.token;
    }

    /** The pl-status-dot token: online, warning, error or idle. */
    public @NonNull String dot() {
        return this.dot;
    }

    /**
     * The wording shown beside the dot.
     *
     * @return null for {@link #OK}, which shows the daemon label instead of a state word
     */
    public @Nullable Microcopy wording() {
        return this.wordingKey == null
            ? null : Microcopy.of(this.wordingKey).withFilter("scope", "server");
    }

    static {
        BlastDrySerializers.registerNameEnum(HostState.class);
    }
}
