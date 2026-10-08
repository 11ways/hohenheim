package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.zenit.common.orm.activity.ActivityText;
import be.elevenways.zenit.cms.common.render.activity.ActivitySentenceCell;
import be.elevenways.hohenheim.activity.ActivityRecordCell;
import be.elevenways.hohenheim.server.HohenheimActivity;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.server.dns.DnsFederationTrace;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.FilterState;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.auth.test.TestAccounts;
import be.elevenways.zenit.cms.server.panel.PanelGate;
import be.elevenways.zenit.cms.server.panel.PartsLists;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.cms.server.resource.ActivityAdmin;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.test.support.TestAccessContexts;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.orm.activity.ActivityActions;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityVisibility;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.ZenitPrincipalKind;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin activity log as an operator reads it: background noise out of the way but
 * reachable, verbs as words, and the record a row names both linked and filterable.
 *
 * AIDEV-NOTE: the seeded rows are written straight into zenit_activity rather than through
 * ActivityLog, because the point is the READING surface -- the origin, verb and model of
 * each row have to be chosen, and ActivityLog derives all three from whatever lane calls it.
 */
class AdminActivityListTest extends HohenheimTestBase {

    @Test
    void actorFilterReadsDisplayedNamesRatherThanNumericFragments() throws Exception {
        String first = "hh-actor-name-zelda";
        String second = "hh-actor-name-alpha";
        int zeldaId = TestAccounts.create("zelda-" + UUID.randomUUID() + "@example.com", "Zelda Actor", true, false);
        int alphaId = TestAccounts.create("alpha-" + UUID.randomUUID() + "@example.com", "Alpha Actor", true, true);
        write(ServerModel.MODEL_ID.toString(), first, first, "updated", Accountability.ORIGIN_WEB,
            Instant.parse("2998-01-01T00:00:00Z"));
        write(ServerModel.MODEL_ID.toString(), second, second, "updated", Accountability.ORIGIN_WEB,
            Instant.parse("2998-01-02T00:00:00Z"));
        Row zelda = rowFor(first);
        zelda.set(ActivityModel.ACTOR, Integer.toString(zeldaId));
        zelda.set(ActivityModel.ACTOR_KIND, ZenitPrincipalKind.ACCOUNT.id().toString());
        zelda.set(ActivityModel.ACTOR_LABEL, null);
        new ActivityModel().save(zelda);
        Row alpha = rowFor(second);
        alpha.set(ActivityModel.ACTOR, Integer.toString(alphaId));
        alpha.set(ActivityModel.ACTOR_KIND, ZenitPrincipalKind.ACCOUNT.id().toString());
        alpha.set(ActivityModel.ACTOR_LABEL, null);
        new ActivityModel().save(alpha);
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
            .withFilter(FilterState.of(Map.of("record_id", "hh-actor-name"))).withSort(SortSpec.asc("actor"));
        assertThat(PartsReads.listRows(PanelGate.request(PanelRegistry.getBySlug("admin"), access), resource, null,
            applied, access).stream().map(row -> row.get(ActivityModel.RECORD_ID)).toList())
            .as("step 4: account names sort Alpha before Zelda, not by their ids").containsExactly(second, first);

        Row user = Models.get(UserModel.class).findById(zeldaId);
        user.set(UserModel.DISPLAY_NAME, null);
        Models.get(UserModel.class).save(user);
        Microcopy email = (Microcopy) PartsReads.cellValue(null, resource, null, zelda, column(resource, "actor"));
        assertThat(email.resolve(LocaleChain.ofTags("en"), new ShippedCatalogs()))
            .as("step 5: missing display name falls back to the stored email").isEqualTo(user.get(UserModel.EMAIL));
        Models.get(UserModel.class).find().where(UserModel.ID.eq(zeldaId)).delete();
        Microcopy deleted = (Microcopy) PartsReads.cellValue(null, resource, null, zelda, column(resource, "actor"));
        assertThat(deleted.resolve(LocaleChain.ofTags("en"), new ShippedCatalogs()))
            .as("step 6: a deleted author is named as unknown, never by its id").isEqualTo("Account no longer known");
    }

