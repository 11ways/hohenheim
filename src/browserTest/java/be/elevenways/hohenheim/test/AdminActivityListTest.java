package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.hohenheim.server.cms.AdminActivityResource;
import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.model.InstallMediaFetchModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteSessionModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.model.WebhookDeliveryModel;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.common.orm.history.HistoryDeclarations;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.server.dns.DnsFederationTrace;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.auth.test.TestAccounts;
import be.elevenways.zenit.cms.server.panel.PanelGate;
import be.elevenways.zenit.cms.server.panel.PartsLists;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.cms.server.resource.ActivityAdmin;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.orm.activity.ActivitySeverity;
import be.elevenways.zenit.common.orm.history.HistoryVisibility;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.history.ActionModel;
import be.elevenways.zenit.common.orm.history.ChangeOp;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.history.HistoryEntry;
import be.elevenways.zenit.common.orm.history.HistoryText;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.security.SystemPrincipal;
import be.elevenways.zenit.common.security.ZenitPrincipalKind;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import be.elevenways.zenit.test.support.EndpointConduit;
import be.elevenways.zenit.test.support.HistoryFixtureRows;
import be.elevenways.zenit.test.support.TestAccessContexts;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.regex.Pattern;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin activity log as an operator reads it: background noise out of the way but reachable, actions as
 * sentences, and the record an action names both linked and filterable.
 *
 * AIDEV-NOTE: the seeded actions are written straight into the history tables (HistoryFixtureRows) rather than
 * through the change writer, because the point is the READING surface -- the origin, actor and command of each action
 * have to be chosen, and the writer derives all three from whatever lane calls it. A sentence names its record by the
 * record's live title, so every record an assertion reads by name is a real record titled with a marker, and a marker
 * that cannot be a record's title is an actor's display name. The log lists the newest committed change first, so
 * the actions written here head its first page.
 */
class AdminActivityListTest extends HohenheimTestBase {

