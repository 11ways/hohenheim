package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.orm.PendingDeletes;
import be.elevenways.zenit.common.orm.model.Models;

/**
 * A hosted zone's delete cascade, on every delete lane: its records (generated ones
 * included) and its zone-peer links (NOTIFY targets, AXFR authorizations) die with it.
 *
 * AIDEV-NOTE: moved here from {@code DnsZoneResource.deleteRow} on 2026-08-29, so a zone
 * removed by anything other than the admin form (a direct model delete, the peer API) no
 * longer strands its records; the zone-peer sweep was never on the resource at all, so a
 * deleted zone left links behind that only {@code DnsZoneSecondariesPage} could see. The
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

    /** Install the zone hooks; idempotent, called at the MODULES boot stage. */
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
    }
}