    private static final String OPERATOR_TITLE = "hh-activity-operator-subject";
    private static final String BACKGROUND_TITLE = "hh-activity-background-subject";
    private static final String UNLINKABLE_TITLE = "hh-activity-unlinkable-subject";
    private static final String NARROWED_TITLE = "hh-activity-narrowed-subject";
    private static final String SITE_TITLE = "hh-activity-site-subject";

    /** A verb no catalog declares anywhere, so its cell must fall open to this text. */
    private static final String UNREGISTERED_VERB = "hh_unregistered_verb";

    private static final String OPERATOR_RECORD_ID = "4242";
    private static final String BACKGROUND_RECORD_ID = "4243";
    private static final String UNLINKABLE_RECORD_ID = "4244";
    private static final String NARROWED_RECORD_ID = "918273";
    private static final String SITE_RECORD_ID = "4246";

    @Test
    void principalPickerDoesNotMatchNumericFragmentsOrAnotherKind() throws Exception {
        Map<String, String[]> actors = Map.of(
            "hh-principal-fragment-one", new String[]{"zenit:account", "1"},
            "hh-principal-fragment-ten", new String[]{"zenit:account", "10"},
            "hh-principal-fragment-twenty-one", new String[]{"zenit:account", "21"},
            "hh-principal-fragment-system", new String[]{"zenit:system", "1"});
        for (var actor : actors.entrySet()) {
            write(ServerModel.MODEL_ID.toString(), actor.getKey(), actor.getKey(), "updated", Accountability.ORIGIN_WEB,
                Instant.parse("2997-01-01T00:00:00Z"));
            Row row = rowFor(actor.getKey());
            row.set(ActivityModel.ACTOR_KIND, actor.getValue()[0]);
            row.set(ActivityModel.ACTOR, actor.getValue()[1]);
            row.set(ActivityModel.ACTOR_LABEL, null);
            new ActivityModel().save(row);
        }
        var one = adminGet("/admin/activity?filter.record_id=hh-principal-fragment&filter.actor="
            + URLEncoder.encode("zenit:account#1", StandardCharsets.UTF_8));
        assertThat(one.body()).as("step 1: account 1 is not account 10, account 21 or system 1")
            .contains("hh-principal-fragment-one").doesNotContain("hh-principal-fragment-ten",
                "hh-principal-fragment-twenty-one", "hh-principal-fragment-system");
        var system = adminGet("/admin/activity?filter.record_id=hh-principal-fragment&filter.actor="
            + URLEncoder.encode("zenit:system#1", StandardCharsets.UTF_8));
        assertThat(system.body()).as("step 2: the system's complete reference selects only its rows")
            .contains("hh-principal-fragment-system").doesNotContain("hh-principal-fragment-one",
                "hh-principal-fragment-ten", "hh-principal-fragment-twenty-one");
    }