    @Test
    void actorFilterReadsDisplayedNamesRatherThanNumericFragments() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String first = "hh-actor-name-zelda-" + suffix;
        String second = "hh-actor-name-alpha-" + suffix;
        UUID zeldaId = TestAccounts.create("zelda-" + UUID.randomUUID() + "@example.com", "Zelda Actor " + suffix,
            true, false);
        UUID alphaId = TestAccounts.create("alpha-" + UUID.randomUUID() + "@example.com", "Alpha Actor " + suffix,
            true, true);
        String zelda = act(HistoryFixtureRows.action(datasource()).by(PrincipalRef.account(zeldaId))
            .origin(Accountability.ORIGIN_WEB), ServerModel.MODEL_ID, host(first), ChangeOp.UPDATE);
        String alpha = act(HistoryFixtureRows.action(datasource()).by(PrincipalRef.account(alphaId))
            .origin(Accountability.ORIGIN_WEB), ServerModel.MODEL_ID, host(second), ChangeOp.UPDATE);
        HttpResponse<String> response = adminGet("/admin/activity?filter.actor="
            + URLEncoder.encode(PrincipalRef.account(zeldaId).key(), StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("step 1: the actor-name filter renders").isEqualTo(200);
        assertThat(response.body()).as("step 1: the filter matches the displayed name, not every numeric actor")
            .contains(first).doesNotContain(second);
        assertThat(response.body()).as("step 2: disabled accounts retain their display names").contains("Zelda Actor");

        HttpResponse<String> searched = adminGet("/admin/activity?text=Alpha");
        assertThat(searched.body()).as("step 3: relation search reads the account display name")
            .contains(second).doesNotContain(first);

        var access = TestAccessContexts.allAllowed();
        var resource = adminActivityResource();
        var applied = PartsLists.tableView(resource, access).apply(PartsLists.<Row>tableSpec(resource))
            .withSearch(suffix).withSort(SortSpec.asc("actor"));
        assertThat(PartsReads.listRows(PanelGate.request(PanelRegistry.getBySlug("admin"), access), resource, null,
            applied, access).stream().map(row -> (String) row.get(ActionModel.ID)).toList())
            .as("step 4: account names sort Alpha before Zelda, not by their ids").containsExactly(alpha, zelda);

        Row zeldaRow = actionRow(zelda);
        Row user = Models.get(UserModel.class).findById(zeldaId);
        user.set(UserModel.DISPLAY_NAME, null);
        Models.get(UserModel.class).save(user);
        Microcopy email = (Microcopy) PartsReads.cellValue(null, resource, null, zeldaRow, column(resource, "actor"));
        assertThat(email.resolve(LocaleChain.ofTags("en"), new ShippedCatalogs()))
            .as("step 5: missing display name falls back to the stored email").isEqualTo(user.get(UserModel.EMAIL));
        Models.get(UserModel.class).find().where(UserModel.ID.eq(zeldaId)).delete();
        Microcopy deleted = (Microcopy) PartsReads.cellValue(null, resource, null, zeldaRow, column(resource, "actor"));
        assertThat(deleted.resolve(LocaleChain.ofTags("en"), new ShippedCatalogs()))
            .as("step 6: a deleted author is named as unknown, never by its id").isEqualTo("Account no longer known");
    }

    @Test
    void principalPickerDoesNotMatchNumericFragmentsOrAnotherKind() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        // Ids sharing every leading character but their last: a fragment of one must never select another.
        UUID one = UUID.fromString("01a00000-0000-7000-8000-000000000001");
        UUID ten = UUID.fromString("01a00000-0000-7000-8000-000000000010");
        UUID twentyOne = UUID.fromString("01a00000-0000-7000-8000-000000000021");
        Map<String, PrincipalRef> actors = Map.of(
            "hh-principal-fragment-one-" + suffix, PrincipalRef.account(one),
            "hh-principal-fragment-ten-" + suffix, PrincipalRef.account(ten),
            "hh-principal-fragment-twenty-one-" + suffix, PrincipalRef.account(twentyOne),
            "hh-principal-fragment-system-" + suffix, SystemPrincipal.INSTANCE.reference());
        for (var actor : actors.entrySet()) {
            act(HistoryFixtureRows.action(datasource()).by(actor.getValue()).origin(Accountability.ORIGIN_WEB),
                ServerModel.MODEL_ID, host(actor.getKey()), ChangeOp.UPDATE);
        }
        var first = adminGet("/admin/activity?filter.actor="
            + URLEncoder.encode(PrincipalRef.account(one).key(), StandardCharsets.UTF_8));
        assertThat(first.body()).as("step 1: one account is not another sharing its leading digits, nor the system")
            .contains("hh-principal-fragment-one-" + suffix).doesNotContain("hh-principal-fragment-ten-" + suffix,
                "hh-principal-fragment-twenty-one-" + suffix, "hh-principal-fragment-system-" + suffix);
        var system = adminGet("/admin/activity?filter.actor="
            + URLEncoder.encode(new PrincipalRef(ZenitPrincipalKind.SYSTEM, SystemPrincipal.ID).key(),
                StandardCharsets.UTF_8));
        assertThat(system.body()).as("step 2: the system's complete reference selects only its actions")
            .contains("hh-principal-fragment-system-" + suffix).doesNotContain("hh-principal-fragment-one-" + suffix,
                "hh-principal-fragment-ten-" + suffix, "hh-principal-fragment-twenty-one-" + suffix);
    }

    @Test
    void systemActivityNamesTheSystemRatherThanAccountOne() throws Exception {
        String record = "hh-system-actor-no-account-one-" + UUID.randomUUID().toString().substring(0, 8);
        int hostId = host(record);
        String action = act(HistoryFixtureRows.action(datasource()).by(SystemPrincipal.INSTANCE.reference())
            .legacyLabel("work wait sweep").origin(Accountability.ORIGIN_SYSTEM), ServerModel.MODEL_ID, hostId,
            ChangeOp.UPDATE);

        // 1. The host preserves the core projection instead of resolving the system's id as a user.
        PanelResource<Row> resource = adminActivityResource();
        Object cell = PartsReads.cellValue(null, resource, null, actionRow(action),
            column(resource, ActionModel.ACTOR.getName()));
        assertThat(cell).as("step 1: the localized core actor name is preserved").isInstanceOf(Microcopy.class);
        assertThat(((Microcopy) cell).resolve(LocaleChain.ofTags("en"), new ShippedCatalogs()))
            .as("step 1: system work is not an account").isEqualTo("System");

        // 2. The live admin list carries that name and never the internal reason.
        HttpResponse<String> response = adminGet("/admin/activity?filter.origin=system&filter."
            + AdminActivityResource.HOST_FILTER + "=" + hostId);
        assertThat(response.statusCode()).as("step 2: system activity renders").isEqualTo(200);
        assertThat(response.body()).as("step 2: the system action has its public name")
            .contains(record, "System").doesNotContain("work wait sweep");
        HttpResponse<String> selected = adminGet("/admin/activity?filter.origin.__cleared=1&filter.actor="
            + URLEncoder.encode(new PrincipalRef(ZenitPrincipalKind.SYSTEM, SystemPrincipal.ID).key(),
                StandardCharsets.UTF_8));
        assertThat(selected.body()).as("step 3: the principal picker selects the declared system identity")
            .contains(record).doesNotContain("work wait sweep");
    }

