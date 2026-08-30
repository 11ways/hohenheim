package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.dns.DelegationVerdict;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * THE home of the controller's declared nameserver set ({@code dns.nameservers}): what a
 * new primary zone's apex NS rows are seeded from, what a zone-file import substitutes
 * for the file's foreign NS set, and the yardstick the delegation check holds the served
 * apex NS set against.
 *
 * AIDEV-NOTE: seeding happens ONCE, at zone creation, and is never re-asserted. The apex
 * NS rows stay ordinary operator-editable records (a zone whose delegation deliberately
 * differs from the controller's own set is expressible), so the declared set is a default
 * and a comparison, never an owner of the rows.
 */
public final class DnsNameservers {

    private DnsNameservers() {}

    /** @return the declared names, canonical and deduplicated by the setting's coercer; empty when none */
    public static @NonNull List<String> declared() {
        List<String> names = HohenheimSettings.VALUES.getValue(HohenheimSettings.Dns.NAMESERVERS);
        return names == null ? List.of() : names;
    }

    /**
     * The SOA MNAME a new primary zone falls back to when the operator named none.
     *
     * AIDEV-NOTE: an MNAME taken from another zone's nameserver (the form left it to the
     * operator, who pasted a name from the previous zone) names a primary the delegation
     * does not point at, and usually a host with no address; defaulting it to the first
     * declared name keeps it inside the very set the apex NS rows are seeded from.
     *
     * @return the first declared nameserver, or null when nothing is declared
     */
    public static @Nullable String defaultPrimaryNs() {
        List<String> names = declared();
        return names.isEmpty() ? null : names.get(0);
    }

    /**
     * Writes one enabled apex NS row per declared name onto a freshly created zone.
     *
     * @return the names written, empty when nothing is declared
     */
    public static @NonNull List<String> seedApexRows(int zoneId) {
        List<String> names = declared();
        DnsRecordModel model = Models.get(DnsRecordModel.class);
        for (String name : names) {
            model.save(apexNsRow(model, zoneId, name));
        }
        return names;
    }

    /** An unsaved, enabled apex NS row carrying the zone's default TTL. */
    static @NonNull Row apexNsRow(@NonNull DnsRecordModel model, int zoneId, @NonNull String target) {
        Row row = model.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zoneId);
        row.set(DnsRecordModel.NAME, DnsNames.APEX);
        row.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_NS);
        row.set(DnsRecordModel.VALUE, target);
        row.set(DnsRecordModel.DATA, DnsRecordModel.dataFor(DnsRecordModel.TYPE_NS, null, null, null));
        row.set(DnsRecordModel.ENABLED, true);
        return row;
    }

    /**
     * The disagreement between a zone's served apex NS set and the declared set, one
     * {@link DelegationVerdict#APEX_UNDECLARED} finding per name on either side only.
     *
     * @return no findings when nothing is declared or the two sets agree
     */
    public static @NonNull List<DelegationCheck.Finding> compareWithDeclared(@NonNull List<String> apex) {
        List<DelegationCheck.Finding> findings = new ArrayList<>();
        List<String> declared = declared();
        if (declared.isEmpty()) {
            return findings;
        }
        TreeSet<String> served = new TreeSet<>();
        for (String name : apex) {
            served.add(name.toLowerCase(Locale.ROOT));
        }
        TreeSet<String> wanted = new TreeSet<>(declared);
        for (String name : served) {
            if (!wanted.contains(name)) {
                findings.add(new DelegationCheck.Finding(DelegationVerdict.APEX_UNDECLARED,
                    name + " served but not declared"));
            }
        }
        for (String name : wanted) {
            if (!served.contains(name)) {
                findings.add(new DelegationCheck.Finding(DelegationVerdict.APEX_UNDECLARED,
                    name + " declared but not served"));
            }
        }
        return findings;
    }
}
