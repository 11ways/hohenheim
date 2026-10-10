package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.cms.AppDirectory.App;
import be.elevenways.hohenheim.server.cms.AppDirectory.Source;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.tls.CertificateExpiry;
import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Apps list reads every kind of app Hohenheim serves as one row of its own, with the kind, address, host and
 * HTTPS state the app's record page says, and opens that record page; what belongs to another record (a stack's
 * members, a database's container, a database engine) is no app. A website runs where its instance runs, and a
 * catch-all says what it catches. A broken row offers its verdict's fix, its glyph and HTTPS badge agree, and a
 * certificate's expiry reads in whole days everywhere.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class AppsListJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "apps-journey-";

    @Test
    void everyKindOfAppIsOneRowThatOpensItsOwnPage() throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            int local = ServerModel.localServerId();
            String host = ServerModel.nameOf(local);
            // System containers and virtual machines run on an Incus host only; the implicit local host is Docker.
            int incus = HostFixtures.admittedIncusHost(PREFIX + "incus");
            cleanup.add(() -> HardDeletes.row(Models.get(ServerModel.class),
                Models.get(ServerModel.class).findById(incus)));
            String incusHost = ServerModel.nameOf(incus);

            // 1. One app of every kind: four kinds of website, a Docker workload with its site, a system container
            //    and a virtual machine no site serves (the machine publishing a port), a stack with two services, and
            //    the records that are NOT apps: a stack's service instance, a database's container, an engine's.
            Row proxy = site(cleanup, "proxy", "hohenheim:address",
                Map.of("forward_scheme", "http", "forward_host", "10.0.0.5", "forward_port", 8080), null);
            domain(proxy, "proxy.apps-journey.test", false);
            Row redirect = site(cleanup, "redirect", "hohenheim:redirect",
                Map.of("target_url", "https://example.com/", "http_status", "301"), null);
            domain(redirect, "redirect.apps-journey.test", false);
            Row files = site(cleanup, "static", "hohenheim:static", Map.of("root_path", "/tmp"), null);
            domain(files, "static.apps-journey.test", true);
            Row passthrough = site(cleanup, "passthrough", SiteModel.UPSTREAM_TLS_PASSTHROUGH,
                Map.of("forward_host", "10.0.0.6", "forward_port", 443), null);
            domain(passthrough, "passthrough.apps-journey.test", false);

            Row docker = instance(cleanup, "docker", "hohenheim:docker_container",
                Map.of("image", "alpine", "command", "sleep 60"), InstanceModel.STATUS_STOPPED, local);
            Row served = site(cleanup, "docker-site", "hohenheim:instance", Map.of(), docker.get(InstanceModel.ID));
            domain(served, "docker.apps-journey.test", false);
            domain(served, "www.docker.apps-journey.test", false);

            Row lxc = instance(cleanup, "lxc", "hohenheim:system_container",
                Map.of("image", "images:debian/12"), InstanceModel.STATUS_STOPPED, incus);
            Row vm = instance(cleanup, "vm", "hohenheim:vm", Map.of("image", "debian-12"),
                InstanceModel.STATUS_STOPPED, incus);
            PortLedger.recordObserved(incus, "127.0.0.1", 25588, "tcp", InstanceModel.MODEL_ID,
                vm.get(InstanceModel.ID), "apps journey");
            cleanup.add(() -> Models.get(PortAllocationModel.class).find()
                .where(PortAllocationModel.OWNER_ID.eq(vm.get(InstanceModel.ID))).all()
                .forEach(claim -> HardDeletes.row(Models.get(PortAllocationModel.class), claim)));

            Row stack = row(cleanup, Models.get(StackModel.class), Map.of(StackModel.NAME.getName(), PREFIX + "stack",
                StackModel.SERVER_ID.getName(), local, StackModel.STATUS.getName(), StackModel.STATUS_ACTIVE,
                StackModel.ENABLED.getName(), false));
            Row web = row(cleanup, Models.get(StackServiceModel.class), Map.of(
                StackServiceModel.STACK_ID.getName(), stack.get(StackModel.ID),
                StackServiceModel.NAME.getName(), "web", StackServiceModel.IMAGE.getName(), "nginx",
                StackServiceModel.ENABLED.getName(), false));
            row(cleanup, Models.get(StackServiceModel.class), Map.of(
                StackServiceModel.STACK_ID.getName(), stack.get(StackModel.ID),
                StackServiceModel.NAME.getName(), "cache", StackServiceModel.IMAGE.getName(), "redis",
                StackServiceModel.ENABLED.getName(), false));
            Row stackWeb = generated(cleanup, "stack", StackServiceModel.MODEL_ID, web.get(StackServiceModel.ID),
                "stack-web", "hohenheim:stack_service", Map.of("image", "nginx"), local);
            generated(cleanup, "database", DatabaseModel.MODEL_ID, 919191, "database",
                "hohenheim:database_container", Map.of("engine", "mariadb"), local);
            generated(cleanup, "database", DatabaseEngineModel.MODEL_ID, 929292, "engine",
                "hohenheim:database_container", Map.of("engine", "postgres"), local);

            Map<String, App> apps = journeyApps();

            // 2. Exactly the apps a person put online: one row each, and no row for what another record owns.
            assertThat(apps.keySet()).as("step 2: one row per app, a site serving a workload folded into it")
                .containsExactlyInAnyOrder(PREFIX + "proxy", PREFIX + "redirect", PREFIX + "static",
                    PREFIX + "passthrough", PREFIX + "docker", PREFIX + "lxc", PREFIX + "vm", PREFIX + "stack");
            assertThat(apps.keySet()).as("step 2: a stack's service, a database container and an engine are no apps")
                .doesNotContain(PREFIX + "stack-web", PREFIX + "database", PREFIX + "engine", PREFIX + "docker-site");

            // 3. Websites: their upstream's kind, their first name, no host, the HTTPS state of their names, and the
            //    site's own overview.
            assertRow(apps.get(PREFIX + "proxy"), Source.WEBSITE, "Proxy to an address", "proxy.apps-journey.test",
                null, CertCoverage.NONE, "/admin/sites/" + proxy.get(SiteModel.ID) + "/page/overview", "step 3");
            assertRow(apps.get(PREFIX + "redirect"), Source.WEBSITE, "Redirect", "redirect.apps-journey.test",
                null, CertCoverage.NONE, "/admin/sites/" + redirect.get(SiteModel.ID) + "/page/overview", "step 3");
            assertRow(apps.get(PREFIX + "static"), Source.WEBSITE, "Static files", "static.apps-journey.test",
                null, CertCoverage.ERROR, "/admin/sites/" + files.get(SiteModel.ID) + "/page/overview", "step 3");
            assertThat(apps.get(PREFIX + "static").health().tone())
                .as("step 3: a name forced to HTTPS without a certificate is the broken verdict the site page shows")
                .isEqualTo(HealthTone.BROKEN);
            // The HTTPS column is the main address's HTTPS, never the app's verdict: the Addresses list's own words.
            StateLineCell staticHttps = apps.get(PREFIX + "static").https();
            assertThat(say(staticHttps.label())).as("step 3: the forced name reads as the Addresses list says it")
                .isEqualTo("Not working").isNotEqualTo("Error page");
            assertThat(say(staticHttps.detail())).as("step 3: with the Addresses list's reason beside it")
                .isEqualTo(say(DomainParts.httpsDetail(domainOf(files), CertCoverage.ERROR, null)));
            assertThat(say(apps.get(PREFIX + "proxy").https().label()))
                .as("step 3: an unforced name without a certificate says so in the same words")
                .isEqualTo(say(CertCoverage.NONE.label()));
            assertRow(apps.get(PREFIX + "passthrough"), Source.WEBSITE, "TLS passthrough",
                "passthrough.apps-journey.test", null, CertCoverage.NOT_USED,
                "/admin/sites/" + passthrough.get(SiteModel.ID) + "/page/overview", "step 3");

            // 4. Workloads: their kind, the names of the sites serving them (or the port they publish, or nothing),
            //    the host they run on, and the workload's own overview.
            App dockerApp = apps.get(PREFIX + "docker");
            assertRow(dockerApp, Source.WORKLOAD, "Docker container", "docker.apps-journey.test", host,
                CertCoverage.NONE, "/admin/instances/" + docker.get(InstanceModel.ID) + "/page/overview", "step 4");
            assertThat(dockerApp.address().moreCount()).as("step 4: the second name of its site counts as one more")
                .isEqualTo(1);
            assertRow(apps.get(PREFIX + "lxc"), Source.WORKLOAD, "System container (LXC)", null, incusHost,
                null, "/admin/instances/" + lxc.get(InstanceModel.ID) + "/page/overview", "step 4");
            App vmApp = apps.get(PREFIX + "vm");
            assertThat(vmApp.addressText()).as("step 4: a machine no site serves is reached at the port it publishes")
                .endsWith(":25588");
            assertRow(vmApp, Source.WORKLOAD, "Virtual machine", vmApp.addressText(), incusHost, null,
                "/admin/instances/" + vm.get(InstanceModel.ID) + "/page/overview", "step 4");

            // 5. A stack: one row naming its members, on its host, opening its services.
            assertRow(apps.get(PREFIX + "stack"), Source.STACK, "Stack with 2 services", null, host, null,
                "/admin/stacks/" + stack.get(StackModel.ID) + "/page/services", "step 5");
            assertThat(apps.get(PREFIX + "stack").health().tone()).as("step 5: an active stack is working")
                .isEqualTo(HealthTone.OK);

            // 6. The list page draws them: each row links to its record page, carries its verdict's glyph, and names
            //    nothing that is not an app.
            String list = adminGet("/admin/apps?_search=apps-journey").body();
            for (App app : apps.values()) {
                int link = list.indexOf("href=\"" + app.target().toUrl());
                assertThat(link).as("step 6: the list links " + app.name() + " to its record page").isNotNegative();
                // The leading health glyph is no title: the record link is the app's NAME (the board's Apps list).
                assertThat(list.substring(list.lastIndexOf("<a", link), list.indexOf("</a>", link)))
                    .as("step 6: the link of " + app.name() + " is its name, not the health glyph")
                    .contains("cms-row-link")
                    .contains(app.name())
                    .doesNotContain("data-cms-health");
            }
            assertThat(list).as("step 6: the broken static site carries the broken glyph")
                .contains("data-cms-health=\"broken\"");
            String dashboard = adminGet("/admin/dashboard").body();
            int row = dashboard.indexOf("data-hh-dashboard-app=\"" + PREFIX + "static\"");
            assertThat(row).as("step 6: the dashboard's Apps band lists the broken static site").isNotNegative();
            assertThat(dashboard.substring(row, dashboard.indexOf("</pl-list-item>", row)))
                .as("step 6: with the same verdict's glyph as the list").contains("data-cms-health=\"broken\"");
            assertThat(list).as("step 6: a stack member is not listed")
                .doesNotContain(PREFIX + "stack-web")
                .doesNotContain(PREFIX + "database")
                .doesNotContain(PREFIX + "engine");

            // 7. The state and kind filters narrow the same rows.
            String broken = adminGet("/admin/apps?_search=apps-journey&filter.state=broken").body();
            assertThat(broken).as("step 7: the state filter keeps the broken app")
                .contains(PREFIX + "static")
                .doesNotContain(PREFIX + "redirect");
            String stacks = adminGet("/admin/apps?_search=apps-journey&filter.type=stack").body();
            assertThat(stacks).as("step 7: the kind filter keeps the stack alone")
                .contains(PREFIX + "stack")
                .doesNotContain(PREFIX + "proxy");

            // 8. A site-only app's page reads as a website: no workload tabs or cards, and the form tab is called
            //    Configuration.
            for (Row site : List.of(proxy, redirect)) {
                String page = adminGet("/admin/sites/" + site.get(SiteModel.ID) + "/page/overview").body();
                assertThat(page).as("step 8: a website offers no workload tab")
                    .doesNotContain("/page/console")
                    .doesNotContain("/page/files")
                    .doesNotContain("/page/metrics")
                    .doesNotContain("/page/backups");
                assertThat(page).as("step 8: its cards are the website's own")
                    .contains("Addresses")
                    .doesNotContain("Resources");
                assertThat(page).as("step 8: the form tab says Configuration").contains("Configuration");
            }

            // 9. A website runs where the instance serving it runs (board Apps-List): one served by an instance that
            //    is no app of its own (the stack's service) names that instance's host; one no instance serves names
            //    none, and its cell reads the board's dash, never the framework's "None".
            Row stackSite = site(cleanup, "stack-site", "hohenheim:instance", Map.of(), stackWeb.get(InstanceModel.ID));
            domain(stackSite, "stack-site.apps-journey.test", false);
            App stackSiteApp = journeyApps().get(PREFIX + "stack-site");
            assertThat(stackSiteApp).as("step 9: the site serving a stack's service is a website of its own")
                .isNotNull();
            assertThat(stackSiteApp.source()).as("step 9: read from the site").isEqualTo(Source.WEBSITE);
            assertThat(stackSiteApp.host()).as("step 9: on the host its instance runs on").isEqualTo(host);
            assertThat(journeyApps().get(PREFIX + "redirect").host()).as("step 9: a redirect runs on no host")
                .isNull();
            String redirectRow = adminGet("/admin/apps?_search=" + PREFIX + "redirect").body();
            assertThat(redirectRow).as("step 9: the list draws its host cell as the board's dash")
                .contains("class=\"cms-cell-absent\" aria-label=\"-\"").contains(">-</pb-microcopy></span>")
                .as("step 9: never as None").doesNotContain(">None<");
            assertThat(adminGet("/admin/apps?_search=" + PREFIX + "stack-site").body())
                .as("step 9: the served website's row names the host")
                .containsPattern("data-column=\"host\"[^>]*><div class=\"cms-cell-clamp\">\\s*" + host + "\\s*<");

            // 10. A catch-all website (only a pattern) serves its visitors, and its verdict says what it catches: a
            //     pattern is never presented as an address to visit ("Live at *.catch...").
            Row catchAll = site(cleanup, "catch-all", "hohenheim:static", Map.of("root_path", "/tmp"), null);
            SiteDomainModel names = Models.get(SiteDomainModel.class);
            Row pattern = names.createEmptyRow();
            pattern.set(SiteDomainModel.SITE_ID, catchAll.get(SiteModel.ID));
            pattern.set(SiteDomainModel.HOSTNAME, "*.catch.apps-journey.test");
            pattern.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_WILDCARD);
            names.save(pattern);
            App catchApp = journeyApps().get(PREFIX + "catch-all");
            assertThat(catchApp.health().tone()).as("step 10: the catch-all serves its visitors")
                .isEqualTo(HealthTone.OK);
            assertThat(say(catchApp.health().headline())).as("step 10: saying what it catches")
                .isEqualTo("Answers every address matching *.catch.apps-journey.test");
            assertThat(catchApp.health().headline().resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver()))
                .as("step 10: in Dutch too").isEqualTo("Beantwoordt elk adres dat past op *.catch.apps-journey.test");
            assertThat(adminGet("/admin/sites/" + catchAll.get(SiteModel.ID) + "/page/overview").body())
                .as("step 10: its page never says it is live at the pattern").doesNotContain("Live at *.")
                .contains("Answers every address matching *.catch.apps-journey.test");
            // Its HTTPS state is said too, never an empty cell (DEP10's catch-all row on the dashboard): a pattern
            // answers many names, so HTTPS works per name a certificate covers.
            assertThat(catchApp.https()).as("step 10: the catch-all's HTTPS cell exists").isNotNull();
            assertThat(catchApp.https().state()).as("step 10: as the pattern state")
                .isEqualTo(CertCoverage.PATTERN.token());
            assertThat(say(catchApp.https().label())).as("step 10: in words").isEqualTo("Per name");
            assertThat(say(catchApp.https().detail())).as("step 10: saying what that means")
                .isEqualTo("A pattern answers many names; HTTPS works for each one a certificate covers.");

            // 11. One app, one name: a site serving a workload is that workload's app, so every surface that names the
            //     app from the site says the workload's name, as the Apps list does (DEP10: "alchemy-skeleton" in the
            //     lists, "Alchemy skeleton" in "Fix on ..." and the Addresses list).
            assertThat(AppDirectory.nameOf(served)).as("step 11: the served site names its workload's app")
                .isEqualTo(PREFIX + "docker").isNotEqualTo(PREFIX + "docker-site");
            assertThat(AppDirectory.nameOf(served, false)).as("step 11: a reader without the workload reads the site")
                .isEqualTo(PREFIX + "docker-site");
            assertThat(AppDirectory.nameOf(files)).as("step 11: a website is its own app").isEqualTo(PREFIX + "static");
            domain(served, "forced.docker.apps-journey.test", true);
            List<AttentionItem> forced = new ArrayList<>();
            ProxyAttention.forcedWithoutCertificate(forced);
            assertThat(forced).as("step 11: the forced address's item offers its fix on the app by the app's name")
                .anySatisfy(item -> assertThat(say(item.action())).isEqualTo("Fix on " + PREFIX + "docker"))
                .noneSatisfy(item -> assertThat(say(item.action())).isEqualTo("Fix on " + PREFIX + "docker-site"));

            // 12. A broken row offers its fix (board Apps-List), not only the words of it: the forced static site's
            //     first fix is Get a certificate, leading to its addresses, drawn in its row by the framework's fix
            //     cell as its band offers it. A working app offers none.
            String domainsTab = "/admin/sites/" + files.get(SiteModel.ID) + "/page/" + SiteParts.DOMAINS_TAB;
            assertThat(fixCellOf(PREFIX + "static", Source.WEBSITE, files.get(SiteModel.ID)))
                .as("step 12: the broken static site offers Get a certificate, leading to its addresses")
                .contains("Get a certificate").contains(domainsTab);
            assertThat(fixCellOf(PREFIX + "redirect", Source.WEBSITE, redirect.get(SiteModel.ID)))
                .as("step 12: a working app offers no fix").isNull();

            // 13. Glyph and badge read one verdict: a stopped workload whose only name is forced to HTTPS without a
            //     certificate is broken (its visitors get an error page whether or not it runs), as its HTTPS badge
            //     says, on the list and on the dashboard's Apps band alike; its fix is its site's.
            Row held = instance(cleanup, "forced-workload", "hohenheim:docker_container",
                Map.of("image", "alpine", "command", "sleep 60"), InstanceModel.STATUS_STOPPED, local);
            Row heldSite = site(cleanup, "forced-workload-site", "hohenheim:instance", Map.of(),
                held.get(InstanceModel.ID));
            domain(heldSite, "forced-workload.apps-journey.test", true);
            App heldApp = journeyApps().get(PREFIX + "forced-workload");
            assertThat(heldApp.https().state()).as("step 13: its HTTPS badge says not working")
                .isEqualTo(CertCoverage.ERROR.token());
            assertThat(heldApp.health().tone()).as("step 13: and its glyph says broken, never only 'not running'")
                .isEqualTo(HealthTone.BROKEN);
            assertThat(say(heldApp.health().headline())).as("step 13: in visitors' words")
                .isEqualTo("Visitors get an error page");
            assertThat(fixCellOf(PREFIX + "forced-workload", Source.WORKLOAD, held.get(InstanceModel.ID)))
                .as("step 13: offering its site's fix")
                .contains("Get a certificate")
                .contains("/admin/sites/" + heldSite.get(SiteModel.ID) + "/page/" + SiteParts.DOMAINS_TAB);
            String board = adminGet("/admin/dashboard").body();
            int heldRow = board.indexOf("data-hh-dashboard-app=\"" + PREFIX + "forced-workload\"");
            assertThat(heldRow).as("step 13: the dashboard lists it").isNotNegative();
            assertThat(board.substring(heldRow, board.indexOf("</pl-list-item>", heldRow)))
                .as("step 13: with the broken glyph beside its Not working badge")
                .contains("data-cms-health=\"broken\"").contains("Not working");

            // 14. A certificate's expiry reads the same everywhere, in whole days (the dashboard's and the boards'
            //     "in 35 days", never "a month and 5 days from now"): the app's HTTPS cell, the Certificates list.
            Row certified = site(cleanup, "certified", "hohenheim:static", Map.of("root_path", "/tmp"), null);
            domain(certified, "certified.apps-journey.test", false);
            CertificateModel certificates = Models.get(CertificateModel.class);
            Row certificate = row(cleanup, certificates, Map.of(CertificateModel.NICE_NAME.getName(), PREFIX + "cert",
                CertificateModel.PROVIDER.getName(), CertificateModel.PROVIDER_CUSTOM,
                CertificateModel.STATUS.getName(), CertificateModel.STATUS_ACTIVE,
                CertificateModel.DOMAIN_NAMES_TEXT.getName(), "certified.apps-journey.test",
                CertificateModel.EXPIRES_ON.getName(),
                Now.instant().plus(Duration.ofDays(35)).plus(Duration.ofHours(1))));
            StateLineCell certifiedHttps = journeyApps().get(PREFIX + "certified").https();
            assertThat(say(certifiedHttps.note())).as("step 14: the HTTPS cell counts whole days")
                .isEqualTo("Expires in 35 days");
            assertThat(certifiedHttps.note().resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver()))
                .as("step 14: in Dutch too").isEqualTo("Verloopt over 35 dagen");
            assertThat(say(CertificateParts.stateCell(certificates.findById(certificate.get(CertificateModel.ID)))
                .detail())).as("step 14: the Certificates list says the same").isEqualTo("Expires in 35 days");
            assertThat(adminGet("/admin/apps?_search=" + PREFIX + "certified").body())
                .as("step 14: the list draws it, never the browser's relative time")
                .contains("Expires in 35 days").doesNotContain("pl-relative-time");
            assertThat(say(CertificateExpiry.of(Now.instant().plus(Duration.ofHours(25)))))
                .as("step 14: one day").isEqualTo("Expires in 1 day");
            assertThat(say(CertificateExpiry.of(Now.instant().plus(Duration.ofHours(2)))))
                .as("step 14: the last day").isEqualTo("Expires today");
            assertThat(say(CertificateExpiry.of(Now.instant().minus(Duration.ofDays(3)).minus(Duration.ofHours(1)))))
                .as("step 14: and past it").isEqualTo("Expired 3 days ago");
            assertThat(say(CertificateExpiry.inSentence(
                    Now.instant().plus(Duration.ofDays(12)).plus(Duration.ofHours(1)))))
                .as("step 14: inside a sentence").isEqualTo("expires in 12 days");
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
        }
    }

    /** @return this journey's apps, by name, as the operator's Apps list reads them now */
    private static Map<String, App> journeyApps() {
        Map<String, App> apps = new LinkedHashMap<>();
        for (App app : AppDirectory.read(Objects.requireNonNull(PanelRegistry.getBySlug("admin")),
                TenantConduits.operator())) {
            if (app.name().startsWith(PREFIX)) {
                apps.put(app.name(), app);
            }
        }
        return apps;
    }

    /**
     * @return the framework fix cell of one app's row in the operator's Apps list, searched down to that app, from the
     *         cell to the row's end; null when its row offers no fix
     */
    private String fixCellOf(String name, Source source, int id) throws Exception {
        String list = adminGet("/admin/apps?_search=" + name).body();
        int row = list.indexOf("data-row-key=\"" + source.token() + "-" + id + "\"");
        assertThat(row).as(name + " is listed").isNotNegative();
        String markup = list.substring(row, list.indexOf("</pl-table-row>", row));
        int at = markup.indexOf("data-cms-health-fix");
        return at < 0 ? null : markup.substring(at);
    }

    private static void assertRow(App app, Source source, String kind, String address, String host,
                                  CertCoverage https, String target, String step) {
        assertThat(app).as(step + ": the app is listed").isNotNull();
        assertThat(app.source()).as(step + ": " + app.name() + " is read from its record").isEqualTo(source);
        assertThat(app.kind()).as(step + ": " + app.name() + "'s kind").isEqualTo(kind);
        assertThat(app.addressText()).as(step + ": " + app.name() + "'s address").isEqualTo(address);
        assertThat(app.host()).as(step + ": " + app.name() + "'s host").isEqualTo(host);
        assertThat(app.https() == null ? null : app.https().state()).as(step + ": " + app.name() + "'s HTTPS state")
            .isEqualTo(https == null ? null : https.token());
        assertThat(app.target().toUrl()).as(step + ": " + app.name() + " opens its record page").isEqualTo(target);
    }

    /** @return the one name stored for this site */
    private static Row domainOf(Row site) {
        return Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(site.get(SiteModel.ID))).first();
    }

    private static String say(Microcopy copy) {
        return copy == null ? "" : copy.resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver());
    }

    private static Row site(List<Runnable> cleanup, String name, String kind, Map<String, Object> settings,
                            Integer instanceId) {
        Map<String, Object> values = new LinkedHashMap<>(Map.of(SiteModel.NAME.getName(), PREFIX + name,
            SiteModel.SLUG.getName(), PREFIX + name, SiteModel.UPSTREAM_KIND.getName(), kind,
            SiteModel.SETTINGS.getName(), settings, SiteModel.ENABLED.getName(), true));
        if (instanceId != null) {
            values.put(SiteModel.INSTANCE_ID.getName(), instanceId);
        }
        return row(cleanup, Models.get(SiteModel.class), values);
    }

    private static Row instance(List<Runnable> cleanup, String name, String kind, Map<String, Object> settings,
                                String status, int serverId) {
        return row(cleanup, Models.get(InstanceModel.class), Map.of(InstanceModel.NAME.getName(), PREFIX + name,
            InstanceModel.KIND.getName(), kind, InstanceModel.SETTINGS.getName(), new LinkedHashMap<>(settings),
            InstanceModel.STATUS.getName(), status, InstanceModel.SERVER_ID.getName(), serverId));
    }

    /**
     * An instance another record generates (a stack service, a database container), created and removed again inside
     * its owner's scope: outside it the row is read-only.
     */
    private static Row generated(List<Runnable> cleanup, String source, Identifier owner, int ownerId, String name,
                                 String kind, Map<String, Object> settings, int serverId) {
        InstanceModel instances = Models.get(InstanceModel.class);
        Row[] row = new Row[1];
        OwnedInstances.inScopeUnchecked(source, owner, ownerId, () -> {
            row[0] = instances.createEmptyRow();
            Map.of(InstanceModel.NAME.getName(), PREFIX + name, InstanceModel.KIND.getName(), kind,
                InstanceModel.SETTINGS.getName(), new LinkedHashMap<>(settings),
                InstanceModel.STATUS.getName(), InstanceModel.STATUS_STOPPED,
                InstanceModel.SERVER_ID.getName(), serverId).forEach(row[0]::set);
            instances.save(row[0]);
        });
        cleanup.add(() -> OwnedInstances.inScopeUnchecked(source, owner, ownerId,
            () -> HardDeletes.row(instances, row[0])));
        return row[0];
    }

    private static void domain(Row site, String hostname, boolean forceSsl) {
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.FORCE_SSL, forceSsl);
        domains.save(domain);
    }

    /** Saves one row of these values, removed again when the journey ends. */
    private static Row row(List<Runnable> cleanup, Model model, Map<String, Object> values) {
        Row row = model.createEmptyRow();
        values.forEach(row::set);
        model.save(row);
        cleanup.add(() -> HardDeletes.row(model, row));
        return row;
    }
}
