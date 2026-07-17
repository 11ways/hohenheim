package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Name;

/**
 * Loads (and lazily mints) a signing zone's CSK from its row, and derives the
 * DS record an operator hands to the registrar.
 */
public final class DnsSecMaterial {

    private DnsSecMaterial() {}

    /**
     * @return the zone's signing key, generating and persisting one on first
     *         use; null only when the origin itself is unparseable
     */
    public static @Nullable DnsSecKeys ensure(@NonNull Row zone) {
        String originString = zone.get(DnsZoneModel.ORIGIN);
        Name origin;
        try {
            origin = Name.fromString(originString + ".");
        }
        catch (Exception e) {
            return null;
        }

        String privateB64 = zone.get(DnsZoneModel.DNSSEC_PRIVATE_KEY);
        String publicB64 = zone.get(DnsZoneModel.DNSSEC_PUBLIC_KEY);
        if (privateB64 != null && !privateB64.isBlank() && publicB64 != null && !publicB64.isBlank()) {
            return DnsSecKeys.load(origin, privateB64, publicB64);
        }

        DnsSecKeys keys = DnsSecKeys.generate(origin);
        zone.set(DnsZoneModel.DNSSEC_ALGORITHM, DnsSecKeys.ALGORITHM);
        zone.set(DnsZoneModel.DNSSEC_PRIVATE_KEY, keys.privateKeyBase64());
        zone.set(DnsZoneModel.DNSSEC_PUBLIC_KEY, keys.publicKeyBase64());
        zone.set(DnsZoneModel.DNSSEC_KEY_TAG, keys.getKeyTag());
        Models.get(DnsZoneModel.class).save(zone);
        Blast.log("DNS: generated a DNSSEC signing key for", originString, "key tag", keys.getKeyTag());
        return keys;
    }

    /** @return the SHA-256 DS record for the registrar, or null when the zone has no key */
    public static @Nullable DSRecord dsRecord(@NonNull Row zone) {
        String originString = zone.get(DnsZoneModel.ORIGIN);
        String publicB64 = zone.get(DnsZoneModel.DNSSEC_PUBLIC_KEY);
        String privateB64 = zone.get(DnsZoneModel.DNSSEC_PRIVATE_KEY);
        if (publicB64 == null || publicB64.isBlank() || privateB64 == null || privateB64.isBlank()) {
            return null;
        }
        try {
            Name origin = Name.fromString(originString + ".");
            DnsSecKeys keys = DnsSecKeys.load(origin, privateB64, publicB64);
            return new DSRecord(origin, org.xbill.DNS.DClass.IN, 3600, DSRecord.Digest.SHA256, keys.getDnsKey());
        }
        catch (Exception e) {
            return null;
        }
    }
}
