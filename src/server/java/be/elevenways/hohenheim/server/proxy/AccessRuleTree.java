package be.elevenways.hohenheim.server.proxy;

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
 * Disabled rules are skipped as though absent. An UNKNOWN rule type FAILS CLOSED with
 * one log line -- never a denylist, never a skip.
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

    /** Evaluate the tree for one request; see the class docs for the semantics. */
    public @NonNull Result evaluate(@NonNull HttpServerExchange exchange, @Nullable String clientIp) {
        return this.root.evaluate(new Evaluation(exchange, clientIp, IpRanges.parseLiteral(clientIp)));
    }

    /**
     * Compile the stored rows of one access list into a tree.
     *
     * @param satisfy the LIST's satisfy column: the implicit root group's mode
     * @param rules   every rule row of this list, in sort order
     */
    public static @NonNull AccessRuleTree compile(@Nullable String satisfy, @NonNull List<Row> rules,
                                                  @NonNull LeafContext context) {
        Map<Integer, Row> byId = new LinkedHashMap<>();
        for (Row rule : rules) {
            Integer id = rule.get(AccessRuleModel.ID);
            if (id != null) {
                byId.put(id, rule);
            }
        }

        // Every row must hang off the root through a finite chain of rows in THIS list.
        // A dangling parent or a cycle means the operator's policy cannot be reconstructed,
        // so it cannot be honoured: refuse everything rather than silently enforcing a
        // different tree (a dropped deny rule would WIDEN access). The schema's foreign key
        // and the cascade delete make both unreachable through the admin surface.
        for (Row rule : rules) {
            Integer parent = rule.get(AccessRuleModel.PARENT_ID);
            for (int steps = 0; parent != null; steps++) {
                Row ancestor = byId.get(parent);
                if (ancestor == null || steps > byId.size()) {
                    Blast.log("AccessRuleTree: rule", rule.get(AccessRuleModel.ID),
                        "has no usable parent chain (missing parent or cycle);",
                        "DENYING the whole list.");
                    return denyAll();
                }
                parent = ancestor.get(AccessRuleModel.PARENT_ID);
            }
        }

        Map<Integer, List<Row>> childrenByParent = new LinkedHashMap<>();
        List<Row> rootRows = new ArrayList<>();
        for (Row rule : rules) {
            Integer parent = rule.get(AccessRuleModel.PARENT_ID);
            if (parent == null) {
                rootRows.add(rule);
            } else {
                childrenByParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(rule);
            }
        }

        List<SiteAuthGate> gates = new ArrayList<>();
        CompileFacts facts = new CompileFacts();
        List<Node> children = build(rootRows, childrenByParent, context, gates, facts);
        boolean all = AccessListModel.SATISFY_ALL.equals(satisfy);
        return new AccessRuleTree(new GroupNode(all, children), gates, facts);
    }

    /** A tree that refuses every request; the fail-closed answer to an unusable list. */
    static @NonNull AccessRuleTree denyAll() {
        return new AccessRuleTree(new UnknownNode("(unusable rule set)"), List.of(), new CompileFacts());
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
                boolean all = AccessListModel.SATISFY_ALL.equals(
                    AccessRuleModel.text(data.get(AccessRuleModel.GROUP_SATISFY.getName())));
                return new GroupNode(all,
                    build(children, childrenByParent, context, gates, facts));
            }
            case AccessRuleModel.TYPE_IP_ALLOW -> {
                return new NetworkNode(AccessRuleModel.parseNetwork(
                    AccessRuleModel.text(data.get(AccessRuleModel.NETWORK.getName()))), true);
            }
            case AccessRuleModel.TYPE_IP_DENY -> {
                return new NetworkNode(AccessRuleModel.parseNetwork(
                    AccessRuleModel.text(data.get(AccessRuleModel.NETWORK.getName()))), false);
            }
            case AccessRuleModel.TYPE_BASIC_AUTH -> {
                facts.blocking = true;
                facts.basicLeaf = true;
                return new BasicAuthNode(
                    AccessRuleModel.text(data.get(AccessRuleModel.BASIC_AUTH_USERNAME.getName())),
                    AccessRuleModel.text(data.get(AccessRuleModel.BASIC_AUTH_PASSWORD.getName())),
                    context.realm(), context.siteId());
            }
            case AccessRuleModel.TYPE_AUTH_PROVIDER -> {
                facts.blocking = true;
                Integer providerId = data.get(AccessRuleModel.PROVIDER_ID.getName())
                    instanceof Number number ? number.intValue() : null;
                SiteAuthGate gate = providerId == null ? null : context.gateFor(providerId,
                    AccessRuleModel.text(data.get(
                        AccessRuleModel.PROVIDER_REQUIRED_PERMISSION.getName())));
                if (gate == null) {
                    // A provider rule whose provider is gone or misconfigured denies; it
                    // must never degrade into "no identity required".
                    return new UnknownNode("auth_provider " + providerId);
                }
                gates.add(gate);
                return new AuthProviderNode(gate, context.sessionStore(), context.siteId());
            }
            default -> {
                return new UnknownNode(type);
            }
        }
    }

    /** What the compile learned about the tree while building it. */
    private static final class CompileFacts {
        boolean blocking;
        boolean basicLeaf;
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
    private record Evaluation(@NonNull HttpServerExchange exchange, @Nullable String clientIp,
                              byte @Nullable [] clientAddress) {
    }

    private sealed interface Node
        permits GroupNode, NetworkNode, UnknownNode, BasicAuthNode, AuthProviderNode {

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

    /**
     * An address leaf. A null range is an unparseable rule, which matches nothing: an
     * allow leaf then fails and a deny leaf passes, in both cases the SAFE direction.
     */
    private record NetworkNode(IpRanges.@Nullable Range range, boolean passWhenInside) implements Node {

        @Override
        public @NonNull Result evaluate(@NonNull Evaluation evaluation) {
            boolean inside = this.range != null && this.range.matches(evaluation.clientAddress());
            return new Result(inside == this.passWhenInside ? Verdict.PASS : Verdict.FAIL, null);
        }
    }

    /** A rule this build cannot evaluate: it denies, loudly, once per compiled tree. */
    private static final class UnknownNode implements Node {

        private final @Nullable String type;
        private boolean logged;

        private UnknownNode(@Nullable String type) {
            this.type = type;
        }

        @Override
        public @NonNull Result evaluate(@NonNull Evaluation evaluation) {
            if (!this.logged) {
                this.logged = true;
                Blast.log("AccessRuleTree: access rule of unknown type", this.type,
                    "-- denying. A rule the proxy cannot evaluate never widens access.");
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
            Long retryAfter = ProxyAuthThrottle.spendFor(evaluation.clientIp(), this.siteId);
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