    @Test
    void systemActivityNamesTheSystemRatherThanAccountOne() throws Exception {
        String record = "hh-system-actor-no-account-one";
        write(ServerModel.MODEL_ID.toString(), record, record, "updated", Accountability.ORIGIN_SYSTEM,
            Instant.parse("2999-01-02T00:00:00Z"));
        Row row = rowFor(record);
        row.set(ActivityModel.ACTOR, "1");
        row.set(ActivityModel.ACTOR_KIND, ZenitPrincipalKind.SYSTEM.id().toString());
        row.set(ActivityModel.ACTOR_LABEL, "work wait sweep");
        new ActivityModel().save(row);

        // 1. The host preserves the core projection instead of resolving system id 1 as a user.
        PanelResource<Row> resource = adminActivityResource();
        Object cell = PartsReads.cellValue(null, resource, null, row, column(resource, ActivityModel.ACTOR.getName()));
        assertThat(cell).as("step 1: the localized core actor name is preserved").isInstanceOf(Microcopy.class);
        assertThat(((Microcopy) cell).resolve(LocaleChain.ofTags("en"), new ShippedCatalogs()))
            .as("step 1: system work is not account #1").isEqualTo("System");

        // 2. The live admin list carries that name and never the internal reason.
        HttpResponse<String> response = adminGet("/admin/activity?filter.origin=system&filter.record_id=" + record);
        assertThat(response.statusCode()).as("step 2: system activity renders").isEqualTo(200);
        assertThat(response.body()).as("step 2: the system row has its public name")
            .contains(record, "System").doesNotContain("work wait sweep");
        HttpResponse<String> selected = adminGet("/admin/activity?filter.origin.__cleared=1&filter.actor="
            + URLEncoder.encode("zenit:system#1", StandardCharsets.UTF_8));
        assertThat(selected.body()).as("step 3: the principal picker selects the declared system identity")
            .contains(record).doesNotContain("work wait sweep");
    }