    @Test
    void activityListJourney() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String operatorTitle = "hh-activity-operator-subject-" + suffix;
        String backgroundTitle = "hh-activity-background-subject-" + suffix;
        String narrowedTitle = "hh-activity-narrowed-subject-" + suffix;
        String siteTitle = "hh-activity-site-subject-" + suffix;
        String unlinkableActor = "hh-activity-unlinkable-actor-" + suffix;

        // 1. The hohenheim resource describes the SAME columns the framework resource does: its table derives from
        //    ActivityAdmin.table() and adds filters only, so a column added upstream reaches the panel.
        PanelResource<Row> resource = adminActivityResource();
        List<String> ours = PartsLists.tableSpec(resource).columns().stream().map(ColumnSpec::name).toList();
        List<String> framework = ActivityAdmin.withFace(ActivityAdmin.table()).columns()
            .stream().map(ColumnSpec::name).toList();
        assertThat(ours)
            .as("step 1: the panel's activity columns match the framework's")
            .isEqualTo(framework);

        // 2. Every filter an operator needs to reach ONE record is declared: the model, the app, host or site, and the
        //    origin that flips the default scope. No filter names a bare record id: an id says nothing without its model.
        List<String> filters = PartsLists.tableSpec(resource).filters().stream()
            .map(FilterSpec::name).toList();
        assertThat(filters)
            .as("step 2: model, app, host, site and origin are all filterable")
            .contains(ActivityAdmin.MODEL_FILTER, AdminActivityResource.APP_FILTER, AdminActivityResource.HOST_FILTER,
                AdminActivityResource.SITE_FILTER, ActionModel.ORIGIN.getName())
            .doesNotContain(ActionModel.SUBJECT_ID.getName());

        int operatorHost = host(operatorTitle);
        int backgroundHost = host(backgroundTitle);
        int narrowedHost = host(narrowedTitle);
        int siteId = site(siteTitle);
        UUID unlinkableId = TestAccounts.create("unlinkable-" + suffix + "@example.com", unlinkableActor, true,
            false);
        String operatorAction = act(HistoryFixtureRows.action(datasource()).origin(Accountability.ORIGIN_WEB),
            ServerModel.MODEL_ID, operatorHost, ChangeOp.CREATE);
        String backgroundAction = act(HistoryFixtureRows.action(datasource())
                .by(SystemPrincipal.INSTANCE.reference()).origin(Accountability.ORIGIN_SYSTEM),
            ServerModel.MODEL_ID, backgroundHost, ChangeOp.UPDATE);
        String narrowedAction = act(HistoryFixtureRows.action(datasource()).origin(Accountability.ORIGIN_WEB),
            ServerModel.MODEL_ID, narrowedHost, ChangeOp.UPDATE);
        String siteAction = act(HistoryFixtureRows.action(datasource()).origin(Accountability.ORIGIN_WEB),
            SiteModel.MODEL_ID, siteId, ChangeOp.UPDATE);
        act(HistoryFixtureRows.action(datasource()).by(PrincipalRef.account(unlinkableId))
            .origin(Accountability.ORIGIN_WEB), SiteSessionModel.MODEL_ID, "hh-activity-unlinkable-" + suffix,
            ChangeOp.UPDATE);

        // 3. The default list is what a PERSON did: the system-origin action is not on it.
        HttpResponse<String> defaultList = adminGet("/admin/activity");
        assertThat(defaultList.statusCode()).as("step 3: the activity list renders").isEqualTo(200);
        assertThat(defaultList.body())
            .as("step 3: operator activity is on the default list")
            .contains(operatorTitle);
        assertThat(defaultList.body())
            .as("step 3: background activity is hidden by default")
            .doesNotContain(backgroundTitle);

