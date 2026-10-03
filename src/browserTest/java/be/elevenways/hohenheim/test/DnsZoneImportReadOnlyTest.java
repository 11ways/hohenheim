package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.cms.DnsRecordParts;
import be.elevenways.hohenheim.server.cms.DnsZoneFilePage;
import be.elevenways.hohenheim.server.cms.DnsZoneParts;
import be.elevenways.hohenheim.server.cms.SiteParts;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.cms.common.access.AccessRefusedException;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.render.CmsRefusalCopy;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.test.support.TestPermissions;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A read-only zone takes no zone-file import: the tab offers none, and the import refuses with zenit-cms's
 * archived-parent refusal before it writes a record.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class DnsZoneImportReadOnlyTest extends HohenheimTestBase {

    @Test
    void aReadOnlyZoneTakesNoZoneFileImportJourney() {
        int zoneId = DnsFixtures.createZone("import-readonly.example");

        // 1. The zone-file tab offers its import on a writable zone and none on a read-only one.
        assertThat(renderZoneFileTab(zoneId, false).get("writable"))
            .as("step 1: a writable zone offers the import").isEqualTo(true);
        assertThat(renderZoneFileTab(zoneId, true).get("writable"))
            .as("step 1: a read-only zone offers none").isEqualTo(false);

        // 2. The import asks zenit-cms before it writes: a live zone in the operator panel takes it.
        AccessContext operator = AccessContext.of(TenantConduits.stubFor(adminPrincipal()));
        assertThatCode(() -> DnsRecordParts.requireImportable(ApiConduits.adminPanel(), zoneId, operator))
            .as("step 2: a live zone takes an import").doesNotThrowAnyException();

        // 3. A zone read-only through a trashed record it belongs to is refused with the archived-parent refusal,
        //    the answer zenit-cms gives every record create there.
        int site = site("import-readonly-owner");
        Panel zonesUnderSites = new ZonesUnderSites(Map.of(zoneId, site));
        assertThatCode(() -> DnsRecordParts.requireImportable(zonesUnderSites, zoneId, operator))
            .as("step 3: under a live owner the zone takes an import").doesNotThrowAnyException();
        Models.get(SiteModel.class).delete(site);
        assertThatThrownBy(() -> DnsRecordParts.requireImportable(zonesUnderSites, zoneId, operator))
            .as("step 3: under a trashed owner the import is refused")
            .isInstanceOfSatisfying(AccessRefusedException.class, refused -> assertThat(refused.code())
                .as("step 3: with the archived-parent refusal").isEqualTo(CmsRefusalCopy.ARCHIVED_PARENT));
    }

    private static int site(String slug) {
        Model model = Models.get(SiteModel.class);
        Row row = model.createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, false);
        model.save(row);
        return row.get(SiteModel.ID);
    }

    /** The zone-file tab's render for the test administrator, with the host zone stamped read-only or not. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> renderZoneFileTab(int zoneId, boolean hostReadOnly) {
        Conduit conduit = TenantConduits.stubFor(adminPrincipal());
        conduit.setAttribute(RecordTab.RECORD_READ_ONLY, hostReadOnly);
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
        return (Map<String, Object>) new DnsZoneFilePage().render(conduit, AccessContext.of(conduit), zone).get();
    }

    /** A panel where zones belong to sites: the shape under which a trashed record makes a zone read-only. */
    private static final class ZonesUnderSites extends Panel {

        private final List<PanelEntry> peers;

        ZonesUnderSites(Map<Integer, Integer> siteOfZone) {
            super(Identifier.of("hohenheim_test", "zones_under_sites"), "zones-under-sites",
                Microcopy.of("zones_under_sites"), TestPermissions.declared("hohenheim_test.zones_under_sites"));
            PanelResource<Row> sites = SiteParts.admin();
            PanelResource<Row> owner = PanelResource.builder(sites.id(), sites.slug(), sites.subject())
                .label(sites.label()).reads(sites.reads()).list(sites.list()).form(sites.form())
                .writes(sites.writes()).archive(sites.archive()).authority(sites.authority())
                .tabs(ResourceTabs.none()).build();
            this.peers = List.of(owner, PanelResource.builder(
                    Identifier.of("hohenheim_test", "site_owned_zones"), DnsZoneParts.SLUG,
                    SubjectType.record(DnsZoneModel.MODEL_ID))
                .label(Microcopy.literal("Zones")).reads(ResourceReads.rows())
                .list(ResourceList.rows(TableSpec.<Row>builder().columnFromField(DnsZoneModel.ORIGIN).build()).build())
                .parent(ResourceParent.<Row>of(HohenheimSlugs.SITES,
                    row -> siteOfZone.get(row.get(DnsZoneModel.ID))))
                .build());
        }

        @Override
        protected @NonNull List<PanelEntry> buildEntries() {
            return this.peers;
        }
    }

    private static UserPrincipal adminPrincipal() {
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }
}