    @Test
    void activityListJourney() throws Exception {

        // 1. The hohenheim resource still describes the SAME columns the framework
        //    resource does: its spec is a copy (TableSpec has no toBuilder), so a column
        //    added upstream has to fail here rather than silently vanish from the panel.
        PanelResource<Row> resource = adminActivityResource();
        List<String> ours = PartsLists.tableSpec(resource).columns().stream().map(ColumnSpec::name).toList();
        List<String> framework = ActivityAdmin.table().columns()
            .stream().map(ColumnSpec::name).toList();
        assertThat(ours)
            .as("step 1: the panel's activity columns match the framework's")
            .isEqualTo(framework);

        // 2. Every filter an operator needs to reach ONE record is declared: the model,
        //    the record id, and the origin that flips the default scope.
        List<String> filters = PartsLists.tableSpec(resource).filters().stream()
            .map(FilterSpec::name).toList();
        assertThat(filters)
            .as("step 2: model, record and origin are all filterable")
            .contains(ActivityModel.MODEL.getName(), ActivityModel.RECORD_ID.getName(),
                ActivityModel.ORIGIN.getName());

        seedActivityRows();

        // 3. The default list is what a PERSON did: the system-origin sweep is not on it.
        HttpResponse<String> defaultList = adminGet("/admin/activity");
        assertThat(defaultList.statusCode()).as("step 3: the activity list renders").isEqualTo(200);
        assertThat(defaultList.body())
            .as("step 3: operator activity is on the default list")
            .contains(OPERATOR_TITLE);
        assertThat(defaultList.body())
            .as("step 3: background activity is hidden by default")
            .doesNotContain(BACKGROUND_TITLE);

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

        // 3d. The cleared marker genuinely suppresses it: background rows appear
        //     beside operator rows, and its chip is gone while the framework's own
        //     defaults (one row per action, internal records hidden) stay, because the
        //     panel extends ActivityAdmin.defaultFilter() instead of replacing it.
        HttpResponse<String> clearedList = adminGet("/admin/activity?filter.origin.__cleared=1");
        assertThat(clearedList.statusCode()).as("step 3d: the cleared list renders").isEqualTo(200);
        assertThat(clearedList.body())
            .as("step 3d: clearing the default shows background activity")
            .contains(BACKGROUND_TITLE)
            .contains(OPERATOR_TITLE);
        assertThat(clearedList.body())
            .as("step 3d: the people-only chip is gone while cleared")
            .doesNotContain("People only");
        assertThat(clearedList.body())
            .as("step 3d: the framework's default chips stay")
            .contains("One row per action")
            .contains("Internal records hidden");

        // 4. The origin filter genuinely flips it: naming the system origin shows the
        //    sweeps, so the default is a starting point and never a cage.
        HttpResponse<String> systemList = adminGet("/admin/activity?filter.origin="
            + Accountability.ORIGIN_SYSTEM);
        assertThat(systemList.statusCode()).as("step 4: the filtered list renders").isEqualTo(200);
        assertThat(systemList.body())
            .as("step 4: the origin filter reveals background activity")
            .contains(BACKGROUND_TITLE);
        assertThat(systemList.body())
            .as("step 4: the origin filter narrows to that origin")
            .doesNotContain(OPERATOR_TITLE);

        // 5. A verb leaves the resource as its member's localized label, not as the raw
        //    snake_case token the column used to print; the legacy "created" reads as the core verb (F6).
        Object verbCell = PartsReads.cellValue(null, resource, null, rowFor(OPERATOR_RECORD_ID),
            column(resource, ActivityModel.ACTION.getName()));
        assertThat(verbCell)
            .as("step 5: the verb cell is the localized label")
            .isInstanceOf(Microcopy.class);
        assertThat(((Microcopy) verbCell).key())
            .as("step 5: the label is the core create verb's")
            .isEqualTo(ActivityActions.label(ZenitActivityAction.CREATE).key());

        // 6. An undeclared verb reads as the one unknown label, never as its raw text and
        //    never as a blank cell.
        Object unknownCell = PartsReads.cellValue(null, resource, null, rowFor(UNLINKABLE_RECORD_ID),
            column(resource, ActivityModel.ACTION.getName()));
        assertThat(((Microcopy) unknownCell).key())
            .as("step 6: an undeclared verb reads as the unknown label")
            .isEqualTo(ActivityActions.unknownLabel().key());
        assertThat(defaultList.body())
            .as("step 6: and that label is what the list prints")
            .contains("Unknown action");

        // 7. A record a registered resource serves is a LINK to that record; the label is
        //    the title the row stored, never a fresh lookup.
        Object linked = PartsReads.cellValue(null, resource, null, rowFor(OPERATOR_RECORD_ID),
            column(resource, ActivityModel.RECORD_ID.getName()));
        assertThat(linked).as("step 7: the record cell is structured").isInstanceOf(ActivityRecordCell.class);
        ActivityRecordCell linkedCell = (ActivityRecordCell) linked;
        assertThat(linkedCell.label())
            .as("step 7: the link reads as the stored record title")
            .isEqualTo(OPERATOR_TITLE);
        assertThat(linkedCell.url())
            .as("step 7: and points at that record's front door, which opens its landing tab")
            .isEqualTo("/admin/servers/" + OPERATOR_RECORD_ID + "/open");

        // 7b. A model BOTH panels mount (sites: the admin resource and its /manage narrowing)
        //     still links into THIS panel: the admin activity list never sends an operator to
        //     /manage, whatever order the framework's panel registry iterates in.
        ActivityRecordCell siteCell = (ActivityRecordCell) PartsReads.cellValue(null, resource, null,
            rowFor(SITE_RECORD_ID), column(resource, ActivityModel.RECORD_ID.getName()));
        assertThat(siteCell.url())
            .as("step 7b: a site row links to the admin site page")
            .isEqualTo("/admin/sites/" + SITE_RECORD_ID + "/open");
        assertThat(defaultList.body())
            .as("step 7b: and the rendered list carries that /admin link")
            .contains("/admin/sites/" + SITE_RECORD_ID)
            .doesNotContain("/manage/sites/" + SITE_RECORD_ID);

        // 8. A record no resource serves stays plain text -- named, but not linked.
        ActivityRecordCell orphan = (ActivityRecordCell) PartsReads.cellValue(null, resource, null,
            rowFor(UNLINKABLE_RECORD_ID), column(resource, ActivityModel.RECORD_ID.getName()));
        assertThat(orphan.label())
            .as("step 8: an unlinkable record is still named")
            .isEqualTo(UNLINKABLE_TITLE);
        assertThat(orphan.url())
            .as("step 8: and carries no link")
            .isNull();

        // 9. The record filter narrows the log to the history of ONE record.
        HttpResponse<String> narrowed = adminGet("/admin/activity?filter.record_id="
            + NARROWED_RECORD_ID);
        assertThat(narrowed.statusCode()).as("step 9: the record-filtered list renders").isEqualTo(200);
        assertThat(narrowed.body())
            .as("step 9: the named record's activity is on it")
            .contains(NARROWED_TITLE);
        assertThat(narrowed.body())
            .as("step 9: and nothing else is")
            .doesNotContain(OPERATOR_TITLE)
            .doesNotContain(UNLINKABLE_TITLE);

        // 10. The list reads as sentences: the time and who did what to what are shown, the detail columns wait in the
        //     column picker, and an anonymous web write reads as someone not signed in, never as "Web".
        List<String> shown = PartsLists.tableSpec(resource).columns().stream()
            .filter(column -> !column.hidden()).map(ColumnSpec::name).toList();
        assertThat(shown).as("step 10: only the time and the sentence head the table")
            .containsExactly(ActivityModel.CREATED_AT.getName(), ActivityAdmin.SUMMARY_COLUMN);
        assertThat(defaultList.body())
            .as("step 10: the operator row reads as one sentence")
            .contains("Someone not signed in created " + OPERATOR_TITLE);

        // 11. The sentence links to its record inside /admin, through the record's front door.
        Object sentence = PartsReads.cellValue(null, resource, null, rowFor(SITE_RECORD_ID),
            column(resource, ActivityAdmin.SUMMARY_COLUMN));
        assertThat(sentence).as("step 11: the summary is the sentence cell").isInstanceOf(ActivitySentenceCell.class);
        assertThat(((ActivitySentenceCell) sentence).url())
            .as("step 11: and links to the admin site page")
            .isEqualTo("/admin/sites/" + SITE_RECORD_ID + "/open");

        // 12. Hohenheim's own operations tell what happened in their own words.
        Row ran = rowFor(OPERATOR_RECORD_ID);
        ran.set(ActivityModel.COMMAND_TYPE, PutOnline.PUT_ONLINE.id().toString());
        assertThat(ActivityText.headline(ran).resolve(LocaleChain.ofTags("en"), new ShippedCatalogs()))
            .as("step 12: putting something online reads as such")
            .isEqualTo("Someone not signed in put " + OPERATOR_TITLE + " online");
        ran.set(ActivityModel.COMMAND_TYPE, ProtectPath.OPERATION.id().toString());
        assertThat(ActivityText.headline(ran).resolve(LocaleChain.ofTags("nl"), new ShippedCatalogs()))
            .as("step 12: protecting a path reads as such, in Dutch too")
            .isEqualTo("Iemand die niet is aangemeld beveiligde " + OPERATOR_TITLE);
    }