        // 3b. The default scope is VISIBLE: an ordinary removable chip, marked as a
        //     default, reading the declared sentence -- never an invisible withholding.
        assertThat(defaultList.body())
            .as("step 3b: the default renders as a marked chip")
            .contains("data-chip-default");
        assertThat(defaultList.body())
            .as("step 3b: the chip reads the declared description, not the expression")
            .contains("People only");

        // 3c. Its remove link carries the persistence marker, so removing it survives
        //     the next navigation instead of re-triggering on a bare URL.
        assertThat(defaultList.body())
            .as("step 3c: the chip removes through the cleared marker")
            .contains("filter.origin.__cleared=1");

        // 3d. The cleared marker genuinely suppresses it: background actions appear beside operator ones, and its
        //     chip is gone while the framework's own default stays, because the panel extends
        //     ActivityAdmin.defaultFilter() instead of replacing it: internal records hidden as the row's "Show
        //     internal records" switch, off. (One row IS one action, so no per-action chip is left to show.)
        HttpResponse<String> clearedList = adminGet("/admin/activity?filter.origin.__cleared=1");
        assertThat(clearedList.statusCode()).as("step 3d: the cleared list renders").isEqualTo(200);
        assertThat(clearedList.body())
            .as("step 3d: clearing the default shows background activity")
            .contains(backgroundTitle)
            .contains(operatorTitle);
        assertThat(clearedList.body())
            .as("step 3d: the people-only chip is gone while cleared")
            .doesNotContain("People only");
        assertThat(clearedList.body())
            .as("step 3d: the internal default is the row's switch")
            .doesNotContain("Internal records hidden")
            .contains("Show internal records")
            .contains("filter.internal.__cleared");

        // 4. The origin filter genuinely flips it: naming the system origin shows the background action, so the
        //    default is a starting point and never a cage. (Each host's own creation is the test's system work, so the
        //    actions are told apart by their detail links, not by the hosts' names.)
        HttpResponse<String> systemList = adminGet("/admin/activity?filter.origin="
            + Accountability.ORIGIN_SYSTEM);
        assertThat(systemList.statusCode()).as("step 4: the filtered list renders").isEqualTo(200);
        assertThat(systemList.body())
            .as("step 4: the origin filter reveals background activity")
            .contains("/admin/activity/" + backgroundAction);
        assertThat(systemList.body())
            .as("step 4: the origin filter narrows to that origin")
            .doesNotContain("/admin/activity/" + operatorAction);

        // 5. An action is marked by what it did, in core's own mark: a creation reads as created.
        AccessContext reader = TestAccessContexts.allAllowed();
        HistoryEntry created = Objects.requireNonNull(History.ofAction(operatorAction, reader), "operator action");
        assertThat(HistoryText.mark(created).severity())
            .as("step 5: the operator's creation is marked as a creation").isEqualTo(ActivitySeverity.CREATED);

        // 6. A record a registered resource serves is a LINK to that record's front door, which opens its landing
        //    tab; the sentence names it by its live title.
        assertThat(defaultList.body())
            .as("step 6: the sentence links the record's front door")
            .contains("/admin/servers/" + operatorHost + "/open");

        // 6b. A model BOTH panels mount (sites: the admin resource and its /manage narrowing) still links into THIS
        //     panel: the admin activity list never sends an operator to /manage, whatever order the framework's
        //     panel registry iterates in.
        assertThat(defaultList.body())
            .as("step 6b: and the rendered list carries the /admin site link")
            .contains("/admin/sites/" + siteId)
            .doesNotContain("/manage/sites/" + siteId);

        // 7. A record no resource serves stays plain text: named, but not linked.
        String unlinkable = adminGet("/admin/activity?filter.internal=true&filter.actor="
            + URLEncoder.encode(PrincipalRef.account(unlinkableId).key(), StandardCharsets.UTF_8)).body();
        assertThat(unlinkable).as("step 7: the action on a record no resource serves is listed")
            .contains(unlinkableActor);
        assertThat(unlinkable).as("step 7: and carries no record link")
            .doesNotContain("cms-activity-subject");

