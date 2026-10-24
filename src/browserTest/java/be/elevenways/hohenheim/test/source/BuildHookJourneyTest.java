package be.elevenways.hohenheim.test.source;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.BuildHookModel;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.DeployTrigger;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.cms.BuildHookParts;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.source.BuildHookHandler;
import be.elevenways.hohenheim.server.source.BuildHooks;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.TlsCertificateTest;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.flash.FlashNotice;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.SecretResult;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.server.http.ExchangeRateLimits;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import be.elevenways.zenit.server.http.TrustedProxies;
import be.elevenways.zenit.common.routing.RateLimitPolicy;
import be.elevenways.zenit.test.support.DatasourceProbe;
import io.undertow.server.HttpServerExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A build hook end to end: the owner creates one and sees its URL once, a POST deploys the application through the
 * release engine attributed to the hook, and everything short of a live hook answers one 404 and deploys nothing.
 *
 * AIDEV-NOTE: the daemon is the hermetic fake, scripted to answer the candidate unhealthy, so the hook's release is
 * a real release operation that fails at the health gate and leaves nothing running behind the class.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class BuildHookJourneyTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "buildhooks-";
    private static final String REFUSAL = "{\"error\":\"not found\"}";

    /** The key a fronting Hohenheim presents, which lets the test's POSTs say they arrived over HTTPS. */
    private static final String FRONT_KEY = "buildhooks-front-key";

    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5)).build();

    private static FakeDockerDaemon daemon;
    private static ProxyServer proxy;
    private static int proxyPort;
    private static Integer savedProxyPort;
    private static Integer savedProbeTimeout;
    private static Integer savedProbeInterval;
    private static Integer savedDrain;
    private static List<String> savedProxyKeys;

    @BeforeAll
    static void startProxyAndDaemon() throws Exception {
        RateLimitMiddleware.limiter().clear();
        daemon = new FakeDockerDaemon();
        daemon.install();
        savedProbeTimeout = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        savedProbeInterval = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        savedDrain = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 2);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, 50);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 0);
        HostFixtures.admitLocal();

        savedProxyPort = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        savedProxyKeys = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of(FRONT_KEY));
        proxy.start();
        ServerMain.adoptProxyServer(proxy);
        proxyPort = ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();
    }

    @AfterAll
    static void stopProxyAndDaemon() {
        ServerMain.adoptProxyServer(null);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, savedProxyPort);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, savedProxyKeys);
        FakeDockerDaemon.restore();
        if (daemon != null) {
            daemon.close();
            daemon = null;
        }
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, savedProbeTimeout);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, savedProbeInterval);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, savedDrain);
        RateLimitMiddleware.limiter().clear();
    }

    @Test
    void aHookDeploysItsApplicationNamedAsTheHookAndNothingElseDoesJourney() throws Exception {
        int shop = application(PREFIX + "shop");
        int other = application(PREFIX + "other");
        UUID tenantId = ApiSupport.user(PREFIX + "owner@surface.test", "Build Hook Owner");
        try {
            // 1. The operator creates a hook under the application's Build hooks tab: the create answers its URL as
            //    the one-time toast, on the tab where the hook now lists.
            HttpResponse<String> created = adminPostForm(CmsRoutes.createUnder(HohenheimSlugs.ADMIN,
                    HohenheimSlugs.BUILD_HOOKS, shop).toUrl(),
                "name=" + URLEncoder.encode("Headless CMS", StandardCharsets.UTF_8) + "&"
                    + PanelResourceCalls.createEnvelope());
            assertThat(created.statusCode()).as("step 1: the create lands").isIn(302, 303);
            assertThat(landingOf(created)).as("step 1: on the application's Build hooks tab").isEqualTo(tab(shop));
            FlashNotice shown = popFlash(created);
            assertThat(shown).as("step 1: the create announces the hook").isNotNull();
            assertThat(shown.message().key()).as("step 1: in the create's one-time secret toast")
                .isEqualTo("created_secret");
            String url = String.valueOf(shown.message().args().asMap().get("secret"));
            assertThat(url).as("step 1: the URL is the hook route").startsWith(BuildHookHandler.PREFIX);
            String token = url.substring(BuildHookHandler.PREFIX.length());
            Row hook = Models.get(BuildHookModel.class).find().where(BuildHookModel.INSTANCE_ID.eq(shop)).first();
            assertThat(hook).as("step 1: one hook stored").isNotNull();
            assertThat(String.valueOf(hook.get(BuildHookModel.TOKEN_DIGEST))).as("step 1: never the token at rest")
                .doesNotContain(token);

            // 2. After that the tab shows the hook by name with the masked preview only.
            String listed = adminGet(tab(shop)).body();
            assertThat(listed).as("step 2: the hook is listed").contains("Headless CMS")
                .as("step 2: with its masked URL").contains(BuildHookHandler.PREFIX + "****"
                    + token.substring(token.length() - 4))
                .as("step 2: and never the URL itself").doesNotContain(token);

            // 3. A POST answers 202 at once and deploys the application in the background: the release operation
            //    names the hook trigger and the hook as its principal, and the Deploys tab says "Build hook <name>".
            daemon.answerWithForNextWorkload(503);
            HttpResponse<String> accepted = postHook(url);
            assertThat(accepted.statusCode()).as("step 3: accepted").isEqualTo(202);
            assertThat(accepted.body()).as("step 3: queued").contains("queued");
            Row release = Poll.value("step 3: the hook's release ends", Duration.ofSeconds(30), Duration.ofMillis(100),
                () -> finishedRelease(shop));
            assertThat(release.get(ReleaseOperationModel.TRIGGER)).as("step 3: recorded as a build hook")
                .isEqualTo(DeployTrigger.HOOK.word());
            assertThat(ReleaseOperationModel.TRIGGERER.read(release)).as("step 3: named after the hook, not a person")
                .isEqualTo(BuildHooks.principalOf(hook));
            assertThat(Models.get(BuildHookModel.class).findById(hook.get(BuildHookModel.ID))
                .get(BuildHookModel.LAST_TRIGGERED_AT)).as("step 3: the hook says when it last ran").isNotNull();
            String deploys = adminGet("/admin/instances/" + shop + "/page/" + HohenheimSlugs.Tab.DEPLOYMENTS).body();
            assertThat(deploys).as("step 3: the Deploys tab marks the trigger")
                .contains("data-hh-deploy-trigger=\"hook\"")
                .as("step 3: and names the hook").contains("Build hook Headless CMS");
            long releases = releasesOf(shop);

            // 4. An unknown token, a wrong one and a path-shaped one answer the same 404, and deploy nothing.
            for (String probe : List.of(BuildHookHandler.PREFIX + "unknown-" + UUID.randomUUID(),
                    BuildHookHandler.PREFIX + token.substring(1) + "x", BuildHookHandler.PREFIX + token + "/x")) {
                HttpResponse<String> refused = postHook(probe);
                assertThat(refused.statusCode()).as("step 4: %s is not found", probe).isEqualTo(404);
                assertThat(refused.body()).as("step 4: in the one constant body").isEqualTo(REFUSAL);
            }
            //    Over plain HTTP the live URL and an unknown one alike are refused before any lookup: the token is
            //    the credential and never deploys from cleartext.
            for (String cleartext : List.of(url, BuildHookHandler.PREFIX + "unknown-" + UUID.randomUUID())) {
                HttpResponse<String> refused = postHookOverCleartext(cleartext);
                assertThat(refused.statusCode()).as("step 4: %s over plain HTTP is refused", cleartext).isEqualTo(403);
                assertThat(refused.body()).as("step 4: saying HTTPS is required").contains("https required");
            }

            // 5. The branch parameter must name the application's declared branch; an image-built application
            //    declares none, and a name git would read as an option is never one. Neither deploys.
            for (String branch : List.of("main", "--upload-pack=touch")) {
                HttpResponse<String> refused = postHook(url + "?branch="
                    + URLEncoder.encode(branch, StandardCharsets.UTF_8));
                assertThat(refused.statusCode()).as("step 5: branch %s is refused", branch).isEqualTo(422);
            }
            assertThat(releasesOf(shop)).as("step 4-5: nothing deployed").isEqualTo(releases);

            // 6. The hook's own budget: once it is spent the POST is refused with a Retry-After, deploying nothing;
            //    the client's budget refuses before any token is looked up.
            drain(BuildHookHandler.HOOK_POLICY, String.valueOf(hook.get(BuildHookModel.ID)));
            HttpResponse<String> limited = postHook(url);
            assertThat(limited.statusCode()).as("step 6: the spent hook is limited").isEqualTo(429);
            assertThat(limited.headers().firstValue("Retry-After")).as("step 6: saying when to retry").isPresent();
            drain(BuildHookHandler.POLICY, null);
            assertThat(postHook(BuildHookHandler.PREFIX + "unknown").statusCode())
                .as("step 6: a spent client is limited before any lookup").isEqualTo(429);
            assertThat(releasesOf(shop)).as("step 6: nothing deployed").isEqualTo(releases);
            RateLimitMiddleware.limiter().clear();

            // 7. Regenerate answers the new URL once; the old one is dead at once, the new one live.
            HttpResponse<String> regenerated = adminPostForm(invoke(HohenheimSlugs.ADMIN, BuildHookParts.REGENERATE,
                hook), confirmed(""));
            assertThat(regenerated.statusCode()).as("step 7: regenerated").isIn(302, 303);
            FlashNotice renewed = popFlash(regenerated);
            assertThat(renewed).as("step 7: the new URL is announced").isNotNull();
            String newUrl = String.valueOf(renewed.message().args().asMap().get("url"));
            assertThat(newUrl).as("step 7: a new hook URL").startsWith(BuildHookHandler.PREFIX).isNotEqualTo(url);
            HttpResponse<String> stale = postHook(url);
            assertThat(stale.statusCode()).as("step 7: the old URL is gone").isEqualTo(404);
            assertThat(stale.body()).as("step 7: like any unknown one").isEqualTo(REFUSAL);
            assertThat(postHook(newUrl + "?branch=nope").statusCode())
                .as("step 7: the new URL names the live hook (refused only for its branch)").isEqualTo(422);

            // 8. Revoked, the URL answers like any unknown one and deploys nothing; the row stays to name its release.
            HttpResponse<String> revoked = adminPostForm(invoke(HohenheimSlugs.ADMIN, BuildHookParts.REVOKE, hook),
                confirmed(""));
            assertThat(revoked.statusCode()).as("step 8: revoked").isIn(302, 303);
            HttpResponse<String> dead = postHook(newUrl);
            assertThat(dead.statusCode()).as("step 8: the revoked URL is not found").isEqualTo(404);
            assertThat(dead.body()).as("step 8: in the one constant body").isEqualTo(REFUSAL);
            assertThat(releasesOf(shop)).as("step 7-8: nothing deployed").isEqualTo(releases);
            assertThat(adminGet("/admin/instances/" + shop + "/page/" + HohenheimSlugs.Tab.DEPLOYMENTS).body())
                .as("step 8: the release still names the revoked hook").contains("Build hook Headless CMS");

            // 9. An owner reads, creates and revokes its own application's hooks, and nothing of another's.
            RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, shop,
                HohenheimCapabilities.MANAGE, true);
            Row own = Models.get(BuildHookModel.class).findById(
                BuildHooks.mint(shop, PREFIX + "nightly").key());
            Row foreign = Models.get(BuildHookModel.class).findById(
                BuildHooks.mint(other, PREFIX + "foreign").key());
            TestSession owner = sessionFor(tenantId);
            HttpResponse<String> ownTab = httpGet(manageTab(shop), owner.token());
            assertThat(ownTab.statusCode()).as("step 9: the owner opens its Build hooks").isEqualTo(200);
            assertThat(ownTab.body()).as("step 9: with its hooks").contains(PREFIX + "nightly")
                .as("step 9: and not another application's").doesNotContain(PREFIX + "foreign");
            assertThat(httpGet(manageTab(other), owner.token()).statusCode())
                .as("step 9: another application's hooks are missing, never forbidden").isEqualTo(404);
            HttpResponse<String> foreignRevoke = httpPostForm(invoke(HohenheimSlugs.MANAGE, BuildHookParts.REVOKE,
                foreign), confirmed(""), owner.token(), owner.csrf());
            assertThat(foreignRevoke.statusCode()).as("step 9: another's hook cannot be revoked").isIn(403, 404);
            assertThat(Models.get(BuildHookModel.class).findById(foreign.get(BuildHookModel.ID))
                .get(BuildHookModel.REVOKED_AT)).as("step 9: and stays live").isNull();
            long foreignHooks = hooksOf(other);
            HttpResponse<String> foreignCreate = httpPostForm(CmsRoutes.createUnder(HohenheimSlugs.MANAGE,
                    HohenheimSlugs.BUILD_HOOKS, other).toUrl(), "name=sneaky&" + PanelResourceCalls.createEnvelope(),
                owner.token(), owner.csrf());
            assertThat(landingOf(foreignCreate)).as("step 9: a refused create lands on no Build hooks tab")
                .doesNotContain("/page/" + HohenheimSlugs.Tab.BUILD_HOOKS);
            assertThat(hooksOf(other)).as("step 9: no hook is created under another's application")
                .isEqualTo(foreignHooks);
            HttpResponse<String> ownRevoke = httpPostForm(invoke(HohenheimSlugs.MANAGE, BuildHookParts.REVOKE, own),
                confirmed(""), owner.token(), owner.csrf());
            assertThat(ownRevoke.statusCode()).as("step 9: the owner revokes its own hook").isIn(302, 303);
            assertThat(Models.get(BuildHookModel.class).findById(own.get(BuildHookModel.ID))
                .get(BuildHookModel.REVOKED_AT)).as("step 9: revoked").isNotNull();

            // 10. The tab reads the same at 3 hooks as at 30, each with a release of its own: the last deploy of
            //     every listed hook is one grouped read for the page.
            BuildHooks.mint(shop, PREFIX + "third");
            Map<String, Integer> few = measure(tab(shop));
            for (int index = 0; index < 27; index++) {
                Row extra = Models.get(BuildHookModel.class).findById(
                    BuildHooks.mint(shop, PREFIX + "extra-" + index).key());
                attributedRelease(shop, extra);
            }
            Map<String, Integer> many = measure(tab(shop));
            assertThat(many).as("step 10: 30 hooks cost the reads 3 did").isEqualTo(few);
        } finally {
            Models.get(ReleaseOperationModel.class).find()
                .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
                .where(ReleaseOperationModel.FOR_ID.in(List.of(shop, other))).delete();
            Models.get(BuildHookModel.class).find().where(BuildHookModel.INSTANCE_ID.in(List.of(shop, other))).delete();
            Models.get(UserModel.class).delete(tenantId);
        }
    }

    @Test
    void revokingOrRegeneratingDeclinesOnlyHookDeploysNotYetStartedAndOnlyAcceptedPostsSpendTheHookJourney()
            throws Exception {
        RateLimitMiddleware.limiter().clear();
        int shop = application(PREFIX + "guarded");
        int served = application(PREFIX + "served");
        Row site = null;
        Row certificate = null;
        Integer savedHttpsPort = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.HTTPS_PORT);
        try {
            // 1. A regenerate or a revoke that read the hook live before a revoke landed changes nothing and is
            //    refused by name: the hook stays revoked, its token as the revoke left it.
            Row racing = Models.get(BuildHookModel.class).findById(BuildHooks.mint(shop, PREFIX + "racing").key());
            BuildHooks.revoke(Models.get(BuildHookModel.class).findById(racing.get(BuildHookModel.ID)));
            Row revoked = Models.get(BuildHookModel.class).findById(racing.get(BuildHookModel.ID));
            assertThatThrownBy(() -> BuildHooks.regenerate(racing)).as("step 1: the stale regenerate is refused")
                .isInstanceOfSatisfying(DomainRefusal.class, refusal -> assertThat(
                    refusal.is(HohenheimRefusalReason.BUILD_HOOK_REVOKED)).as("step 1: as a revoked hook").isTrue());
            assertThatThrownBy(() -> BuildHooks.revoke(racing)).as("step 1: and so is a second revoke")
                .isInstanceOfSatisfying(DomainRefusal.class, refusal -> assertThat(
                    refusal.is(HohenheimRefusalReason.BUILD_HOOK_REVOKED)).as("step 1: by the same name").isTrue());
            Row after = Models.get(BuildHookModel.class).findById(racing.get(BuildHookModel.ID));
            assertThat(after.get(BuildHookModel.REVOKED_AT)).as("step 1: still revoked, when it was")
                .isEqualTo(revoked.get(BuildHookModel.REVOKED_AT));
            assertThat(after.get(BuildHookModel.TOKEN_DIGEST)).as("step 1: with the token the revoke left")
                .isEqualTo(revoked.get(BuildHookModel.TOKEN_DIGEST));

            // 2. While the application is busy, its hook deploys queue up to the cap; one more is refused, saying when
            //    to retry. A revoke landing before they get the application declines every one of them (a deploy
            //    that already started would complete): each is recorded as refused, none releases, the hook never
            //    reads as triggered, and only the accepted POSTs spent the hook's budget across all clients.
            SecretResult<UUID> queued = BuildHooks.mint(shop, PREFIX + "queued");
            String queuedUrl = queued.secret().reveal();
            long releases = releasesOf(shop);
            long refusedBefore = refusalsOf(shop);
            InstanceOperationLock.production().exclusive(shop, InstanceOperationLock.Contention.QUEUE, () -> {
                for (int index = 0; index < BuildHooks.PENDING_PER_APPLICATION; index++) {
                    assertThat(postHookUnchecked(queuedUrl).statusCode()).as("step 2: deploy %s is accepted", index)
                        .isEqualTo(202);
                }
                HttpResponse<String> capped = postHookUnchecked(queuedUrl);
                assertThat(capped.statusCode()).as("step 2: one past the cap is refused").isEqualTo(429);
                assertThat(capped.headers().firstValue("Retry-After")).as("step 2: saying when a slot may be free")
                    .contains(String.valueOf(BuildHooks.PENDING_RETRY_AFTER.toSeconds()));
                assertThat(BuildHooks.pendingOf(shop)).as("step 2: the cap is held")
                    .isEqualTo(BuildHooks.PENDING_PER_APPLICATION);
                BuildHooks.revoke(Models.get(BuildHookModel.class).findById(queued.key()));
            });
            Poll.until("step 2: every queued deploy is declined", Duration.ofSeconds(30), Duration.ofMillis(50),
                () -> refusalsOf(shop) == refusedBefore + BuildHooks.PENDING_PER_APPLICATION);
            assertThat(remainingTotal(queued.key())).as("step 2: the capped POST spent nothing shared")
                .isEqualTo(BuildHookHandler.HOOK_TOTAL_POLICY.requests() - BuildHooks.PENDING_PER_APPLICATION);
            assertThat(newestRefusalReason(shop)).as("step 2: saying the hook was revoked")
                .isEqualTo(HohenheimViolations.textOf(
                    HohenheimMicrocopy.VIOLATIONS.of("build_hook_revoked_before_start")));
            assertThat(releasesOf(shop)).as("step 2: nothing released").isEqualTo(releases);
            assertThat(BuildHooks.pendingOf(shop)).as("step 2: no slot stays held").isZero();
            assertThat(Models.get(BuildHookModel.class).findById(queued.key()).get(BuildHookModel.LAST_TRIGGERED_AT))
                .as("step 2: a declined deploy is no trigger").isNull();

            // 3. Regenerating a leaked URL stops what the old URL queued: the deploy it had accepted is declined in
            //    its own words when it gets the application, while the hook stays live under its new URL.
            SecretResult<UUID> rotated = BuildHooks.mint(shop, PREFIX + "rotated");
            String leakedUrl = rotated.secret().reveal();
            long refusedBeforeRotation = refusalsOf(shop);
            InstanceOperationLock.production().exclusive(shop, InstanceOperationLock.Contention.QUEUE, () -> {
                assertThat(postHookUnchecked(leakedUrl).statusCode()).as("step 3: the leaked URL queues a deploy")
                    .isEqualTo(202);
                BuildHooks.regenerate(Models.get(BuildHookModel.class).findById(rotated.key()));
            });
            Poll.until("step 3: the old URL's deploy is declined", Duration.ofSeconds(30), Duration.ofMillis(50),
                () -> refusalsOf(shop) == refusedBeforeRotation + 1);
            assertThat(newestRefusalReason(shop)).as("step 3: saying the URL was regenerated")
                .isEqualTo(HohenheimViolations.textOf(
                    HohenheimMicrocopy.VIOLATIONS.of("build_hook_regenerated_before_start")));
            Row renewed = Models.get(BuildHookModel.class).findById(rotated.key());
            assertThat(renewed.get(BuildHookModel.REVOKED_AT)).as("step 3: the hook stays live").isNull();
            assertThat(renewed.get(BuildHookModel.LAST_TRIGGERED_AT)).as("step 3: and was not triggered").isNull();
            assertThat(releasesOf(shop)).as("step 3: nothing released").isEqualTo(releases);
            assertThat(BuildHooks.pendingOf(shop)).as("step 3: no slot stays held").isZero();

            // 4. The hook's budget across every client is spent only by an accepted POST: refused ones (here for
            //    their branch) leave it whole. Spent from elsewhere, a POST from a client with its own budget
            //    untouched is refused with a Retry-After and queues nothing.
            SecretResult<UUID> picky = BuildHooks.mint(shop, PREFIX + "picky");
            for (int index = 0; index < 2; index++) {
                assertThat(postHook(picky.secret().reveal() + "?branch=main").statusCode())
                    .as("step 4: a refused branch").isEqualTo(422);
            }
            assertThat(remainingTotal(picky.key())).as("step 4: the refused POSTs spent nothing shared")
                .isEqualTo(BuildHookHandler.HOOK_TOTAL_POLICY.requests());
            SecretResult<UUID> leaked = BuildHooks.mint(shop, PREFIX + "leaked");
            assertThat(remainingTotal(leaked.key())).as("step 4: the budget drains within its size")
                .isEqualTo(BuildHookHandler.HOOK_TOTAL_POLICY.requests());
            HttpResponse<String> limited = postHook(leaked.secret().reveal());
            assertThat(limited.statusCode()).as("step 4: the spent hook is limited for a fresh client").isEqualTo(429);
            assertThat(limited.headers().firstValue("Retry-After")).as("step 4: saying when to retry").isPresent();
            assertThat(BuildHooks.pendingOf(shop)).as("step 4: nothing queued").isZero();
            assertThat(releasesOf(shop)).as("step 4: nothing released").isEqualTo(releases);

            // 5. The URL a hook discloses is https on a hostname exposing its application only while a visitor's
            //    handshake works for that name; before a certificate covers it, the hook discloses only its path
            //    and the tab says why.
            site = Models.get(SiteModel.class).createEmptyRow();
            site.set(SiteModel.NAME, PREFIX + "served-site");
            site.set(SiteModel.SLUG, PREFIX + "served-site");
            site.set(SiteModel.UPSTREAM_KIND, "hohenheim:instance");
            site.set(SiteModel.INSTANCE_ID, served);
            site.set(SiteModel.ENABLED, false);
            Models.get(SiteModel.class).save(site);
            Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
            domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
            domain.set(SiteDomainModel.HOSTNAME, "buildhooks-served.example.test");
            domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
            domain.set(SiteDomainModel.FORCE_SSL, false);
            Models.get(SiteDomainModel.class).save(domain);
            String bare = BuildHooks.mint(served, PREFIX + "uncertified").secret().reveal();
            assertThat(bare).as("step 5: no working certificate covers the name: only the path")
                .startsWith(BuildHookHandler.PREFIX);
            assertThat(adminGet(tab(served)).body()).as("step 5: and the tab says why")
                .contains("It answers over HTTPS only");
            certificate = installCertificate("buildhooks-served.example.test");
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 0);
            proxy.reload();
            assertThat(proxy.isHttpsTerminationAvailable()).as("step 5: HTTPS terminates with the certificate")
                .isTrue();
            assertThat(BuildHooks.mint(served, PREFIX + "public").secret().reveal())
                .as("step 5: over https on the exposing hostname, even one not forcing it")
                .startsWith("https://buildhooks-served.example.test" + BuildHookHandler.PREFIX);
        } finally {
            if (certificate != null) {
                Models.get(CertificateModel.class).delete(certificate);
            }
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, savedHttpsPort);
            proxy.reload();
            if (site != null) {
                Models.get(SiteDomainModel.class).find().where(SiteDomainModel.SITE_ID.eq(site.get(SiteModel.ID)))
                    .delete();
                Models.get(SiteModel.class).find().where(SiteModel.ID.eq(site.get(SiteModel.ID))).delete();
            }
            Models.get(BuildOperationModel.class).find()
                .where(BuildOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
                .where(BuildOperationModel.FOR_ID.in(List.of(shop, served))).delete();
            Models.get(BuildHookModel.class).find().where(BuildHookModel.INSTANCE_ID.in(List.of(shop, served)))
                .delete();
            RateLimitMiddleware.limiter().clear();
        }
    }

    /** @return the refused deploys recorded on the application, each a terminal build operation */
    private static long refusalsOf(int applicationId) {
        return Models.get(BuildOperationModel.class).find()
            .where(BuildOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(BuildOperationModel.FOR_ID.eq(applicationId))
            .where(BuildOperationModel.STATUS.eq(BuildOperationModel.LIFECYCLE.stored(OperationStatus.REFUSED)))
            .count();
    }

    /** @return the failure reason of the application's newest recorded deploy operation */
    private static Object newestRefusalReason(int applicationId) {
        return Models.get(BuildOperationModel.class).find()
            .where(BuildOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(BuildOperationModel.FOR_ID.eq(applicationId))
            .orderBy(BuildOperationModel.ID, SortOrder.DESC).first()
            .get(BuildOperationModel.FAILURE_REASON);
    }

    /** @return how many deploys the hook's budget across all clients still allows, spending all of them */
    private static int remainingTotal(UUID hookId) {
        int spent = 0;
        while (BuildHookHandler.spendHookTotal(hookId).allowed()) {
            assertThat(++spent).as("the shared budget drains within its size")
                .isLessThanOrEqualTo(BuildHookHandler.HOOK_TOTAL_POLICY.requests());
        }
        return spent;
    }

    /** A self-signed certificate for {@code hostname}, stored the way a custom upload is. */
    private static Row installCertificate(String hostname) throws Exception {
        KeyPair keyPair = TlsCertificateTest.generateKeyPair();
        X509Certificate cert = TlsCertificateTest.generateSelfSignedCert(keyPair, hostname);
        Row row = Models.get(CertificateModel.class).createEmptyRow();
        row.set(CertificateModel.NICE_NAME, PREFIX + "served");
        row.set(CertificateModel.PROVIDER, "custom");
        row.set(CertificateModel.STATUS, "active");
        row.set(CertificateModel.DOMAIN_NAMES_TEXT, hostname);
        row.set(CertificateModel.CERTIFICATE_PEM, TlsCertificateTest.certToPem(cert));
        row.set(CertificateModel.PRIVATE_KEY_PEM, TlsCertificateTest.keyToPem(keyPair));
        return Models.get(CertificateModel.class).save(row);
    }

    /** {@link #postHook} for a lambda that may not throw. */
    private static HttpResponse<String> postHookUnchecked(String path) {
        try {
            return postHook(path);
        } catch (Exception failed) {
            throw new AssertionError("the hook POST to " + path + " failed", failed);
        }
    }

    /** The build hook and release reads of one render of {@code path}, after a warm-up render. */
    private Map<String, Integer> measure(String path) throws Exception {
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Models.get(InstanceModel.class)
            .getResolvedDatasource());
        assertThat(adminGet(path).statusCode()).as("the warm-up render").isEqualTo(200);
        probe.reset();
        assertThat(adminGet(path).statusCode()).as("the measured render").isEqualTo(200);
        Map<String, Integer> reads = new TreeMap<>();
        reads.put("build_hooks", probe.queriesAgainst(BuildHookModel.MODEL_ID));
        reads.put("release_operations", probe.queriesAgainst(ReleaseOperationModel.MODEL_ID));
        reads.put("instances", probe.queriesAgainst(InstanceModel.MODEL_ID));
        return reads;
    }

    /** A finished release the hook started on {@code applicationId}, as the engine records one. */
    private static void attributedRelease(int applicationId, Row hook) {
        Row operation = Models.get(ReleaseOperationModel.class).createEmptyRow();
        operation.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
        operation.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        operation.set(ReleaseOperationModel.FOR_ID, applicationId);
        operation.set(ReleaseOperationModel.STATUS, ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));
        operation.set(ReleaseOperationModel.TRIGGER, DeployTrigger.HOOK.word());
        ReleaseOperationModel.TRIGGERER.write(operation, BuildHooks.principalOf(hook));
        operation.set(ReleaseOperationModel.STARTED_AT, Instant.parse("2026-10-01T08:00:00Z"));
        operation.set(ReleaseOperationModel.FINISHED_AT, Instant.parse("2026-10-01T08:02:00Z"));
        operation.set(ReleaseOperationModel.STEP_LOG, "");
        Models.get(ReleaseOperationModel.class).save(operation);
    }

    /** @return the application's newest release once it is no longer in flight, else null */
    private static Row finishedRelease(int applicationId) {
        Row newest = Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(applicationId))
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC)
            .first();
        return newest != null && !ReleaseOperationModel.IN_FLIGHT_STATUSES.contains(
            newest.get(ReleaseOperationModel.STATUS)) ? newest : null;
    }

    private static long releasesOf(int applicationId) {
        return Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(applicationId)).count();
    }

    private static long hooksOf(int applicationId) {
        return Models.get(BuildHookModel.class).find().where(BuildHookModel.INSTANCE_ID.eq(applicationId)).count();
    }

    /** Spends every token the test client has left in the policy's family, as a burst of POSTs would. */
    private static void drain(RateLimitPolicy policy, String scope) {
        HttpServerExchange burst = new HttpServerExchange(null);
        TrustedProxies.vouch(burst, "127.0.0.1");
        int spent = 0;
        while (ExchangeRateLimits.tryAcquire(burst, policy, scope).allowed()) {
            assertThat(++spent).as("the burst drains within the budget").isLessThanOrEqualTo(policy.requests());
        }
    }

    /**
     * A hook POST to the proxy, as a CMS sends one over HTTPS: a body the hook never reads, through a fronting
     * Hohenheim (its key) that terminated the TLS, so the test needs no client trust store.
     */
    private static HttpResponse<String> postHook(String path) throws Exception {
        return CLIENT.send(hookRequest(path).header("X-Hohenheim-Key", FRONT_KEY)
                .header("X-Forwarded-Proto", "https").build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** A hook POST straight to the proxy over plain HTTP. */
    private static HttpResponse<String> postHookOverCleartext(String path) throws Exception {
        return CLIENT.send(hookRequest(path).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder hookRequest(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxyPort + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"trigger_title\":\"publish\"}"));
    }

    /** The invoke route of a placed hook operation over one hook. */
    private static String invoke(String panel, Operation<?, ?, ?> operation, Row hook) {
        return CmsRoutes.invoke(panel, HohenheimSlugs.BUILD_HOOKS, operation.id()).toUrl()
            + "?ids=" + hook.get(BuildHookModel.ID);
    }

    private static String tab(int applicationId) {
        return "/admin/instances/" + applicationId + "/page/" + HohenheimSlugs.Tab.BUILD_HOOKS;
    }

    private static String manageTab(int applicationId) {
        return "/manage/instances/" + applicationId + "/page/" + HohenheimSlugs.Tab.BUILD_HOOKS;
    }

    private static int application(String name) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", "v1");
        settings.put("container_port", 8080);
        Row application = Models.get(InstanceModel.class).createEmptyRow();
        application.set(InstanceModel.NAME, name);
        application.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        application.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        application.set(InstanceModel.SETTINGS, settings);
        application.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(application);
        return application.get(InstanceModel.ID);
    }
}
