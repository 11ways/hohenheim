package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.DatabaseResource;
import be.elevenways.hohenheim.server.cms.DnsRecordResource;
import be.elevenways.hohenheim.server.cms.InstanceDatabaseResource;
import be.elevenways.hohenheim.server.cms.InstanceResource;
import be.elevenways.hohenheim.server.cms.InstanceScheduleResource;
import be.elevenways.hohenheim.server.cms.InstanceScheduleStepResource;
import be.elevenways.hohenheim.server.cms.SiteDomainResource;
import be.elevenways.hohenheim.server.cms.SiteDomainsPage;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Affordance-versus-funnel parity on every resource whose READ scope is wider than its
 * WRITE authority: the synthesized Edit/Delete affordances (and the detail form's Save
 * behind them) are offered exactly where the write pipeline would accept -- the
 * {@code InstanceDeviceResource} lesson, applied to the four remaining surfaces.
 *
 * Enforcement was never broken (TenantWrites refuses every one of these on the model
 * pipeline); what these pin is that the SURFACE now agrees with the funnel instead of
 * showing a view-only delegate buttons that can only refuse. Each block carries its
 * positive anchor, so a surface that refuses everyone cannot pass.
 */
class WriteAffordanceParityTest extends HohenheimTestBase {

    private static final String PREFIX = "affparity-";

    private static Integer viewerId;
    private static Integer holderId;

    private static Integer instanceId;
    private static Integer databaseId;
    private static Integer linkId;
    private static Integer zoneId;
    private static Integer recordId;
    private static Integer foreignTypeRecordId;
    private static Integer siteId;
    private static Integer domainId;

    @BeforeAll
    static void seed() {
        viewerId = user("affparity-viewer@surface.test", "Affordance Viewer");
        holderId = user("affparity-holder@surface.test", "Affordance Holder");

        Model instances = Models.get(InstanceModel.class);
        Row instance = instances.createEmptyRow();
        instance.set(InstanceModel.NAME, PREFIX + "instance");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(instance);
        instanceId = instance.get(InstanceModel.ID);

        Model databases = Models.get(DatabaseModel.class);
        Row database = databases.createEmptyRow();
        database.set(DatabaseModel.NAME, PREFIX + "db");
        database.set(DatabaseModel.ENGINE, "postgres");
        database.set(DatabaseModel.DB_NAME, PREFIX + "db");
        database.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(database);
        databaseId = database.get(DatabaseModel.ID);

        Model links = Models.get(InstanceDatabaseModel.class);
        Row link = links.createEmptyRow();
        link.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
        link.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
        link.set(InstanceDatabaseModel.ENV_PREFIX, InstanceDatabaseModel.DEFAULT_PREFIX);
        links.save(link);
        linkId = link.get(InstanceDatabaseModel.ID);

        Model zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, "affparity.test");
        zone.set(DnsZoneModel.ENABLED, true);
        zone.set(DnsZoneModel.DEFAULT_TTL, 3600);
        zone.set(DnsZoneModel.NEGATIVE_TTL, 300);
        zone.set(DnsZoneModel.SOA_REFRESH, 7200);
        zone.set(DnsZoneModel.SOA_RETRY, 3600);
        zone.set(DnsZoneModel.SOA_EXPIRE, 1209600);
        zones.save(zone);
        zoneId = zone.get(DnsZoneModel.ID);

