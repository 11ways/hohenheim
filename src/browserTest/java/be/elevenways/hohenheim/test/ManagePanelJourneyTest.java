package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.OnboardingStep;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.HostFields;
import be.elevenways.hohenheim.server.cms.InstanceParts;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.hohenheim.server.cms.OnboardingCollector;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.cms.server.panel.PartsForms;
import be.elevenways.zenit.cms.server.panel.PartsLists;
import be.elevenways.zenit.cms.server.panel.ResourceVerbs;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.relation.Relation;
import be.elevenways.zenit.common.orm.model.relation.SingleKeyedRelation;
import be.elevenways.zenit.common.orm.query.rules.RuleVocabulary;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tenant walks /manage the way the Manage-Home and Manage-App boards draw it: the flat sidebar, the attention band
 * that carries their own broken app, the Apps band, the usage card, and an app page that offers exactly what the grant
 * allows.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class ManagePanelJourneyTest extends HohenheimTestBase {

    private static final Pattern NAV_LINK = Pattern.compile("<pl-nav-item\\b[^>]*>\\s*<a\\b[^>]*href=\"(/manage/[^\"]*)\"");

    private int tenantId;
    private int appId;
    private int siteId;
    private int domainId;
    private String tenant;
    private Integer previousCountCap;

    @BeforeEach
    void seedTenantAndApp() throws Exception {
        freshSeededDatabase();
        this.tenantId = ApiSupport.user("w9b-tenant@hohenheim.local", "W9b Tenant");
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, "w9b-survival");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_ERROR);
        instances.save(row);
        this.appId = row.get(InstanceModel.ID);
        // The operator's site serving the app: its address is the app's public face, but neither the site nor its
        // domain is granted to the tenant.
        Model sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "w9b-survival-web");
        site.set(SiteModel.SLUG, "w9b-survival-web");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:instance");
        site.set(SiteModel.INSTANCE_ID, this.appId);
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        this.siteId = site.get(SiteModel.ID);
        Model domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, this.siteId);
        domain.set(SiteDomainModel.HOSTNAME, "w9b-survival.example.test");
        domains.save(domain);
        this.domainId = domain.get(SiteDomainModel.ID);
        RecordGrants.grant(GrantSubjectType.USER, this.tenantId, InstanceModel.MODEL_ID, this.appId,
            HohenheimAccess.VIEW, true);
        this.tenant = sessionFor(this.tenantId).token();
        this.previousCountCap = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER);
    }

    @AfterEach
    void restoreCap() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER, this.previousCountCap);
    }

    @Test
    void aTenantSeesTheirAppsTheirProblemsAndOnlyTheDoorsTheirGrantOpens() throws Exception {
        // 1. The sidebar is the admin's flat shape cut to what this tenant holds: Overview, Apps and the Domains
        //    cluster (certificates are readable to any signed-in tenant); no Instances or Sites row, no groups.
        String home = page("/manage/dashboard");
        assertThat(navLinks(home))
            .as("step 1: the tenant's sidebar rows, in the board's order")
            .containsExactly("/manage/dashboard", "/manage/apps", "/manage/domain-names");

        // 2. The attention band carries the app's own verdict, never "All clear" while the app cannot serve: with no
        //    admitted host the placement gate speaks first, in the host-free words the app's page leads with.
        assertThat(home)
            .as("step 2: the app's verdict leads the band")
            .contains("w9b-survival cannot start yet")
            .contains("Open w9b-survival")
            .doesNotContain("All clear");
        assertThat(page("/manage/instances/" + this.appId + "/page/overview"))
            .as("step 2: the app's page leads with the same verdict")
            .contains("w9b-survival cannot start yet");

        // 3. The Apps band, headed "Your apps" as the board heads it, lists the app by name, linked to its page.
        assertThat(home)
            .as("step 3: the Apps band names the tenant's app under the board's heading")
            .contains("data-hh-dashboard-app=\"w9b-survival\"")
            .contains(">Your apps</pb-microcopy>");

        // 4. An uncapped budget draws no line (the instance count has no cap yet; previews carry a default one); a
        //    tenant who may not create is not offered Put something online.
        assertThat(home)
            .as("step 4: no Apps line while the instance count is uncapped")
            .doesNotContain("data-hh-usage=\"Apps\"");
        assertThat(home)
            .as("step 4: no Put something online for a tenant who may not create")
            .doesNotContain("/manage/put-online");

        // 5. A cap set by the operator shows as the usage card, against the tenant's own creation bucket.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER, 5);
        assertThat(page("/manage/dashboard"))
            .as("step 5: the usage card reads the capped budget")
            .contains("data-hh-tenant-usage")
            .contains("data-hh-usage=\"Apps\"")
            .contains("0 of 5");

        // 6. The app page with VIEW alone: the overview, a card saying the tenant may look and share (every instance
        //    capability is delegable, and the Access tab is that card's own gate), and no console, files or power
        //    door.
        String viewer = page("/manage/instances/" + this.appId + "/page/overview");
        assertThat(viewer)
            .as("step 6: the delegate's part is worded on the overview, sharing as the Access tab offers it")
            .contains("What you can do here")
            .contains("Look at it and share access; ask the operator for more")
            .contains("Removing this app.")
            .doesNotContain("changing who has access")
            .contains("/manage/instances/" + this.appId + "/page/access");
        assertThat(viewer)
            .as("step 6: no console or files tab without the capability")
            .doesNotContain("/manage/instances/" + this.appId + "/page/console")
            .doesNotContain("/manage/instances/" + this.appId + "/page/files");
        assertThat(viewer)
            .as("step 6: no power door without POWER")
            .doesNotContain("hohenheim.restart_instance");

        // 6b. The address of the operator's site serving the app is shown, but never as a door the tenant would be
        //     refused: no Add address, no Protect a path, no link to the domain.
        assertThat(viewer)
            .as("step 6b: the app's address is read")
            .contains("w9b-survival.example.test");
        assertThat(viewer)
            .as("step 6b: no door into the site or the domain the tenant holds no grant on")
            .doesNotContain("/manage/sites/" + this.siteId + "/page/domains")
            .doesNotContain("/manage/sites/" + this.siteId + "/page/protected-paths")
            .doesNotContain("/manage/domains/" + this.domainId);
        assertThat(adminGet("/admin/instances/" + this.appId + "/page/overview").body())
            .as("step 6b: the operator, who may open the site, keeps the doors")
            .contains("/admin/sites/" + this.siteId + "/page/domains")
            .contains("/admin/domains/" + this.domainId);

        // 7. Granted console and file reading, the two tabs appear and the card lists them.
        RecordGrants.grant(GrantSubjectType.USER, this.tenantId, InstanceModel.MODEL_ID, this.appId,
            HohenheimAccess.CONSOLE, true);
        RecordGrants.grant(GrantSubjectType.USER, this.tenantId, InstanceModel.MODEL_ID, this.appId,
            HohenheimAccess.FILES_READ, true);
        String granted = page("/manage/instances/" + this.appId + "/page/overview");
        assertThat(granted)
            .as("step 7: the console and files tabs follow the grant")
            .contains("/manage/instances/" + this.appId + "/page/console")
            .contains("/manage/instances/" + this.appId + "/page/files");
        assertThat(granted)
            .as("step 7: the card lists the capabilities and the sharing, as one sentence (case=sentence)")
            .doesNotContain("Look at it and share access; ask the operator for more")
            .contains("Console, read files, share access");

        // 7b. Granted power, the app page offers Restart beside Deploy (board Manage-App).
        RecordGrants.grant(GrantSubjectType.USER, this.tenantId, InstanceModel.MODEL_ID, this.appId,
            HohenheimAccess.POWER, true);
        assertThat(page("/manage/instances/" + this.appId + "/page/overview"))
            .as("step 7b: Restart follows the POWER grant")
            .contains("hohenheim.restart_instance")
            .contains("hohenheim.start_instance");

        // 8. The operator on /manage is offered Put something online from the landing.
        assertThat(adminGet("/manage/dashboard").body())
            .as("step 8: an operator is offered Put something online")
            .contains("/manage/put-online");
    }

    @Test
    void theTenantTwinNamesNoHostAndEveryOfferFollowsWhatTheAppAndTheReaderCanDo() throws Exception {
        // A second app of the tenant's, on a host of its own, so a host filter has something to select.
        Model servers = Models.get(ServerModel.class);
        Row secretHost = servers.createEmptyRow();
        secretHost.set(ServerModel.NAME, "d7f-secret-host");
        secretHost.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        secretHost.set(ServerModel.MODE, ServerModel.MODE_SSH);
        secretHost.set(ServerModel.SSH_TARGET, "operator@d7f-secret-host.invalid");
        secretHost.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
        servers.save(secretHost);
        int secretHostId = secretHost.get(ServerModel.ID);
        Model instances = Models.get(InstanceModel.class);
        Row hidden = instances.createEmptyRow();
        hidden.set(InstanceModel.NAME, "d7f-hidden");
        hidden.set(InstanceModel.KIND, "hohenheim:docker_container");
        hidden.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "command", "sleep 60")));
        hidden.set(InstanceModel.SERVER_ID, secretHostId);
        instances.save(hidden);
        int hiddenId = hidden.get(InstanceModel.ID);
        RecordGrants.grant(GrantSubjectType.USER, this.tenantId, InstanceModel.MODEL_ID, hiddenId,
            HohenheimAccess.VIEW, true);
        Panel manage = Objects.requireNonNull(PanelRegistry.getBySlug(ManagePanel.SLUG));
        AccessContext tenantAccess = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(this.tenantId, "W9b Tenant")));

        // 1. The landing is headed "Your services" as board Manage-Home heads it; its sidebar row still reads Overview.
        String home = page("/manage/dashboard");
        assertThat(home)
            .as("step 1: the tenant's landing is headed by the panel's title")
            .contains("<h1>Your services</h1>")
            .doesNotContain("<h1>Overview</h1>")
            .contains("Overview");

        // 2. No /manage twin speaks a host: every field naming a host (HostFields) is outside the tenant's vocabulary,
        //    and every relation of a /manage model to a host is named there, so a new one cannot slip past.
        for (PanelEntry entry : manage.entries()) {
            if (!(entry instanceof PanelResource<?> resource) || resource.subject().modelId() == null) {
                continue;
            }
            Model model = Models.get(resource.subject().modelId());
            Set<String> declared = new LinkedHashSet<>();
            for (Field<?, ?> field : HostFields.of(model.getModelId())) {
                declared.add(field.getName());
            }
            for (Relation<?, ?> relation : model.getSchema().getRelations().values()) {
                if (relation instanceof SingleKeyedRelation<?> keyed
                        && (keyed.getRelatedModelSupplier().get() instanceof ServerModel
                            || keyed.getRelatedModelSupplier().get() instanceof DatabaseEngineModel)) {
                    assertThat(declared)
                        .as("step 2: %s relates to a host through %s, which HostFields must name", model.getModelId(),
                            keyed.getLocalKey().getName())
                        .contains(keyed.getLocalKey().getName());
                }
            }
            if (declared.isEmpty()) {
                continue;
            }
            RuleVocabulary vocabulary = PartsLists.filterVocabulary(manage, entry, tenantAccess);
            for (String name : declared) {
                assertThat(vocabulary.find(name))
                    .as("step 2: the tenant cannot filter /manage %s by host field %s", entry.slug(), name)
                    .isNull();
                assertThat(PartsForms.queryGate(entry).mayQuery(name, tenantAccess))
                    .as("step 2: nor sort, search or facet it").isFalse();
            }
        }
        Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN));
        assertThat(PartsLists.filterVocabulary(admin, Objects.requireNonNull(admin.entryBySlug(InstanceParts.SLUG)),
                TenantConduits.operator()).find(InstanceModel.SERVER_ID.getName()))
            .as("step 2: the operator's own list still filters by host").isNotNull();

        // 3. A tenant request carrying a host filter or a host sort is refused or ignored, never honoured, and the
        //    list names no host; the operator's same filter does select by host (the probe is a real filter).
        String hostRule = "q=" + URLEncoder.encode("server_id = " + secretHostId, StandardCharsets.UTF_8);
        HttpResponse<String> filtered = httpGet("/manage/instances?" + hostRule, this.tenant);
        assertThat(filtered.statusCode() >= 400 || filtered.body().contains("w9b-survival"))
            .as("step 3: the tenant's host rule is refused or ignored (status %s)", filtered.statusCode())
            .isTrue();
        assertThat(filtered.body()).as("step 3: and names no host").doesNotContain("d7f-secret-host");
        String sorted = page("/manage/instances?sort=server_id");
        assertThat(sorted)
            .as("step 3: a host sort is ignored: both apps listed, no host named")
            .contains("w9b-survival").contains("d7f-hidden").doesNotContain("d7f-secret-host");
        assertThat(adminGet("/admin/instances?" + hostRule).body())
            .as("step 3: the operator's host rule selects the app on that host")
            .contains("d7f-hidden").doesNotContain("w9b-survival");
        assertThat(page("/manage/instances/" + hiddenId + "/page/overview"))
            .as("step 3: the app's own page names no host either").doesNotContain("d7f-secret-host");

        // 4. The app cannot start: no Open site, for the tenant and the operator alike, though its site has an address.
        String tenantApp = page("/manage/instances/" + this.appId + "/page/overview");
        assertThat(tenantApp).as("step 4: no Open site on /manage while the app cannot start")
            .doesNotContain("instance_open_site");
        String operatorApp = adminGet("/admin/instances/" + this.appId + "/page/overview").body();
        assertThat(operatorApp).as("step 4: nor on the operator's page").doesNotContain("instance_open_site");

        // 5. Who clears it decides the words: the tenant reads that the operator has to, the operator on /manage reads
        //    the fix, host-free.
        assertThat(tenantApp).as("step 5: the tenant is told the operator clears it")
            .contains("Your operator has to clear this");
        String operatorOnManage = adminGet("/manage/instances/" + this.appId + "/page/overview").body();
        assertThat(operatorOnManage)
            .as("step 5: the operator on /manage reads how to clear it")
            .contains("Open the host in the admin panel and use Check and admit")
            .doesNotContain("Your operator has to clear this");
        assertThat(adminGet("/manage/dashboard").body())
            .as("step 5: and so does the operator's /manage attention band")
            .doesNotContain("Your operator has to clear this");

        // 6. Nothing runs, so the operator's first-run checklist does not say an app is online, though a site exists.
        assertThat(putOnlineStep().isDone()).as("step 6: a site whose app cannot start is not an app online")
            .isFalse();

        // 7. A Destroy grant has its door: /manage offers the verified destroy, dead with the gate's own words to a
        //    reader without destroy (removal stays the operator's, as the card says), live to a holder, whose card no
        //    longer leaves removal to the operator.
        PanelEntry manageInstances = Objects.requireNonNull(manage.entryBySlug(InstanceParts.SLUG));
        Row app = instances.findById(this.appId);
        assertThat(tenantApp).as("step 7: the app page offers the delete").contains("delete_instance")
            .contains("Removing this app.");
        assertThat(ResourceVerbs.unavailable(manage, manageInstances, ResourceVerb.DELETE, app, tenantAccess))
            .as("step 7: dead without destroy, in the gate's words").isNotNull()
            .extracting(Microcopy::key).isEqualTo("instance_not_permitted");
        RecordGrants.grant(GrantSubjectType.USER, this.tenantId, InstanceModel.MODEL_ID, this.appId,
            HohenheimAccess.DESTROY, true);
        AccessContext destroyer = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(this.tenantId, "W9b Tenant")));
        assertThat(ResourceVerbs.unavailable(manage, manageInstances, ResourceVerb.DELETE, app, destroyer))
            .as("step 7: live for the holder of destroy").isNull();
        assertThat(ResourceVerbs.removableBy(manage, manageInstances, app, destroyer))
            .as("step 7: the /manage entry lets the holder delete").isTrue();
        assertThat(page("/manage/instances/" + this.appId + "/page/overview"))
            .as("step 7: with destroy, nothing is left to the operator")
            .contains("delete_instance")
            .doesNotContain("Removing this app.")
            .doesNotContain("Up to the operator");

        // 8. Once the app runs on a host that takes it, Open site is offered on both panels and the checklist ticks.
        var local = HostFixtures.captureLocal();
        try {
            HostFixtures.makeLocalPlaceable(16L * 1024);
            app = instances.findById(this.appId);
            app.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
            instances.save(app);
            assertThat(page("/manage/instances/" + this.appId + "/page/overview"))
                .as("step 8: a serving app offers Open site to the tenant").contains("instance_open_site");
            assertThat(adminGet("/admin/instances/" + this.appId + "/page/overview").body())
                .as("step 8: and to the operator").contains("instance_open_site");
            assertThat(putOnlineStep().isDone()).as("step 8: a running app is an app online").isTrue();
        } finally {
            local.restore();
        }
    }

    /** The first-run checklist's "Put your first app online" step. */
    private static OnboardingStep putOnlineStep() {
        return OnboardingCollector.collect().stream()
            .filter(step -> "checklist_put_online".equals(step.title().key())).findFirst().orElseThrow();
    }

    private String page(String path) throws Exception {
        HttpResponse<String> answer = httpGet(path, this.tenant);
        assertThat(answer.statusCode()).as("GET %s as the tenant", path).isEqualTo(200);
        return answer.body();
    }

    private static List<String> navLinks(String html) {
        List<String> links = new ArrayList<>();
        Matcher matcher = NAV_LINK.matcher(html);
        while (matcher.find()) {
            links.add(matcher.group(1));
        }
        return links;
    }
}