        // 8. The record filter narrows the log to the history of ONE record: never another model's record sharing its
        //    id, nor a record whose id merely contains it. Rows are told apart by their detail links: the host
        //    picker beside the list still offers every host by its title.
        String sameIdSite = act(HistoryFixtureRows.action(datasource()).origin(Accountability.ORIGIN_WEB),
            SiteModel.MODEL_ID, narrowedHost, ChangeOp.UPDATE);
        String widerIdHost = act(HistoryFixtureRows.action(datasource()).origin(Accountability.ORIGIN_WEB),
            ServerModel.MODEL_ID, narrowedHost + "0", ChangeOp.UPDATE);
        HttpResponse<String> narrowed = adminGet("/admin/activity?filter." + AdminActivityResource.HOST_FILTER + "="
            + narrowedHost);
        assertThat(narrowed.statusCode()).as("step 8: the record-filtered list renders").isEqualTo(200);
        assertThat(narrowed.body())
            .as("step 8: the named record's activity is on it")
            .contains("/admin/activity/" + narrowedAction);
        assertThat(narrowed.body())
            .as("step 8: and nothing else is")
            .doesNotContain("/admin/activity/" + operatorAction)
            .doesNotContain("/admin/activity/" + siteAction);
        assertThat(narrowed.body())
            .as("step 8: a site sharing the host's id is another record")
            .doesNotContain("/admin/activity/" + sameIdSite);
        assertThat(narrowed.body())
            .as("step 8: a host whose id contains the named one is another record")
            .doesNotContain("/admin/activity/" + widerIdHost);

        // 9. The list reads as sentences: the time and who did what to what are shown, the detail columns wait in the
        //     column picker, and an anonymous web write reads as someone not signed in, never as "Web".
        List<String> shown = PartsLists.tableSpec(resource).columns().stream()
            .filter(column -> !column.hidden()).map(ColumnSpec::name).toList();
        assertThat(shown).as("step 9: only the time and the sentence head the table")
            .containsExactly(ActionModel.CREATED_AT.getName(), ActivityAdmin.SUMMARY_COLUMN);
        assertThat(defaultList.body())
            .as("step 9: the operator action reads as one sentence")
            .contains("Someone not signed in created");

        // 10. Only the record's name is the link (zenit-cms a.cms-activity-subject inside span.cms-activity-sentence),
        //     never the whole sentence.
        assertThat(Pattern.compile("<a[^>]*cms-activity-subject[^>]*>\\s*" + operatorTitle + "\\s*</a>")
                .matcher(defaultList.body()).find())
            .as("step 10: the operator action links its record's name alone")
            .isTrue();
        assertThat(defaultList.body())
            .as("step 10: the sentence itself is no link")
            .doesNotContain("a class=\"cms-activity-sentence");

