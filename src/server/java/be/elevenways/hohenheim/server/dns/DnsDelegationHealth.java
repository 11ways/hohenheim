package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.dns.DelegationVerdict;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the delegation check for primary zones, persists the verdict on the zone row and
 * alerts on a TRANSITION into a defective verdict, never on every tick.
 *
 * AIDEV-NOTE: a zone whose apex carries no NS RRset is skipped here on purpose: the
 * existing "no NS records" attention item already names that, and comparing an empty
 * list against the parent would only shout twice. A zone serves no snapshot (disabled,
 * or its build failed) is skipped the same way.
 */
public final class DnsDelegationHealth {

    private DnsDelegationHealth() {}

    /** Checks every enabled primary zone with the production lookup. */
    public static void checkAll() {
        DelegationCheck check = new DelegationCheck(SystemDelegationLookup.INSTANCE);
        for (Row zone : Models.get(DnsZoneModel.class).findEnabled()) {
            if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
                continue;
            }
            check(zone, check);
        }
    }

    /**
     * Checks one zone and persists the outcome.
     *
     * @return the report, or null when the zone is not served or lists no apex NS
     */
    public static DelegationCheck.@Nullable Report check(@NonNull Row zone,
                                                         @NonNull DelegationCheck check) {
        String originString = zone.get(DnsZoneModel.ORIGIN);
        DnsZoneSnapshot snapshot = originString != null
            ? DnsZoneStore.INSTANCE.getZone(originString) : null;
        if (snapshot == null) {
            return null;
        }
        List<Record> nsSet = snapshot.getRrset(snapshot.getOrigin(), Type.NS);
        if (nsSet == null || nsSet.isEmpty()) {
            return null;
        }
        List<String> ours = new ArrayList<>();
        for (Record record : nsSet) {
            ours.add(((NSRecord) record).getTarget().toString(true).toLowerCase(Locale.ROOT));
        }
        Name origin = snapshot.getOrigin();
        DelegationCheck.Report report = check.check(origin, ours, snapshot.getSerial())
            .with(DnsNameservers.compareWithDeclared(ours))
            .with(mnameFindings(snapshot, ours));

        DelegationVerdict previous = DelegationVerdict.forToken(zone.get(DnsZoneModel.DELEGATION_STATUS));
        zone.set(DnsZoneModel.DELEGATION_STATUS, report.verdict().token());
        zone.set(DnsZoneModel.DELEGATION_DETAIL, report.detail());
        zone.set(DnsZoneModel.DELEGATION_CHECKED_AT, Instant.now());
        Models.get(DnsZoneModel.class).save(zone);

        if (report.verdict().severity() != null && report.verdict() != previous) {
            Alerts.send(NotificationEvents.DNS_DELEGATION_BROKEN,
                "Delegation of zone " + originString + ": " + report.verdict().token(),
                report.detail());
            Blast.slog("dns.delegation_verdict", Map.of(
                "zone", originString, "verdict", report.verdict().token(),
                "previous", previous != null ? previous.token() : "none"));
        }
        return report;
    }

    /**
     * The disagreement between the SOA MNAME this primary serves and its own apex NS set.
     *
     * AIDEV-NOTE: the MNAME is judged against what is SERVED, not against the stored
     * column: a blank column is synthesized into {@code ns1.<origin>} by the snapshot
     * builder, and that synthesized name is exactly the one that ends up naming a host
     * with no address.
     *
     * @return one {@link DelegationVerdict#SOA_MNAME_UNLISTED} finding, or nothing
     */
    private static @NonNull List<DelegationCheck.Finding> mnameFindings(
            @NonNull DnsZoneSnapshot snapshot, @NonNull List<String> apex) {
        String mname = snapshot.getSoa().getHost().toString(true).toLowerCase(Locale.ROOT);
        for (String name : apex) {
            if (name.equalsIgnoreCase(mname)) {
                return List.of();
            }
        }
        return List.of(new DelegationCheck.Finding(DelegationVerdict.SOA_MNAME_UNLISTED,
            mname + " not in apex NS set"));
    }
}
