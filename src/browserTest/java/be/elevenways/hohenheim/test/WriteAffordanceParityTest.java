package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.instance.InstanceAttachmentOperations;
import be.elevenways.hohenheim.server.cms.InstanceAttachmentParts;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.hohenheim.server.cms.InstanceParts;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.DatabaseParts;
import be.elevenways.hohenheim.server.cms.DnsRecordParts;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.hohenheim.server.cms.DomainParts;
import be.elevenways.hohenheim.instance.InstanceScheduleOperations;
import be.elevenways.hohenheim.server.cms.InstanceScheduleParts;
import be.elevenways.hohenheim.server.cms.InstanceScheduleStepParts;
import be.elevenways.hohenheim.server.cms.SiteParts;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.cms.common.render.panel.ChildListSectionState;
import be.elevenways.zenit.cms.common.render.table.TableState;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.cms.server.panel.PanelResourceViews;
import be.elevenways.zenit.cms.server.panel.ResourceVerbs;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.test.support.EndpointConduit;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.server.panel.PanelActionOffers;
import be.elevenways.zenit.cms.server.render.action.RowOffer;
import be.elevenways.zenit.server.operation.OperationPipeline;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import java.util.Objects;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Affordance-versus-funnel parity on every resource whose READ scope is wider than its
 * WRITE authority: the synthesized Edit/Delete affordances (and the detail form's Save
 * behind them) are offered exactly where the write pipeline would accept -- the
 * {@code InstanceAttachmentParts.devicesAdmin()} lesson, applied to the four remaining surfaces.
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
        viewerId = ApiSupport.user("affparity-viewer@surface.test", "Affordance Viewer");
        holderId = ApiSupport.user("affparity-holder@surface.test", "Affordance Holder");

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
            HardDeletes.byId(Models.get(SiteModel.class), siteId);
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
            HardDeletes.byId(Models.get(InstanceModel.class), instanceId);
        }
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
        RowResource resource = PanelEntryViews.of(HohenheimSlugs.ADMIN, InstanceParts.SLUG);

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
        // A delegate reaches databases through the /manage twin, whose delete is the same operation.
        Panel manage = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.MANAGE));
        Resource<Row> resource = PanelResourceViews.forCaller(DatabaseParts.manage(), manage);

        assertThat(ResourceVerbs.removableBy(manage, resource, database, viewer()))
            .as("a view-only delegate is offered no destroy button").isFalse();
        // MANAGE implies DESTROY on databases, so the holder passes the implied row.
        assertThat(ResourceVerbs.removableBy(manage, resource, database, holder()))
            .as("a manage holder keeps its destroy button").isTrue();
        assertThat(ResourceVerbs.removableBy(manage, resource, database, operator()))
            .as("and the operator passes").isTrue();
    }

    /** A link write is two-sided: instance CONFIG plus database MANAGE, both or neither. */
    @Test
    void theAttachmentAffordancesFollowBothSides() {
        Row link = Models.get(InstanceDatabaseModel.class).findById(linkId);
        RowResource resource = PanelEntryViews.of(HohenheimSlugs.ADMIN, InstanceAttachmentParts.DATABASES);

        assertThat(resource.updatableBy(link, viewer()))
            .as("a view-only delegate is offered no attachment editor").isFalse();
        assertThat(detachOffered(link, viewer()))
            .as("nor a detach button").isFalse();
        assertThat(resource.updatableBy(link, holder()))
            .as("the two-sided holder keeps its editor").isTrue();
        assertThat(detachOffered(link, holder()))
            .as("and its detach button").isTrue();

        // ONE-SIDED: revoke the database half and the affordance must fall with it --
        // the exact laundering the two-sided funnel rule exists to refuse. revoke,
        // never grant(false): a planted deny is sticky and would outlive the finally.
        RecordGrants.revoke(GrantSubjectType.USER, holderId, DatabaseModel.MODEL_ID, databaseId,
            HohenheimAccess.MANAGE);
        try {
            assertThat(resource.updatableBy(link, holder()))
                .as("instance config alone does not earn the attachment editor").isFalse();
            assertThat(detachOffered(link, holder()))
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
        var resource = PanelResourceViews.forCaller(DnsRecordParts.admin());

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
        PanelResource<Row> resource = DomainParts.admin();

        // 1. The resource's own answer: manage on the OWNING SITE, nothing else.
        assertThat(ResourceVerbs.permits(resource, ResourceVerb.UPDATE, domain, viewer()))
            .as("a delegate without manage on the site is offered no domain editor").isFalse();
        assertThat(ResourceVerbs.permits(resource, ResourceVerb.UPDATE, domain, holder()))
            .as("a manage holder keeps its editor").isTrue();
        assertThat(ResourceVerbs.permits(resource, ResourceVerb.DELETE, domain, holder()))
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

    /**
     * A domain create under a site opens with that site's TLS defaults only where the caller may read the site: a
     * TLS passthrough site the caller cannot reach opens the create exactly as no site does, so the form is no probe
     * of another tenant's configuration (GPT review 25 D03).
     */
    @Test
    void aDomainCreateUnderAnUnreachableSiteOpensAsUnderNone() {
        int own = passthroughSite("own");
        int foreign = passthroughSite("foreign");
        RecordGrants.grant(GrantSubjectType.USER, holderId, SiteModel.MODEL_ID, own, HohenheimAccess.MANAGE, true);
        try {
            Map<String, Object> none = createDefaults(holder(), HohenheimSlugs.MANAGE, null);

            // 1. Under the holder's own passthrough site, the create opens with HTTPS forcing and ACME off.
            assertThat(createDefaults(holder(), HohenheimSlugs.MANAGE, own))
                .as("step 1: the holder's own passthrough site sets its TLS defaults")
                .containsEntry(SiteDomainModel.FORCE_SSL.getName(), false)
                .containsEntry(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName(), true);

            // 2. Under a passthrough site outside the holder's scope, the create opens exactly as under none.
            assertThat(createDefaults(holder(), HohenheimSlugs.MANAGE, foreign))
                .as("step 2: an unreachable site's configuration is never read into the form")
                .isEqualTo(none);

            // 3. The operator reaches every site, so the same site sets its defaults on /admin.
            assertThat(createDefaults(operator(), HohenheimSlugs.ADMIN, foreign))
                .as("step 3: the operator's create under that site reads it")
                .containsEntry(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName(), true);
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, holderId, SiteModel.MODEL_ID, own, HohenheimAccess.MANAGE);
            HardDeletes.byId(Models.get(SiteModel.class), own);
            HardDeletes.byId(Models.get(SiteModel.class), foreign);
        }
    }

    /** The defaults a domain create form opens with on {@code panel}, under {@code parent} when given. */
    private static Map<String, Object> createDefaults(AccessContext ctx, String panel, Integer parent) {
        EndpointConduit conduit = new EndpointConduit()
            .withAttribute(ConduitAttributes.PRINCIPAL, ctx.principal())
            .setParameter(CmsEndpoints.PANEL_PARAM, panel)
            .setParameter(CmsEndpoints.RESOURCE_PARAM, DomainParts.SLUG);
        if (parent != null) {
            conduit.setQueryParam(CmsEndpoints.PARENT_PARAM.getName(), String.valueOf(parent));
        }
        AccessContext access = AccessContext.of(conduit);
        PanelRequest request = new PanelRequest(PanelRegistry.getBySlug(panel), conduit, access, null);
        PanelResource<Row> resource = HohenheimSlugs.ADMIN.equals(panel) ? DomainParts.admin() : DomainParts.manage();
        return resource.form().createDefaults(request);
    }

    private static int passthroughSite(String name) {
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, PREFIX + "passthrough-" + name);
        site.set(SiteModel.SLUG, PREFIX + "passthrough-" + name);
        site.set(SiteModel.UPSTREAM_KIND, SiteModel.UPSTREAM_TLS_PASSTHROUGH);
        site.set(SiteModel.SETTINGS, Map.of("forward_host", "127.0.0.1", "forward_port", 8443));
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, false);
        Models.get(SiteModel.class).save(site);
        return site.get(SiteModel.ID);
    }

    /** The Domains tab's rendered (edit, remove) pair for its one domain row. */
    @SuppressWarnings("unchecked")
    private static List<Boolean> rowAffordances(Row site, AccessContext ctx) {
        // The tab renders under the panel the principal reaches: a delegate's is /manage, the operator's /admin.
        String panel = HohenheimAccess.isAdmin(ctx) ? HohenheimSlugs.ADMIN : HohenheimSlugs.MANAGE;
        // The tab dispatches through the framework's record subpage route, as a click on it does.
        EndpointConduit conduit = new EndpointConduit()
            .withAttribute(ConduitAttributes.PRINCIPAL, ctx.principal())
            .setParameter(CmsEndpoints.PANEL_PARAM, panel)
            .setParameter(CmsEndpoints.RESOURCE_PARAM, HohenheimSlugs.SITES)
            .setParameter(CmsEndpoints.RESOURCE_ID_PARAM, String.valueOf((Object) site.get(SiteModel.ID)))
            .setParameter(CmsEndpoints.SUBPAGE_PARAM, SiteParts.DOMAINS_TAB);
        Object tab = CmsEndpoints.RECORD_SUBPAGE.handle(conduit);
        if (tab == null) {
            // The panel refused the site itself: a delegate's /manage site entry lists only the sites it manages, so
            // a view-only delegate never reaches the tab, let alone a row on it.
            assertThat(conduit.status).as("the tab is refused, not broken").isIn(403, 404);
            return List.of(false, false);
        }
        Map<String, Object> vars = ((RenderTemplateResult) tab).get();
        List<ChildListSectionState> sections = (List<ChildListSectionState>) vars.get("sections");
        assertThat(sections).as("the tab embeds the one domains section").hasSize(1);
        List<TableState.RowState> rows = sections.get(0).table().rows();
        if (rows.isEmpty()) {
            // The /manage domain scope lists only the domains of sites the caller manages: a delegate without manage
            // on the site is not even shown the row, so it is offered nothing on it.
            return List.of(false, false);
        }
        assertThat(rows).as("the tab lists its one domain").hasSize(1);
        TableState.RowState row = rows.get(0);
        List<String> offered = new ArrayList<>();
        Stream.of(row.actions(), row.overflowActions(), row.destructiveActions())
            .flatMap(List::stream).forEach(action -> offered.add(String.valueOf(action.id())));
        Stream.of(row.invokeActions(), row.overflowInvokeActions(), row.destructiveInvokeActions())
            .flatMap(List::stream).forEach(action -> offered.add(String.valueOf(action.id())));
        return List.of(offered.contains("zenit:edit"), offered.contains("zenit:delete"));
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
            var resource = DnsRecordParts.admin();
            List<BiPredicate<Row, AccessContext>> predicates = resource.actions().stream()
                .filter(action -> action.id().toString().contains("dyndns"))
                .map(action -> (BiPredicate<Row, AccessContext>) (row, access) ->
                    !(OperationPipeline.offer((Operation<Row, ?, ?>) action.operation(), access, row)
                        instanceof OperationPipeline.Offer.Hidden))
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
        RowResource resource = PanelEntryViews.of(HohenheimSlugs.ADMIN, InstanceAttachmentParts.DATABASES);
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
                assertThat(InstanceScheduleStepParts.writableBy(step, ctx))
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
            RowResource resource = PanelEntryViews.of(HohenheimSlugs.ADMIN, InstanceParts.SLUG);

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
            // Every instance verb is a placed operation now: the list asks them through the render's own batched
            // offer, once for all rows, exactly as the admin list draws them.
            Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN), "the admin panel");
            PanelRequest request = new PanelRequest(admin,
                new EndpointConduit().withAttribute(ConduitAttributes.PRINCIPAL, ctx.principal()), ctx, null);
            Function<Row, List<RowOffer>> offers = PanelActionOffers.rowsForRender(
                request, InstanceParts.admin(), null, rows, ctx, null);
            for (Row row : rows) {
                sawAnAffordance |= resource.updatableBy(row, ctx);
                sawAnAffordance |= !offers.apply(row).isEmpty();
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
                HardDeletes.byId(instances, id);
            }
        }
    }

    /**
     * The schedule list asks the SAME question twice per row ({@code writableBy} and the
     * run_now operation's offer); its {@code requireManage} write gate reads identically
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
            List<Row> rows = new ArrayList<>();
            for (Integer id : ids) {
                rows.add(schedules.findById(id));
            }

            AtomicInteger finds = new AtomicInteger();
            RecordGrantModel.SCHEMA.addBeforeFindHook(ignored -> finds.incrementAndGet());
            finds.set(0);
            AccessContext ctx = holder();
            for (Row row : rows) {
                assertThat(InstanceScheduleParts.writableBy(row, ctx))
                    .as("the config holder keeps the schedule editor").isTrue();
                OperationPipeline.offer(InstanceScheduleOperations.RUN_SCHEDULE, ctx, row);
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

    /** Whether the attachment's detach is offered to this caller: the operation's own offer. */
    private static boolean detachOffered(Row link, AccessContext access) {
        return !(OperationPipeline.offer(InstanceAttachmentOperations.DELETE_DATABASE_LINK, access, link)
            instanceof OperationPipeline.Offer.Hidden);
    }
}