        Model sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, PREFIX + "site");
        site.set(SiteModel.SLUG, PREFIX + "site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        siteId = site.get(SiteModel.ID);

        Model domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, "affparity.example.com");
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.FORCE_SSL, false);
        domains.save(domain);
        domainId = domain.get(SiteDomainModel.ID);

        recordId = dnsRecord("editable", DnsRecordModel.TYPE_A, "192.0.2.10");
        foreignTypeRecordId = dnsRecord("delegated", DnsRecordModel.TYPE_NS, "ns1.example.org");

        // The viewer holds the READ half everywhere it exists as a verb.
        RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.VIEW, true);
        RecordGrants.grant(GrantSubjectType.USER, viewerId, DatabaseModel.MODEL_ID, databaseId,
            HohenheimAccess.VIEW, true);
        RecordGrants.grant(GrantSubjectType.USER, viewerId, DnsRecordModel.MODEL_ID, recordId,
            HohenheimAccess.VIEW, true);

        // The holder carries exactly what each funnel demands.
        RecordGrants.grant(GrantSubjectType.USER, holderId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.CONFIG, true);
        RecordGrants.grant(GrantSubjectType.USER, holderId, DatabaseModel.MODEL_ID, databaseId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, holderId, DnsRecordModel.MODEL_ID, recordId,
            HohenheimAccess.EDIT, true);
        RecordGrants.grant(GrantSubjectType.USER, holderId, DnsRecordModel.MODEL_ID, foreignTypeRecordId,
            HohenheimAccess.EDIT, true);
        RecordGrants.grant(GrantSubjectType.USER, holderId, SiteModel.MODEL_ID, siteId,
            HohenheimAccess.MANAGE, true);
    }

    private static int dnsRecord(String name, String type, String value) {
        Model records = Models.get(DnsRecordModel.class);
        Row row = records.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zoneId);
        row.set(DnsRecordModel.NAME, name);
        row.set(DnsRecordModel.TYPE, type);
        row.set(DnsRecordModel.VALUE, value);
        row.set(DnsRecordModel.TTL, 300);
        row.set(DnsRecordModel.ENABLED, true);
        records.save(row);
        return row.get(DnsRecordModel.ID);
    }

    @AfterAll
    static void cleanUp() {
        if (domainId != null) {
            Models.get(SiteDomainModel.class).delete(domainId);
        }
        if (siteId != null) {
            Models.get(SiteModel.class).delete(siteId);
        }
        if (linkId != null) {
            Models.get(InstanceDatabaseModel.class).delete(linkId);
        }
        if (recordId != null) {
            Models.get(DnsRecordModel.class).delete(recordId);
        }
        if (foreignTypeRecordId != null) {
            Models.get(DnsRecordModel.class).delete(foreignTypeRecordId);
        }
        if (zoneId != null) {
            Models.get(DnsZoneModel.class).delete(zoneId);
        }
        if (databaseId != null) {
            Models.get(DatabaseModel.class).delete(databaseId);
        }
        if (instanceId != null) {
            Models.get(InstanceModel.class).delete(instanceId);
        }
    }

    private static int user(String email, String name) {
        Row row = AuthModels.users().createEmptyRow();
        row.set(UserModel.EMAIL, email);
        row.set(UserModel.DISPLAY_NAME, name);
        row.set(UserModel.ENABLED, true);
        row.set(UserModel.CREATED_AT, Instant.now());
        row.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(row);
        return row.get(UserModel.ID);
    }

    private static AccessContext viewer() {
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(viewerId, "Affordance Viewer")));
    }

    private static AccessContext holder() {
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(holderId, "Affordance Holder")));
    }

    private static AccessContext operator() {
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(admin.get(UserModel.ID), "Test Admin")));
    }

    /** An instance UPDATE is a CONFIG act (TenantWrites.checkInstanceWrite). */
    @Test
    void theInstanceEditorFollowsConfig() {
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        InstanceResource resource = new InstanceResource();

        assertThat(resource.updatableBy(instance, viewer()))
            .as("a view-only delegate is offered no instance editor").isFalse();
        assertThat(resource.updatableBy(instance, holder()))
            .as("a config holder keeps it").isTrue();
        assertThat(resource.updatableBy(instance, operator()))
            .as("and the operator passes through the walk's admin row").isTrue();
    }

    /** A database DELETE is a DESTROY act (the model's before-remove hook). */
    @Test
    void theDatabaseDeleteFollowsDestroy() {
        Row database = Models.get(DatabaseModel.class).findById(databaseId);
        DatabaseResource resource = new DatabaseResource();

        assertThat(resource.deletableBy(database, viewer()))
            .as("a view-only delegate is offered no destroy button").isFalse();
        // MANAGE implies DESTROY on databases, so the holder passes the implied row.
        assertThat(resource.deletableBy(database, holder()))
            .as("a manage holder keeps its destroy button").isTrue();
        assertThat(resource.deletableBy(database, operator()))
            .as("and the operator passes").isTrue();
    }

    /** A link write is two-sided: instance CONFIG plus database MANAGE, both or neither. */
    @Test
    void theAttachmentAffordancesFollowBothSides() {
        Row link = Models.get(InstanceDatabaseModel.class).findById(linkId);
        InstanceDatabaseResource resource = new InstanceDatabaseResource();

        assertThat(resource.updatableBy(link, viewer()))
            .as("a view-only delegate is offered no attachment editor").isFalse();
        assertThat(resource.deletableBy(link, viewer()))
            .as("nor a detach button").isFalse();
        assertThat(resource.updatableBy(link, holder()))
            .as("the two-sided holder keeps its editor").isTrue();
        assertThat(resource.deletableBy(link, holder()))
            .as("and its detach button").isTrue();

        // ONE-SIDED: revoke the database half and the affordance must fall with it --
        // the exact laundering the two-sided funnel rule exists to refuse. revoke,
        // never grant(false): a planted deny is sticky and would outlive the finally.
        RecordGrants.revoke(GrantSubjectType.USER, holderId, DatabaseModel.MODEL_ID, databaseId,
            HohenheimAccess.MANAGE);
        try {
            assertThat(resource.updatableBy(link, holder()))
                .as("instance config alone does not earn the attachment editor").isFalse();
            assertThat(resource.deletableBy(link, holder()))
                .as("nor the detach button").isFalse();
        } finally {
            RecordGrants.grant(GrantSubjectType.USER, holderId, DatabaseModel.MODEL_ID, databaseId,
                HohenheimAccess.MANAGE, true);
        }
    }

    /**
     * A DNS record write is the union of per-record {@code edit} and hostname authority,
     * inside the tenant-authorable TYPE allow-list -- and the affordance mirrors all
     * three clauses, foreign types included.
     */
    @Test
    void theDnsRecordAffordancesFollowTheRecordLanes() {
        Row editable = Models.get(DnsRecordModel.class).findById(recordId);
        Row delegated = Models.get(DnsRecordModel.class).findById(foreignTypeRecordId);
        DnsRecordResource resource = new DnsRecordResource();

        assertThat(resource.updatableBy(editable, viewer()))
            .as("a view-only delegate is offered no record editor").isFalse();
        assertThat(resource.deletableBy(editable, viewer()))
            .as("nor a delete button").isFalse();
        assertThat(resource.updatableBy(editable, holder()))
            .as("an edit-grant holder keeps its editor").isTrue();
        assertThat(resource.deletableBy(editable, holder()))
            .as("and its delete button").isTrue();

        // The TYPE clause: an NS row is a zone-compromise primitive the pipeline refuses
        // for EVERY tenant writer, edit grant or not -- so no affordance either.
        assertThat(resource.updatableBy(delegated, holder()))
            .as("an NS row offers no tenant editor even to an edit-grant holder")
            .isFalse();
        assertThat(resource.deletableBy(delegated, holder()))
            .as("nor a delete button").isFalse();

        // While the operator, whom the tenant lanes never gate, keeps both on both rows.
        assertThat(resource.updatableBy(delegated, operator()))
            .as("the operator keeps the NS editor").isTrue();
        assertThat(resource.deletableBy(editable, operator()))
            .as("and every delete button").isTrue();
    }

    /**
     * The site's Domains tab renders the DOMAIN RESOURCE's answer, per row, instead of a
     * second hand-rolled boolean: the page asked {@code canManageSite} while the resource's
     * {@code writableBy} asks {@code reachesRecord}, so a narrowed override on the
     * delegated mirror would have moved the endpoint without moving the affordance.
     */
    @Test
    void theSiteDomainsTabFollowsTheDomainResource() {
        Row domain = Models.get(SiteDomainModel.class).findById(domainId);
        Row site = Models.get(SiteModel.class).findById(siteId);
        SiteDomainResource resource = new SiteDomainResource();

        // 1. The resource's own answer: manage on the OWNING SITE, nothing else.
        assertThat(resource.updatableBy(domain, viewer()))
            .as("a delegate without manage on the site is offered no domain editor").isFalse();
        assertThat(resource.updatableBy(domain, holder()))
            .as("a manage holder keeps its editor").isTrue();
        assertThat(resource.deletableBy(domain, holder()))
            .as("and its detach button").isTrue();

        // 2. The TAB answers exactly the same, row by row.
        assertThat(rowAffordances(site, viewer()))
            .as("the tab offers a non-holder no row affordances")
            .containsExactly(false, false);
        assertThat(rowAffordances(site, holder()))
            .as("and offers the holder exactly what the resource grants")
            .containsExactly(true, true);
        assertThat(rowAffordances(site, operator()))
            .as("the operator, whom the resource never gates, keeps both")
            .containsExactly(true, true);
    }

    /** The Domains tab's rendered (edit link, remove form) pair for its one domain row. */
    @SuppressWarnings("unchecked")
    private static List<Boolean> rowAffordances(Row site, AccessContext ctx) {
        Map<String, Object> vars = (Map<String, Object>) new SiteDomainsPage()
            .render(ctx.conduit(), ctx, site).get();
        List<Map<String, Object>> rows = (List<Map<String, Object>>) vars.get("domains");
        assertThat(rows).as("the tab lists its one domain").hasSize(1);
        return List.of(Boolean.TRUE.equals(rows.get(0).get("canEdit")),
            Boolean.TRUE.equals(rows.get(0).get("canRemove")));
    }

    // --- Query budgets: per-row predicates answer off the request memo ---------------

    /**
     * The dyndns row actions' {@code visibleFor} runs once per rendered row (twice: mint
     * and revoke), so it must answer off the request memo ({@code reachesRecord}) instead
     * of walking the grant store per row -- the TenantDomainDnsScopeTest budget idiom,
     * pinned here at the predicate itself so the regression names this resource.
     */
    @Test
    void theDyndnsVisibilityPredicateStaysInsideTheGrantQueryBudget() {
        Model records = Models.get(DnsRecordModel.class);
        List<Integer> extra = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            extra.add(dnsRecord("budget-" + i, DnsRecordModel.TYPE_A, "192.0.2." + (20 + i)));
        }
        RecordGrants.grant(GrantSubjectType.USER, viewerId, DnsRecordModel.MODEL_ID, recordId,
            HohenheimAccess.DYNDNS, true);
        try {
            DnsRecordResource resource = new DnsRecordResource();
            List<BiPredicate<Row, AccessContext>> predicates = resource.rowActions().stream()
                .filter(action -> action instanceof RowAction.Invoke<Row> invoke
                    && invoke.id().toString().contains("dyndns"))
                .map(action -> ((RowAction.Invoke<Row>) action).visibleFor())
                .toList();
            assertThat(predicates).as("both dyndns actions carry a per-row predicate").hasSize(2);

            List<Row> rows = new ArrayList<>();
            rows.add(records.findById(recordId));
            for (Integer id : extra) {
                rows.add(records.findById(id));
            }

            AtomicInteger finds = new AtomicInteger();
            RecordGrantModel.SCHEMA.addBeforeFindHook(ignored -> finds.incrementAndGet());
            finds.set(0);
            AccessContext ctx = viewer();
            for (Row row : rows) {
                for (BiPredicate<Row, AccessContext> predicate : predicates) {
                    predicate.test(row, ctx);
                }
            }
            // Memoized: ONE dns_record#dyndns enumeration (candidate fetch + walk
            // confirmations) for all 12 evaluations. The un-memoized spelling walks
            // per evaluation and lands far outside this cap. Fix by removing queries,
            // never by raising the cap.
            assertThat(finds.get())
                .as("record-grant finds across 12 dyndns visibility evaluations "
                    + "(one capability set)")
                .isBetween(1, 4);
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, viewerId, DnsRecordModel.MODEL_ID, recordId,
                HohenheimAccess.DYNDNS);
            for (Integer id : extra) {
                records.delete(id);
            }
        }
    }

    /**
     * The attachment's two-sided {@code writableBy} runs once per rendered row and asks
     * about TWO models, so an un-memoized spelling paid two grant walks per row.
     */
    @Test
    void theAttachmentAffordanceStaysInsideTheGrantQueryBudget() {
        InstanceDatabaseResource resource = new InstanceDatabaseResource();
        Row link = Models.get(InstanceDatabaseModel.class).findById(linkId);

        AtomicInteger finds = new AtomicInteger();
        RecordGrantModel.SCHEMA.addBeforeFindHook(ignored -> finds.incrementAndGet());
        finds.set(0);
        AccessContext ctx = holder();
        for (int i = 0; i < 6; i++) {
            resource.updatableBy(link, ctx);
        }
        // Memoized: one enumeration per DISTINCT set (instance#config, database#manage)
        // for all 6 rows. Un-memoized was 2 walks x 6 rows. Never raise the cap.
        assertThat(finds.get())
            .as("record-grant finds across 6 attachment writability checks "
                + "(two capability sets)")
            .isBetween(1, 8);
    }

    /**
     * The schedule-step {@code writableBy} loads the parent schedule AND asks a
     * capability per row; both must collapse to once per request -- the steps list is
     * scoped to ONE schedule, so per-row loads were pure duplication.
     */
    @Test
    void theScheduleStepAffordanceStaysInsideBothQueryBudgets() {
        Model schedules = Models.get(RecordScheduleModel.class);
        Row schedule = schedules.createEmptyRow();
        schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceId));
        schedule.set(RecordScheduleModel.NAME, PREFIX + "budget-schedule");
        schedule.set(RecordScheduleModel.CRON, "0 4 * * *");
        schedule.set(RecordScheduleModel.ENABLED, true);
        schedule.set(RecordScheduleModel.RUN_AS, holderId.longValue());
        schedules.save(schedule);
        Integer scheduleId = schedule.get(RecordScheduleModel.ID);
        try {
            InstanceScheduleStepResource resource = new InstanceScheduleStepResource();
            Row step = Models.get(RecordScheduleStepModel.class).createEmptyRow();
            step.set(RecordScheduleStepModel.SCHEDULE_ID, scheduleId);

            AtomicInteger grantFinds = new AtomicInteger();
            AtomicInteger scheduleFinds = new AtomicInteger();
            RecordGrantModel.SCHEMA.addBeforeFindHook(ignored -> grantFinds.incrementAndGet());
            RecordScheduleModel.SCHEMA.addBeforeFindHook(ignored -> scheduleFinds.incrementAndGet());
            grantFinds.set(0);
            scheduleFinds.set(0);
            AccessContext ctx = holder();
            for (int i = 0; i < 6; i++) {
                assertThat(resource.writableBy(step, ctx))
                    .as("the config holder keeps the step editor").isTrue();
            }
            assertThat(scheduleFinds.get())
                .as("schedule loads across 6 step writability checks (one schedule)")
                .isEqualTo(1);
            assertThat(grantFinds.get())
                .as("record-grant finds across 6 step writability checks "
                    + "(one capability set)")
                .isBetween(1, 4);
        } finally {
            schedules.delete(scheduleId);
        }
    }

    /**
     * The instances list is the headline: {@code updatableBy} plus SIX action
     * {@code visibleFor} predicates all answer a per-record capability question, so an
     * un-memoized spelling paid up to seven grant-store round trips PER RENDERED ROW.
     */
    @Test
    void theInstanceListAffordancesStayInsideTheGrantQueryBudget() {
        Model instances = Models.get(InstanceModel.class);
        List<Integer> extra = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            extra.add(instanceRow(PREFIX + "budget-" + i));
        }
        RecordGrants.grant(GrantSubjectType.USER, holderId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.POWER, true);
        try {
            InstanceResource resource = new InstanceResource();
            List<RowAction<Row>> actions = resource.rowActions();

            List<Row> rows = new ArrayList<>();
            rows.add(instances.findById(instanceId));
            for (Integer id : extra) {
                rows.add(instances.findById(id));
            }

            AtomicInteger finds = new AtomicInteger();
            RecordGrantModel.SCHEMA.addBeforeFindHook(ignored -> finds.incrementAndGet());
            finds.set(0);
            AccessContext ctx = holder();
            boolean sawAnAffordance = false;
            for (Row row : rows) {
                sawAnAffordance |= resource.updatableBy(row, ctx);
                for (RowAction<Row> action : actions) {
                    sawAnAffordance |= action.isVisibleFor(row, ctx);
                }
            }
            assertThat(sawAnAffordance)
                .as("the granted instance still offers its affordances").isTrue();
            // Memoized this measures 8: one enumeration per DISTINCT capability set
            // asked of instance (config, power, snapshots, backups) for all 6 rows,
            // each costing a candidate fetch plus walk confirmations. Reverting ONE
            // predicate to the fresh walk measures 13, which is what the cap catches --
            // fix a breach by removing queries, never by raising the cap.
            assertThat(finds.get())
                .as("record-grant finds across 6 instance rows x 7 affordance checks "
                    + "(four capability sets)")
                .isBetween(1, 10);
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, holderId, InstanceModel.MODEL_ID,
                instanceId, HohenheimAccess.POWER);
            for (Integer id : extra) {
                instances.delete(id);
            }
        }
    }

    /**
     * The schedule list asks the SAME question twice per row ({@code writableBy} and the
     * run_now {@code visibleFor}); its {@code requireManage} write gate reads identically
     * and deliberately keeps the fresh walk, so this pins which of the two is memoized.
     */
    @Test
    void theScheduleListAffordancesStayInsideTheGrantQueryBudget() {
        Model schedules = Models.get(RecordScheduleModel.class);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Row schedule = schedules.createEmptyRow();
            schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
            schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceId));
            schedule.set(RecordScheduleModel.NAME, PREFIX + "list-schedule-" + i);
            schedule.set(RecordScheduleModel.CRON, "0 4 * * *");
            schedule.set(RecordScheduleModel.ENABLED, true);
            schedule.set(RecordScheduleModel.RUN_AS, holderId.longValue());
            schedules.save(schedule);
            ids.add(schedule.get(RecordScheduleModel.ID));
        }
        try {
            InstanceScheduleResource resource = new InstanceScheduleResource();
            List<RowAction<Row>> actions = resource.rowActions();
            List<Row> rows = new ArrayList<>();
            for (Integer id : ids) {
                rows.add(schedules.findById(id));
            }

            AtomicInteger finds = new AtomicInteger();
            RecordGrantModel.SCHEMA.addBeforeFindHook(ignored -> finds.incrementAndGet());
            finds.set(0);
            AccessContext ctx = holder();
            for (Row row : rows) {
                assertThat(resource.writableBy(row, ctx))
                    .as("the config holder keeps the schedule editor").isTrue();
                for (RowAction<Row> action : actions) {
                    action.isVisibleFor(row, ctx);
                }
            }
            // Memoized: ONE instance#config enumeration for all 12 evaluations.
            // Un-memoized was 2 walks x 6 rows. Never raise the cap.
            assertThat(finds.get())
                .as("record-grant finds across 6 schedule rows x 2 affordance checks "
                    + "(one capability set)")
                .isBetween(1, 4);
        } finally {
            for (Integer id : ids) {
                schedules.delete(id);
            }
        }
    }

    private static int instanceRow(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }
}
