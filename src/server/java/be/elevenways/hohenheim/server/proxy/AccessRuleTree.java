package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.proxy.auth.CredentialOwner;
import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthThrottle;
import be.elevenways.hohenheim.server.proxy.auth.ProxySessionSupport;
import be.elevenways.hohenheim.server.proxy.auth.SessionAuthority;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.cache.Cache;
import be.elevenways.zenit.common.net.IpRanges;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.session.SessionStore;
import be.elevenways.zenit.server.http.TrustedProxies;
import be.elevenways.zenit.server.security.SecureTokens;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An access list's rule tree, compiled once at route load and evaluated per request.
 *
 * EVALUATION is three-valued, because a credential leaf has no answer until the client is
 * asked for one:
 *
 * {@code ip_allow} PASSES when the client address is inside its network,
 * {@code ip_deny} passes when it is OUTSIDE.
 *
 * {@code basic_auth} and {@code auth_provider} PASS when the request already carries
 * the credential/session they name, and are otherwise PENDING -- never FAIL.
 *
 * An {@code any} group passes when a child passes, fails when every child fails, and
 * is PENDING in between; an {@code all} group fails when a child fails, passes when
 * every child passes, and is PENDING in between. An EMPTY group PASSES (which is what
 * keeps a rule-less list inert).
 *
 * Disabled rules are skipped as though absent. A rule this build cannot evaluate (an UNKNOWN
 * type, an unbuildable provider, a network spelling that no longer parses) FAILS CLOSED with
 * one log line and is listed in {@link #unusableRules()} -- never a denylist, never a skip.
 *
 * The request is allowed iff the root passes. A PENDING root is exactly the case where a
 * credential could still flip the verdict, and that is when -- and only when -- the
 * challenge is emitted: address rules that already decide the outcome answer 403 without
 * ever asking for a password. That reproduces the flat gate's behaviour, where satisfy=all
 * with a refused IP answered 403 while satisfy=any with a refused IP asked for credentials.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class AccessRuleTree {

    /** Three-valued verdict: PENDING means "a credential could still decide this". */
    public enum Verdict { PASS, FAIL, PENDING }

    private final Node root;
    private final List<SiteAuthGate> gates;
    private final boolean needsBlockingEvaluation;
    private final boolean ownsAuthorizationHeader;
    private final boolean ownsPersistentCookie;
    private final List<String> unusableRules;

    private AccessRuleTree(Node root, List<SiteAuthGate> gates, CompileFacts facts) {
        this.root = root;
        this.gates = List.copyOf(gates);
        this.needsBlockingEvaluation = facts.blocking;
        boolean authorization = facts.basicLeaf;
        boolean persistentCookie = false;
        for (SiteAuthGate gate : this.gates) {
            if (gate instanceof CredentialOwner owner) {
                authorization |= owner.ownsAuthorizationHeader();
                persistentCookie |= owner.ownsPersistentCookie();
            }
        }
        this.ownsAuthorizationHeader = authorization;
        this.ownsPersistentCookie = persistentCookie;
        this.unusableRules = List.copyOf(facts.unusable);
    }

    /**
     * One description per enabled rule this tree compiled into a refusing leaf, for the route
     * load to surface as a routing problem.
     */
    public @NonNull List<String> unusableRules() {
        return this.unusableRules;
    }

    /** Gates this tree built and therefore owns; the route table destroys them on reload. */
    public @NonNull List<SiteAuthGate> gates() {
        return this.gates;
    }

    /**
     * Whether evaluating this tree may block (argon2 verification, an identity provider's
     * HTTP round trip) and must therefore run off the I/O thread.
     */
    public boolean needsBlockingEvaluation() {
        return this.needsBlockingEvaluation;
    }

    /**
     * Whether a leaf of this tree reads the {@code Authorization} header as its own credential
     * (a {@code basic_auth} leaf, or a provider gate that does), so it must not reach the upstream.
     */
    public boolean ownsAuthorizationHeader() {
        return this.ownsAuthorizationHeader;
    }

    /** Whether a provider gate of this tree owns the persistent remember-me cookie on the site's host. */
    public boolean ownsPersistentCookie() {
        return this.ownsPersistentCookie;
    }

    /**
     * Evaluate the tree for one request; see the class docs for the semantics.
     *
     * AIDEV-NOTE: the client is the one the dispatcher vouched for on the exchange
     * ({@link TrustedProxies#clientIpOf}), the same client every address leaf and the
     * verification budget key on; never a second argument that could disagree with it.
     */
    public @NonNull Result evaluate(@NonNull HttpServerExchange exchange) {
        return this.root.evaluate(new Evaluation(exchange,
            IpRanges.parseLiteral(TrustedProxies.clientIpOf(exchange))));
    }

    /**
     * Compile the stored rows of one access list into a tree.
     *
     * @param satisfy the LIST's satisfy column: the implicit root group's mode
     * @param rules   every rule row of this list, in sort order
     */
    public static @NonNull AccessRuleTree compile(@Nullable String satisfy, @NonNull List<Row> rules,
                                                  @NonNull LeafContext context) {
        Partition partition = Partition.of(rules);
        if (partition.brokenRule() != null) {
            Blast.log("AccessRuleTree: rule", partition.brokenRule(),
                "has no usable parent chain (missing parent or cycle);",
                "DENYING the whole list.");
            return denyAll("rule " + partition.brokenRule() + " (no usable parent chain)");
        }

        List<SiteAuthGate> gates = new ArrayList<>();
        CompileFacts facts = new CompileFacts();
        List<Node> children = build(partition.roots(), partition.childrenByParent(), context, gates, facts);
        boolean all = AccessListModel.SATISFY_ALL.equals(satisfy);
        return new AccessRuleTree(new GroupNode(all, children), gates, facts);
    }

    /**
     * Whether the tree these rows compile to lets every request through unconditionally: its root
     * group states no requirement once disabled rows are skipped, the case where a "protected" path
     * is open to everyone.
     *
     * AIDEV-NOTE: answered from the ROWS with the compile's own partition and group modes, so a write
     * hook can ask it without building leaves (provider gates are resources the route table owns). An
     * enabled leaf of any type, an unknown one included, is a requirement: it compiles to a node that
     * can FAIL. A broken parent chain compiles to a deny-all tree, which admits nobody.
     *
     * @param satisfy the list's satisfy column, the implicit root group's mode
     * @param rules   every rule row of the list
     */
    public static boolean admitsEveryone(@Nullable String satisfy, @NonNull List<Row> rules) {
        Partition partition = Partition.of(rules);
        if (partition.brokenRule() != null) {
            return false;
        }
        return statesNoRequirement(AccessListModel.SATISFY_ALL.equals(satisfy), partition.roots(),
            partition.childrenByParent());
    }

    /** An empty group passes in both modes, an all-group passes when every child does, an any-group when one does. */
    private static boolean statesNoRequirement(boolean all, @NonNull List<Row> rows,
                                               @NonNull Map<Integer, List<Row>> childrenByParent) {
        boolean anyChild = false;
        for (Row row : rows) {
            if (!Boolean.TRUE.equals(row.get(AccessRuleModel.ENABLED))) {
                continue;
            }
            anyChild = true;
            boolean vacuous = AccessRuleModel.TYPE_GROUP.equals(row.get(AccessRuleModel.TYPE))
                && statesNoRequirement(groupAll(row), childrenByParent.getOrDefault(row.get(AccessRuleModel.ID),
                    List.of()), childrenByParent);
            if (all && !vacuous) {
                return false;
            }
            if (!all && vacuous) {
                return true;
            }
        }
        return !anyChild || all;
    }

    /** @return whether a group row demands every child, read the one way compile reads it */
    private static boolean groupAll(@NonNull Row group) {
        return AccessListModel.SATISFY_ALL.equals(Texts.trimmedOrNull(
            AccessRuleModel.dataOf(group).get(AccessRuleModel.GROUP_SATISFY.getName())));
    }

    /**
     * One list's rows split into root rows and children per parent, or the first rule whose parent chain is unusable.
     *
     * Every row must hang off the root through a finite chain of rows in THIS list. A dangling parent or a cycle
     * means the operator's policy cannot be reconstructed, so it cannot be honoured: refuse everything rather than
     * silently enforcing a different tree (a dropped deny rule would WIDEN access). The schema's foreign key and the
     * cascade delete make both unreachable through the admin surface.
     */
    private record Partition(@NonNull List<Row> roots, @NonNull Map<Integer, List<Row>> childrenByParent,
                             @Nullable Object brokenRule) {

        static @NonNull Partition of(@NonNull List<Row> rules) {
            Map<Integer, Row> byId = new LinkedHashMap<>();
            for (Row rule : rules) {
                Integer id = rule.get(AccessRuleModel.ID);
                if (id != null) {
                    byId.put(id, rule);
                }
            }
            for (Row rule : rules) {
                Integer parent = rule.get(AccessRuleModel.PARENT_ID);
                for (int steps = 0; parent != null; steps++) {
                    Row ancestor = byId.get(parent);
                    if (ancestor == null || steps > byId.size()) {
                        return new Partition(List.of(), Map.of(), rule.get(AccessRuleModel.ID));
                    }
                    parent = ancestor.get(AccessRuleModel.PARENT_ID);
                }
            }
            Map<Integer, List<Row>> childrenByParent = new LinkedHashMap<>();
            List<Row> roots = new ArrayList<>();
            for (Row rule : rules) {
                Integer parent = rule.get(AccessRuleModel.PARENT_ID);
                if (parent == null) {
                    roots.add(rule);
                } else {
                    childrenByParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(rule);
                }
            }
            return new Partition(roots, childrenByParent, null);
        }
    }

    /**
     * A tree that refuses every request; the fail-closed answer to an unusable list.
     *
     * @param why what made the list unusable, listed in {@link #unusableRules()}
     */
    static @NonNull AccessRuleTree denyAll(@NonNull String why) {
        CompileFacts facts = new CompileFacts();
        return new AccessRuleTree(facts.unusable(why), List.of(), facts);
    }

    /** Build the enabled rows of one level; a disabled row is skipped with its subtree. */
    private static @NonNull List<Node> build(@NonNull List<Row> rows,
                                             @NonNull Map<Integer, List<Row>> childrenByParent,
                                             @NonNull LeafContext context,
                                             @NonNull List<SiteAuthGate> gates,
                                             @NonNull CompileFacts facts) {
        List<Node> nodes = new ArrayList<>();
        for (Row row : rows) {
            if (!Boolean.TRUE.equals(row.get(AccessRuleModel.ENABLED))) {
                continue;
            }
            nodes.add(node(row, childrenByParent, context, gates, facts));
        }
        return List.copyOf(nodes);
    }

    private static @NonNull Node node(@NonNull Row row,
                                      @NonNull Map<Integer, List<Row>> childrenByParent,
                                      @NonNull LeafContext context,
                                      @NonNull List<SiteAuthGate> gates,
                                      @NonNull CompileFacts facts) {
        String type = row.get(AccessRuleModel.TYPE);
        Map<String, Object> data = AccessRuleModel.dataOf(row);

        // AIDEV-NOTE: no default branch that skips. Every unrecognised type lands on
        // UnknownNode, which FAILS and says so once -- a rule the proxy cannot understand
        // must never widen access by being ignored.
        switch (type == null ? "" : type) {
            case AccessRuleModel.TYPE_GROUP -> {
                List<Row> children = childrenByParent.getOrDefault(row.get(AccessRuleModel.ID), List.of());
                return new GroupNode(groupAll(row),
                    build(children, childrenByParent, context, gates, facts));
            }
            case AccessRuleModel.TYPE_IP_ALLOW, AccessRuleModel.TYPE_IP_DENY -> {
                // AIDEV-NOTE: an unparseable network is a refusing leaf in BOTH directions. It
                // used to match nothing, which made an ip_deny leaf PASS everyone: a deny rule
                // whose stored spelling a newer parser rejects silently stopped blocking.
                String network = Texts.trimmedOrNull(data.get(AccessRuleModel.NETWORK.getName()));
                IpRanges.Range range = AccessRuleModel.parseNetwork(network);
                if (range == null) {
                    return facts.unusable(type + " " + network);
                }
                return new NetworkNode(range, AccessRuleModel.TYPE_IP_ALLOW.equals(type));
            }
            case AccessRuleModel.TYPE_BASIC_AUTH -> {
                facts.blocking = true;
                facts.basicLeaf = true;
                return new BasicAuthNode(
                    Texts.trimmedOrNull(data.get(AccessRuleModel.BASIC_AUTH_USERNAME.getName())),
                    Texts.trimmedOrNull(data.get(AccessRuleModel.BASIC_AUTH_PASSWORD.getName())),
                    context.realm(), context.siteId());
            }
            case AccessRuleModel.TYPE_AUTH_PROVIDER -> {
                facts.blocking = true;
                Integer providerId = RawValues.parsedInt(data.get(AccessRuleModel.PROVIDER_ID.getName()));
                SiteAuthGate gate = providerId == null ? null : context.gateFor(providerId,
                    Texts.trimmedOrNull(data.get(
                        AccessRuleModel.PROVIDER_REQUIRED_PERMISSION.getName())));
                if (gate == null) {
                    // A provider rule whose provider is gone or misconfigured denies; it
                    // must never degrade into "no identity required".
                    return facts.unusable("auth_provider " + providerId);
                }
                gates.add(gate);
                return new AuthProviderNode(gate, context.sessionStore(), context.siteId());
            }
            default -> {
                return facts.unusable("of unknown type " + type);
            }
        }
    }

    /** What the compile learned about the tree while building it. */
    private static final class CompileFacts {
        boolean blocking;
        boolean basicLeaf;
        final List<String> unusable = new ArrayList<>();

        /** Record one rule the tree cannot evaluate and return the leaf that refuses in its place. */
        @NonNull UnusableNode unusable(@NonNull String rule) {
            this.unusable.add(rule);
            return new UnusableNode(rule);
        }
    }

    /** What a leaf needs from the site it guards. */
    public interface LeafContext {

        /** The HTTP basic realm to challenge with (the site's name). */
        @NonNull String realm();

        int siteId();

        @NonNull SessionStore sessionStore();

        /**
         * Build a gate for one provider record, narrowed by this leaf's own required
         * permission.
         *
         * @return null when the provider record is missing, of an unknown type, or refuses
         *         to build -- every one of which must deny
         */
        @Nullable SiteAuthGate gateFor(int providerId, @Nullable String requiredPermission);
    }

    /** The outcome of one evaluation, plus the leaf that could still flip a PENDING one. */
    public record Result(@NonNull Verdict verdict, @Nullable CredentialNode challenger) {

        public boolean allowed() {
            return this.verdict == Verdict.PASS;
        }

        /**
         * The response to send for a verdict that is not PASS: the pending leaf's own
         * challenge, or a plain refusal when nothing could change the answer.
         *
         * @return null when the challenged leaf turned out to be satisfied after all (an
         *         identity gate that logged the visitor in from a remember-me cookie), in
         *         which case the caller re-evaluates instead of refusing
         */
        public @Nullable SiteAuthDecision refusal(@NonNull HttpServerExchange exchange) {
            if (this.verdict == Verdict.PENDING && this.challenger != null) {
                return this.challenger.challenge(exchange);
            }
            return SiteAuthDecision.deny(403, "Forbidden");
        }
    }

    /** Per-request state shared by every node of one evaluation. */
    private record Evaluation(@NonNull HttpServerExchange exchange, byte @Nullable [] clientAddress) {
    }

    private sealed interface Node
        permits GroupNode, NetworkNode, UnusableNode, BasicAuthNode, AuthProviderNode {

        @NonNull Result evaluate(@NonNull Evaluation evaluation);
    }

    /** A leaf that can ask the client for something it did not send. */
    public sealed interface CredentialNode permits BasicAuthNode, AuthProviderNode {

        /** @return the response that asks for the credential, or null to allow after all */
        @Nullable SiteAuthDecision challenge(@NonNull HttpServerExchange exchange);
    }

    private record GroupNode(boolean all, @NonNull List<Node> children) implements Node {

        @Override
        public @NonNull Result evaluate(@NonNull Evaluation evaluation) {
            CredentialNode pending = null;
            for (Node child : this.children) {
                Result result = child.evaluate(evaluation);
                switch (result.verdict()) {
                    case PASS -> {
                        if (!this.all) {
                            return new Result(Verdict.PASS, null);
                        }
                    }
                    case FAIL -> {
                        if (this.all) {
                            return new Result(Verdict.FAIL, null);
                        }
                    }
                    case PENDING -> {
                        if (pending == null) {
                            pending = result.challenger();
                        }
                    }
                }
            }
            if (pending != null) {
                return new Result(Verdict.PENDING, pending);
            }
            // An empty group passes in BOTH modes: it states no requirement, and a list
            // whose root group is empty is what "no rules configured" looks like.
            return new Result(this.all || this.children.isEmpty() ? Verdict.PASS : Verdict.FAIL, null);
        }
    }

    /** An address leaf; an unparseable network never gets here, it compiles to an {@link UnusableNode}. */
    private record NetworkNode(IpRanges.@NonNull Range range, boolean passWhenInside) implements Node {

        @Override
        public @NonNull Result evaluate(@NonNull Evaluation evaluation) {
            boolean inside = this.range.matches(evaluation.clientAddress());
            return new Result(inside == this.passWhenInside ? Verdict.PASS : Verdict.FAIL, null);
        }
    }

    /** A rule this build cannot evaluate: it denies, loudly, once per compiled tree. */
    private static final class UnusableNode implements Node {

        private final @NonNull String rule;
        private boolean logged;

        private UnusableNode(@NonNull String rule) {
            this.rule = rule;
        }

        @Override
        public @NonNull Result evaluate(@NonNull Evaluation evaluation) {
            if (!this.logged) {
                this.logged = true;
                Blast.log("AccessRuleTree: access rule", this.rule,
                    "cannot be evaluated -- denying. A rule the proxy cannot evaluate never widens access.");
            }
            return new Result(Verdict.FAIL, null);
        }
    }

    /**
     * A basic-auth leaf. It holds no session (Basic re-sends the header on every request), so
     * without help every request carrying the header would pay one argon2 verification.
     *
     * AIDEV-NOTE: two bounds keep that from being an amplifier. A credential that VERIFIED is
     * remembered for {@link #VERIFIED_TTL_MILLIS} under a digest of the leaf's binding (site,
     * username, stored hash; ProxySessionSupport.binding, the digest the provider gates bind
     * their sessions to) plus the presented pair, so a changed password or username can never
     * hit an old entry, and a recompile at route reload drops the whole cache with the node.
     * Every verification that does run first spends a ProxyAuthThrottle token, exactly like the
     * provider gates, so failures are bounded per client and site and a spent budget answers
     * 429 instead of 401. A request that re-evaluates the tree (AccessListGate's pass loop)
     * never verifies the same refused pair twice.
     */
    private static final class BasicAuthNode implements Node, CredentialNode {

        /** How long one verified credential skips argon2. */
        private static final long VERIFIED_TTL_MILLIS = 60_000;

        /** Bound on remembered credentials per leaf; LRU past it. */
        private static final int VERIFIED_MAX = 256;

        /** The throttle's retry-after for this exchange, set when a leaf's verification was refused. */
        private static final AttachmentKey<Long> THROTTLED = AttachmentKey.create(Long.class);

        /** The credential digests this exchange already failed to verify, across re-evaluations. */
        private static final AttachmentKey<Set<String>> REFUSED = AttachmentKey.create(Set.class);

        private final @Nullable String username;
        private final @Nullable String passwordHash;
        private final @NonNull String realm;
        private final int siteId;
        private final @NonNull String binding;
        private final Cache<String, Boolean> verified = new Cache<>(VERIFIED_MAX, VERIFIED_TTL_MILLIS);

        private BasicAuthNode(@Nullable String username, @Nullable String passwordHash,
                              @NonNull String realm, int siteId) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.realm = realm;
            this.siteId = siteId;
            this.binding = ProxySessionSupport.binding("access_rule:basic_auth", String.valueOf(siteId),
                username, passwordHash, SecureTokens.randomToken(16));
        }

        @Override
        public @NonNull Result evaluate(@NonNull Evaluation evaluation) {
            if (this.username == null || this.passwordHash == null) {
                return new Result(Verdict.FAIL, null);
            }
            HttpServerExchange exchange = evaluation.exchange();
            String header = exchange.getRequestHeaders().getFirst(Headers.AUTHORIZATION);
            BasicCredentials.Presented presented = BasicCredentials.parse(header);
            if (presented == null) {
                // Nothing presented costs nothing: no argon2, no budget.
                return new Result(Verdict.PENDING, this);
            }
            String key = ProxySessionSupport.binding(this.binding, presented.username(), presented.password());
            if (this.verified.get(key) != null) {
                return new Result(Verdict.PASS, null);
            }
            Set<String> refused = exchange.getAttachment(REFUSED);
            if (refused != null && refused.contains(key)) {
                return new Result(Verdict.PENDING, this);
            }
            Long retryAfter = ProxyAuthThrottle.spendFor(exchange, this.siteId);
            if (retryAfter != null) {
                exchange.putAttachment(THROTTLED, retryAfter);
                return new Result(Verdict.PENDING, this);
            }
            if (BasicCredentials.matchesHeader(header, this.username, this.passwordHash, this.realm)) {
                this.verified.set(key, Boolean.TRUE);
                return new Result(Verdict.PASS, null);
            }
            if (refused == null) {
                refused = new HashSet<>();
                exchange.putAttachment(REFUSED, refused);
            }
            refused.add(key);
            return new Result(Verdict.PENDING, this);
        }

        @Override
        public @Nullable SiteAuthDecision challenge(@NonNull HttpServerExchange exchange) {
            Long retryAfter = exchange.getAttachment(THROTTLED);
            if (retryAfter != null) {
                return ProxyAuthThrottle.refusal(exchange, retryAfter);
            }
            exchange.getResponseHeaders().put(new HttpString("WWW-Authenticate"),
                "Basic realm=\"" + this.realm.replace("\"", "") + "\"");
            return SiteAuthDecision.deny(401, "Unauthorized");
        }
    }

    /**
     * An identity leaf: it COMPOSES the site auth gate the provider type builds and never
     * reimplements a login flow. Evaluation is side-effect free (it only reads the proxy
     * session); the gate itself runs only when this leaf is the one being challenged.
     *
     * AIDEV-NOTE: the leaf PASSES only when its own gate accepts the session (SessionAuthority):
     * the provider RECORD that minted it, the configuration it was minted under, and THIS
     * leaf's required permission re-checked against the claim the session stored at login.
     * It used to pass on the provider id alone, so a session minted by a permission-less site
     * gate satisfied a leaf on the same provider that demanded 'admin'. A gate that cannot
     * judge sessions accepts none.
     */
    private record AuthProviderNode(@NonNull SiteAuthGate gate, @NonNull SessionStore store,
                                    int siteId) implements Node, CredentialNode {

        @Override
        public @NonNull Result evaluate(@NonNull Evaluation evaluation) {
            boolean accepted = this.gate instanceof SessionAuthority authority
                && ProxySessionSupport.acceptedSession(evaluation.exchange(), this.store, this.siteId,
                    authority) != null;
            return accepted
                ? new Result(Verdict.PASS, null)
                : new Result(Verdict.PENDING, this);
        }

        @Override
        public @Nullable SiteAuthDecision challenge(@NonNull HttpServerExchange exchange) {
            return this.gate.evaluate(exchange);
        }
    }

}