        // 11. Hohenheim's own operations tell what happened in their own words.
        String putOnline = act(HistoryFixtureRows.action(datasource()).origin(Accountability.ORIGIN_WEB)
            .command(PutOnline.PUT_ONLINE.id()), ServerModel.MODEL_ID, operatorHost, ChangeOp.UPDATE);
        assertThat(heading(putOnline, "en"))
            .as("step 11: putting something online reads as such")
            .isEqualTo("Someone not signed in put " + operatorTitle + " online");
        String protect = act(HistoryFixtureRows.action(datasource()).origin(Accountability.ORIGIN_WEB)
            .command(ProtectPath.OPERATION.id()), ServerModel.MODEL_ID, operatorHost, ChangeOp.UPDATE);
        assertThat(heading(protect, "nl"))
            .as("step 11: protecting a path reads as such, in Dutch too")
            .isEqualTo("Iemand die niet is aangemeld beveiligde " + operatorTitle);
    }

    @Test
    void theAppFilterNarrowsTheLogToWhatHappenedToOneApp() throws Exception {
        int alpha = instance("hh-app-filter-alpha-" + UUID.randomUUID().toString().substring(0, 8));
        int beta = instance("hh-app-filter-beta-" + UUID.randomUUID().toString().substring(0, 8));
        act(HistoryFixtureRows.action(datasource()).origin(Accountability.ORIGIN_WEB), InstanceModel.MODEL_ID, alpha,
            ChangeOp.UPDATE);
        act(HistoryFixtureRows.action(datasource()).origin(Accountability.ORIGIN_WEB), InstanceModel.MODEL_ID, beta,
            ChangeOp.UPDATE);

        // 1. The filter is labelled as the app it picks, in both shipped locales.
        FilterSpec app = PartsLists.tableSpec(adminActivityResource()).filters().stream()
            .filter(filter -> AdminActivityResource.APP_FILTER.equals(filter.name())).findFirst().orElseThrow();
        assertThat(app.label().resolve(LocaleChain.ofTags("en"), new ShippedCatalogs()))
            .as("step 1: the app filter reads App").isEqualTo("App");
        assertThat(app.label().resolve(LocaleChain.ofTags("nl"), new ShippedCatalogs()))
            .as("step 1: in Dutch too").isEqualTo("App");

        // 2. Picking one app leaves what happened to it, and nothing about another app. The picker offers both apps
        //    by name, so the actions are told apart by the record each links.
        HttpResponse<String> picked = adminGet("/admin/activity?filter." + AdminActivityResource.APP_FILTER + "="
            + alpha);
        assertThat(picked.statusCode()).as("step 2: the app-filtered list renders").isEqualTo(200);
        assertThat(picked.body()).as("step 2: the picked app's actions are listed")
            .contains("/admin/instances/" + alpha + "/open");
        assertThat(picked.body()).as("step 2: another app's actions are not")
            .doesNotContain("/admin/instances/" + beta + "/open");

        // 3. An id no app the reader may read carries narrows to nothing, like a missing one, never to everything.
        HttpResponse<String> guessed = adminGet("/admin/activity?filter." + AdminActivityResource.APP_FILTER
            + "=987654321");
        assertThat(guessed.body()).as("step 3: a guessed app shows no app's actions")
            .doesNotContain("/admin/instances/" + alpha + "/open")
            .doesNotContain("/admin/instances/" + beta + "/open");
    }

    @Test
    void peopleOnlyLeavesPlumbingAndSeedsBehindTheToggleOnTheListAndTheDashboard() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String person = "hh-people-person-" + suffix;
        String seeded = "hh-people-seeded-" + suffix;
        String plumbing = "hh-people-plumbing-" + suffix;

        // 1. Hohenheim's plumbing models are declared internal; a model people also write is not.
        for (Model model : List.<Model>of(Models.get(PortAllocationModel.class), Models.get(InstanceQuotaModel.class),
                Models.get(ReleasedRouteClaimModel.class), Models.get(SiteSessionModel.class),
                Models.get(ControllerIdentityModel.class), Models.get(InstallMediaFetchModel.class),
                Models.get(WebhookDeliveryModel.class), Models.get(SystemUserModel.class))) {
            assertThat(Objects.requireNonNull(HistoryDeclarations.of(model)).visibility())
                .as("step 1: " + model.getModelId() + " is declared internal").isEqualTo(HistoryVisibility.INTERNAL);
        }
        assertThat(Objects.requireNonNull(HistoryDeclarations.of(Models.get(GrantModel.class))).visibility())
            .as("step 1: zenit-auth's grants are declared internal in this runtime too")
            .isEqualTo(HistoryVisibility.INTERNAL);
        assertThat(Objects.requireNonNull(HistoryDeclarations.of(Models.get(RecordGrantModel.class)))
                .visibility())
            .as("step 1: and its record grants").isEqualTo(HistoryVisibility.INTERNAL);
        assertThat(Objects.requireNonNull(HistoryDeclarations.of(Models.get(ServerModel.class))).visibility())
            .as("step 1: hosts stay listed, operators admit and edit them").isEqualTo(HistoryVisibility.LISTED);

        // 2. A heartbeat is bookkeeping, never activity: a probe success records no change for its host.
        String hostName = "hh-heartbeat-" + suffix;
        int hostId = host(hostName);
        ServerModel servers = Models.get(ServerModel.class);
        int before = RecordedChanges.of(servers, hostId).size();
        HostProbe.recordSuccess(hostName);
        assertThat(servers.findByName(hostName).get(ServerModel.LAST_SEEN_AT))
            .as("step 2: the probe did record when the host was seen").isNotNull();
        assertThat(RecordedChanges.of(servers, hostId))
            .as("step 2: and recorded no change for it").hasSize(before);

        // 2b. The DNS server's own trace stamps (a NOTIFY sent, an AXFR served) are bookkeeping too: on starfleet the
        //     AXFR responder's identityless save read as "Unattributed changed Dns zone peer #1".
        Row peer = Models.get(DnsPeerModel.class).createEmptyRow();
        peer.set(DnsPeerModel.NAME, "hh-trace-peer-" + suffix);
        peer.set(DnsPeerModel.TRANSFER_HOST, "192.0.2.10");
        Models.get(DnsPeerModel.class).save(peer);
        Row zone = Models.get(DnsZoneModel.class).createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, "trace-" + suffix + ".test.");
        zone.set(DnsZoneModel.ROLE, DnsZoneModel.ROLE_PRIMARY);
        zone.set(DnsZoneModel.ENABLED, true);
        Models.get(DnsZoneModel.class).save(zone);
        Row link = Models.get(DnsZonePeerModel.class).createEmptyRow();
        link.set(DnsZonePeerModel.ZONE_ID, zone.get(DnsZoneModel.ID));
        link.set(DnsZonePeerModel.PEER_ID, peer.get(DnsPeerModel.ID));
        Models.get(DnsZonePeerModel.class).save(link);
        Object linkId = link.get(DnsZonePeerModel.ID);
        int linkChanges = RecordedChanges.of(Models.get(DnsZonePeerModel.class), linkId).size();
        DnsFederationTrace.notifySent(link, peer, "trace-" + suffix + ".test.", 7, "ok");
        assertThat(Models.get(DnsZonePeerModel.class).findById(linkId).get(DnsZonePeerModel.LAST_NOTIFY_SERIAL))
            .as("step 2b: the trace did stamp the link").isEqualTo(7);
        assertThat(RecordedChanges.of(Models.get(DnsZonePeerModel.class), linkId))
            .as("step 2b: and recorded no change for it").hasSize(linkChanges);

        // 3. The list opens on what a person did: a seed, an internal change and work that declared no identity at
        //    all are not on it. Each marker is its actor's display name, except the unattributed action's, which
        //    has no actor and names a site titled with its marker.
        String unattributed = "hh-people-unattributed-" + suffix;
        int siteId = site("hh-people-site-" + suffix);
        String personAction = act(HistoryFixtureRows.action(datasource()).by(PrincipalRef.account(account(person)))
            .origin(Accountability.ORIGIN_WEB), SiteModel.MODEL_ID, siteId, ChangeOp.UPDATE);
        String seededAction = act(HistoryFixtureRows.action(datasource()).by(PrincipalRef.account(account(seeded)))
            .origin(AccountabilityOrigin.SEED.token()), SiteModel.MODEL_ID, siteId, ChangeOp.UPDATE);
        UUID plumber = account(plumbing);
        String plumbingAction = act(HistoryFixtureRows.action(datasource()).by(PrincipalRef.account(plumber))
            .origin(Accountability.ORIGIN_WEB), PortAllocationModel.MODEL_ID, "hh-people-port-" + suffix,
            ChangeOp.CREATE);
        String unattributedAction = act(HistoryFixtureRows.action(datasource())
            .origin(Accountability.ORIGIN_UNATTRIBUTED), SiteModel.MODEL_ID, site(unattributed), ChangeOp.UPDATE);
        // A sign-in is bookkeeping by its fact (core's LOGIN is internal): it reads "<who> signed in", so the marker is
        // the actor's name.
        String signedIn = "hh-people-signin-" + suffix;
        UUID signer = account(signedIn);
        HistoryFixtureRows.Action signIn = HistoryFixtureRows.action(datasource()).by(PrincipalRef.account(signer))
            .origin(Accountability.ORIGIN_WEB).change(UserModel.MODEL_ID, signer, ChangeOp.EVENT)
            .fact(ZenitActivityAction.LOGIN).done();
        signIn.write();
        String opening = adminGet("/admin/activity").body();
        assertThat(opening).as("step 3: a person's action opens the list").contains(person);
        assertThat(opening).as("step 3: a sign-in does not").doesNotContain(signedIn);
        assertThat(opening).as("step 3: a seed does not").doesNotContain(seeded);
        assertThat(opening).as("step 3: an internal change does not").doesNotContain(plumbing);
        assertThat(opening).as("step 3: unattributed work is not a person's either").doesNotContain(unattributed);

        // 4. The framework's internal toggle brings the plumbing back, still searchable.
        assertThat(adminGet("/admin/activity?filter.internal=true&filter.actor="
                + URLEncoder.encode(PrincipalRef.account(plumber).key(), StandardCharsets.UTF_8)).body())
            .as("step 4: internal records are one toggle away").contains(plumbing);
        assertThat(adminGet("/admin/activity?filter.internal=true&filter.actor="
                + URLEncoder.encode(PrincipalRef.account(signer).key(), StandardCharsets.UTF_8)).body())
            .as("step 4: and so are sign-ins").contains(signedIn);

        // 5. The dashboard's recent activity reads the SAME scope as the list's opening. An empty install shows no
        //    activity band at all; the sites above already put apps on it. Its Apps band names every site, so the
        //    feed's rows are told apart by the action each links.
        String dashboard = adminGet("/admin/dashboard").body();
        assertThat(dashboard).as("step 5: the dashboard shows what a person did")
            .contains("/admin/activity/" + personAction);
        assertThat(dashboard).as("step 5: and neither seeds, plumbing nor unattributed work")
            .doesNotContain("/admin/activity/" + seededAction).doesNotContain("/admin/activity/" + plumbingAction)
            .doesNotContain("/admin/activity/" + unattributedAction).doesNotContain("/admin/activity/" + signIn.id());
    }

    // -- fixtures ---------------------------------------------------------------

    private static @NonNull Datasource datasource() {
        return Models.get(ServerModel.class).getResolvedDatasource();
    }

    /**
     * Writes one action changing one record, with the record as its subject as an operation stamps it.
     *
     * @return the action's id
     */
    private static @NonNull String act(HistoryFixtureRows.@NonNull Action action, @NonNull Identifier model,
                                       @NonNull Object recordId, @NonNull ChangeOp op) {
        action.change(model, recordId, op).done().write();
        new ActionModel(datasource()).find().where(ActionModel.ID.eq(action.id()))
            .assign(ActionModel.SUBJECT_MODEL, model.toString())
            .assign(ActionModel.SUBJECT_ID, String.valueOf(recordId)).updateAll();
        return action.id();
    }

    /** @return the action's stored header, as the list reads it */
    private static @NonNull Row actionRow(@NonNull String actionId) {
        Row row = new ActionModel(datasource()).find().where(ActionModel.ID.eq(actionId)).first();
        assertThat(row).as("the seeded action " + actionId).isNotNull();
        return row;
    }

    /** @return the action's heading as a reader in {@code locale} reads it */
    private static @NonNull String heading(@NonNull String actionId, @NonNull String locale) {
        EndpointConduit conduit = new EndpointConduit();
        conduit.setLocales(LocaleChain.ofTags(locale));
        AccessContext reader = AccessContext.of(conduit, TestAccessContexts.authenticated(
            TestAccessContexts.TEST_ACCOUNT, "operator"), TestAccessContexts.allowAll());
        HistoryEntry entry = Objects.requireNonNull(History.ofAction(actionId, reader), "action " + actionId);
        return HistoryText.heading(entry, reader).text();
    }

    /** @return a new account whose display name is {@code name} */
    private static @NonNull UUID account(@NonNull String name) {
        return TestAccounts.create(name + "@example.com", name, true, false);
    }

    /** A host record titled {@code name}, admitted nowhere. */
    private static int host(@NonNull String name) {
        ServerModel servers = Models.get(ServerModel.class);
        Row host = servers.createEmptyRow();
        host.set(ServerModel.NAME, name);
        host.set(ServerModel.MODE, ServerModel.MODE_SSH);
        host.set(ServerModel.SSH_TARGET, "operator@" + name + ".invalid");
        host.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
        servers.save(host);
        return host.get(ServerModel.ID);
    }

    /** A static site titled {@code name}. */
    private static int site(@NonNull String name) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        return site.get(SiteModel.ID);
    }

    /** A bare instance record, enough for the record source to title and offer it. */
    private static int instance(@NonNull String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** The panel's own activity log, viewed as its caller sees it -- never a fresh one, the registered one. */
    @SuppressWarnings("unchecked")
    private static PanelResource<Row> adminActivityResource() {
        Panel panel = PanelRegistry.getBySlug("admin");
        assertThat(panel).as("the admin panel is registered").isNotNull();
        if (panel.entryBySlug("activity") instanceof PanelResource<?> activity) {
            return (PanelResource<Row>) activity;
        }
        throw new AssertionError("the admin panel exposes no activity resource");
    }

    private static ColumnSpec column(PanelResource<Row> resource, String name) {
        ColumnSpec column = PartsLists.tableSpec(resource).column(name);
        assertThat(column).as("the activity table declares a '" + name + "' column").isNotNull();
        return column;
    }
}
