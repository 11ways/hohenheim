package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.cms.DnsRecordResource;
import be.elevenways.hohenheim.server.cms.DnsZoneFilePage;
import be.elevenways.hohenheim.server.cms.DnsZoneResource;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.cms.common.access.AccessRefusedException;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.render.CmsRefusalCopy;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
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
        DnsRecordResource records = new DnsRecordResource();
        assertThatCode(() -> records.requireImportable(ApiConduits.adminPanel(), zoneId, operator))
            .as("step 2: a live zone takes an import").doesNotThrowAnyException();

        // 3. A zone read-only through a trashed record it belongs to is refused with the archived-parent refusal,
        //    the answer zenit-cms gives every record create there.
        int site = site("import-readonly-owner");
        Panel zonesUnderSites = new ZonesUnderSites(Map.of(zoneId, site));
        assertThatCode(() -> records.requireImportable(zonesUnderSites, zoneId, operator))
            .as("step 3: under a live owner the zone takes an import").doesNotThrowAnyException();
        Models.get(SiteModel.class).delete(site);
        assertThatThrownBy(() -> records.requireImportable(zonesUnderSites, zoneId, operator))
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
        conduit.setAttribute(RecordScopedPage.RECORD_READ_ONLY, hostReadOnly);
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
        return (Map<String, Object>) new DnsZoneFilePage().render(conduit, AccessContext.of(conduit), zone).get();
    }

    /** A panel where zones belong to sites: the shape under which a trashed record makes a zone read-only. */
    private static final class ZonesUnderSites extends Panel {

        private final List<PanelPeer> peers;

        ZonesUnderSites(Map<Integer, Integer> siteOfZone) {
            super(Identifier.of("hohenheim_test", "zones_under_sites"), "zones-under-sites",
                Microcopy.of("zones_under_sites"), Permission.of("hohenheim-test.zones_under_sites"));
            this.peers = List.of(new Sites(), new SiteOwnedZones(siteOfZone));
        }

        @Override
        protected @NonNull List<PanelPeer> buildPeers() {
            return this.peers;
        }
    }

    private static final class Sites extends RowResource {

        @Override public @NonNull Identifier id() { return Identifier.of("hohenheim_test", "owner_sites"); }
        @Override public @NonNull Microcopy label() { return Microcopy.of("owner_sites"); }
        @Override public @NonNull String slug() { return "owner-sites"; }
        @Override public @NonNull Model model() { return Models.get(SiteModel.class); }
        @Override public @NonNull FormSpec formSpec() { return FormSpec.builder().add(SiteModel.NAME).build(); }

        // Like SiteResource: a trashed site still loads, as ARCHIVED; without it the parent is merely
        // unreachable, which zenit-cms refuses as out of scope, never as the trash.
        @Override public boolean offersTrash() { return true; }

        @Override
        public @NonNull TableSpec<Row> tableSpec() {
            return TableSpec.<Row>builder()
                .column(ColumnSpec.fromField(SiteModel.NAME).build())
                .filter(this.archivedFilter())
                .build();
        }
    }

    private static final class SiteOwnedZones extends RowResource {

        private final Map<Integer, Integer> siteOfZone;

        SiteOwnedZones(Map<Integer, Integer> siteOfZone) {
            this.siteOfZone = siteOfZone;
        }

        @Override public @NonNull Identifier id() { return Identifier.of("hohenheim_test", "site_owned_zones"); }
        @Override public @NonNull Microcopy label() { return Microcopy.of("site_owned_zones"); }
        @Override public @NonNull String slug() { return DnsZoneResource.SLUG; }
        @Override public @NonNull Model model() { return Models.get(DnsZoneModel.class); }
        @Override public @NonNull FormSpec formSpec() { return FormSpec.builder().add(DnsZoneModel.ORIGIN).build(); }

        @Override
        public @Nullable ResourceParent<Row> parent() {
            return ResourceParent.<Row>of("owner-sites", row -> this.siteOfZone.get(row.get(DnsZoneModel.ID)));
        }
    }

    private static UserPrincipal adminPrincipal() {
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }
}
