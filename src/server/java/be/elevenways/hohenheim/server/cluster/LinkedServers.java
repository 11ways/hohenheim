package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.cluster.JoinRefusal;
import be.elevenways.hohenheim.cluster.LinkFailure;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.model.HohenheimPrincipalKind;
import be.elevenways.hohenheim.model.LinkedServerCodeModel;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.MachineName;
import be.elevenways.hohenheim.server.cms.InstallationCounts;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.async.AsyncFailures;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.thread.JobRunnerGroup;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.bootstrap.RuntimeJobs;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.lease.Lease;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.orm.lease.Leases;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.routing.RateLimitPolicy;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.ExternalUrls;
import be.elevenways.zenit.server.orm.command.CommandPayloads;
import be.elevenways.zenit.server.security.OneTimeCodes;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * THE verbs of the link between two Hohenheims: issue a join code, add a server with one, answer a join, a status
 * and a leave, check a linked server and remove one.
 *
 * AIDEV-NOTE: credentials go both ways at the join. The master mints the key the managed server presents to it and
 * sends it with the code; the managed server stores that key encrypted, mints the key the master presents to it and
 * answers it. Each side keeps only the keyed digest ({@link #DIGEST_PURPOSE}, core's CommandPayloads.keyedDigests) of
 * the key it accepts, so a database copy never holds a usable incoming credential. A key names a linked server
 * (HohenheimPrincipalKind.LINKED_SERVER) and carries no authority.
 *
 * AIDEV-NOTE: every creation of a linked-server row (a join answered here, an add made here) runs holding the one
 * lease {@link #CREATE_KEY}, its checks (one master, a managed server manages nobody, the name and the address are
 * free) inside it, so two joins or adds at the same instant never both pass them, on one process or several over this
 * database. Core's Leases refuse an acquisition inside a transaction on SQLite: it is taken before the join code's
 * transaction, never inside it.
 *
 * AIDEV-NOTE: the state fields are the probe's: an answer clears failing_since and alerted_at, a failure sets
 * failing_since once, and alerted_at once the run of failures lasted Alerts.DOWN_AFTER (the grace a site outage
 * waits too), or at once for a failure that stops all sending. Each move is a compare-and-set on the two values read,
 * and the "stopped answering" and "answers again" alerts are sent only by the call that won it, so two sweepers over
 * one database never alert twice; "answers again" only follows a "stopped answering" that was sent. Nothing else of
 * SiteOutages' bookkeeping is shared: its up/down latch serves a passive reading with five outcomes, which a call that
 * either answers or fails does not have.
 *
 * AIDEV-NOTE: a sweep asks its servers at the same time ({@link #CHECKS}, at most {@link #CHECKS_AT_ONCE}), each
 * writing its own row: up to that many silent servers cost one call deadline together, not one each in turn.
 *
 * AIDEV-NOTE: a join commits on two servers apart: the joining server commits its master's row, the grants and the
 * renumbering, answers, and only then does the master write its row of that server. So each side's row is made
 * UNCONFIRMED (confirmed_at null). The joining side's row is confirmed by a status call its master makes (proof the
 * master holds its row; the STATUS handler answers only once {@link #confirmed} stamped it, so an answer never comes
 * from a row just undone) or by its own call the master answers; the master's row is confirmed ONLY by an answer to
 * its own status call, never by a call it receives: so a confirmed master row implies a confirmed joining row. The
 * master's add makes that call at once. Only the joining side undoes by time: a join not confirmed within
 * {@link #CONFIRM_WITHIN} is undone at its next failing check (the master's row, then back to the own numbering
 * with CodeBlocks.leave; the renumbered codes keep their new numbers and old spellings, the master's grant stays void
 * and its numbers unused). The master forgets an unconfirmed row the other side refuses (that side never confirmed,
 * and it undid its join or never kept it). Both undoes delete only a row still unconfirmed, so a confirmation that
 * wins the race keeps the link. A master's unconfirmed row whose server stays unreachable says so ("Joining, not
 * confirmed yet") until the two speak again, which settles it either way: no side can know more without that call.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class LinkedServers {

    /** The keyring purpose a link key's digest is keyed under. */
    private static final String DIGEST_PURPOSE = "hohenheim.cluster_link";

    /** Random bytes per link key: 256 bits. */
    private static final int KEY_BYTES = 32;

    /** The letters of a join code, shown as three groups of four. */
    public static final int CODE_LENGTH = 12;

    /** How long a join code waits. */
    public static final Duration CODE_LIFE = Duration.ofMinutes(10);

    /** The longest name a linked server takes, the column's width. */
    public static final int NAME_MAX = 100;

    /** Wrong join codes one address may try before the codes stop being looked at. */
    static final RateLimitPolicy JOIN_FAILURES = RateLimitPolicy.of(5, Duration.ofMinutes(15))
        .keyBy(RateLimitPolicy.KeyBy.IP)
        .named("hh_cluster_join_failures");

    private static final OneTimeCodes<UUID> CODES = OneTimeCodes.of("hohenheim.cluster_join", CODE_LENGTH, CODE_LIFE,
        JOIN_FAILURES, new OneTimeCodes.Store<>(() -> Models.get(LinkedServerCodeModel.class),
            LinkedServerCodeModel.ID, LinkedServerCodeModel.OWNER_ID, LinkedServerCodeModel.CODE_DIGEST,
            LinkedServerCodeModel.EXPIRES_AT, LinkedServerCodeModel.USED_AT, LinkedServerCodeModel.CREATED_AT));

    /** The one lease every creation of a linked-server row holds. */
    static final String CREATE_KEY = LeaseKeys.singleton(HohenheimIds.id("cluster_link"), "hohenheim:cluster_link")
        .key();

    /** How long a creation waits for another: longer than the one call an add makes while it holds the lease. */
    private static final int CREATE_WAIT_SECONDS = (int) LinkCalls.DEADLINE.plusSeconds(5).toSeconds();

    /** How long a join may take here before it is refused: half the deadline the master waits for its answer. */
    private static final Duration JOIN_ANSWER_WITHIN = LinkCalls.DEADLINE.dividedBy(2);

    /** How long a joining server waits for its join to be confirmed before its next failing check undoes it. */
    public static final Duration CONFIRM_WITHIN = Duration.ofMinutes(5);

    /** How many linked servers one sweep asks at the same time; the rest wait their turn in the sweep. */
    static final int CHECKS_AT_ONCE = 8;

    /**
     * The pool a sweep's checks run on: a runtime member, so the runtime's stop lets a running check end (one call
     * deadline) before the datasources it writes close, and drops the ones still waiting.
     */
    private static final JobRunnerGroup.Member CHECKS =
        RuntimeJobs.GROUP.member("hh-cluster-checks", JobRunnerGroup.StopMode.DRAIN, CHECKS_AT_ONCE);

    private LinkedServers() {
    }

    /** What removing a linked server did on the other side. */
    public enum Removal {

        /** The other server forgot the link too, or never held it. */
        LEFT("left"),

        /** The other server did not answer: it holds the link until it is removed there. */
        KEPT_THERE("kept_there"),

        /** Nothing was sent: another server answers at that address. */
        NOT_SENT("not_sent");

        private final String token;

        Removal(@NonNull String token) {
            this.token = token;
        }

        /** @return the token a placement's result carries */
        public @NonNull String token() {
            return this.token;
        }

        /** @return what the toast says, naming the removed server */
        public @NonNull Microcopy message(@NonNull String name) {
            return HohenheimMicrocopy.LINKED_SERVER.of("linked_server_removed_" + this.token).withArg("name", name);
        }

        /** @return the member a token names, null for one this build does not declare */
        public static @Nullable Removal byToken(@Nullable String token) {
            for (Removal removal : values()) {
                if (removal.token.equals(token)) {
                    return removal;
                }
            }
            return null;
        }
    }

    // --- Reading ---

    /** @return this server's own name: the setting, else its machine's host name up to the first dot */
    public static @NonNull String ownName() {
        String configured = Texts.trimmedOrNull(Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Cluster.NAME));
        return configured != null ? configured : MachineName.firstLabel();
    }

    /** @return this server's version, the one a linked server answering differently is compared with */
    public static @NonNull String ownVersion() {
        return HostPreflight.controllerVersion();
    }

    /** @return every linked server, by name */
    public static @NonNull List<Row> all() {
        return Models.get(LinkedServerModel.class).find().noCache().orderBy(LinkedServerModel.NAME, SortOrder.ASC)
            .orderBy(LinkedServerModel.ID, SortOrder.ASC).all();
    }

    /** @return the linked server with this id, null for none */
    public static @Nullable Row byId(@NonNull UUID id) {
        return Models.get(LinkedServerModel.class).find().noCache().where(LinkedServerModel.ID.eq(id)).first();
    }

    /** @return this server's master, null while no other server manages it */
    public static @Nullable Row master() {
        return Models.get(LinkedServerModel.class).find().noCache()
            .where(LinkedServerModel.ROLE.eq(LinkRole.MANAGED_BY.token())).first();
    }

    /** @return whether this server manages at least one other */
    public static boolean managesAny() {
        return Models.get(LinkedServerModel.class).find().noCache()
            .where(LinkedServerModel.ROLE.eq(LinkRole.MANAGES.token())).count() > 0;
    }

    /** @return the role a linked server's row stores */
    public static @Nullable LinkRole roleOf(@NonNull Row row) {
        return LinkRole.byToken(row.get(LinkedServerModel.ROLE));
    }

    /** @return the principal a linked server's key names: which server asks, with no authority of its own */
    public static @NonNull PrincipalRef principalOf(@NonNull Row row) {
        return new PrincipalRef(HohenheimPrincipalKind.LINKED_SERVER, row.get(LinkedServerModel.ID));
    }

    /**
     * @param key the key a caller presented
     * @return the linked server whose key it is, null for an unknown key
     */
    public static @Nullable Row byKey(@Nullable String key) {
        String presented = Texts.trimmedOrNull(key);
        if (presented == null) {
            return null;
        }
        return Models.get(LinkedServerModel.class).find().noCache()
            .where(LinkedServerModel.LINK_KEY_DIGEST.in(CommandPayloads.keyedDigests(DIGEST_PURPOSE, presented)))
            .first();
    }

    // --- The one master rule ---

    /**
     * The one answer to "may this server manage another": a managed server manages nobody; Add a Hohenheim server is
     * offered dead in these words and refuses in them.
     *
     * @return why not, null when it may
     */
    public static @Nullable Microcopy whyCannotManage() {
        Row master = master();
        return master == null ? null : HohenheimMicrocopy.VIOLATIONS.of("linked_server_is_managed")
            .withArg("name", String.valueOf((Object) master.get(LinkedServerModel.NAME)));
    }

    /** @return why this server cannot get a master, null when it can */
    public static @Nullable JoinRefusal whyNoMaster() {
        if (master() != null) {
            return JoinRefusal.HAS_MASTER;
        }
        return managesAny() ? JoinRefusal.MANAGES_OTHERS : null;
    }

    /**
     * The one answer to "may this server offer a join code": no master and nobody it manages; Let another Hohenheim
     * manage this server is offered dead in these words and refuses in them.
     *
     * @return why not, null when it may
     */
    public static @Nullable Microcopy whyCannotOffer() {
        JoinRefusal refusal = whyNoMaster();
        return refusal == null ? null : refusal.message();
    }

    // --- Joining: the managed side ---

    /**
     * Issues the account's join code, using up the one it had waiting.
     *
     * @return the code's row id with the code, the only time the code is ever answered
     * @throws Violations when this server cannot get a master
     */
    public static @NonNull IssuedCode offerJoin(@NonNull UUID owner) {
        Microcopy refused = whyCannotOffer();
        if (refused != null) {
            throw Violations.ofForm(refused);
        }
        OneTimeCodes.Issued issued = CODES.issue(owner);
        Row waiting = CODES.waiting(owner, Now.instant());
        if (waiting == null) {
            throw new IllegalStateException("A join code just issued is not waiting");
        }
        return new IssuedCode(waiting.get(LinkedServerCodeModel.ID), issued.code(), issued.expiresAt());
    }

    /**
     * A join code just issued.
     *
     * @param id        its row
     * @param code      the letters, shown once
     * @param expiresAt until when it is valid
     */
    public record IssuedCode(@NonNull UUID id, @NonNull String code, @NonNull Instant expiresAt) {

        /** @return the code as it is shown: groups of four letters ("7KQ2-M9XD-4TRA") */
        public @NonNull String shown() {
            StringBuilder shown = new StringBuilder(this.code.length() + this.code.length() / 4);
            for (int index = 0; index < this.code.length(); index++) {
                if (index > 0 && index % 4 == 0) {
                    shown.append('-');
                }
                shown.append(this.code.charAt(index));
            }
            return shown.toString();
        }
    }

    /** @return until when the code issued under this row id is valid, null for no such code */
    public static @Nullable Instant codeExpiry(@NonNull UUID id) {
        Row code = Models.get(LinkedServerCodeModel.class).find().noCache()
            .where(LinkedServerCodeModel.ID.eq(id)).first();
        return code == null ? null : code.get(LinkedServerCodeModel.EXPIRES_AT);
    }

    /** @return the account's join code still waiting now, null for none */
    public static @Nullable Row waitingCode(@NonNull UUID owner) {
        return CODES.waiting(owner, Now.instant());
    }

    /** What a join call is answered with: the answer's fields, or why it was refused. */
    public sealed interface Joined permits Accepted, Refused {
    }

    /** The join was accepted: what this server answers. */
    public record Accepted(@NonNull Map<String, Object> answer) implements Joined {
    }

    /** The join was refused. */
    public record Refused(@NonNull JoinRefusal refusal) implements Joined {
    }

    /**
     * Answers a master's join under the creation lease: the code is redeemed within the asking address's failure
     * budget, and inside the code's transaction this server's row of its master is made, unconfirmed, with the key this
     * server presents to it, and its code numbers are taken from that master (ClusterCodes.join); a failure there rolls
     * all of it back and refuses the join. The master's first status call confirms the row ({@link #confirmed}).
     *
     * @param body  the master's name, address, controller token, version, the key this server shall present to it, its
     *              ticket for the code numbers and the code
     * @param scope what wrong codes are counted against: the asking address
     */
    public static @NonNull Joined acceptJoin(@NonNull Map<String, Object> body, @NonNull String scope) {
        Lease lease = createLease();
        if (lease == null) {
            return new Refused(JoinRefusal.BUSY);
        }
        try {
            JoinRefusal refusal = whyNoMaster();
            if (refusal != null) {
                return new Refused(refusal);
            }
            String name = text(body.get("name"));
            String baseUrl = baseUrlOrNull(text(body.get("base_url")));
            String token = text(body.get("token"));
            String key = text(body.get("key"));
            String ticket = text(body.get("ticket"));
            if (name == null || name.length() > NAME_MAX || baseUrl == null || token == null || key == null
                    || ticket == null) {
                return new Refused(JoinRefusal.UNREADABLE);
            }
            String build = text(body.get("build"));
            String ownKey = SecureTokens.randomToken(KEY_BYTES);
            OneTimeCodes.Redeemed<Row> redeemed;
            Now.Stopwatch asked = Now.stopwatch();
            try {
                redeemed = CODES.redeem(text(body.get("code")), scope, code -> {
                    Row master = Models.get(LinkedServerModel.class).createEmptyRow();
                    master.set(LinkedServerModel.ID, Uuids.v7());
                    master.set(LinkedServerModel.NAME, name);
                    master.set(LinkedServerModel.ROLE, LinkRole.MANAGED_BY.token());
                    master.set(LinkedServerModel.BASE_URL, baseUrl);
                    master.set(LinkedServerModel.LINK_KEY, key);
                    master.set(LinkedServerModel.LINK_KEY_DIGEST, digestOf(ownKey));
                    master.set(LinkedServerModel.REMOTE_TOKEN, token);
                    master.set(LinkedServerModel.BUILD, build);
                    master.set(LinkedServerModel.LAST_SEEN_AT, Now.instant());
                    Models.get(LinkedServerModel.class).insert(master, Change.PLAIN);
                    ClusterCodes.join(master, key, ticket);
                    // The master waits one call deadline for this answer: a join that took half of it is given up
                    // here, so it is never kept on this side while the master stopped waiting.
                    if (asked.elapsedNanos() > JOIN_ANSWER_WITHIN.toNanos()) {
                        throw new DomainRefusal(ZenitRefusalReason.SERVICE_UNAVAILABLE, "the code numbers took longer"
                            + " than " + JOIN_ANSWER_WITHIN + " to arrive");
                    }
                    return master;
                });
            } catch (DomainRefusal noNumbers) {
                Blast.log("CLUSTER: a join is refused, the code numbers were not taken -", noNumbers.getMessage());
                // A switch of where numbers come from held the fence past the wait: busy, as a held creation lease is.
                return new Refused(noNumbers.is(ZenitRefusalReason.IN_PROGRESS) ? JoinRefusal.BUSY : JoinRefusal.CODES);
            }
            return switch (redeemed) {
                case OneTimeCodes.Refused<Row> refused -> new Refused(switch (refused.why()) {
                    case TOO_MANY_TRIES -> JoinRefusal.TOO_MANY_TRIES;
                    case NO_SUCH_CODE -> JoinRefusal.NO_SUCH_CODE;
                    case EXPIRED -> JoinRefusal.EXPIRED;
                });
                case OneTimeCodes.Used<Row> used -> new Accepted(ownStatus(ownKey));
            };
        } finally {
            lease.release();
        }
    }

    // --- Joining: the master's side ---

    /**
     * Links the server at {@code address} as one this server manages, presenting the join code it issued, under the
     * creation lease.
     *
     * @return the new linked server
     * @throws Violations naming the field or the refusal, in words
     */
    public static @NonNull Row add(@Nullable String name, @Nullable String address, @Nullable String code) {
        Lease lease = createLease();
        if (lease == null) {
            throw Violations.ofForm(JoinRefusal.BUSY.message());
        }
        Row server;
        try {
            server = addHolding(name, address, code);
        } finally {
            lease.release();
        }
        // The confirming call, outside the lease: a creation waiting behind this one waits one call, not two.
        Row checked = check(server);
        if (byId(server.get(LinkedServerModel.ID)) == null) {
            // That server refused the link it just answered: it holds none, so neither does this one.
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("linked_server_join_unanswered")
                .withArg("address", String.valueOf((Object) server.get(LinkedServerModel.BASE_URL)))
                .withArg("window", CONFIRM_WITHIN));
        }
        return checked;
    }

    private static @NonNull Row addHolding(@Nullable String name, @Nullable String address, @Nullable String code) {
        Microcopy refused = whyCannotManage();
        if (refused != null) {
            throw Violations.ofForm(refused);
        }
        String trimmed = Texts.trimmedOrNull(name);
        if (trimmed == null || trimmed.length() > NAME_MAX) {
            throw Violations.ofField("name", name, HohenheimMicrocopy.VIOLATIONS.of("linked_server_name_invalid")
                .withArg("max", NAME_MAX));
        }
        String baseUrl = baseUrlOrNull(address);
        if (baseUrl == null) {
            throw Violations.ofField("address", address,
                HohenheimMicrocopy.VIOLATIONS.of("linked_server_address_invalid"));
        }
        for (Row linked : all()) {
            if (trimmed.equalsIgnoreCase(linked.get(LinkedServerModel.NAME))) {
                throw Violations.ofField("name", name, HohenheimMicrocopy.VIOLATIONS.of("linked_server_name_taken"));
            }
            if (baseUrl.equals(linked.get(LinkedServerModel.BASE_URL))) {
                throw Violations.ofField("address", address,
                    HohenheimMicrocopy.VIOLATIONS.of("linked_server_address_taken")
                        .withArg("name", String.valueOf((Object) linked.get(LinkedServerModel.NAME))));
            }
        }
        String ownBase = baseUrlOrNull(ExternalUrls.configuredBase());
        if (ownBase == null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("linked_server_needs_own_address"));
        }
        if (baseUrl.equalsIgnoreCase(ownBase)) {
            throw Violations.ofField("address", address, HohenheimMicrocopy.VIOLATIONS.of("linked_server_address_own"));
        }
        UUID id = Uuids.v7();
        String theirKey = SecureTokens.randomToken(KEY_BYTES);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code == null ? "" : code);
        body.put("name", ownName());
        body.put("base_url", ownBase);
        body.put("token", ControllerIdentity.token());
        body.put("build", ownVersion());
        body.put("key", theirKey);
        body.put("ticket", ClusterCodes.ticket(id, theirKey));
        LinkCalls.Answer answer = LinkCalls.join(baseUrl, body);
        if (!(answer instanceof LinkCalls.Answered answered)) {
            // That server may have joined and lost only its answer: it undoes the join itself once unconfirmed.
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("linked_server_join_unanswered")
                .withArg("address", baseUrl).withArg("window", CONFIRM_WITHIN));
        }
        Map<String, Object> joined = answered.body();
        if (answered.status() != 200) {
            JoinRefusal refusal = joined.get("refusal") instanceof String token ? JoinRefusal.byToken(token)
                : JoinRefusal.UNREADABLE;
            throw switch (refusal) {
                case NO_SUCH_CODE, EXPIRED, TOO_MANY_TRIES -> Violations.ofField("join_code", code, refusal.message());
                case HAS_MASTER, MANAGES_OTHERS, BUSY, UNREADABLE, CODES -> Violations.ofForm(refusal.message());
            };
        }
        return recorded(id, trimmed, baseUrl, theirKey, joined);
    }

    /**
     * Writes the master's row of a server whose join it answered, unconfirmed, in force with that join's grants, and
     * asks for that server's records; the add's commit, which a master stopping right after leaves unconfirmed.
     *
     * @param theirKey the key the master minted for that server to present here
     * @param joined   that server's answer to the join
     * @return the new row
     * @throws Violations when the answer carries no key or identity
     */
    static @NonNull Row recorded(@NonNull UUID id, @NonNull String name, @NonNull String baseUrl,
                                 @NonNull String theirKey, @NonNull Map<String, Object> joined) {
        String token = text(joined.get("token"));
        String key = text(joined.get("key"));
        if (token == null || key == null) {
            throw Violations.ofForm(JoinRefusal.UNREADABLE.message());
        }
        Row server = Models.get(LinkedServerModel.class).createEmptyRow();
        server.set(LinkedServerModel.ID, id);
        server.set(LinkedServerModel.NAME, name);
        server.set(LinkedServerModel.ROLE, LinkRole.MANAGES.token());
        server.set(LinkedServerModel.BASE_URL, baseUrl);
        server.set(LinkedServerModel.LINK_KEY, key);
        server.set(LinkedServerModel.LINK_KEY_DIGEST, digestOf(theirKey));
        server.set(LinkedServerModel.REMOTE_TOKEN, token);
        server.set(LinkedServerModel.BUILD, text(joined.get("build")));
        server.set(LinkedServerModel.APPS, count(joined.get("apps")));
        server.set(LinkedServerModel.SITES, count(joined.get("sites")));
        server.set(LinkedServerModel.LAST_SEEN_AT, Now.instant());
        // The row and the grants made for this join come into force together; a join never recorded leaves them void.
        Models.get(LinkedServerModel.class).getResolvedDatasource().withTransaction(transaction -> {
            Models.get(LinkedServerModel.class).insert(server, Change.PLAIN);
            ClusterCodes.joined(server);
        });
        // The master takes in everything that server has at once, in the background, so the add answers now.
        ClusterRecords.request(server.get(LinkedServerModel.ID));
        return server;
    }

    // --- Status and leave: the called side ---

    /**
     * @return what this server answers a linked server's status call: its name, identity, version, time and the two
     *         counts its own dashboard and Sites list show (InstallationCounts)
     */
    public static @NonNull Map<String, Object> ownStatus() {
        return ownStatus(null);
    }

    private static @NonNull Map<String, Object> ownStatus(@Nullable String key) {
        InstallationCounts counts = InstallationCounts.read();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("name", ownName());
        status.put("token", ControllerIdentity.token());
        status.put("build", ownVersion());
        status.put("time", Now.instant().toString());
        status.put("apps", counts.apps());
        status.put("sites", counts.sites());
        if (key != null) {
            status.put("key", key);
        }
        return status;
    }

    /** Forgets a linked server whose own key asked this server to: the called side of a removal. */
    public static void left(@NonNull Row row) {
        forget(row);
    }

    // --- Checking ---

    /**
     * Asks every linked server of both roles at the same time, a failing one too, so its return is noticed within a
     * minute; one that another database answers for is asked nothing until an administrator joins it again. Returns
     * once every check it started ended; a runtime stopping meanwhile starts no further one.
     *
     * @return how many were asked
     */
    public static int sweep() {
        List<CompletableFuture<Boolean>> checks = new ArrayList<>();
        for (Row row : all()) {
            if (stopsSending(row)) {
                undoUnconfirmed(row, null);
                continue;
            }
            CompletableFuture<Void> started;
            try {
                started = CHECKS.get().runAsync(() -> check(row));
            } catch (IllegalStateException stopping) {
                Blast.log("CLUSTER: the runtime stops, no further server is checked -", stopping.getMessage());
                break;
            }
            checks.add(started.handle((done, failed) -> {
                if (failed == null) {
                    return true;
                }
                // One server must never end the sweep for the rest.
                Blast.log("CLUSTER: could not check", row.get(LinkedServerModel.NAME), "-",
                    AsyncFailures.cause(failed).getMessage());
                return false;
            }));
        }
        int asked = 0;
        for (CompletableFuture<Boolean> check : checks) {
            if (check.join()) {
                asked++;
            }
        }
        return asked;
    }

    /**
     * Asks one linked server who answers at its address and writes what came back; asks nothing of one another
     * database answers for. An answer confirms an unconfirmed link; a failure may undo one ({@link #undoUnconfirmed}).
     *
     * @return the row as it stands after the call; the row as read when the call undid the link
     */
    public static @NonNull Row check(@NonNull Row row) {
        if (stopsSending(row)) {
            undoUnconfirmed(row, null);
            return row;
        }
        UUID id = row.get(LinkedServerModel.ID);
        LinkCalls.Answer answer = LinkCalls.status(row.get(LinkedServerModel.BASE_URL),
            row.get(LinkedServerModel.LINK_KEY));
        LinkFailure failure = failureOf(row, answer);
        if (failure == null) {
            confirmed(row);
            answered(row, ((LinkCalls.Answered) answer).body());
        } else if (!undoUnconfirmed(row, failure)) {
            failed(row, failure);
        }
        Row now = byId(id);
        return now == null ? row : now;
    }

    /**
     * Confirms a link the other side proved it holds (on the joining side: its master asked with its key; on either
     * side: the other answered this server's key); a link confirmed before stays as it was.
     *
     * @return whether the link stands, confirmed; false once this side undid it, so its key is answered as unknown
     */
    public static boolean confirmed(@NonNull Row row) {
        if (row.get(LinkedServerModel.CONFIRMED_AT) != null) {
            return true;
        }
        if (write(row).and(LinkedServerModel.CONFIRMED_AT.isNull()).assign(LinkedServerModel.CONFIRMED_AT,
                Now.instant()).updateAll() > 0) {
            return true;
        }
        Row now = byId(row.get(LinkedServerModel.ID));
        return now != null && now.get(LinkedServerModel.CONFIRMED_AT) != null;
    }

    /**
     * Undoes a link that was never confirmed and cannot be any more: on the joining side once its join is older than
     * {@link #CONFIRM_WITHIN} and its check failed (or nothing is sent there); on the master's side when the other side
     * refuses its key, as it does once it undid the join or never kept it.
     *
     * @param failure why the check failed; null for a server nothing is sent to
     * @return whether the link was undone
     */
    private static boolean undoUnconfirmed(@NonNull Row row, @Nullable LinkFailure failure) {
        if (row.get(LinkedServerModel.CONFIRMED_AT) != null) {
            return false;
        }
        Instant made = Objects.requireNonNullElse(row.get(LinkedServerModel.CREATED_AT), Instant.EPOCH);
        boolean undone = switch (roleOf(row)) {
            case MANAGED_BY -> !Now.instant().isBefore(made.plus(CONFIRM_WITHIN));
            case MANAGES -> failure == LinkFailure.REFUSED;
            case null -> false;
        };
        if (!undone || !forget(row, true)) {
            return false;
        }
        Blast.log("CLUSTER: the link with", row.get(LinkedServerModel.NAME), "was never confirmed and is undone");
        return true;
    }

    /** @return why the call failed, null when the server that joined answered */
    private static @Nullable LinkFailure failureOf(@NonNull Row row, LinkCalls.@NonNull Answer answer) {
        return switch (answer) {
            case LinkCalls.Failed failed -> failed.failure();
            case LinkCalls.Answered answered -> {
                if (answered.status() == 401 || answered.status() == 403) {
                    yield LinkFailure.REFUSED;
                }
                String token = text(answered.body().get("token"));
                if (answered.status() != 200 || token == null || text(answered.body().get("build")) == null) {
                    yield LinkFailure.BAD_ANSWER;
                }
                yield token.equals(row.get(LinkedServerModel.REMOTE_TOKEN)) ? null : LinkFailure.OTHER_SERVER;
            }
        };
    }

    /**
     * The server answered: its version, its two counts and when are stamped, one whose stop was alerted alerts its
     * return, and a server this one manages is synced.
     */
    private static void answered(@NonNull Row row, @NonNull Map<String, Object> status) {
        Instant now = Now.instant();
        Instant failingSince = row.get(LinkedServerModel.FAILING_SINCE);
        Instant alertedAt = row.get(LinkedServerModel.ALERTED_AT);
        int won = asRead(row).assign(LinkedServerModel.LAST_SEEN_AT, now)
            .assign(LinkedServerModel.BUILD, text(status.get("build")))
            .assign(LinkedServerModel.APPS, count(status.get("apps")))
            .assign(LinkedServerModel.SITES, count(status.get("sites")))
            .assign(LinkedServerModel.FAILING_SINCE, null)
            .assign(LinkedServerModel.LAST_FAILURE, null)
            .assign(LinkedServerModel.ALERTED_AT, null)
            .updateAll();
        switch (roleOf(row)) {
            case MANAGES -> ClusterRecords.request(row.get(LinkedServerModel.ID));
            case MANAGED_BY -> ClusterCodes.ask();
            case null -> {
            }
        }
        if (won == 0 || failingSince == null || alertedAt == null) {
            return;
        }
        String name = String.valueOf((Object) row.get(LinkedServerModel.NAME));
        Alerts.trySend(NotificationEvents.LINKED_SERVER_RECOVERED,
            Alerts.about(LinkedServerModel.MODEL_ID, row.get(LinkedServerModel.ID)),
            HohenheimMicrocopy.ALERT.of("linked_server_recovered_subject").withArg("name", name),
            HohenheimMicrocopy.ALERT.of("linked_server_recovered_body")
                .withArg("duration", Duration.between(failingSince, now)));
    }

    /**
     * The call failed: the first failure of a run is stamped at once, and the run alerts once when it has lasted
     * {@link Alerts#DOWN_AFTER}, or at once for a failure that stops all sending.
     */
    private static void failed(@NonNull Row row, @NonNull LinkFailure failure) {
        Instant now = Now.instant();
        Instant failingSince = row.get(LinkedServerModel.FAILING_SINCE);
        Instant since = failingSince == null ? now : failingSince;
        boolean alerts = row.get(LinkedServerModel.ALERTED_AT) == null
            && (failure.stopsSending() || !now.isBefore(since.plus(Alerts.DOWN_AFTER)));
        QueryBuilder<Row> update = asRead(row).assign(LinkedServerModel.FAILING_SINCE, since)
            .assign(LinkedServerModel.LAST_FAILURE, failure.token());
        if (alerts) {
            update = update.assign(LinkedServerModel.ALERTED_AT, now);
        }
        int won = update.updateAll();
        if (won == 0 || !alerts) {
            return;
        }
        UUID id = row.get(LinkedServerModel.ID);
        String name = String.valueOf((Object) row.get(LinkedServerModel.NAME));
        Alerts.trySend(NotificationEvents.LINKED_SERVER_DOWN, Alerts.about(LinkedServerModel.MODEL_ID, id),
            HohenheimMicrocopy.ALERT.of("linked_server_down_subject").withArg("name", name),
            HohenheimMicrocopy.ALERT.of("linked_server_down_body").withArg("name", name).withArg("since", since)
                .withArg("detail", failure.label()));
    }

    // --- Removing ---

    /**
     * Removes a linked server: the other side is asked to forget it too, and this side forgets it whatever it
     * answered; a server another database answers for is sent nothing.
     *
     * @return what became of the link on the other side
     */
    public static @NonNull Removal remove(@NonNull Row row) {
        if (stopsSending(row)) {
            forget(row);
            return Removal.NOT_SENT;
        }
        LinkCalls.Answer answer = LinkCalls.leave(row.get(LinkedServerModel.BASE_URL),
            row.get(LinkedServerModel.LINK_KEY));
        forget(row);
        if (answer instanceof LinkCalls.Answered answered
                && (answered.status() == 200 || answered.status() == 401 || answered.status() == 403)) {
            // A refused key means the other side holds no link with this key any more: it is gone there too.
            return Removal.LEFT;
        }
        return Removal.KEPT_THERE;
    }

    /**
     * Removes a linked server's row and, in the same step, everything this server keeps of it; a master's row leaves
     * this server's code numbers to its own sequence again (ClusterCodes.left).
     */
    private static void forget(@NonNull Row row) {
        forget(row, false);
    }

    /**
     * {@link #forget(Row)}, of a row still unconfirmed alone when asked: a confirmation that won the race keeps it.
     *
     * @return whether the row was removed
     */
    private static boolean forget(@NonNull Row row, boolean onlyUnconfirmed) {
        UUID id = row.get(LinkedServerModel.ID);
        return Models.get(LinkedServerModel.class).getResolvedDatasource().inTransaction(() -> {
            QueryBuilder<Row> rows = Models.get(LinkedServerModel.class).find().where(LinkedServerModel.ID.eq(id));
            if (onlyUnconfirmed) {
                rows = rows.and(LinkedServerModel.CONFIRMED_AT.isNull());
            }
            if (rows.delete() == 0) {
                return false;
            }
            ClusterRecords.forget(id);
            if (roleOf(row) == LinkRole.MANAGED_BY) {
                ClusterCodes.left();
            }
            return true;
        });
    }

    // --- Helpers ---

    /** @return whether nothing more is sent to this server's address until an administrator joins it again */
    public static boolean stopsSending(@NonNull Row row) {
        LinkFailure failure = LinkFailure.byToken(row.get(LinkedServerModel.LAST_FAILURE));
        return failure != null && failure.stopsSending();
    }

    /**
     * @return an address as it is stored: an https origin with a host, no path, query or credentials, without a
     *         trailing slash; null for anything else
     */
    public static @Nullable String baseUrlOrNull(@Nullable String address) {
        String trimmed = Texts.trimmedOrNull(address);
        if (trimmed == null) {
            return null;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException malformed) {
            return null;
        }
        boolean bare = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) && uri.getRawQuery() == null
            && uri.getRawFragment() == null && uri.getRawUserInfo() == null;
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && bare ? trimmed : null;
    }

    /** @return the creation lease, waited for; null when another creation held it for the whole wait */
    private static @Nullable Lease createLease() {
        return Leases.of(Models.get(LinkedServerModel.class).getResolvedDatasource())
            .acquire(CREATE_KEY, CREATE_WAIT_SECONDS);
    }

    static @NonNull String digestOf(@NonNull String key) {
        return CommandPayloads.keyedDigests(DIGEST_PURPOSE, key).getFirst();
    }

    /** @return whether a stored key digest is the presented key's, under any key of the keyring */
    static boolean keyMatches(@NonNull String digest, @NonNull String key) {
        return CommandPayloads.keyedDigests(DIGEST_PURPOSE, key).contains(digest);
    }

    private static @Nullable String text(@Nullable Object value) {
        return value instanceof String string ? Texts.trimmedOrNull(string) : null;
    }

    /** @return a count a status answer carries, null when it carries none (an older version answering) */
    private static @Nullable Integer count(@Nullable Object value) {
        return value instanceof Number number && number.longValue() >= 0 && number.longValue() <= Integer.MAX_VALUE
            ? (int) number.longValue() : null;
    }

    /** The one-row update every state move starts from. */
    private static @NonNull QueryBuilder<Row> write(@NonNull Row row) {
        return Models.get(LinkedServerModel.class).find().where(LinkedServerModel.ID.eq(row.get(LinkedServerModel.ID)));
    }

    /** {@link #write} of a row whose failing_since and alerted_at are as read: the compare-and-set of an alert. */
    private static @NonNull QueryBuilder<Row> asRead(@NonNull Row row) {
        return write(row).and(LinkedServerModel.FAILING_SINCE.asRead(row))
            .and(LinkedServerModel.ALERTED_AT.asRead(row));
    }
}
