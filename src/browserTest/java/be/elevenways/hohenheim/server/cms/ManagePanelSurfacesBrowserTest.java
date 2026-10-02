package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.test.support.PanelSurfaceComparer;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.cms.common.resource.ListLane;
import be.elevenways.zenit.cms.test.support.TwinCorrespondence;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The slice-three entries' surfaces, admin and tenant twins, stored before the move and compared exactly after it;
 * each tenant twin against its admin twin through its explicit difference table (stage 4 contract 4.9, journey "the
 * tenant panel offers exactly what it offers today", DECIDED D2-B07/B08).
 *
 * AIDEV-NOTE: the stored set ({@code /panel-surfaces/manage-slice-three.txt}) is today's behaviour, captured on the
 * stage 4 branch before SiteResource, SiteDomainResource and the template resources move onto parts (recaptured once
 * with the complete control, confirmation and destination facts, still before the move); a failing
 * comparison is a changed /manage or /admin surface, never a file to refresh. A twin table entry states one
 * deliberate tenant difference; an unlisted difference fails closed.
 */
class ManagePanelSurfacesBrowserTest extends HohenheimTestBase {

    private static final String PREFIX = "surfaces-";
    private static final String ADMIN = HohenheimSlugs.ADMIN;
    private static final String MANAGE = HohenheimSlugs.MANAGE;
    private static final String SITES = HohenheimSlugs.SITES;
    private static final String DOMAINS = "domains";
    private static final String TEMPLATES = HohenheimSlugs.INSTANCE_TEMPLATES;
    private static final String DEVICES = "instance-devices";
    /** The plain Select binding the device type control carries on both twins. */
    private static final String SELECT_BINDING = " binding=searchable:false,clearable:true,presentation:DEFAULT";

    private static String siteId;
    private static String disabledSiteId;
    private static String trashedSiteId;
    private static String domainId;
    private static String trashedDomainId;
    private static String approvedTemplateId;
    private static String unapprovedTemplateId;
    private static String instanceId;
    private static String diskId;
    private static String cdromId;
    private static String unknownDeviceId;
    private static AccessContext tenantView;
    private static AccessContext tenantConfig;
    private static AccessContext operator;
    private static AccessContext tenantOne;
    private static AccessContext tenantEmpty;

    @BeforeAll
    static void seed() {
        int oneId = ApiSupport.user(PREFIX + "one@hohenheim.local", "Surfaces Tenant One");
        int emptyId = ApiSupport.user(PREFIX + "empty@hohenheim.local", "Surfaces Tenant Empty");
        int site = site(PREFIX + "site", true);
        siteId = String.valueOf(site);
        domainId = String.valueOf(domain(site, PREFIX + "site.surfaces.test"));
        int disabled = site(PREFIX + "disabled", false);
        disabledSiteId = String.valueOf(disabled);
        int trashed = site(PREFIX + "trashed", true);
        trashedSiteId = String.valueOf(trashed);
        trashedDomainId = String.valueOf(domain(trashed, PREFIX + "trashed.surfaces.test"));
        Row trashedRow = Models.get(SiteModel.class).findById(trashed);
        trashedRow.set(SiteModel.DELETED_AT, Now.instant());
        Models.get(SiteModel.class).save(trashedRow);
        approvedTemplateId = String.valueOf(template(PREFIX + "approved", true));
        unapprovedTemplateId = String.valueOf(template(PREFIX + "unapproved", false));
        RecordGrants.grant(GrantSubjectType.USER, oneId, SiteModel.MODEL_ID, site, HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, oneId, SiteModel.MODEL_ID, disabled, HohenheimAccess.MANAGE, true);
        int viewId = ApiSupport.user(PREFIX + "view@hohenheim.local", "Surfaces Instance Viewer");
        int configId = ApiSupport.user(PREFIX + "config@hohenheim.local", "Surfaces Instance Configurer");
        int instance = instance(PREFIX + "instance");
        instanceId = String.valueOf(instance);
        diskId = String.valueOf(device(instance, InstanceDeviceModel.TYPE_DISK, "data"));
        cdromId = String.valueOf(device(instance, InstanceDeviceModel.TYPE_CDROM, "install"));
        unknownDeviceId = String.valueOf(unknownDevice(instance));
        RecordGrants.grant(GrantSubjectType.USER, viewId, InstanceModel.MODEL_ID, instance, HohenheimAccess.VIEW,
            true);
        RecordGrants.grant(GrantSubjectType.USER, configId, InstanceModel.MODEL_ID, instance,
            HohenheimAccess.CONFIG, true);
        tenantView = access(new UserPrincipal(viewId, "Surfaces Instance Viewer"));
        tenantConfig = access(new UserPrincipal(configId, "Surfaces Instance Configurer"));
        operator = access(operatorPrincipal());
        tenantOne = access(new UserPrincipal(oneId, "Surfaces Tenant One"));
        tenantEmpty = access(new UserPrincipal(emptyId, "Surfaces Tenant Empty"));
    }

    @Test
    void theSliceThreeEntriesOfferWhatTheyOfferedBeforeTheMove() {
        SurfaceBaselines stored = SurfaceBaselines.load(ManagePanelSurfacesBrowserTest.class,
            "/panel-surfaces/manage-slice-three.txt");

        // 1. The admin entries for the operator, record-less and on each record; a tenant is refused the panel.
        for (String entry : List.of(SITES, DOMAINS, TEMPLATES)) {
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "operator", operator)));
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "tenant-one", tenantOne)
                .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        }
        stored.check(capture(SurfaceCase.of(ADMIN, SITES, "operator", operator)
            .onRecord(siteId, "site")));
        stored.check(capture(SurfaceCase.of(ADMIN, DOMAINS, "operator", operator)
            .onRecord(domainId, "domain")));
        stored.check(capture(SurfaceCase.of(ADMIN, TEMPLATES, "operator", operator)
            .onRecord(approvedTemplateId, "approved")));
        stored.check(capture(SurfaceCase.of(ADMIN, TEMPLATES, "operator", operator)
            .onRecord(unapprovedTemplateId, "unapproved")));
        // An enabled and a disabled site (the toggle's two states), the trash lane on a trashed site, a domain under
        // that trashed parent, the domain create form prefilled with its parent, and bulk selections per lane.
        stored.check(capture(SurfaceCase.of(ADMIN, SITES, "operator", operator)
            .onRecord(disabledSiteId, "disabled")));
        stored.check(capture(SurfaceCase.of(ADMIN, SITES, "operator", operator).inLane(ListLane.TRASH)));
        stored.check(capture(SurfaceCase.of(ADMIN, SITES, "operator", operator).inLane(ListLane.TRASH)
            .onRecord(trashedSiteId, "trashed").selecting(List.of(trashedSiteId), "sel")));
        stored.check(capture(SurfaceCase.of(ADMIN, SITES, "operator", operator)
            .selecting(List.of(siteId, disabledSiteId), "sel")));
        stored.check(capture(SurfaceCase.of(ADMIN, DOMAINS, "operator", operator)
            .onRecord(trashedDomainId, "trashed-parent")));
        stored.check(capture(SurfaceCase.of(ADMIN, DOMAINS, "operator", operator).named(ADMIN + "." + DOMAINS
            + ".operator.prefill").withParameter(HohenheimParams.SITE_ID_PREFILL.getName(), siteId)));
        stored.check(capture(SurfaceCase.of(ADMIN, DOMAINS, "operator", operator)
            .selecting(List.of(domainId, trashedDomainId), "sel")));
        stored.check(capture(SurfaceCase.of(ADMIN, TEMPLATES, "operator", operator)
            .selecting(List.of(approvedTemplateId, unapprovedTemplateId), "sel")));

        // 2. The /manage twins for a one-site tenant, record-less and on each record; the panel refuses a tenant
        //    holding nothing.
        for (String entry : List.of(SITES, DOMAINS, TEMPLATES)) {
            stored.check(capture(SurfaceCase.of(MANAGE, entry, "tenant-one", tenantOne)));
            stored.check(capture(SurfaceCase.of(MANAGE, entry, "tenant-empty", tenantEmpty)
                .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        }
        stored.check(capture(SurfaceCase.of(MANAGE, SITES, "tenant-one", tenantOne)
            .onRecord(siteId, "site")));
        stored.check(capture(SurfaceCase.of(MANAGE, DOMAINS, "tenant-one", tenantOne)
            .onRecord(domainId, "domain")));
        stored.check(capture(SurfaceCase.of(MANAGE, TEMPLATES, "tenant-one", tenantOne)
            .onRecord(approvedTemplateId, "approved")));
        stored.check(capture(SurfaceCase.of(MANAGE, TEMPLATES, "tenant-one", tenantOne)
            .onRecord(unapprovedTemplateId, "unapproved")));
        stored.check(capture(SurfaceCase.of(MANAGE, SITES, "tenant-one", tenantOne)
            .onRecord(disabledSiteId, "disabled")));
        stored.check(capture(SurfaceCase.of(MANAGE, SITES, "tenant-one", tenantOne)
            .onRecord(trashedSiteId, "trashed")));
        stored.check(capture(SurfaceCase.of(MANAGE, SITES, "tenant-one", tenantOne)
            .selecting(List.of(siteId, disabledSiteId, trashedSiteId), "sel")));
        stored.check(capture(SurfaceCase.of(MANAGE, DOMAINS, "tenant-one", tenantOne).named(MANAGE + "." + DOMAINS
            + ".tenant-one.prefill").withParameter(HohenheimParams.SITE_ID_PREFILL.getName(), siteId)));
        stored.check(capture(SurfaceCase.of(MANAGE, DOMAINS, "tenant-one", tenantOne)
            .selecting(List.of(domainId, trashedDomainId), "sel")));
        stored.check(capture(SurfaceCase.of(MANAGE, TEMPLATES, "tenant-one", tenantOne)
            .selecting(List.of(approvedTemplateId, unapprovedTemplateId), "sel")));

        // 3. Each tenant twin against its admin twin on the same record, through its explicit difference table.
        List<AssertionError> twins = new ArrayList<>();
        twin(twins, stored, MANAGE + "." + SITES + ".tenant-one.site", ADMIN + "." + SITES + ".operator.site",
            () -> sitesTable());
        twin(twins, stored, MANAGE + "." + SITES + ".tenant-one.disabled", ADMIN + "." + SITES + ".operator.disabled",
            () -> sitesTable());
        twin(twins, stored, MANAGE + "." + DOMAINS + ".tenant-one.domain", ADMIN + "." + DOMAINS + ".operator.domain",
            () -> domainsTable());
        twin(twins, stored, MANAGE + "." + TEMPLATES + ".tenant-one.approved",
            ADMIN + "." + TEMPLATES + ".operator.approved", () -> templatesTable());

        // 4. Every stored case matched exactly, and every twin difference is listed.
        List<String> failures = new ArrayList<>();
        try {
            stored.finish();
        } catch (AssertionError mismatch) {
            failures.add(mismatch.getMessage());
        }
        twins.forEach(twin -> failures.add(twin.getMessage()));
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("\n\n", failures));
        }
    }

    /**
     * A capture whose domain listen_on Select writes the host's own addresses as one element: no stored set can pin
     * them, while any other choice it offers stays compared.
     */
    private static PanelSurfaces capture(SurfaceCase fixture) {
        SurfaceCase keyed = fixture.hostOptions("listen_on", UpdateSystemIpAddresses.getLocalAddresses());
        // Every generated fixture id at the bindings a destination carries it (an entry's key segment, a query
        // parameter), so a stored capture pins it across runs while every other value stays literal.
        if (DEVICES.equals(fixture.entrySlug())) {
            keyed = keyed.key(DEVICES, "disk", diskId).key(DEVICES, "cdrom", cdromId)
                .key(DEVICES, "unknown_device", unknownDeviceId).key(HohenheimSlugs.INSTANCES, "instance", instanceId)
                .key("parent", "instance", instanceId).key("instance_id", "instance", instanceId);
        } else {
            keyed = keyed.key(SITES, "site", siteId).key(SITES, "disabled_site", disabledSiteId)
                .key(SITES, "trashed_site", trashedSiteId).key(DOMAINS, "domain", domainId)
                .key(DOMAINS, "trashed_domain", trashedDomainId).key(TEMPLATES, "approved_template", approvedTemplateId)
                .key(TEMPLATES, "unapproved_template", unapprovedTemplateId)
                .key("site_id", "site", siteId).key("parent", "site", siteId)
                .key("template", "approved_template", approvedTemplateId)
                .key("template", "unapproved_template", unapprovedTemplateId);
        }
        return PanelSurfaces.capture(keyed);
    }

    @Test
    void theInstanceDeviceTwinsOfferWhatTheyOfferedBeforeTheMove() {
        SurfaceBaselines stored = SurfaceBaselines.load(ManagePanelSurfacesBrowserTest.class,
            "/panel-surfaces/manage-instance-devices.txt");
        Map<String, String> devices = Map.of("disk", diskId, "cdrom", cdromId, "unknown", unknownDeviceId);

        // 1. The admin device entry and its tenant twin, for a VIEW-only and a CONFIG delegate of the instance, on a
        //    known, an operator-only and an unknown device type.
        stored.check(capture(SurfaceCase.of(ADMIN, DEVICES, "operator", operator)));
        stored.check(capture(SurfaceCase.of(MANAGE, DEVICES, "tenant-view", tenantView)));
        stored.check(capture(SurfaceCase.of(MANAGE, DEVICES, "tenant-config", tenantConfig)));
        for (String type : List.of("disk", "cdrom", "unknown")) {
            stored.check(capture(SurfaceCase.of(ADMIN, DEVICES, "operator", operator)
                .onRecord(devices.get(type), type)));
            stored.check(capture(SurfaceCase.of(MANAGE, DEVICES, "tenant-view", tenantView)
                .onRecord(devices.get(type), type)));
            stored.check(capture(SurfaceCase.of(MANAGE, DEVICES, "tenant-config", tenantConfig)
                .onRecord(devices.get(type), type)));
        }

        // 2. The CONFIG delegate's twin against the admin's, per device type, through the one difference table.
        List<AssertionError> twins = new ArrayList<>();
        for (String type : List.of("disk", "cdrom", "unknown")) {
            twin(twins, stored, MANAGE + "." + DEVICES + ".tenant-config." + type,
                ADMIN + "." + DEVICES + ".operator." + type, () -> devicesTable(type));
        }

        // 3. Every stored case matched exactly, and every twin difference is listed.
        List<String> failures = new ArrayList<>();
        try {
            stored.finish();
        } catch (AssertionError mismatch) {
            failures.add(mismatch.getMessage());
        }
        twins.forEach(twin -> failures.add(twin.getMessage()));
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("\n\n", failures));
        }
    }

    /**
     * The /manage device twin's deliberate differences from the admin device resource on one device's record.
     *
     * AIDEV-NOTE: install media is operator-only, so the tenant's type Select never offers cdrom, except as the
     * stored value of a device already carrying it; an unknown stored type stays offered as itself on both sides.
     */
    private static TwinCorrespondence devicesTable(String type) {
        TwinCorrespondence table = TwinCorrespondence.between(MANAGE + "/" + DEVICES, ADMIN + "/" + DEVICES)
            .own("control CREATE%20type kind=zenitforms:form/select input=select required=false readonly=false"
                + " options=disk,nic" + SELECT_BINDING);
        return switch (type) {
            case "disk" -> table
                .own("control EDIT%20type kind=zenitforms:form/select input=select required=false readonly=false"
                    + " options=disk,nic" + SELECT_BINDING);
            case "unknown" -> table.own("control EDIT%20type kind=zenitforms:form/select input=select"
                + " required=false readonly=false options=disk,nic,floppy" + SELECT_BINDING);
            case "cdrom" -> table;
            default -> throw new IllegalArgumentException("No device fixture " + type);
        };
    }

    /** A twin comparison whose failure, or whose table refusing a stale line, joins the run's failures. */
    private static void twin(List<AssertionError> twins, SurfaceBaselines stored, String tenantCase,
                             String adminCase, Supplier<TwinCorrespondence> table) {
        try {
            PanelSurfaceComparer.assertNarrower(stored.captured(tenantCase), stored.captured(adminCase), table.get());
        } catch (AssertionError difference) {
            twins.add(difference);
        } catch (IllegalArgumentException stale) {
            twins.add(new AssertionError("Twin " + tenantCase + " table: " + stale.getMessage(), stale));
        }
    }

    /** The /manage site twin's deliberate differences from the admin site resource. */
    private static TwinCorrespondence sitesTable() {
        return TwinCorrespondence.between(MANAGE + "/" + SITES, ADMIN + "/" + SITES)
            // ListChrome.MINIMAL, no trash on /manage.
            .own("chrome - views=false advanced=false search=true columns=false rail=false selection=false"
                + " export=false trash=false tree=false searchable=true")
            // Its own plain name column (no slug subtext, no filter) and a shown enabled column.
            .own("column name shown=true hidden=false sortable=false filterable=false copyable=false subtext="
                + " relation=false")
            .own("column enabled shown=true hidden=false sortable=false filterable=false copyable=false subtext="
                + " relation=false");
    }

    /** The /manage domain twin's deliberate differences from the admin domain resource. */
    private static TwinCorrespondence domainsTable() {
        return TwinCorrespondence.between(MANAGE + "/" + DOMAINS, ADMIN + "/" + DOMAINS);
    }

    /** The /manage template twin's deliberate differences from the admin template resource. */
    private static TwinCorrespondence templatesTable() {
        return TwinCorrespondence.between(MANAGE + "/" + TEMPLATES, ADMIN + "/" + TEMPLATES)
            // Its own list: name with a description subtext, a hidden description source and a shown version.
            .own("column name shown=true hidden=false sortable=false filterable=false copyable=false"
                + " subtext=description relation=false")
            .own("column description shown=false hidden=true sortable=false filterable=false copyable=false subtext="
                + " relation=false")
            .own("column version shown=true hidden=false sortable=false filterable=false copyable=false subtext="
                + " relation=false")
            // A tenant reads an approved template's form; it never edits it.
            .own("control EDIT%20name kind=zenitforms:form/plain input=text required=true readonly=true options="
                + " binding=")
            .own("control EDIT%20description kind=zenitforms:form/plain input=multiline required=false readonly=true"
                + " options= binding=")
            .own("control EDIT%20version kind=zenitforms:form/plain input=number required=false readonly=true"
                + " options= binding=");
    }

    private static AccessContext access(UserPrincipal principal) {
        return AccessContext.of(TenantConduits.stubFor(principal));
    }

    private static UserPrincipal operatorPrincipal() {
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }

    private static int site(String slug, boolean enabled) {
        Model sites = Models.get(SiteModel.class);
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, enabled);
        sites.save(row);
        return row.get(SiteModel.ID);
    }

    private static int domain(int site, String hostname) {
        Model domains = Models.get(SiteDomainModel.class);
        Row row = domains.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, site);
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        row.set(SiteDomainModel.FORCE_SSL, false);
        domains.save(row);
        return row.get(SiteDomainModel.ID);
    }

    private static int instance(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int device(int instance, String type, String name) {
        Model devices = Models.get(InstanceDeviceModel.class);
        Row row = devices.createEmptyRow();
        row.set(InstanceDeviceModel.INSTANCE_ID, instance);
        row.set(InstanceDeviceModel.TYPE, type);
        row.set(InstanceDeviceModel.NAME, name);
        if (InstanceDeviceModel.TYPE_CDROM.equals(type)) {
            row.set(InstanceDeviceModel.SOURCE_MEDIA, "install-media.iso");
        } else {
            row.set(InstanceDeviceModel.SIZE_GB, 1);
        }
        devices.save(row);
        return row.get(InstanceDeviceModel.ID);
    }

    /** A device row whose stored type no DeviceType declares: the write path refuses one, so it is stored raw. */
    private static int unknownDevice(int instance) {
        int id = device(instance, InstanceDeviceModel.TYPE_NIC, "legacy");
        Models.get(InstanceDeviceModel.class).find().where(InstanceDeviceModel.ID.eq(id))
            .assign(InstanceDeviceModel.TYPE, "floppy").updateAll();
        return id;
    }

    private static int template(String name, boolean approved) {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row row = templates.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.DESCRIPTION, "surfaces fixture");
        row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        row.set(InstanceTemplateModel.VERSION, 1);
        row.set(InstanceTemplateModel.READINESS_KIND, ReadinessKind.CONSOLE_LINE.token());
        row.set(InstanceTemplateModel.READINESS_LINE, "ready");
        row.set(InstanceTemplateModel.STOP_COMMAND, "stop");
        if (approved) {
            row.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
        }
        templates.save(row);
        return row.get(InstanceTemplateModel.ID);
    }
}
