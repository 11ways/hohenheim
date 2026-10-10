package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.FormStep;
import be.elevenways.zenit.common.edit.StepAnswers;
import be.elevenways.zenit.common.edit.StepSummary;
import be.elevenways.zenit.common.edit.SummaryLine;
import be.elevenways.zenit.common.operation.OperationResult;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.OperationRun;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.server.operation.OperationRuns;
import be.elevenways.zenit.test.support.OutboundFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * "Put something online" walked from its chooser to a running app: a template becomes an app with its website and
 * address in one background run, an address that needs no workload becomes a website, and what cannot be done (HTTPS
 * while Let's Encrypt is off, an address claimed twice, an address a tenant may not give) is said in words.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class PutOnlineJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "put-online-journey-";

    private static int hostId;
    private static Row template;
    private static AccessContext tenant;

    @BeforeAll
    static void seed() {
        FakeNativeDaemons.register();
        hostId = HostFixtures.admittedIncusHost(PREFIX + "host");
        Row row = Models.get(InstanceTemplateModel.class).createEmptyRow();
        row.set(InstanceTemplateModel.NAME, PREFIX + "shop");
        row.set(InstanceTemplateModel.DESCRIPTION, "A shop that sells nothing");
        row.set(InstanceTemplateModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "fake/image")));
        row.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
        row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, 1L);
        Models.get(InstanceTemplateModel.class).save(row);
        template = row;

        int tenantId = ApiSupport.user("tenant@" + PREFIX + "test", "Put Online Tenant");
        GrantService.createDirectGrant(GrantSubjectType.USER, tenantId, HohenheimAccess.INSTANCES_CREATE.value(), true);
        tenant = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(tenantId, "Put Online Tenant")));
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, false);
    }

    @AfterAll
    static void cleanUp() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, true);
        for (Row site : Models.get(SiteModel.class).find()
                .where(SiteModel.NAME.in(List.of(PREFIX + "shop-app", PREFIX + "old-shop"))).all()) {
            for (Row domain : Models.get(SiteDomainModel.class).find()
                    .where(SiteDomainModel.SITE_ID.eq(site.get(SiteModel.ID))).all()) {
                HardDeletes.row(Models.get(SiteDomainModel.class), domain);
            }
            HardDeletes.row(Models.get(SiteModel.class), site);
        }
    }

    @Test
    void aTemplateAndAnAddressGoOnlineInOneRunEach() throws Exception {
        AccessContext operator = TenantConduits.operator();

        // 1. The dashboard leads to the chooser; the chooser offers the template and the address kinds, and dev
        //    namespaces and served instances are no apps.
        assertThat(adminGet("/admin/dashboard").body()).as("step 1: the dashboard's header offers the flow")
            .contains("data-cms-dashboard-actions").contains("href=\"/admin/" + HohenheimSlugs.PUT_ONLINE + "\"");
        String chooser = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE).body();
        assertThat(chooser).as("step 1: the templates group lists the template")
            .contains("data-hh-put-online-group=\"templates\"").contains(PREFIX + "shop");
        assertThat(chooser).as("step 1: the address kinds are a group of their own")
            .contains("data-hh-put-online-group=\"addresses\"")
            .contains("kind:hohenheim:redirect").doesNotContain("kind:hohenheim:instance")
            .doesNotContain("kind:hohenheim:dev_namespace");
        assertThat(chooser).as("step 1: whole machines are a group, and static files sit with the operator's own code")
            .contains("data-hh-put-online-group=\"machine\"").contains("instance:hohenheim:vm")
            .contains("data-hh-put-online-group=\"code\"").contains("kind:hohenheim:static")
            .contains("data-hh-put-online-steps");
        // 1b. A family Hohenheim ships reads its short card line, not its catalogue description; a template the
        //     operator wrote keeps its own words.
        Row starter = Models.get(InstanceTemplateModel.class).createEmptyRow();
        starter.set(InstanceTemplateModel.NAME, "WordPress (PHP 99.9)");
        starter.set(InstanceTemplateModel.DESCRIPTION, "Catalogue form: official Apache image, PHP 99.9, a docroot volume");
        starter.set(InstanceTemplateModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        starter.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "fake/image")));
        starter.set(InstanceTemplateModel.SOURCE, InstanceTemplateModel.SOURCE_STARTER);
        starter.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
        starter.set(InstanceTemplateModel.APPROVED_BY_USER_ID, 1L);
        Models.get(InstanceTemplateModel.class).save(starter);
        try {
            String shipped = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE).body();
            assertThat(shipped).as("step 1b: the shipped WordPress family reads its short line")
                .contains("A WordPress site with its own database.")
                .doesNotContain("Catalogue form: official Apache image, PHP 99.9");
            assertThat(shipped).as("step 1b: the operator's own template keeps its description")
                .contains("A shop that sells nothing");
        } finally {
            HardDeletes.row(Models.get(InstanceTemplateModel.class), starter);
        }

        HttpResponse<String> picked = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE
            + "?choice=kind:hohenheim:redirect");
        assertThat(picked.headers().firstValue("location").orElse(""))
            .as("step 1: Continue with a card opens that card's flow")
            .contains(HohenheimSlugs.PUT_ONLINE).contains("kind=hohenheim%3Aredirect");
        assertThat(adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?choice=https://elsewhere.example").body())
            .as("step 1: a value the chooser did not draw only redraws the chooser").contains("data-hh-put-online-chooser");

        // 2. Choosing opens the stepped wizard: where, the template's options, HTTPS.
        String wizard = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?template=" + template
            .get(InstanceTemplateModel.ID))
            .body();
        assertThat(wizard).as("step 2: the template's wizard has its Where and HTTPS steps")
            .contains("data-zf-step=\"where\"").contains("data-zf-step=\"https\"");
        String addressWizard = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?kind=hohenheim:redirect").body();
        assertThat(addressWizard).as("step 2: an address kind opens its own wizard")
            .contains("data-zf-step=\"where\"").contains("data-zf-step=\"https\"");
        assertThat(wizard).as("step 2: a template may go online without an address, and its hint says so")
            .contains("nobody reaches by name");
        assertThat(addressWizard).as("step 2: an address kind has nothing to serve without one, so no such hint")
            .doesNotContain("nobody reaches by name").contains("You can add more addresses later.");
        assertThat(addressWizard)
            .as("step 2: the wizard's state carries the journey around it, What before it and Going live after")
            .contains("step_what").contains("step_live");
        assertThat(addressWizard).as("step 2: the chooser fixed what it serves: the kind rides along as transport")
            .contains("type=\"hidden\" name=\"upstream_kind\" value=\"hohenheim:redirect\"");
        assertThat(addressWizard.split("name=\"upstream_kind\"", -1).length - 1)
            .as("step 2: and is never asked again").isEqualTo(1);
        assertThat(addressWizard).as("step 2: the chosen kind's card carries its icon")
            .contains("data-hh-put-online-what-icon");

        // 3. Putting the template online answers with a run at once; the run creates the app, its website and its
        //    address, starts it, and says HTTPS waits because Let's Encrypt is off.
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("name", PREFIX + "shop-app");
        form.put(PutOnline.HOSTNAME.getName(), "shop." + PREFIX + "test");
        form.put("serverId", hostId);
        form.put(PutOnline.HTTPS.getName(), PutOnline.HTTPS_AUTOMATIC);
        form.put("variables", Map.of());
        OperationResult<?> answer = OperationPipeline.invoke(OperationRequest.of(PutOnline.PUT_ONLINE,
                CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator()).subjects(List.of(template)).form(form));
        assertThat(answer.run()).as("step 3: the answer is a run, before the work").isNotNull();
        OperationRun run = ended(runOf(answer), operator, "step 3");
        assertThat(run.status()).as("step 3: the run succeeded (%s)", run.outcome()).isEqualTo(OperationRunStatus.SUCCEEDED);
        assertThat(run.stepsDone()).as("step 3: every step passed").isEqualTo(5L);
        assertThat(Objects.requireNonNull(run.steps()).journeyBefore())
            .as("step 3: the run ends the journey the wizard walked: what, where, options, https").hasSize(4);
        assertThat(run.skipped(3)).as("step 3: the certificate waits for the setting, so its step reads skipped")
            .isTrue();
        assertThat(run.skipped(2)).as("step 3: the website was made, so its step is done").isFalse();
        assertThat(Objects.requireNonNull(run.outcome()).key()).as("step 3: its words say HTTPS waits for the setting")
            .isEqualTo("outcome_https_off_note");
        Row app = Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(PREFIX + "shop-app")).first();
        assertThat(app).as("step 3: the app exists").isNotNull();
        assertThat(String.valueOf((Object) app.get(InstanceModel.STATUS))).as("step 3: and was started")
            .isEqualTo(InstanceModel.STATUS_RUNNING);
        assertThat(OperationRuns.recentFor(InstanceOperations.INSTANCE, String.valueOf((Object) app.get(InstanceModel.ID)),
                5, operator)).as("step 3: the run is found from the app it made").extracting(OperationRun::id)
            .contains(run.id());
        Row website = Models.get(SiteModel.class).find().where(SiteModel.INSTANCE_ID.eq(app.get(InstanceModel.ID)))
            .first();
        assertThat(website).as("step 3: a website serves the app").isNotNull();
        assertThat(Models.get(SiteDomainModel.class).find().where(SiteDomainModel.HOSTNAME.eq("shop." + PREFIX + "test"))
            .first()).as("step 3: on the address given").isNotNull();

        // 4. A redirect needs no workload: one run creates only its website and address.
        Map<String, Object> redirect = new LinkedHashMap<>();
        redirect.put(SiteModel.NAME.getName(), PREFIX + "old-shop");
        redirect.put(PutOnline.HOSTNAME.getName(), "old." + PREFIX + "test");
        redirect.put(SiteModel.UPSTREAM_KIND.getName(), "hohenheim:redirect");
        redirect.put(SiteModel.SETTINGS.getName(), Map.of("target_url", "https://shop." + PREFIX + "test/",
            "http_status", "301"));
        redirect.put(PutOnline.HTTPS.getName(), PutOnline.HTTPS_LATER);
        OperationRun redirected = ended(runOf(OperationPipeline.invoke(OperationRequest.of(
                PutOnline.PUT_ADDRESS_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator()).form(redirect))), operator, "step 4");
        assertThat(redirected.status()).as("step 4: the run succeeded (%s)", redirected.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        assertThat(Objects.requireNonNull(redirected.outcome()).key()).as("step 4: HTTPS was asked for later")
            .isEqualTo("outcome_https_later_note");
        assertThat(List.of(redirected.skipped(0), redirected.skipped(1)))
            .as("step 4: the website is done and the certificate, left for later, is skipped")
            .containsExactly(false, true);
        Row old = Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(PREFIX + "old-shop")).first();
        assertThat(old).as("step 4: the redirect's website exists").isNotNull();
        assertThat(String.valueOf((Object) old.get(SiteModel.UPSTREAM_KIND))).as("step 4: and redirects")
            .isEqualTo("hohenheim:redirect");
        assertThat(OperationRuns.recentFor(SiteOperations.SITE, String.valueOf((Object) old.get(SiteModel.ID)), 5,
            operator)).as("step 4: its run is found from the website").extracting(OperationRun::id)
            .contains(redirected.id());

        // 5. The same address twice: the second run is refused in words on the address, and makes no website.
        redirect.put(SiteModel.NAME.getName(), PREFIX + "copy");
        OperationRun claimed = ended(runOf(OperationPipeline.invoke(OperationRequest.of(
                PutOnline.PUT_ADDRESS_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator()).form(redirect))), operator, "step 5");
        assertThat(claimed.status()).as("step 5: a claimed address is refused").isEqualTo(OperationRunStatus.REFUSED);
        assertThat(claimed.outcome()).as("step 5: with words").isNotNull();
        assertThat(Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(PREFIX + "copy")).first())
            .as("step 5: and the website was rolled back").isNull();

        // 6. A served instance's kind is no app of its own: it is no option, so the request refuses it on that field
        //    before any run.
        redirect.put(SiteModel.UPSTREAM_KIND.getName(), "hohenheim:instance");
        redirect.put(PutOnline.HOSTNAME.getName(), "instance." + PREFIX + "test");
        redirect.put(SiteModel.SETTINGS.getName(), Map.of());
        Violations noApp = catchThrowableOfType(Violations.class, () -> OperationPipeline.invoke(
            OperationRequest.of(PutOnline.PUT_ADDRESS_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
                .caller(TenantConduits.operator()).form(redirect)));
        assertThat((Object) noApp).as("step 6: refused in the request").isNotNull();
        assertThat(noApp.all()).as("step 6: on the kind").extracting(Violation::fieldName)
            .contains(SiteModel.UPSTREAM_KIND.getName());

        // 7. A tenant puts the template online without an address (placement picks the host) and may not give one.
        Map<String, Object> tenantForm = new LinkedHashMap<>();
        tenantForm.put("name", PREFIX + "tenant-app");
        tenantForm.put(PutOnline.HTTPS.getName(), PutOnline.HTTPS_LATER);
        tenantForm.put("variables", Map.of());
        OperationRun tenantRun = ended(runOf(OperationPipeline.invoke(OperationRequest.of(
                PutOnline.PUT_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(tenant).subjects(List.of(template)).form(tenantForm))), tenant, "step 7");
        assertThat(tenantRun.status()).as("step 7: the tenant's run succeeded (%s)", tenantRun.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        assertThat(Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(PREFIX + "tenant-app")).first())
            .as("step 7: the tenant's app exists").isNotNull();
        tenantForm.put("name", PREFIX + "tenant-site");
        tenantForm.put(PutOnline.HOSTNAME.getName(), "tenant." + PREFIX + "test");
        DomainRefusal noAddress = catchThrowableOfType(DomainRefusal.class, () -> OperationPipeline.invoke(
            OperationRequest.of(PutOnline.PUT_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
                .caller(tenant).subjects(List.of(template)).form(tenantForm)));
        assertThat(noAddress).as("step 7: an address from a tenant is refused in the request").isNotNull();
    }

    /** @return the run a background answer names */
    private static long runOf(OperationResult<?> answer) {
        Long run = answer.run();
        assertThat(run).as("a background answer names its run").isNotNull();
        return run;
    }

    /** Polls until the run has ended, failing after twenty seconds. */
    @Test
    void theHttpsStepSummarizesWhatWillBeCreatedAndWhetherTheAddressPointsHere() {
        var servers = Models.get(ServerModel.class);
        Row local = servers.findById(ServerModel.localServerId());
        String declared = local.get(ServerModel.PUBLIC_IPV4);
        FormSpec form = PutOnline.PUT_ADDRESS_ONLINE.input().form();
        StepSummary summary = form.steps().stream().filter(step -> step.id().equals("https")).findFirst()
            .map(FormStep::summary).orElseThrow();
        try (OutboundFixture here = OutboundFixture.route(PREFIX + "here.test", 9);
             OutboundFixture elsewhere = OutboundFixture.route(PREFIX + "elsewhere.test", 9)) {
            local.set(ServerModel.PUBLIC_IPV4, here.publicAddress().getHostAddress());
            servers.save(local);

            // 1. An address that points here: the summary names what it serves, its name and address, and says so.
            List<SummaryLine> lines = summary.summarize(StepAnswers.of(form, Map.of(
                SiteModel.NAME.getName(), "Old shop", SiteDomainModel.HOSTNAME.getName(), PREFIX + "here.test",
                SiteModel.UPSTREAM_KIND.getName(), "hohenheim:redirect")));
            assertThat(lines).as("step 1: what, name, address and where it points, in that order")
                .extracting(line -> line.label().key())
                .containsExactly("summary_serves", "summary_name", "summary_address", "reach_label");
            assertThat(lines.get(1).value().key()).as("step 1: the name is the operator's own text")
                .isEqualTo("Old shop");
            assertThat(lines.get(3).value().key()).as("step 1: the address points here").isEqualTo("reach_here");

            // 2. A name that resolves elsewhere is a notice, never a refusal: the summary says where it points.
            List<SummaryLine> away = summary.summarize(StepAnswers.of(form, Map.of(
                SiteDomainModel.HOSTNAME.getName(), PREFIX + "elsewhere.test",
                SiteModel.UPSTREAM_KIND.getName(), "hohenheim:redirect")));
            SummaryLine reach = away.get(away.size() - 1);
            assertThat(reach.value().key()).as("step 2: it points elsewhere").isEqualTo("reach_elsewhere");
            assertThat(String.valueOf(reach.value().args().get("addresses")))
                .as("step 2: naming the address it points to")
                .isEqualTo(elsewhere.publicAddress().getHostAddress());

            // 3. A name that does not resolve at all reads as not pointing anywhere yet.
            List<SummaryLine> nowhere = summary.summarize(StepAnswers.of(form, Map.of(
                SiteDomainModel.HOSTNAME.getName(), PREFIX + "nowhere.invalid",
                SiteModel.UPSTREAM_KIND.getName(), "hohenheim:redirect")));
            assertThat(nowhere.get(nowhere.size() - 1).value().key()).as("step 3: it does not resolve")
                .isEqualTo("reach_unresolved");
        } finally {
            local = servers.findById(ServerModel.localServerId());
            local.set(ServerModel.PUBLIC_IPV4, declared);
            servers.save(local);
        }
    }

    private static OperationRun ended(long runId, AccessContext viewer, String step) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        BooleanSupplier done = () -> {
            OperationRun run = OperationRuns.of(runId, viewer);
            return run != null && run.status() != OperationRunStatus.RUNNING;
        };
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(step + ": run " + runId + " did not end within twenty seconds");
            }
            Thread.sleep(50);
        }
        return Objects.requireNonNull(OperationRuns.of(runId, viewer));
    }
}
