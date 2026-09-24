package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldQueryGate;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A field a viewer may not query leaves every list lane (filter, sort, search, the rule tiers), and a
 * record-aware FieldAccess answers the cross-record question with its NULL record, which here means
 * "the create form". Every binding that hides a field only as presentation (on the create form, or on
 * the records it does not apply to) therefore declares its cross-record answer, and this pins each one.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class RecordAwareQueryGateTest extends HohenheimTestBase {

    /** The bindings decide on the record alone, so any viewer answers the same. */
    private static final AccessContext VIEWER = AccessContext.anonymous();

    @Test
    void presentationOnlyHidingNeverWithholdsAFieldFromTheList() {
        // 1. Bans: the create form hides the stored state it never takes as input...
        BanResource bans = new BanResource();
        assertThat(bans.fieldAccessFor(BanModel.ACTIVE.getName()).decide(VIEWER))
            .as("step 1: the create form hides the active flag")
            .isEqualTo(FieldAccess.Decision.HIDDEN);
        // ...yet across records it is queryable, so the list's Active filter stays offered.
        FieldQueryGate banGate = bans.queryGate();
        assertThat(banGate.mayQuery(BanModel.ACTIVE.getName(), VIEWER))
            .as("step 1: the active flag may be filtered by").isTrue();
        TableSpec<?> spec = bans.tableSpec().queryableBy(name -> banGate.mayQuery(name, VIEWER));
        assertThat(spec.filter(BanModel.ACTIVE.getName()))
            .as("step 1: the viewer's list still offers the Active filter").isNotNull();

        // 2. DNS zones: replication diagnostics show only on the zones of their role, never on the
        //    create form, and stay queryable across the zone list.
        FieldQueryGate zoneGate = new DnsZoneResource().queryGate();
        for (String diagnostic : new String[] {DnsZoneModel.TRANSFER_STATUS.getName(),
                DnsZoneModel.LAST_TRANSFER_AT.getName(), DnsZoneModel.DELEGATION_STATUS.getName(),
                DnsZoneModel.DELEGATION_CHECKED_AT.getName()}) {
            assertThat(zoneGate.mayQuery(diagnostic, VIEWER))
                .as("step 2: the zone list may still filter and sort by %s", diagnostic).isTrue();
        }

        // 3. Databases: a failure reason shows only on a record that carries one, and stays queryable.
        assertThat(new DatabaseResource().queryGate().mayQuery(DatabaseModel.FAILURE_REASON.getName(), VIEWER))
            .as("step 3: the failure reason may still be queried across records").isTrue();
    }
}