    @Test
    void peopleOnlyLeavesPlumbingAndSeedsBehindTheToggleOnTheListAndTheDashboard() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String person = "hh-people-person-" + suffix;
        String seeded = "hh-people-seeded-" + suffix;
        String plumbing = "hh-people-plumbing-" + suffix;

        // 1. Hohenheim's plumbing models are declared internal; a model people also write is not.
        for (var model : HohenheimActivity.INTERNAL) {
            assertThat(ActivityLog.visibilityFor(model))
                .as("step 1: " + model + " is declared internal").isEqualTo(ActivityVisibility.INTERNAL);
        }
        assertThat(ActivityLog.visibilityFor(PortAllocationModel.MODEL_ID))
            .as("step 1: port claims are plumbing").isEqualTo(ActivityVisibility.INTERNAL);
        assertThat(ActivityLog.internalModelTokens())
            .as("step 1: zenit-auth's grants are declared internal in this runtime too")
            .contains("zenit:grant", "zenit:record_grant");
        assertThat(ActivityLog.visibilityFor(ServerModel.MODEL_ID))
            .as("step 1: hosts stay listed, operators admit and edit them").isEqualTo(ActivityVisibility.LISTED);

        // 2. A heartbeat is bookkeeping, never activity: a probe success writes no row for its host.
        String hostName = "hh-heartbeat-" + suffix;
        ServerModel servers = Models.get(ServerModel.class);
        Row host = servers.createEmptyRow();
        host.set(ServerModel.NAME, hostName);
        host.set(ServerModel.MODE, ServerModel.MODE_SSH);
        host.set(ServerModel.SSH_TARGET, "operator@" + hostName + ".invalid");
        host.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
        servers.save(host);
        String hostId = String.valueOf((Object) host.get(ServerModel.ID));
        long before = activityCount(ServerModel.MODEL_ID.toString(), hostId);
        HostProbe.recordSuccess(hostName);
        assertThat(servers.findByName(hostName).get(ServerModel.LAST_SEEN_AT))
            .as("step 2: the probe did record when the host was seen").isNotNull();
        assertThat(activityCount(ServerModel.MODEL_ID.toString(), hostId))
            .as("step 2: and wrote no activity row for it").isEqualTo(before);

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
        String linkId = String.valueOf((Object) link.get(DnsZonePeerModel.ID));
        long linkRows = activityCount(DnsZonePeerModel.MODEL_ID.toString(), linkId);
        DnsFederationTrace.notifySent(link, peer, "trace-" + suffix + ".test.", 7, "ok");
        assertThat(Models.get(DnsZonePeerModel.class).findById(link.get(DnsZonePeerModel.ID))
            .get(DnsZonePeerModel.LAST_NOTIFY_SERIAL))
            .as("step 2b: the trace did stamp the link").isEqualTo(7);
        assertThat(activityCount(DnsZonePeerModel.MODEL_ID.toString(), linkId))
            .as("step 2b: and wrote no activity row for it").isEqualTo(linkRows);

