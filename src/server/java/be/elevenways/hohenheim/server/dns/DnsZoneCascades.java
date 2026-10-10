package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.orm.PendingDeletes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;

/**
 * A hosted zone's integrity on every write lane: its delete cascade (its records, generated
 * ones included, and its zone-peer links die with it) and the refusal of a record row inside
 * a SECONDARY zone.
 *
 * AIDEV-NOTE: moved here from {@code DnsZoneResource.deleteRow} on 2026-08-29, so a zone
 * removed by anything other than the admin form (a direct model delete, the peer API) no
 * longer strands its records; the zone-peer sweep was never on the resource at all, so a
 * deleted zone left links behind that only the zone's Secondaries tab could see. The
 * SWEEPING scope is required, not decoration: a generated row is un-deletable through
 * every tenant path (GeneratedDnsRecords' remove guard), and a cascade from the declaring
 * container is the one legitimate exception -- the record the challenge was published
 * into is going away, which is exactly the reclaim condition. The served snapshot is NOT
 * rebuilt here: {@code DnsZoneStore.reload} reads the tables, so it must run after the
 * delete commits, and the resource keeps calling it exactly where it always did.
 */
public final class DnsZoneCascades {

    private static volatile boolean installed;

    private DnsZoneCascades() {
    }

    /**
     * Install the zone hooks; idempotent, called at the MODULES boot stage.
     *
     * AIDEV-NOTE: the secondary refusal is the MODEL-level backstop DnsZoneStore's lookup
     * split documents: a secondary (replica) zone authors no rows -- everything it serves
     * arrives over AXFR -- so a row written against its id publishes nothing (reloadNow
     * skips secondaries, the next transfer replaces the snapshot) while the serial bump
     * inflates the replica's stored serial and suppresses genuine transfers. Callers that
     * picked a zone from the enabled-zone list (game domains, previews, the /manage record
     * form's origin lookup) wrote exactly that; this refuses it for every writer, operator and
     * system included, because no writer has a legitimate reason to. A write against a zone
     * id that does not exist is left to the ordinary validation.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        DnsZoneModel.SCHEMA.addBeforeRemoveHook(context -> {
            GeneratedDnsRecords.sweeping(() -> PendingDeletes.deleteDependents(
                Models.get(DnsRecordModel.class), DnsRecordModel.ZONE, context));
            PendingDeletes.deleteDependents(Models.get(DnsZonePeerModel.class),
                DnsZonePeerModel.ZONE, context);
        });

        DnsRecordModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Row stored = StoredRows.of(Models.get(DnsRecordModel.class), row);
            Object zoneId = row.afterWrite(DnsRecordModel.ZONE_ID, stored);
            Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
            if (zone != null && DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
                throw Violations.ofField(DnsRecordModel.ZONE_ID.getName(), zoneId,
                    HohenheimMicrocopy.VIOLATIONS.of("record_secondary_zone"));
            }
        });
    }
}
