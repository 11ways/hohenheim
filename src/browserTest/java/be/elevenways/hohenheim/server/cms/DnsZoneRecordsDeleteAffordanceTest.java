package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import be.elevenways.zenit.cms.common.render.table.TableState;
import be.elevenways.zenit.cms.server.render.table.TableStateTranslator;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The zone's Records tab forwards the record resource's per-record delete verdict, the
 * {@code WriteAffordanceParityTest} shape applied to the tab's own render: the page is
 * rendered and its row map read, rather than its markup grepped.
 *
 * AIDEV-NOTE: this exists because the tab used to call a {@code TableStateTranslator}
 * overload that had no {@code deleteUnavailableReason} parameter at all, so the resource's
 * answer was dropped silently while the generated list page honoured it -- a divergence no
 * markup assertion could see, since the shipped {@link DnsRecordResource} declares no
 * refusal reason today. The fixture below declares one so the FORWARDING is what is under
 * test, never hohenheim's policy: the day the shipped resource grows a real reason, this
 * test already covers the wire it travels on.
 */
class DnsZoneRecordsDeleteAffordanceTest extends HohenheimTestBase {

    /** The record whose delete the fixture resource declares refused. */
    private static final String LOCKED = "locked";

    /** The record the fixture leaves alone, so a resource refusing everyone cannot pass. */
    private static final String FREE = "free";

    /**
     * The refusal text. Any Microcopy does: the assertions compare the very instance the
     * fixture handed back, so nothing here depends on the key resolving to anything.
     */
    private static final Microcopy REASON =
        Microcopy.of("delete_unavailable").withFilter("scope", "dns_zone_records_test");

    private static int zoneId;

    @BeforeAll
    static void seedZone() {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, "delete-affordance.example");
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1.delete-affordance.example");
        zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@delete-affordance.example");
        zone.set(DnsZoneModel.ROLE, DnsZoneModel.ROLE_PRIMARY);
        zone.set(DnsZoneModel.ENABLED, true);
        zones.save(zone);
        zoneId = zone.get(DnsZoneModel.ID);

        record(LOCKED, "192.0.2.40");
        record(FREE, "192.0.2.41");
    }

    /**
     * One render, both verdicts: the tab asks the resource per row, so the refused record's
     * delete stays on the menu DEAD carrying the resource's own text, while the record the
     * resource says nothing about keeps a live delete.
     */
    @Test
    void theRecordsTabForwardsThePerRecordDeleteRefusal() {
        AccessContext operator = operator();

        // 1. The shipped resource refuses nobody, so every delete on the tab is live --
        //    the baseline that proves the fixture below is what moves the answer.
        TableState shipped = tableFor(operator, new DnsRecordResource());
        assertThat(deleteOf(shipped, LOCKED).disabledReason())
            .as("step 1: the shipped resource declares no refusal, so the delete is live")
            .isNull();

        // 2. Rendered through a resource that DOES declare a per-record refusal, the same
        //    row's delete is dead and carries that resource's own text.
        TableState declared = tableFor(operator, new RefusingDnsRecordResource());
        InvokeActionState locked = deleteOf(declared, LOCKED);
        assertThat(locked.disabled())
            .as("step 2: the refused record's delete renders dead")
            .isTrue();
        assertThat(locked.disabledReason())
            .as("step 2: carrying the resource's own reason, not a reworded copy")
            .isSameAs(REASON);

        // 3. And the delete is still OFFERED: hiding it teaches nothing, which is the whole
        //    point of the third answer beside deletable() and deletableBy().
        assertThat(locked.target().toString())
            .as("step 3: a dead delete still names the route it would have posted to")
            .contains(String.valueOf(recordId(LOCKED)));

        // 4. The verdict is per ROW, never once for the table: the record the resource says
        //    nothing about keeps its live delete in the very same render.
        InvokeActionState free = deleteOf(declared, FREE);
        assertThat(free.disabled())
            .as("step 4: a record the resource does not refuse keeps a live delete")
            .isFalse();
        assertThat(free.disabledReason())
            .as("step 4: and carries no reason at all")
            .isNull();
    }

    /** A resource that refuses exactly one record's delete, so the FORWARDING is the subject. */
    private static final class RefusingDnsRecordResource extends DnsRecordResource {
        @Override
        public @Nullable Microcopy deleteUnavailableReason(@NonNull Row record,
                                                          @NonNull AccessContext accessContext) {
            return LOCKED.equals(record.get(DnsRecordModel.NAME)) ? REASON : null;
        }
    }

    /** The tab's own render, read as the row state it hands the template. */
    @SuppressWarnings("unchecked")
    private static TableState tableFor(AccessContext accessContext, DnsRecordResource resource) {
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
        Map<String, Object> vars = (Map<String, Object>) new DnsZoneRecordsPage()
            .renderLocal(accessContext.conduit(), accessContext, zone, resource).get();
        TableState table = (TableState) vars.get("table");
        assertThat(table).as("the tab rendered a table").isNotNull();
        return table;
    }

    /** The synthesized delete on the row for {@code name}. */
    private static InvokeActionState deleteOf(TableState table, String name) {
        String key = String.valueOf(recordId(name));
        TableState.RowState row = table.rows().stream()
            .filter(candidate -> key.equals(candidate.key()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the tab listed no row for '" + name + "'"));
        return row.destructiveInvokeActions().stream()
            .filter(action -> TableStateTranslator.DELETE_ACTION.equals(action.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("row '" + name + "' offers no delete at all"));
    }

    private static AccessContext operator() {
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(admin.get(UserModel.ID), "Test Admin")));
    }

    private static int recordId(String name) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq(name))
            .first().get(DnsRecordModel.ID);
    }

    private static void record(String name, String value) {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row record = records.createEmptyRow();
        record.set(DnsRecordModel.ZONE_ID, zoneId);
        record.set(DnsRecordModel.NAME, name);
        record.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_A);
        record.set(DnsRecordModel.VALUE, value);
        record.set(DnsRecordModel.TTL, 300);
        record.set(DnsRecordModel.ENABLED, true);
        records.save(record);
    }
}