        // 3. The list opens on what a person did: a seed row, an internal row and a row written by work that declared
        //    no identity at all are not on it.
        String unattributed = "hh-people-unattributed-" + suffix;
        write(SiteModel.MODEL_ID.toString(), person, person, "updated",
            Accountability.ORIGIN_WEB, Instant.parse("2999-02-01T00:00:03Z"));
        write(SiteModel.MODEL_ID.toString(), seeded, seeded, "created",
            AccountabilityOrigin.SEED.token(), Instant.parse("2999-02-01T00:00:02Z"));
        write(PortAllocationModel.MODEL_ID.toString(), plumbing, plumbing, "created",
            Accountability.ORIGIN_WEB, Instant.parse("2999-02-01T00:00:01Z"));
        write(SiteModel.MODEL_ID.toString(), unattributed, unattributed, "updated",
            Accountability.ORIGIN_UNATTRIBUTED, Instant.parse("2999-02-01T00:00:04Z"));
        // A sign-in is bookkeeping by its verb (core's LOGIN is internal): it reads "<who> signed in", so the marker is
        // the actor's name.
        String signedIn = "hh-people-signin-" + suffix;
        ActivityModel activities = new ActivityModel();
        Row signIn = activities.createEmptyRow();
        signIn.set(ActivityModel.MODEL, SiteModel.MODEL_ID.toString());
        signIn.set(ActivityModel.RECORD_ID, signedIn);
        signIn.set(ActivityModel.ACTION, ZenitActivityAction.LOGIN.id().toString());
        signIn.set(ActivityModel.ACTOR_LABEL, signedIn);
        signIn.set(ActivityModel.ORIGIN, Accountability.ORIGIN_WEB);
        signIn.set(ActivityModel.CREATED_AT, Instant.parse("2999-02-01T00:00:05Z"));
        activities.save(signIn);
        String opening = adminGet("/admin/activity").body();
        assertThat(opening).as("step 3: a person's row opens the list").contains(person);
        assertThat(opening).as("step 3: a sign-in does not").doesNotContain(signedIn);
        assertThat(opening).as("step 3: a seed row does not").doesNotContain(seeded);
        assertThat(opening).as("step 3: an internal row does not").doesNotContain(plumbing);
        assertThat(opening).as("step 3: an unattributed row is not a person's either").doesNotContain(unattributed);

