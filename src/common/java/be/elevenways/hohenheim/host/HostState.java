package be.elevenways.hohenheim.host;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.dry.BlastDrySerializers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.plumage.component.StatusDotStatus;
import org.checkerframework.checker.nullness.qual.NonNull;

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
 * the web boundary as its own name; {@code @BlastAutoLoad} forces TeaVM to run it.
 */
@BlastAutoLoad
public enum HostState {

    /** Security verdict, read off {@code quarantined_at} and winning over everything. */
    QUARANTINED("quarantined", StatusDotStatus.DESTRUCTIVE, "state_quarantined", true, false),

    /** The last probe failed; the typed failure class travels beside it. */
    ERROR("error", StatusDotStatus.DESTRUCTIVE, "state_error", false, false),

    /** Reached once, but the last contact is older than the placement bound. */
    SILENT("silent", StatusDotStatus.WARNING, "state_silent", false, true),

    /** Enrolled but never reached, so nothing about it is known yet. */
    NEVER_PROBED("never_probed", StatusDotStatus.IDLE, "state_never_probed", false, false),

    /** Reached recently with no error: the ONLY state that is allowed to look green. */
    OK("ok", StatusDotStatus.ONLINE, "state_ok", false, true);

    private final String token;
    private final StatusDotStatus dot;
    private final String wordingKey;
    private final boolean loud;
    private final boolean namesDaemon;

    HostState(String token, StatusDotStatus dot, String wordingKey, boolean loud,
              boolean namesDaemon) {
        this.token = token;
        this.dot = dot;
        this.wordingKey = wordingKey;
        this.loud = loud;
        this.namesDaemon = namesDaemon;
    }

    /** The stable token rendered as {@code data-host-state} and branched on in templates. */
    public @NonNull String token() {
        return this.token;
    }

    /** The typed pl-status-dot state: only OK is online. */
    public @NonNull StatusDotStatus dot() {
        return this.dot;
    }

    /** Whether the wording renders emphasized: a security verdict must not read like weather. */
    public boolean loud() {
        return this.loud;
    }

    /**
     * Whether the wording names the daemon ({@code {$daemon}}): a host that answered says what answered and that the
     * time after it is when it was last seen ("Docker 27.1, seen 3 minutes ago").
     */
    public boolean namesDaemon() {
        return this.namesDaemon;
    }

    /** @return the wording shown beside the dot, before its arguments */
    public @NonNull Microcopy wording() {
        return HohenheimMicrocopy.SERVER.of(this.wordingKey);
    }

    static {
        BlastDrySerializers.registerNameEnum(HostState.class);
    }
}
