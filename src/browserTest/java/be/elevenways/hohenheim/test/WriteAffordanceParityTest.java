package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.instance.InstanceAttachmentOperations;
import be.elevenways.hohenheim.server.cms.InstanceAttachmentParts;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.DatabaseParts;
import be.elevenways.hohenheim.server.cms.DnsRecordParts;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.cms.server.page.QuickAddState;
import be.elevenways.zenit.forms.common.choose.InlineCreateFieldState;
import be.elevenways.zenit.forms.common.choose.InlineCreateFormState;
import be.elevenways.zenit.forms.common.choose.InlineCreateEndpoints;
import be.elevenways.zenit.forms.common.choose.InlineCreateResult;
import be.elevenways.zenit.forms.common.choose.ZfChooserClient;
import be.elevenways.zenit.common.data.RecordSourceEndpoints;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
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
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.cms.common.render.inline.InlineEditState;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.panel.PageAddress;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.render.table.ResourceListState;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.cms.common.render.panel.ChildListSectionState;
import be.elevenways.zenit.cms.common.render.table.TableState;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.cms.server.panel.PartsForms;
import be.elevenways.zenit.cms.server.panel.ResourceVerbs;
import be.elevenways.zenit.common.edit.FieldAccess;
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
import java.util.UUID;
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

    /** Why a passthrough site's domain cannot change its TLS switches, as the forms say it in English. */
    private static final String PASSTHROUGH_REASON =
        "This site passes TLS through to the app, so its HTTPS is handled there.";

    private static UUID viewerId;
    private static UUID holderId;

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
            HohenheimCapabilities.VIEW, true);
        RecordGrants.grant(GrantSubjectType.USER, viewerId, DatabaseModel.MODEL_ID, databaseId,
            HohenheimCapabilities.VIEW, true);
        RecordGrants.grant(GrantSubjectType.USER, viewerId, DnsRecordModel.MODEL_ID, recordId,
            HohenheimCapabilities.VIEW, true);

        // The holder carries exactly what each funnel demands.
        RecordGrants.grant(GrantSubjectType.USER, holderId, InstanceModel.MODEL_ID, instanceId,
            HohenheimCapabilities.CONFIG, true);
        RecordGrants.grant(GrantSubjectType.USER, holderId, DatabaseModel.MODEL_ID, databaseId,
            HohenheimCapabilities.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, holderId, DnsRecordModel.MODEL_ID, recordId,
            HohenheimCapabilities.EDIT, true);
        RecordGrants.grant(GrantSubjectType.USER, holderId, DnsRecordModel.MODEL_ID, foreignTypeRecordId,
            HohenheimCapabilities.EDIT, true);
        RecordGrants.grant(GrantSubjectType.USER, holderId, SiteModel.MODEL_ID, siteId,
            HohenheimCapabilities.MANAGE, true);
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
        Row admin = Models.get(UserModel.class).find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(admin.get(UserModel.ID), "Test Admin")));
    }

    /** An instance UPDATE is a CONFIG act (TenantWrites.checkInstanceWrite). */
    @Test
    void theInstanceEditorFollowsConfig() {
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        var resource = PanelEntryViews.of(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES);
        Panel panel = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN));

        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, instance, viewer()))
            .as("a view-only delegate is offered no instance editor").isFalse();
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, instance, holder()))
            .as("a config holder keeps it").isTrue();
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, instance, operator()))
            .as("and the operator passes through the walk's admin row").isTrue();
    }

    /** A database DELETE is a DESTROY act (the model's before-remove hook). */
    @Test
    void theDatabaseDeleteFollowsDestroy() {
        Row database = Models.get(DatabaseModel.class).findById(databaseId);
        // A delegate reaches databases through the /manage twin, whose delete is the same operation.
        Panel manage = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.MANAGE));
        PanelResource<Row> resource = DatabaseParts.manage();

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
        var resource = PanelEntryViews.of(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_DATABASES);
        Panel panel = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN));

        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, link, viewer()))
            .as("a view-only delegate is offered no attachment editor").isFalse();
        assertThat(detachOffered(link, viewer()))
            .as("nor a detach button").isFalse();
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, link, holder()))
            .as("the two-sided holder keeps its editor").isTrue();
        assertThat(detachOffered(link, holder()))
            .as("and its detach button").isTrue();

        // ONE-SIDED: revoke the database half and the affordance must fall with it --
        // the exact laundering the two-sided funnel rule exists to refuse. revoke,
        // never grant(false): a planted deny is sticky and would outlive the finally.
        RecordGrants.revoke(GrantSubjectType.USER, holderId, DatabaseModel.MODEL_ID, databaseId,
            HohenheimCapabilities.MANAGE);
        try {
            assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, link, holder()))
                .as("instance config alone does not earn the attachment editor").isFalse();
            assertThat(detachOffered(link, holder()))
                .as("nor the detach button").isFalse();
        } finally {
            RecordGrants.grant(GrantSubjectType.USER, holderId, DatabaseModel.MODEL_ID, databaseId,
                HohenheimCapabilities.MANAGE, true);
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
        PanelResource<Row> resource = DnsRecordParts.admin();
        Panel panel = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN));

        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, editable, viewer()))
            .as("a view-only delegate is offered no record editor").isFalse();
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.DELETE, editable, viewer()))
            .as("nor a delete button").isFalse();
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, editable, holder()))
            .as("an edit-grant holder keeps its editor").isTrue();
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.DELETE, editable, holder()))
            .as("and its delete button").isTrue();
        // An edit grant reads its record (DNS VIEW is impliedBy EDIT): the delete offer's subject read loads it, and
        // the model's declared source lists it for the holder.
        Integer editableId = editable.get(DnsRecordModel.ID);
        assertThat(PartsReads.loadRows(panel, resource, List.of(editableId), holder()))
            .as("the edit-grant holder reads its record").hasSize(1);
        Criteria listed = RecordSourceRegistry.INSTANCE.requireDefaultFor(DnsRecordModel.MODEL_ID)
            .scopeCriteria(null, null, null, holder());
        assertThat(listed == null || Models.get(DnsRecordModel.class).find()
                .where(DnsRecordModel.ID.eq(editableId)).where(listed).first() != null)
            .as("and lists it through the model's source").isTrue();

        // The TYPE clause: an NS row is a zone-compromise primitive the pipeline refuses
        // for EVERY tenant writer, edit grant or not -- so no affordance either.
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, delegated, holder()))
            .as("an NS row offers no tenant editor even to an edit-grant holder")
            .isFalse();
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.DELETE, delegated, holder()))
            .as("nor a delete button").isFalse();

        // While the operator, whom the tenant lanes never gate, keeps both on both rows.
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, delegated, operator()))
            .as("the operator keeps the NS editor").isTrue();
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.DELETE, editable, operator()))
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
        Panel panel = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN));

        // 1. The resource's own answer: manage on the OWNING SITE, nothing else.
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, domain, viewer()))
            .as("a delegate without manage on the site is offered no domain editor").isFalse();
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, domain, holder()))
            .as("a manage holder keeps its editor").isTrue();
        assertThat(ResourceVerbs.permitsBy(panel, resource, ResourceVerb.DELETE, domain, holder()))
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
     * A domain created under a TLS passthrough site stores HTTPS forcing and ACME off on every create lane, a create
     * without a request included, because the rule is the domain write's, read off the written site. The create form
     * previews those switches fixed, at the values the save stores, only under a site the caller may read, so it is
     * no probe of another tenant's configuration (GPT review 25 D03).
     */
    @Test
    void aDomainCreateUnderAPassthroughSiteStoresItsTlsSwitchesOnEveryLane() throws Exception {
        int own = passthroughSite("own");
        int foreign = passthroughSite("foreign");
        RecordGrants.grant(GrantSubjectType.USER, holderId, SiteModel.MODEL_ID, own, HohenheimCapabilities.MANAGE,
            true);
        Model domains = Models.get(SiteDomainModel.class);
        List<Object> created = new ArrayList<>();
        try {
            // 1. Under the holder's own passthrough site the create form shows both switches fixed, at the values
            //    the save stores; HSTS stays the caller's choice.
            Map<String, Object> none = createDefaults(holder(), HohenheimSlugs.MANAGE, null);
            Map<String, Object> fixed = new LinkedHashMap<>(none);
            fixed.put(SiteDomainModel.FORCE_SSL.getName(), false);
            fixed.put(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName(), true);
            assertThat(createDefaults(holder(), HohenheimSlugs.MANAGE, own))
                .as("step 1: the holder's own passthrough site previews the stored switches").isEqualTo(fixed);
            assertThat(switchAccess(holder(), HohenheimSlugs.MANAGE, own))
                .as("step 1: and shows them read-only, HSTS editable")
                .containsExactly(FieldAccess.Decision.READONLY, FieldAccess.Decision.READONLY,
                    FieldAccess.Decision.EDITABLE);

            // 1b. A site the holder cannot read previews exactly as no site: editable, at the spec's defaults.
            assertThat(createDefaults(holder(), HohenheimSlugs.MANAGE, foreign))
                .as("step 1b: an unreachable site prefills nothing").isEqualTo(none);
            assertThat(switchAccess(holder(), HohenheimSlugs.MANAGE, foreign))
                .as("step 1b: and fixes nothing").containsOnly(FieldAccess.Decision.EDITABLE);

            // 1c. The operator reads every site, so the same site previews its switches on /admin.
            assertThat(createDefaults(operator(), HohenheimSlugs.ADMIN, foreign))
                .as("step 1c: the operator's create form previews the stored switches")
                .containsEntry(SiteDomainModel.FORCE_SSL.getName(), false)
                .containsEntry(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName(), true);
            assertThat(switchAccess(operator(), HohenheimSlugs.ADMIN, foreign))
                .as("step 1c: and shows them read-only").containsExactly(FieldAccess.Decision.READONLY,
                    FieldAccess.Decision.READONLY, FieldAccess.Decision.EDITABLE);

            // 1d. The create form says why both switches are fixed, in place of their own help; HSTS keeps its own.
            String createForm = adminGet("/admin/domains/new?parent=" + foreign).body();
            assertThat(entryHelp(createForm, SiteDomainModel.FORCE_SSL.getName()))
                .as("step 1d: the create form says why force_ssl is fixed").isEqualTo(PASSTHROUGH_REASON);
            assertThat(entryHelp(createForm, SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName()))
                .as("step 1d: and why the ACME exclusion is").isEqualTo(PASSTHROUGH_REASON);
            assertThat(entryHelp(createForm, SiteDomainModel.HSTS_ENABLED.getName()))
                .as("step 1d: HSTS keeps its own help").isNotEqualTo(PASSTHROUGH_REASON);

            // 1e. The Addresses list's quick-add bar under that site decides force_ssl by the create form's own access
            //     and help: fixed at the stored value, with the reason, and never posted. Under a site that terminates
            //     TLS it stays the operator's switch with its own help.
            ResourceListState.QuickAdd fixedBar = quickAddBar(operator(), foreign);
            InlineCreateFormState fixedForm = fixedBar.quickAddForm();
            InlineCreateFieldState fixedSwitch = createField(fixedForm, SiteDomainModel.FORCE_SSL.getName());
            assertThat(fixedSwitch.readonly()).as("step 1e: the bar shows force_ssl fixed").isTrue();
            assertThat(fixedSwitch.help()).as("step 1e: and says why, in the create form's words")
                .isEqualTo(HohenheimMicrocopy.HELP.of("tls_passthrough_switch"));
            assertThat(fixedBar.quickAddValues().get(SiteDomainModel.FORCE_SSL.getName()))
                .as("step 1e: at the value the save stores").isEqualTo(false);
            assertThat(ZfChooserClient.createBody(Map.of(SiteDomainModel.HOSTNAME.getName(), "bar.example.test",
                    SiteDomainModel.FORCE_SSL.getName(), true), Map.of(), "", fixedForm.fields()))
                .as("step 1e: an add posts the hostname and nothing for the fixed switch")
                .containsOnlyKeys(SiteDomainModel.HOSTNAME.getName());
            InlineCreateFieldState openSwitch = createField(quickAddBar(operator(), siteId).quickAddForm(),
                SiteDomainModel.FORCE_SSL.getName());
            assertThat(openSwitch.readonly()).as("step 1e: a TLS-terminating site's bar keeps the switch").isFalse();
            assertThat(openSwitch.help()).as("step 1e: with its own help").isNotNull()
                .isNotEqualTo(HohenheimMicrocopy.HELP.of("tls_passthrough_switch"));

            // 2. A create without a request (a job, an MCP call) under the passthrough site stores both switches.
            Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN));
            Object detachedId = ResourceWrites.create(admin, DomainParts.admin(),
                Map.of(SiteDomainModel.SITE_ID.getName(), String.valueOf(own),
                    SiteDomainModel.HOSTNAME.getName(), PREFIX + "detached.example.test"),
                operator().detach());
            created.add(detachedId);
            Row detached = domains.findById(detachedId);
            assertThat((Boolean) detached.get(SiteDomainModel.FORCE_SSL))
                .as("step 2: the detached create does not force HTTPS").isFalse();
            assertThat((Boolean) detached.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT))
                .as("step 2: the detached create asks no ACME certificate").isTrue();

            // 3. The create form keeps storing them, whatever its switches submitted.
            String hostname = PREFIX + "form.example.test";
            var response = adminPostForm("/admin/domains/new?parent=" + own,
                "site_id=" + own + "&hostname=" + hostname + "&match_type=exact&force_ssl=true");
            assertThat(response.statusCode()).as("step 3: the form create is accepted").isIn(200, 302, 303);
            Row viaForm = domains.find().where(SiteDomainModel.HOSTNAME.eq(hostname)).first();
            assertThat(viaForm).as("step 3: the form create is stored").isNotNull();
            created.add(viaForm.get(SiteDomainModel.ID));
            assertThat((Boolean) viaForm.get(SiteDomainModel.FORCE_SSL))
                .as("step 3: the form create does not force HTTPS").isFalse();
            assertThat((Boolean) viaForm.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT))
                .as("step 3: the form create asks no ACME certificate").isTrue();

            // 4. The quick-add bar's add is decided on the server as that create form decides it, under the site its
            //    context names: a crafted force_ssl=1 naming another site is stored off and under the bar's site. The
            //    refresh after the add keeps the site, so the next add there lands under it too.
            ResourceListState.QuickAdd ownBar = quickAddBar(operator(), own);
            assertThat(ownBar.quickAddContext()).as("step 4: the bar's context is its site")
                .containsEntry(SiteDomainModel.SITE_ID.getName(), String.valueOf(own));
            assertThat(ownBar.quickAddRefreshUrl()).as("step 4: the refresh after an add keeps it")
                .isEqualTo("/admin/domains?site_id=" + own);
            String crafted = PREFIX + "crafted.example.test";
            InlineCreateResult forged = quickAdd(operator(), own, Map.of(SiteDomainModel.HOSTNAME.getName(), crafted,
                SiteDomainModel.FORCE_SSL.getName(), true, SiteDomainModel.SITE_ID.getName(), foreign));
            assertThat(forged.succeeded()).as("step 4: the crafted add is stored: " + forged.violations()).isTrue();
            Row viaBar = domains.find().where(SiteDomainModel.HOSTNAME.eq(crafted)).first();
            assertThat(viaBar).as("step 4: the crafted add is stored").isNotNull();
            created.add(viaBar.get(SiteDomainModel.ID));
            assertThat((Boolean) viaBar.get(SiteDomainModel.FORCE_SSL))
                .as("step 4: the crafted force_ssl=1 is stored off").isFalse();
            assertThat(viaBar.get(SiteDomainModel.SITE_ID)).as("step 4: under the bar's site, never the posted one")
                .isEqualTo(own);
            String next = PREFIX + "next.example.test";
            InlineCreateResult second = quickAdd(operator(), own, Map.of(SiteDomainModel.HOSTNAME.getName(), next));
            assertThat(second.succeeded()).as("step 4: the next add succeeds: " + second.violations()).isTrue();
            Row nextRow = domains.find().where(SiteDomainModel.HOSTNAME.eq(next)).first();
            assertThat(nextRow).as("step 4: the next add is stored").isNotNull();
            created.add(nextRow.get(SiteDomainModel.ID));
            assertThat(nextRow.get(SiteDomainModel.SITE_ID)).as("step 4: under the same site").isEqualTo(own);
        } finally {
            for (Object id : created) {
                domains.delete(id);
            }
            RecordGrants.revoke(GrantSubjectType.USER, holderId, SiteModel.MODEL_ID, own, HohenheimCapabilities.MANAGE);
            HardDeletes.byId(Models.get(SiteModel.class), own);
            HardDeletes.byId(Models.get(SiteModel.class), foreign);
        }
    }

    /**
     * A stored domain under a TLS passthrough site shows force_ssl and the ACME exclusion fixed at their stored values
     * on every edit surface (the edit form, the inline cells both domain lists ask), by the rule its save applies; a
     * domain under any other site keeps them editable.
     */
    @Test
    void aDomainUnderAPassthroughSiteShowsItsTlsSwitchesFixedOnEveryEditSurface() throws Exception {
        int site = passthroughSite("edit");
        RecordGrants.grant(GrantSubjectType.USER, holderId, SiteModel.MODEL_ID, site, HohenheimCapabilities.MANAGE,
            true);
        Model domains = Models.get(SiteDomainModel.class);
        Row stored = domains.createEmptyRow();
        stored.set(SiteDomainModel.SITE_ID, site);
        stored.set(SiteDomainModel.HOSTNAME, PREFIX + "edit.example.test");
        stored.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domains.save(stored);
        Object fixedId = stored.get(SiteDomainModel.ID);
        try {
            // 1. The edit form draws both switches as read-only values, at what the save stored; HSTS stays a switch.
            String form = adminGet("/admin/domains/" + fixedId).body();
            assertThat(readonlyValue(form, SiteDomainModel.FORCE_SSL.getName()))
                .as("step 1: the edit form shows force_ssl fixed off").isEqualTo("No");
            assertThat(readonlyValue(form, SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName()))
                .as("step 1: and the ACME exclusion fixed on").isEqualTo("Yes");
            assertThat(readonlyValue(form, SiteDomainModel.HSTS_ENABLED.getName()))
                .as("step 1: HSTS stays the operator's switch").isNull();
            assertThat(entryHelp(form, SiteDomainModel.FORCE_SSL.getName()))
                .as("step 1: the edit form says why force_ssl is fixed").isEqualTo(PASSTHROUGH_REASON);
            assertThat(entryHelp(form, SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName()))
                .as("step 1: and why the ACME exclusion is").isEqualTo(PASSTHROUGH_REASON);

            // 2. The inline cells (the Domains list and the site's Domains tab ask the same per-record state) offer
            //    only HSTS, and carry the stored switch values.
            InlineEditState cells = cellState("/admin/domains/" + fixedId + "/cell-state");
            assertThat(cells.editableFields()).as("step 2: only HSTS is editable in place")
                .containsExactly(SiteDomainModel.HSTS_ENABLED.getName(), SiteDomainModel.HSTS_SUBDOMAINS.getName());
            assertThat(String.valueOf(cells.values().get(SiteDomainModel.FORCE_SSL.getName())))
                .as("step 2: the force_ssl cell shows the stored value").isEqualTo("false");
            assertThat(String.valueOf(cells.values().get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName())))
                .as("step 2: the ACME cell shows the stored value").isEqualTo("true");

            // 3. The delegated twin answers the same for the site's manage holder.
            Row domain = domains.findById(fixedId);
            assertThat(recordSwitchAccess(holder(), HohenheimSlugs.MANAGE, domain))
                .as("step 3: /manage fixes the same switches").containsExactly(FieldAccess.Decision.READONLY,
                    FieldAccess.Decision.READONLY, FieldAccess.Decision.EDITABLE);

            // 4. A domain under a site that terminates TLS keeps every switch editable on every surface.
            String normal = adminGet("/admin/domains/" + domainId).body();
            assertThat(readonlyValue(normal, SiteDomainModel.FORCE_SSL.getName()))
                .as("step 4: a normal site's domain keeps its force_ssl switch").isNull();
            assertThat(readonlyValue(normal, SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName()))
                .as("step 4: and its ACME switch").isNull();
            assertThat(entryHelp(normal, SiteDomainModel.FORCE_SSL.getName()))
                .as("step 4: a normal site's force_ssl keeps its own help").isNotNull()
                .isNotEqualTo(PASSTHROUGH_REASON);
            assertThat(cellState("/admin/domains/" + domainId + "/cell-state").editableFields())
                .as("step 4: and every inline cell").containsExactly(SiteDomainModel.FORCE_SSL.getName(),
                    SiteDomainModel.HSTS_ENABLED.getName(), SiteDomainModel.HSTS_SUBDOMAINS.getName(),
                    SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName());
            assertThat(recordSwitchAccess(holder(), HohenheimSlugs.MANAGE, domains.findById(domainId)))
                .as("step 4: on /manage too").containsOnly(FieldAccess.Decision.EDITABLE);
        } finally {
            domains.delete(fixedId);
            RecordGrants.revoke(GrantSubjectType.USER, holderId, SiteModel.MODEL_ID, site,
                HohenheimCapabilities.MANAGE);
            HardDeletes.byId(Models.get(SiteModel.class), site);
        }
    }

    /** How an edit of {@code domain} on {@code panel} draws force_ssl, the ACME exclusion and HSTS. */
    private static List<FieldAccess.Decision> recordSwitchAccess(AccessContext ctx, String panel, Row domain) {
        Map<String, FieldAccess> access = PartsForms.fieldAccessByPath(domainResource(panel));
        List<FieldAccess.Decision> decisions = new ArrayList<>();
        for (var field : List.of(SiteDomainModel.FORCE_SSL, SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT,
                SiteDomainModel.HSTS_ENABLED)) {
            decisions.add(access.getOrDefault(field.getName(), FieldAccess.ALWAYS_EDITABLE).decide(ctx, domain));
        }
        return decisions;
    }

    /** The text a form draws for entry {@code path} when it is read-only, null when the entry is an input. */
    private static String readonlyValue(String html, String path) {
        int start = html.indexOf("data-path=\"" + path + "\"");
        assertThat(start).as("the form draws entry " + path).isNotNegative();
        int end = html.indexOf("data-path=\"", start + 1);
        String entry = html.substring(start, end < 0 ? html.length() : end);
        String marker = "class=\"zf-field-readonly\">";
        int value = entry.indexOf(marker);
        if (value < 0) {
            return null;
        }
        int from = value + marker.length();
        // The shown value is microcopy markup inside the read-only box: its text is what the reader sees.
        return entry.substring(from, entry.indexOf("</div>", from)).replaceAll("<[^>]+>", "").trim();
    }

    /** The help a form draws under entry {@code path}, null when it draws none. */
    private static String entryHelp(String html, String path) {
        int start = html.indexOf("data-path=\"" + path + "\"");
        assertThat(start).as("the form draws entry " + path).isNotNegative();
        int end = html.indexOf("data-path=\"", start + 1);
        String entry = html.substring(start, end < 0 ? html.length() : end);
        String marker = "<pl-field-description>";
        int help = entry.indexOf(marker);
        if (help < 0) {
            return null;
        }
        int from = help + marker.length();
        return entry.substring(from, entry.indexOf("</pl-field-description>", from)).replaceAll("<[^>]+>", "").trim();
    }

    /** The inline editor's state for one domain, as the harness admin. */
    private InlineEditState cellState(String path) throws Exception {
        var response = adminGet(path);
        assertThat(response.statusCode()).as("the cell state answered").isEqualTo(200);
        return (InlineEditState) Zenit.DRY.parse(response.body());
    }

    /**
     * The defaults a domain create form opens with on {@code panel}, under {@code parent} when given; the panel is
     * the hook's argument only, never a conduit parameter.
     */
    private static Map<String, Object> createDefaults(AccessContext ctx, String panel, Integer parent) {
        return domainResource(panel).form().createDefaults(createAccess(ctx, parent),
            Objects.requireNonNull(PanelRegistry.getBySlug(panel)));
    }

    /** How a domain create form on {@code panel} under {@code parent} draws force_ssl, the ACME exclusion and HSTS. */
    private static List<FieldAccess.Decision> switchAccess(AccessContext ctx, String panel, Integer parent) {
        Map<String, FieldAccess> access = PartsForms.fieldAccessByPath(domainResource(panel));
        AccessContext create = createAccess(ctx, parent);
        List<FieldAccess.Decision> decisions = new ArrayList<>();
        for (var field : List.of(SiteDomainModel.FORCE_SSL, SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT,
                SiteDomainModel.HSTS_ENABLED)) {
            decisions.add(access.getOrDefault(field.getName(), FieldAccess.ALWAYS_EDITABLE).decide(create, null));
        }
        return decisions;
    }

    /** The Addresses list's quick-add bar on /admin, as the list renders it under {@code site}'s preset. */
    private static ResourceListState.QuickAdd quickAddBar(AccessContext ctx, int site) {
        EndpointConduit conduit = new EndpointConduit()
            .withAttribute(ConduitAttributes.PRINCIPAL, ctx.principal())
            .setParameter(CmsEndpoints.RESOURCE_PARAM, HohenheimSlugs.DOMAINS);
        conduit.setQueryParam(SiteDomainModel.SITE_ID.getName(), String.valueOf(site));
        ResourceListState.QuickAdd bar = QuickAddState.of(
            Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN)), DomainParts.admin(),
            AccessContext.of(conduit), PageAddress.of(conduit), "/admin/domains", "/admin/domains/new");
        assertThat(bar.quickAddEnabled()).as("the Addresses list offers its bar under the site").isTrue();
        return bar;
    }

    private static InlineCreateFieldState createField(InlineCreateFormState form, String name) {
        return form.fields().stream().filter(field -> field.name().equals(name)).findFirst().orElseThrow();
    }

    /** The Addresses bar's add on /admin as the bar posts it under {@code site}: its context as the submit's query. */
    @SuppressWarnings("unchecked")
    private static InlineCreateResult quickAdd(AccessContext ctx, int site, Map<String, Object> body) {
        Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN));
        EndpointConduit conduit = new EndpointConduit()
            .withAttribute(ConduitAttributes.PRINCIPAL, ctx.principal())
            .setParameter(RecordSourceEndpoints.SOURCE_PARAM, DomainParts.admin().sourceIdIn(admin))
            .setBody(new LinkedHashMap<>(body));
        conduit.setQueryParam(SiteDomainModel.SITE_ID.getName(), String.valueOf(site));
        ActionResult<InlineCreateResult> added =
            (ActionResult<InlineCreateResult>) InlineCreateEndpoints.CREATE_SUBMIT.handle(conduit);
        assertThat(added).as("the quick add answers").isNotNull();
        return added.get();
    }

    private static PanelResource<Row> domainResource(String panel) {
        return HohenheimSlugs.ADMIN.equals(panel) ? DomainParts.admin() : DomainParts.manage();
    }

    /** The caller on a domain create request, under {@code parent} when given. */
    private static AccessContext createAccess(AccessContext ctx, Integer parent) {
        EndpointConduit conduit = new EndpointConduit()
            .withAttribute(ConduitAttributes.PRINCIPAL, ctx.principal())
            .setParameter(CmsEndpoints.RESOURCE_PARAM, HohenheimSlugs.DOMAINS);
        if (parent != null) {
            conduit.setQueryParam(CmsEndpoints.PARENT_PARAM.getName(), String.valueOf(parent));
        }
        return AccessContext.of(conduit);
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
            HohenheimCapabilities.DYNDNS, true);
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
                HohenheimCapabilities.DYNDNS);
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
        var resource = PanelEntryViews.of(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_DATABASES);
        Panel panel = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN));
        Row link = Models.get(InstanceDatabaseModel.class).findById(linkId);

        AtomicInteger finds = new AtomicInteger();
        RecordGrantModel.SCHEMA.addBeforeFindHook(ignored -> finds.incrementAndGet());
        finds.set(0);
        AccessContext ctx = holder();
        for (int i = 0; i < 6; i++) {
            ResourceVerbs.permitsBy(panel, resource, ResourceVerb.UPDATE, link, ctx);
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
        schedule.set(RecordScheduleModel.RUN_AS, holderId);
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
            HohenheimCapabilities.POWER, true);
        try {
            var resource = PanelEntryViews.of(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES);

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
                request, resource, null, rows, ctx, null);
            for (Row row : rows) {
                sawAnAffordance |= ResourceVerbs.permitsBy(admin, resource, ResourceVerb.UPDATE, row, ctx);
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
                instanceId, HohenheimCapabilities.POWER);
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
            schedule.set(RecordScheduleModel.RUN_AS, holderId);
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