        // 4. The framework's internal toggle brings the plumbing back, still searchable.
        assertThat(adminGet("/admin/activity?filter.internal=true&filter.record_id=" + plumbing).body())
            .as("step 4: internal records are one toggle away").contains(plumbing);
        assertThat(adminGet("/admin/activity?filter.internal=true&filter.record_id=" + signedIn).body())
            .as("step 4: and so are sign-ins").contains(signedIn);

        // 5. The dashboard's recent activity reads the SAME scope as the list's opening. An empty install shows no
        //    activity band at all, so the journey puts one app online first.
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "People Only Site " + suffix);
        site.set(SiteModel.SLUG, "people-only-site-" + suffix);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        String dashboard = adminGet("/admin/dashboard").body();
        assertThat(dashboard).as("step 5: the dashboard shows what a person did").contains(person);
        assertThat(dashboard).as("step 5: and neither seeds, plumbing nor unattributed work")
            .doesNotContain(seeded).doesNotContain(plumbing).doesNotContain(unattributed).doesNotContain(signedIn);
    }

    private static long activityCount(String model, String recordId) {
        return new ActivityModel().find()
            .where(ActivityModel.MODEL.eq(model))
            .where(ActivityModel.RECORD_ID.eq(recordId))
            .all().size();
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

    private static Row rowFor(String recordId) {
        Row row = new ActivityModel().find()
            .where(ActivityModel.RECORD_ID.eq(recordId))
            .first();
        assertThat(row).as("the seeded activity row for record " + recordId).isNotNull();
        return row;
    }

    /**
     * Four rows spanning both lanes, both link states and a verb nobody declared.
     *
     * AIDEV-NOTE: the timestamps are far in the future on purpose -- the list is sorted
     * created_at DESC and paginated, and every other test in this suite writes activity
     * rows too, so "the first page" is the only place these can be asserted about.
     */
    private static void seedActivityRows() {
        String serverModel = ServerModel.MODEL_ID.toString();
        write(serverModel, OPERATOR_RECORD_ID, OPERATOR_TITLE, "created",
            Accountability.ORIGIN_WEB, Instant.parse("2999-01-01T00:00:04Z"));
        write(serverModel, BACKGROUND_RECORD_ID, BACKGROUND_TITLE, "reconciled",
            Accountability.ORIGIN_SYSTEM, Instant.parse("2999-01-01T00:00:03Z"));
        write("hohenheim:no-such-model", UNLINKABLE_RECORD_ID, UNLINKABLE_TITLE, UNREGISTERED_VERB,
            Accountability.ORIGIN_WEB, Instant.parse("2999-01-01T00:00:02Z"));
        write(serverModel, NARROWED_RECORD_ID, NARROWED_TITLE, "created",
            Accountability.ORIGIN_WEB, Instant.parse("2999-01-01T00:00:01Z"));
        write(SiteModel.MODEL_ID.toString(), SITE_RECORD_ID, SITE_TITLE, "updated",
            Accountability.ORIGIN_WEB, Instant.parse("2999-01-01T00:00:05Z"));
    }

    private static void write(String model, String recordId, String title, String action,
                              String origin, Instant createdAt) {
        ActivityModel activities = new ActivityModel();
        Row row = activities.createEmptyRow();
        row.set(ActivityModel.MODEL, model);
        row.set(ActivityModel.RECORD_ID, recordId);
        row.set(ActivityModel.RECORD_TITLE, title);
        row.set(ActivityModel.ACTION, action);
        row.set(ActivityModel.ORIGIN, origin);
        row.set(ActivityModel.CREATED_AT, createdAt);
        activities.save(row);
    }
}
