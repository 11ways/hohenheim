package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.cms.DnsZoneResource;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replication diagnostics belong to SECONDARY zones: the transfer vocabulary has one
 * declaring home on the model, the list cell is blank on a primary, and the three
 * read-only fields are hidden there rather than shown empty.
 */
class DnsZoneTransferSurfaceTest extends HohenheimTestBase {

    @Test
    void transferDiagnosticsAreSecondaryOnlyEverywhereTheyAppear() {
        // 1. The vocabulary is the EnumField's declared keys -- the service writes these
        //    constants, it no longer keeps its own copy of the three words.
        assertThat(DnsZoneModel.TRANSFER_STATUS.getValues().keySet())
            .as("step 1: exactly the three transfer outcomes, on the model")
            .containsExactly(DnsZoneModel.TRANSFER_OK, DnsZoneModel.TRANSFER_ERROR,
                DnsZoneModel.TRANSFER_EXPIRED);
        for (String value : DnsZoneModel.TRANSFER_STATUS.getValues().keySet()) {
            assertThat(DnsZoneModel.TRANSFER_STATUS.getValues().get(value).getMicrocopy())
                .as("step 1: '" + value + "' renders a translated badge, not a raw word")
                .isNotNull();
        }

        // 2. The list cell: a secondary reports its outcome, a primary reports nothing --
        //    a primary transfers from nobody, so the stored word would be noise.
        DnsZoneResource resource = new DnsZoneResource();
        ColumnSpec statusColumn = null;
        for (ColumnSpec column : resource.tableSpec().columns()) {
            if (DnsZoneModel.TRANSFER_STATUS.getName().equals(column.name())) {
                statusColumn = column;
            }
        }
        assertThat(statusColumn).as("step 2: the list still carries the status column").isNotNull();
        assertThat(resource.cellValue(zone(DnsZoneModel.ROLE_SECONDARY), statusColumn))
            .as("step 2: a secondary renders its transfer outcome")
            .isEqualTo(DnsZoneModel.TRANSFER_ERROR);
        assertThat(resource.cellValue(zone(DnsZoneModel.ROLE_PRIMARY), statusColumn))
            .as("step 2: a primary renders blank, whatever the column holds")
            .isNull();

        // 3. The form: the same three fields are readable on a secondary and absent on a
        //    primary, and a record-less render (create) fails closed the same way.
        Map<String, FieldAccess> bound = new LinkedHashMap<>();
        for (ResourceFieldBinding binding : resource.fieldBindings()) {
            bound.put(binding.path(), binding.access());
        }
        List<String> delegation = List.of(DnsZoneModel.DELEGATION_STATUS.getName(),
            DnsZoneModel.DELEGATION_CHECKED_AT.getName(),
            DnsZoneModel.DELEGATION_DETAIL.getName());
        assertThat(bound.keySet())
            .as("step 3: the diagnostics are the bound fields")
            .containsExactlyInAnyOrder(DnsZoneModel.TRANSFER_STATUS.getName(),
                DnsZoneModel.LAST_TRANSFER_AT.getName(),
                DnsZoneModel.TRANSFER_MESSAGE.getName(),
                DnsZoneModel.DELEGATION_STATUS.getName(),
                DnsZoneModel.DELEGATION_CHECKED_AT.getName(),
                DnsZoneModel.DELEGATION_DETAIL.getName());
        AccessContext anonymous = AccessContext.anonymous();
        for (String path : delegation) {
            // The delegation trio is the mirror image: readable on a primary, absent on a
            // secondary, and absent with no record at all.
            FieldAccess access = bound.remove(path);
            assertThat(access.decide(anonymous, zone(DnsZoneModel.ROLE_PRIMARY)))
                .as("step 3: '" + path + "' is readable on a primary, never editable")
                .isEqualTo(FieldAccess.Decision.READONLY);
            assertThat(access.decide(anonymous, zone(DnsZoneModel.ROLE_SECONDARY)))
                .as("step 3: '" + path + "' is absent on a secondary")
                .isEqualTo(FieldAccess.Decision.HIDDEN);
            assertThat(access.decide(anonymous))
                .as("step 3: '" + path + "' is absent with no record at all")
                .isEqualTo(FieldAccess.Decision.HIDDEN);
        }
        for (Map.Entry<String, FieldAccess> entry : bound.entrySet()) {
            assertThat(entry.getValue().decide(anonymous, zone(DnsZoneModel.ROLE_SECONDARY)))
                .as("step 3: '" + entry.getKey() + "' is readable on a secondary, never editable")
                .isEqualTo(FieldAccess.Decision.READONLY);
            assertThat(entry.getValue().decide(anonymous, zone(DnsZoneModel.ROLE_PRIMARY)))
                .as("step 3: '" + entry.getKey() + "' is absent on a primary")
                .isEqualTo(FieldAccess.Decision.HIDDEN);
            assertThat(entry.getValue().decide(anonymous))
                .as("step 3: '" + entry.getKey() + "' is absent with no record at all")
                .isEqualTo(FieldAccess.Decision.HIDDEN);
        }

        // 4. And the form declares them, so there is something for those bindings to gate.
        List<String> entries = resource.formSpec().entries().stream()
            .map(entry -> entry.name()).toList();
        assertThat(entries)
            .as("step 4: the zone form carries the diagnostics")
            .contains(DnsZoneModel.TRANSFER_STATUS.getName(),
                DnsZoneModel.LAST_TRANSFER_AT.getName(),
                DnsZoneModel.TRANSFER_MESSAGE.getName());
    }

    /** An unsaved zone row carrying a role and a transfer outcome. */
    private static Row zone(String role) {
        Row row = new Row();
        row.set(DnsZoneModel.ORIGIN, "transfer-surface.example");
        row.set(DnsZoneModel.ROLE, role);
        row.set(DnsZoneModel.TRANSFER_STATUS, DnsZoneModel.TRANSFER_ERROR);
        return row;
    }
}
