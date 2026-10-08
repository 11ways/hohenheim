package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            .as("step 7: the card lists the capabilities and the sharing")
            .doesNotContain("Look at it and share access; ask the operator for more")
            .contains("Console, Read files, Share access");

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
