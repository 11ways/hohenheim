package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Re-signs DNSSEC zones daily so their RRSIGs never approach the 14-day
 * validity window's end. Each re-sign MUST bump the zone serial: secondaries
 * only pull when the serial advances, and replicas serve their primary's
 * RRSIGs verbatim -- without the bump they would keep signatures from the
 * last record edit until those expire and the zone goes bogus on the public
 * nameservers.
 */
public class ResignDnssecZones extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Re-sign DNSSEC zones";

    @Override
    public @NonNull ResignDnssecZones newTask() {
        return new ResignDnssecZones();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("0 3 * * *")),
            HohenheimRoles.Role.DNS);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        int resigned = 0;
        for (Row zone : Models.get(DnsZoneModel.class).find()
                .where(DnsZoneModel.DNSSEC_ENABLED.eq(true))
                .and(DnsZoneModel.ENABLED.eq(true))
                .all()) {
            if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
                continue; // a replica carries its primary's signatures
            }
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zone.get(DnsZoneModel.ID));
            resigned++;
        }
        if (resigned > 0) {
            Blast.log("TASK: ResignDnssecZones re-signed", resigned, "zone(s)");
        }
    }
}
