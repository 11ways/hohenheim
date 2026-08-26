package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthKeys;
import be.elevenways.hohenheim.server.proxy.auth.ProxySessionSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.net.IpRanges;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.session.SessionStore;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An access list's rule tree, compiled once at route load and evaluated per request.
 *
 * EVALUATION is three-valued, because a credential leaf has no answer until the client is
 * asked for one:
 * <ul>
 *   <li>{@code ip_allow} PASSES when the client address is inside its network,
 *       {@code ip_deny} passes when it is OUTSIDE.</li>
 *   <li>{@code basic_auth} and {@code auth_provider} PASS when the request already carries
 *       the credential/session they name, and are otherwise PENDING -- never FAIL.</li>
 *   <li>An {@code any} group passes when a child passes, fails when every child fails, and
 *       is PENDING in between; an {@code all} group fails when a child fails, passes when
 *       every child passes, and is PENDING in between. An EMPTY group PASSES (which is what
 *       keeps a rule-less list inert).</li>
 *   <li>Disabled rules are skipped as though absent. An UNKNOWN rule type FAILS CLOSED with
 *       one log line -- never a denylist, never a skip.</li>
 * </ul>
 *
 * The request is allowed iff the root passes. A PENDING root is exactly the case where a
 * credential could still flip the verdict, and that is when -- and only when -- the
 * challenge is emitted: address rules that already decide the outcome answer 403 without
 * ever asking for a password. That reproduces the flat gate's behaviour, where satisfy=all
 * with a refused IP answered 403 while satisfy=any with a refused IP asked for credentials.
 *
 * @author Jelle De Loecker &lt;jelle@elevenways.be&gt;
 * @since 0.1.0
 */
public final class AccessRuleTree {

    /** Three-valued verdict: PENDING means "a credential could still decide this". */
    public enum Verdict { PASS, FAIL, PENDING }

    private final Node root;
    private final List<SiteAuthGate> gates;
    private final boolean needsBlockingEvaluation;

    private AccessRuleTree(Node root, List<SiteAuthGate> gates, boolean needsBlockingEvaluation) {
        this.root = root;
        this.gates = List.copyOf(gates);
        this.needsBlockingEvaluation = needsBlockingEvaluation;
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

    /** Evaluate the tree for one request; see the class docs for the semantics. */
    public @NonNull Result evaluate(@NonNull HttpServerExchange exchange, @Nullable String clientIp) {
        return this.root.evaluate(new Evaluation(exchange, IpRanges.parseLiteral(clientIp)));
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
        boolean[] blocking = new boolean[1];
        List<Node> children = build(rootRows, childrenByParent, context, gates, blocking);
        boolean all = AccessListModel.SATISFY_ALL.equals(satisfy);
        return new AccessRuleTree(new GroupNode(all, children), gates, blocking[0]);
    }

    /** A tree that refuses every request; the fail-closed answer to an unusable list. */
    static @NonNull AccessRuleTree denyAll() {
        return new AccessRuleTree(new UnknownNode("(unusable rule set)"), List.of(), false);
    }

    /** Build the enabled rows of one level; a disabled row is skipped with its subtree. */
    private static @NonNull List<Node> build(@NonNull List<Row> rows,
                                             @NonNull Map<Integer, List<Row>> childrenByParent,
                                             @NonNull LeafContext context,
                                             @NonNull List<SiteAuthGate> gates,
                                             boolean @NonNull [] blocking) {
        List<Node> nodes = new ArrayList<>();
        for (Row row : rows) {
            if (!Boolean.TRUE.equals(row.get(AccessRuleModel.ENABLED))) {
                continue;
            }
            nodes.add(node(row, childrenByParent, context, gates, blocking));
        }
        return List.copyOf(nodes);
    }

    private static @NonNull Node node(@NonNull Row row,
                                      @NonNull Map<Integer, List<Row>> childrenByParent,
                                      @NonNull LeafContext context,
                                      @NonNull List<SiteAuthGate> gates,
                                      boolean @NonNull [] blocking) {
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
                    build(children, childrenByParent, context, gates, blocking));
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
                blocking[0] = true;
                return new BasicAuthNode(
                    AccessRuleModel.text(data.get(AccessRuleModel.BASIC_AUTH_USERNAME.getName())),
                    AccessRuleModel.text(data.get(AccessRuleModel.BASIC_AUTH_PASSWORD.getName())),
                    context.realm());
            }
            case AccessRuleModel.TYPE_AUTH_PROVIDER -> {
                blocking[0] = true;
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
                return new AuthProviderNode(gate, context.sessionStore(), context.siteId(),
                    providerId);
            }
            default -> {
                return new UnknownNode(type);
            }
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

    private record BasicAuthNode(@Nullable String username, @Nullable String passwordHash,
                                 @NonNull String realm) implements Node, CredentialNode {

        @Override
        public @NonNull Result evaluate(@NonNull Evaluation evaluation) {
            if (this.username == null || this.passwordHash == null) {
                return new Result(Verdict.FAIL, null);
            }
            String header = evaluation.exchange().getRequestHeaders().getFirst(Headers.AUTHORIZATION);
            boolean matches = BasicCredentials.matchesHeader(header, this.username,
                this.passwordHash, this.realm);
            return matches
                ? new Result(Verdict.PASS, null)
                : new Result(Verdict.PENDING, this);
        }

        @Override
        public @Nullable SiteAuthDecision challenge(@NonNull HttpServerExchange exchange) {
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
     * AIDEV-NOTE: a session records WHICH provider record established it, so two leaves
     * pointing at different providers cannot satisfy each other. Two leaves on the SAME
     * provider record with different required permissions still can: the permission is
     * enforced at login and the session stores the identity, not the permission it was
     * accepted under.
     */
    private record AuthProviderNode(@NonNull SiteAuthGate gate, @NonNull SessionStore store,
                                    int siteId, int providerId) implements Node, CredentialNode {

        @Override
        public @NonNull Result evaluate(@NonNull Evaluation evaluation) {
            // A request carrying no cookies at all cannot carry a session: answering that
            // from the header skips cookie parsing on the anonymous path, which is most of
            // the traffic an identity-gated site sees before anyone logs in.
            if (evaluation.exchange().getRequestHeaders().getFirst(Headers.COOKIE) == null) {
                return new Result(Verdict.PENDING, this);
            }
            Session session = ProxySessionSupport.authenticatedSession(
                evaluation.exchange(), this.store, this.siteId);
            Integer established = session != null ? session.get(ProxyAuthKeys.PROVIDER_ID) : null;
            return established != null && established == this.providerId
                ? new Result(Verdict.PASS, null)
                : new Result(Verdict.PENDING, this);
        }

        @Override
        public @Nullable SiteAuthDecision challenge(@NonNull HttpServerExchange exchange) {
            return this.gate.evaluate(exchange);
        }
    }

}
