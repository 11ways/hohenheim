package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;

import java.util.List;

/**
 * Re-signs DNSSEC zones daily so their RRSIGs never approach the 14-day
 * validity window's end. Rebuilding a primary snapshot re-signs it with a
 * fresh window without bumping the serial; a no-op when no zone is signed.
 */
public class ResignDnssecZones extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Re-sign DNSSEC zones";

    public static List<ScheduleDeclaration> defaultSchedules() {
        return List.of(ScheduleDeclaration.fallback("0 3 * * *"));
    }

    @Override
    public void executor(TaskContext ctx) {
        // reload() re-signs primary zones and skips secondaries, so counting the
        // DNSSEC-enabled zones is enough to decide whether a rebuild is worthwhile.
        long signed = Models.get(DnsZoneModel.class).find()
            .where(DnsZoneModel.DNSSEC_ENABLED.eq(true))
            .count();
        if (signed == 0) {
            return;
        }
        DnsZoneStore.INSTANCE.reload();
        Blast.log("TASK: ResignDnssecZones refreshed signatures for", signed, "zone(s)");
    }
}
