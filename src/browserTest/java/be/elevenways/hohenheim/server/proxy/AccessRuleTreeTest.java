package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.session.InMemorySessionStore;
import be.elevenways.zenit.common.session.SessionStore;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule tree's own truth table, and its equivalence with the flat gate it replaced.
 *
 * The first journey walks one tree through nesting, an empty group, a disabled rule, an
 * unknown type and every challenge case. The second runs the WHOLE cross product of the
 * old shape (allow list x deny list x credential x satisfy x client x presented
 * credentials) against an oracle that reimplements the old gate literally, proving the
 * mapped tree answers identically to the code this replaced.
 */
class AccessRuleTreeTest {

    private static int listId;
    private static final SessionStore SESSIONS = new InMemorySessionStore();

    @BeforeAll
    static void boot() throws Exception {
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, "Tree fixture");
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        Models.get(AccessListModel.class).save(list);
        listId = list.get(AccessListModel.ID);
    }

    @Test
    void treeSemanticsAcrossNestingChallengesAndFailClosed() {
        String hash = BasicCredentials.hashIfNeeded("s3cret");

        // 1. An EMPTY tree passes: that is what makes a rule-less list inert.
        assertThat(verdict(tree(AccessListModel.SATISFY_ANY, List.of()), "10.0.0.5", null))
            .as("step 1: an empty root group passes")
            .isEqualTo(AccessRuleTree.Verdict.PASS);
        assertThat(verdict(tree(AccessListModel.SATISFY_ALL, List.of()), "10.0.0.5", null))
            .as("step 1: an empty root group passes under 'all' too")
            .isEqualTo(AccessRuleTree.Verdict.PASS);

        // 2. Address leaves: allow passes inside its network, deny passes OUTSIDE it.
        List<Fixture> allow = List.of(leaf(AccessRuleModel.TYPE_IP_ALLOW, "10.0.0.0/8"));
        assertThat(verdict(tree(AccessListModel.SATISFY_ANY, allow), "10.0.0.5", null))
            .as("step 2: an allow leaf passes for an address inside it")
            .isEqualTo(AccessRuleTree.Verdict.PASS);
        assertThat(verdict(tree(AccessListModel.SATISFY_ANY, allow), "192.0.2.9", null))
            .as("step 2: and fails outside it")
            .isEqualTo(AccessRuleTree.Verdict.FAIL);

        List<Fixture> deny = List.of(leaf(AccessRuleModel.TYPE_IP_DENY, "10.0.0.0/8"));
        assertThat(verdict(tree(AccessListModel.SATISFY_ANY, deny), "10.0.0.5", null))
            .as("step 2: a deny leaf fails for an address inside it")
            .isEqualTo(AccessRuleTree.Verdict.FAIL);
        assertThat(verdict(tree(AccessListModel.SATISFY_ANY, deny), "192.0.2.9", null))
            .as("step 2: and passes outside it")
            .isEqualTo(AccessRuleTree.Verdict.PASS);

        // 3. DENY INSIDE ANY: the group passes on the deny leaf alone, which is exactly the
        //    difference from the old flat shape, where the two address lists were one unit.
        List<Fixture> mixed = List.of(
            leaf(AccessRuleModel.TYPE_IP_ALLOW, "10.0.0.0/8"),
            leaf(AccessRuleModel.TYPE_IP_DENY, "203.0.113.0/24"));
        assertThat(verdict(tree(AccessListModel.SATISFY_ANY, mixed), "192.0.2.9", null))
            .as("step 3: under 'any', a deny leaf the client is outside of PASSES the group")
            .isEqualTo(AccessRuleTree.Verdict.PASS);
        assertThat(verdict(tree(AccessListModel.SATISFY_ALL, mixed), "192.0.2.9", null))
            .as("step 3: under 'all' the same tree fails, because the allow leaf fails")
            .isEqualTo(AccessRuleTree.Verdict.FAIL);

        // 4. NESTING: root(all) = [ allow 10/8, group(any) = [ deny 203.0.113/24, allow 10.0.0.5 ] ].
        Fixture group = group(AccessListModel.SATISFY_ANY,
            leaf(AccessRuleModel.TYPE_IP_DENY, "203.0.113.0/24"),
            leaf(AccessRuleModel.TYPE_IP_ALLOW, "10.0.0.5"));
        AccessRuleTree nested = tree(AccessListModel.SATISFY_ALL,
            List.of(leaf(AccessRuleModel.TYPE_IP_ALLOW, "10.0.0.0/8"), group));
        assertThat(verdict(nested, "10.0.0.5", null))
            .as("step 4: both levels pass")
            .isEqualTo(AccessRuleTree.Verdict.PASS);
        assertThat(verdict(nested, "203.0.113.7", null))
            .as("step 4: the outer allow fails, so the tree fails whatever the group says")
            .isEqualTo(AccessRuleTree.Verdict.FAIL);

        // 5. An EMPTY nested group passes, so a group an operator has not filled in yet
        //    never turns into a refusal.
        assertThat(verdict(tree(AccessListModel.SATISFY_ALL, List.of(group(AccessListModel.SATISFY_ALL))),
                "10.0.0.5", null))
            .as("step 5: an empty nested group passes")
            .isEqualTo(AccessRuleTree.Verdict.PASS);

        // 6. A DISABLED rule is skipped as though absent -- including its whole subtree.
        Fixture disabledAllow = leaf(AccessRuleModel.TYPE_IP_ALLOW, "10.0.0.0/8").disabled();
        assertThat(verdict(tree(AccessListModel.SATISFY_ANY, List.of(disabledAllow)), "192.0.2.9", null))
            .as("step 6: with its only rule switched off the tree is empty, and passes")
            .isEqualTo(AccessRuleTree.Verdict.PASS);
        Fixture disabledGroup = group(AccessListModel.SATISFY_ALL,
            leaf(AccessRuleModel.TYPE_IP_ALLOW, "10.0.0.0/8")).disabled();
        assertThat(verdict(tree(AccessListModel.SATISFY_ANY, List.of(disabledGroup)), "192.0.2.9", null))
            .as("step 6: a disabled group takes its children with it")
            .isEqualTo(AccessRuleTree.Verdict.PASS);

        // 7. An UNKNOWN type FAILS CLOSED, in both group modes -- never skipped, never
        //    treated as a rule that happens to pass.
        Fixture unknown = new Fixture("shenanigans", Map.of(), true, List.of());
        assertThat(verdict(tree(AccessListModel.SATISFY_ALL, List.of(unknown)), "10.0.0.5", null))
            .as("step 7: an unknown rule type denies under 'all'")
            .isEqualTo(AccessRuleTree.Verdict.FAIL);
        assertThat(verdict(tree(AccessListModel.SATISFY_ANY, List.of(unknown)), "10.0.0.5", null))
            .as("step 7: and under 'any', where skipping it would have let the request in")
            .isEqualTo(AccessRuleTree.Verdict.FAIL);
        assertThat(verdict(tree(AccessListModel.SATISFY_ANY,
                List.of(unknown, leaf(AccessRuleModel.TYPE_IP_ALLOW, "10.0.0.0/8"))), "10.0.0.5", null))
            .as("step 7: a sibling that passes still passes -- the refusal is the LEAF's, "
                + "not the tree's")
            .isEqualTo(AccessRuleTree.Verdict.PASS);

        // 8. A credential leaf is PENDING, not FAIL, until the client has been asked.
        Fixture credential = credentialLeaf("operator", hash);
        AccessRuleTree credentialOnly = tree(AccessListModel.SATISFY_ANY, List.of(credential));
        assertThat(verdict(credentialOnly, "10.0.0.5", null))
            .as("step 8: an unanswered credential leaf is PENDING")
            .isEqualTo(AccessRuleTree.Verdict.PENDING);
        assertThat(verdict(credentialOnly, "10.0.0.5", basic("operator", "s3cret")))
            .as("step 8: the right credentials pass it")
            .isEqualTo(AccessRuleTree.Verdict.PASS);
        assertThat(verdict(credentialOnly, "10.0.0.5", basic("operator", "wrong")))
            .as("step 8: a wrong password stays PENDING, so the client is asked again")
            .isEqualTo(AccessRuleTree.Verdict.PENDING);
        assertThat(verdict(credentialOnly, "10.0.0.5", basic("someone", "s3cret")))
            .as("step 8: a wrong username stays PENDING too")
            .isEqualTo(AccessRuleTree.Verdict.PENDING);

        // 9. CHALLENGE EMISSION. A pending root challenges; a root that no credential could
        //    rescue answers 403 without ever asking.
        HttpServerExchange exchange = exchange(null);
        AccessRuleTree.Result pending = credentialOnly.evaluate(exchange, "10.0.0.5");
        SiteAuthDecision challenge = pending.refusal(exchange);
        assertThat(challenge).as("step 9: a pending root answers with a challenge")
            .isInstanceOf(SiteAuthDecision.Deny.class);
        assertThat(((SiteAuthDecision.Deny) challenge).statusCode())
            .as("step 9: which is a 401").isEqualTo(401);
        assertThat(exchange.getResponseHeaders().getFirst("WWW-Authenticate"))
            .as("step 9: carrying the site's realm").isEqualTo("Basic realm=\"Guarded Site\"");

        AccessRuleTree decidedByAddress = tree(AccessListModel.SATISFY_ALL,
            List.of(leaf(AccessRuleModel.TYPE_IP_DENY, "10.0.0.0/8"), credential));
        HttpServerExchange plain = exchange(null);
        AccessRuleTree.Result refused = decidedByAddress.evaluate(plain, "10.0.0.5");
        assertThat(refused.verdict()).as("step 9: the address already decides it")
            .isEqualTo(AccessRuleTree.Verdict.FAIL);
        assertThat(((SiteAuthDecision.Deny) refused.refusal(plain)).statusCode())
            .as("step 9: so the answer is 403, not a password prompt").isEqualTo(403);
        assertThat(plain.getResponseHeaders().getFirst("WWW-Authenticate"))
            .as("step 9: and no challenge header is written at all").isNull();

        // 10. The challenge comes from the leaf that is actually blocking, even when it is
        //     nested inside a group.
        AccessRuleTree nestedCredential = tree(AccessListModel.SATISFY_ALL,
            List.of(leaf(AccessRuleModel.TYPE_IP_ALLOW, "10.0.0.0/8"),
                group(AccessListModel.SATISFY_ANY,
                    leaf(AccessRuleModel.TYPE_IP_ALLOW, "203.0.113.0/24"), credential)));
        HttpServerExchange nestedExchange = exchange(null);
        AccessRuleTree.Result nestedResult = nestedCredential.evaluate(nestedExchange, "10.0.0.5");
        assertThat(nestedResult.verdict()).as("step 10: the nested credential is what is missing")
            .isEqualTo(AccessRuleTree.Verdict.PENDING);
        assertThat(((SiteAuthDecision.Deny) nestedResult.refusal(nestedExchange)).statusCode())
            .as("step 10: and it is what gets challenged").isEqualTo(401);

        // 11. An auth_provider leaf whose provider cannot be built DENIES; it never degrades
        //     into "no identity required".
        Fixture provider = new Fixture(AccessRuleModel.TYPE_AUTH_PROVIDER,
            Map.of("provider_id", 4242), true, List.of());
        assertThat(verdict(tree(AccessListModel.SATISFY_ANY, List.of(provider)), "10.0.0.5", null))
            .as("step 11: an unbuildable provider leaf denies")
            .isEqualTo(AccessRuleTree.Verdict.FAIL);

        // 12. With a gate, the leaf is PENDING without a session and delegates its challenge
        //     to that gate rather than reimplementing a login flow.
        SiteAuthDecision redirect = SiteAuthDecision.redirect("https://sso.example.com/login");
        AccessRuleTree gated = compile(AccessListModel.SATISFY_ANY, rows(List.of(provider)),
            new StubContext(gateAnswering(redirect)));
        HttpServerExchange gatedExchange = exchange(null);
        AccessRuleTree.Result gatedResult = gated.evaluate(gatedExchange, "10.0.0.5");
        assertThat(gatedResult.verdict()).as("step 12: no session yet, so the leaf is PENDING")
            .isEqualTo(AccessRuleTree.Verdict.PENDING);
        assertThat(gatedResult.refusal(gatedExchange))
            .as("step 12: and the challenge is the provider gate's own decision")
            .isSameAs(redirect);
    }

    /**
     * The flat gate's whole truth table, replayed against the tree that replaces it.
     *
     * The MAPPING is the claim under test: the old shape's two address lists were ONE unit
     * (deny wins, then the allow list must contain the client), which becomes
     * {@code all[ every deny leaf, any[ every allow leaf ] ]}, combined with the credential
     * leaf by the list's satisfy mode.
     */
    @Test
    void treeAnswersIdenticallyToTheFlatGateItReplaced() {
        String hash = BasicCredentials.hashIfNeeded("s3cret");
        List<String> allowLists = new ArrayList<>();
        allowLists.add(null);
        allowLists.add("10.0.0.0/8");
        allowLists.add("10.0.0.5 192.0.2.0/24");
        List<String> denyLists = new ArrayList<>();
        denyLists.add(null);
        denyLists.add("10.0.0.5");
        denyLists.add("203.0.113.0/24");
        List<String> clients = List.of("10.0.0.5", "10.9.9.9", "192.0.2.7", "203.0.113.7");
        List<String[]> presented = new ArrayList<>();
        presented.add(null);
        presented.add(new String[]{"operator", "s3cret"});
        presented.add(new String[]{"operator", "wrong"});

        int cases = 0;
        for (String allowed : allowLists) {
            for (String denied : denyLists) {
                for (boolean withCredential : List.of(false, true)) {
                    for (String satisfy : List.of(AccessListModel.SATISFY_ANY, AccessListModel.SATISFY_ALL)) {
                        AccessRuleTree tree = tree(satisfy,
                            mappedRules(allowed, denied, withCredential ? hash : null));
                        for (String client : clients) {
                            for (String[] credentials : presented) {
                                String header = credentials == null
                                    ? null : basic(credentials[0], credentials[1]);
                                Outcome expected = flatGateOracle(satisfy, allowed, denied,
                                    withCredential, client, credentials);
                                Outcome actual = outcomeOf(tree, client, header);
                                assertThat(actual)
                                    .as("allowed=%s denied=%s credential=%s satisfy=%s client=%s presented=%s",
                                        allowed, denied, withCredential, satisfy, client,
                                        credentials == null ? "none" : credentials[1])
                                    .isEqualTo(expected);
                                cases++;
                            }
                        }
                    }
                }
            }
        }
        assertThat(cases).as("the whole cross product really ran").isEqualTo(3 * 3 * 2 * 2 * 4 * 3);
    }

    /** What a caller can observe: allowed, refused outright, or asked for a credential. */
    private enum Outcome { ALLOWED, FORBIDDEN, CHALLENGED }

    /**
     * The OLD gate, reimplemented literally from the flat shape (deny wins over allow; an
     * allow list the client is absent from refuses; satisfy combines the address verdict
     * with the credential verdict; a configured credential is challenged whenever its
     * failure is what blocks).
     */
    private static Outcome flatGateOracle(String satisfy, String allowed, String denied,
                                          boolean withCredential, String client,
                                          String[] presented) {
        boolean hasIpRules = allowed != null || denied != null;
        boolean ipAllowed = true;
        if (denied != null) {
            for (String rule : denied.split("\\s+")) {
                if (matchesLiteral(client, rule)) {
                    ipAllowed = false;
                }
            }
        }
        if (ipAllowed && allowed != null) {
            ipAllowed = false;
            for (String rule : allowed.split("\\s+")) {
                if (matchesLiteral(client, rule)) {
                    ipAllowed = true;
                }
            }
        }
        boolean authPassed = !withCredential
            || (presented != null && "operator".equals(presented[0]) && "s3cret".equals(presented[1]));

        if (AccessListModel.SATISFY_ALL.equals(satisfy)) {
            if (hasIpRules && !ipAllowed) {
                return Outcome.FORBIDDEN;
            }
            if (withCredential && !authPassed) {
                return Outcome.CHALLENGED;
            }
            return Outcome.ALLOWED;
        }
        if (hasIpRules && withCredential) {
            if (!ipAllowed && !authPassed) {
                return Outcome.CHALLENGED;
            }
            return Outcome.ALLOWED;
        }
        if (hasIpRules && !ipAllowed) {
            return Outcome.FORBIDDEN;
        }
        if (withCredential && !authPassed) {
            return Outcome.CHALLENGED;
        }
        return Outcome.ALLOWED;
    }

    /** The tree an old flat list maps to; see the test's javadoc for why it is shaped so. */
    private static List<Fixture> mappedRules(String allowed, String denied, String hash) {
        List<Fixture> addressChildren = new ArrayList<>();
        if (denied != null) {
            for (String rule : denied.split("\\s+")) {
                addressChildren.add(leaf(AccessRuleModel.TYPE_IP_DENY, rule));
            }
        }
        if (allowed != null) {
            List<Fixture> allowLeaves = new ArrayList<>();
            for (String rule : allowed.split("\\s+")) {
                allowLeaves.add(leaf(AccessRuleModel.TYPE_IP_ALLOW, rule));
            }
            addressChildren.add(new Fixture(AccessRuleModel.TYPE_GROUP,
                Map.of("satisfy", AccessListModel.SATISFY_ANY), true, allowLeaves));
        }

        List<Fixture> roots = new ArrayList<>();
        if (!addressChildren.isEmpty()) {
            roots.add(new Fixture(AccessRuleModel.TYPE_GROUP,
                Map.of("satisfy", AccessListModel.SATISFY_ALL), true, addressChildren));
        }
        if (hash != null) {
            roots.add(credentialLeaf("operator", hash));
        }
        return roots;
    }

    /**
     * The address primitive, shared with the tree on purpose: the equivalence claim is about
     * the COMBINATION logic, and the matcher itself deliberately changed (literal addresses
     * and CIDR only, no DNS and no 3-part shorthand, which the old InetAddress-based one
     * accepted). Both spellings agree on every literal this matrix uses.
     */
    private static boolean matchesLiteral(String client, String rule) {
        var range = AccessRuleModel.parseNetwork(rule);
        return range != null && range.matches(
            be.elevenways.zenit.common.net.IpRanges.parseLiteral(client));
    }

    // --- fixtures -------------------------------------------------------------------

    /** A rule to persist: its type, its data, whether it is on, and its children. */
    private record Fixture(String type, Map<String, Object> data, boolean enabled,
                           List<Fixture> children) {

        Fixture disabled() {
            return new Fixture(this.type, this.data, false, this.children);
        }
    }

    private static Fixture leaf(String type, String network) {
        return new Fixture(type, Map.of("network", network), true, List.of());
    }

    private static Fixture credentialLeaf(String username, String hash) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", username);
        data.put("password", hash);
        return new Fixture(AccessRuleModel.TYPE_BASIC_AUTH, data, true, List.of());
    }

    private static Fixture group(String satisfy, Fixture... children) {
        return new Fixture(AccessRuleModel.TYPE_GROUP, Map.of("satisfy", satisfy), true,
            List.of(children));
    }

    private static AccessRuleTree tree(String satisfy, List<Fixture> fixtures) {
        return compile(satisfy, rows(fixtures), new StubContext(null));
    }

    private static AccessRuleTree compile(String satisfy, List<Row> rows,
                                          AccessRuleTree.LeafContext context) {
        return AccessRuleTree.compile(satisfy, rows, context);
    }

    /**
     * Persist the fixtures as real rows -- the tree is compiled from stored rows, so the
     * test must not hand it a shape the database could not hold.
     */
    private static List<Row> rows(List<Fixture> fixtures) {
        List<Row> rows = new ArrayList<>();
        persist(fixtures, null, rows);
        return rows;
    }

    private static void persist(List<Fixture> fixtures, Integer parentId, List<Row> collected) {
        var model = Models.get(AccessRuleModel.class);
        int sort = 0;
        for (Fixture fixture : fixtures) {
            Row row = model.createEmptyRow();
            row.set(AccessRuleModel.ACCESS_LIST_ID, listId);
            row.set(AccessRuleModel.PARENT_ID, parentId);
            row.set(AccessRuleModel.TYPE, fixture.type());
            row.set(AccessRuleModel.DATA, new LinkedHashMap<>(fixture.data()));
            row.set(AccessRuleModel.ENABLED, fixture.enabled());
            row.set(AccessRuleModel.SORT, sort++);
            if (AccessRuleModel.TYPE.isValidValue(fixture.type())) {
                model.save(row);
            }
            // An unknown type is deliberately NOT saved: the model's vocabulary hook refuses
            // it, and the gate must still survive a row that reached the column another way.
            collected.add(row);
            persist(fixture.children(), row.get(AccessRuleModel.ID), collected);
        }
    }

    // --- evaluation helpers ---------------------------------------------------------

    private static AccessRuleTree.Verdict verdict(AccessRuleTree tree, String clientIp, String header) {
        return tree.evaluate(exchange(header), clientIp).verdict();
    }

    private static Outcome outcomeOf(AccessRuleTree tree, String clientIp, String header) {
        HttpServerExchange exchange = exchange(header);
        AccessRuleTree.Result result = tree.evaluate(exchange, clientIp);
        if (result.allowed()) {
            return Outcome.ALLOWED;
        }
        SiteAuthDecision refusal = result.refusal(exchange);
        return refusal instanceof SiteAuthDecision.Deny deny && deny.statusCode() == 401
            ? Outcome.CHALLENGED : Outcome.FORBIDDEN;
    }

    private static HttpServerExchange exchange(String authorization) {
        HttpServerExchange exchange = new HttpServerExchange(null);
        if (authorization != null) {
            exchange.getRequestHeaders().put(Headers.AUTHORIZATION, authorization);
        }
        return exchange;
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
            (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static SiteAuthGate gateAnswering(SiteAuthDecision decision) {
        return exchange -> decision;
    }

    /** A leaf context with the site's realm and an optional provider gate. */
    private record StubContext(@Nullable SiteAuthGate gate) implements AccessRuleTree.LeafContext {

        @Override
        public @NonNull String realm() {
            return "Guarded Site";
        }

        @Override
        public int siteId() {
            return 1;
        }

        @Override
        public @NonNull SessionStore sessionStore() {
            return SESSIONS;
        }

        @Override
        public @Nullable SiteAuthGate gateFor(int providerId, @Nullable String requiredPermission) {
            return this.gate;
        }
    }
}
