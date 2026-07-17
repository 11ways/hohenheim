package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsRecordModel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Address;
import org.xbill.DNS.CAARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns stored record rows into dnsjava resource records; doubles as the
 * type-specific value validator (a row that converts is a row that serves).
 */
public final class DnsRecordCodec {

    private static final Set<String> CAA_TAGS = Set.of("issue", "issuewild", "iodef");
    private static final long MAX_TTL = 7L * 24 * 3600;

    private DnsRecordCodec() {}

    /** @throws DnsValueException when the owner cannot be attached to the origin */
    public static @NonNull Name ownerName(@NonNull Name origin, @NonNull String owner) throws DnsValueException {
        if (DnsNames.APEX.equals(owner)) {
            return origin;
        }
        try {
            return Name.fromString(owner, origin);
        }
        catch (TextParseException e) {
            throw new DnsValueException("name", "dns_name_format");
        }
    }

    /** @throws DnsValueException carrying the offending form field and a violations microcopy key */
    public static @NonNull Record toRecord(@NonNull Name origin,
                                           @NonNull String owner,
                                           @NonNull String type,
                                           long ttl,
                                           @NonNull String value,
                                           @Nullable Integer priority,
                                           @Nullable Integer weight,
                                           @Nullable Integer port) throws DnsValueException {

        Name name = ownerName(origin, owner);
        boolean wildcard = owner.startsWith("*");
        boolean apex = DnsNames.APEX.equals(owner);
        String data = value.trim();

        if (data.isEmpty()) {
            throw new DnsValueException("value", "dns_value_required");
        }

        switch (type) {
            case DnsRecordModel.TYPE_A -> {
                return new ARecord(name, DClass.IN, ttl, parseAddress(data, Address.IPv4, "dns_ipv4_format"));
            }
            case DnsRecordModel.TYPE_AAAA -> {
                return new AAAARecord(name, DClass.IN, ttl, parseAddress(data, Address.IPv6, "dns_ipv6_format"));
            }
            case DnsRecordModel.TYPE_CNAME -> {
                if (apex) {
                    throw new DnsValueException("name", "dns_cname_apex");
                }
                return new CNAMERecord(name, DClass.IN, ttl, targetName(data));
            }
            case DnsRecordModel.TYPE_NS -> {
                if (wildcard) {
                    throw new DnsValueException("name", "dns_ns_wildcard");
                }
                return new NSRecord(name, DClass.IN, ttl, targetName(data));
            }
            case DnsRecordModel.TYPE_MX -> {
                if (priority == null || priority < 0 || priority > 65535) {
                    throw new DnsValueException("priority", "dns_priority_required");
                }
                return new MXRecord(name, DClass.IN, ttl, priority, targetName(data));
            }
            case DnsRecordModel.TYPE_TXT -> {
                return new TXTRecord(name, DClass.IN, ttl, chunkTxt(value));
            }
            case DnsRecordModel.TYPE_CAA -> {
                return caaRecord(name, ttl, data);
            }
            case DnsRecordModel.TYPE_SRV -> {
                if (priority == null || priority < 0 || priority > 65535) {
                    throw new DnsValueException("priority", "dns_priority_required");
                }
                if (port == null || port < 0 || port > 65535) {
                    throw new DnsValueException("port", "dns_port_required");
                }
                int srvWeight = weight != null ? weight : 0;
                if (srvWeight < 0 || srvWeight > 65535) {
                    throw new DnsValueException("weight", "dns_weight_format");
                }
                return new SRVRecord(name, DClass.IN, ttl, priority, srvWeight, port, targetName(data));
            }
            default -> throw new DnsValueException("type", "dns_type_unknown");
        }
    }

    /** @throws DnsValueException when the ttl is negative or absurdly large */
    public static long resolveTtl(@Nullable Integer rowTtl, int zoneDefault) throws DnsValueException {
        long ttl = rowTtl != null ? rowTtl : zoneDefault;
        if (ttl < 0 || ttl > MAX_TTL) {
            throw new DnsValueException("ttl", "dns_ttl_range");
        }
        return ttl;
    }

    private static @NonNull InetAddress parseAddress(@NonNull String value, int family, @NonNull String errorKey)
            throws DnsValueException {
        try {
            return Address.getByAddress(value, family);
        }
        catch (UnknownHostException e) {
            throw new DnsValueException("value", errorKey);
        }
    }

    private static @NonNull Name targetName(@NonNull String value) throws DnsValueException {
        String target = value.toLowerCase();
        while (target.endsWith(".")) {
            target = target.substring(0, target.length() - 1);
        }
        if (target.isEmpty() || target.contains("*") || target.contains(" ")) {
            throw new DnsValueException("value", "dns_target_format");
        }
        try {
            return Name.fromString(target + ".");
        }
        catch (TextParseException e) {
            throw new DnsValueException("value", "dns_target_format");
        }
    }

    /** CAA value format: {@code <flags> <tag> <value>}, e.g. {@code 0 issue letsencrypt.org}. */
    private static @NonNull CAARecord caaRecord(@NonNull Name name, long ttl, @NonNull String data)
            throws DnsValueException {
        String[] parts = data.split("\\s+", 3);
        if (parts.length < 3) {
            throw new DnsValueException("value", "dns_caa_format");
        }
        int flags;
        try {
            flags = Integer.parseInt(parts[0]);
        }
        catch (NumberFormatException e) {
            throw new DnsValueException("value", "dns_caa_format");
        }
        if (flags < 0 || flags > 255) {
            throw new DnsValueException("value", "dns_caa_format");
        }
        String tag = parts[1].toLowerCase();
        if (!CAA_TAGS.contains(tag)) {
            throw new DnsValueException("value", "dns_caa_tag");
        }
        String caaValue = parts[2].trim();
        if (caaValue.length() >= 2 && caaValue.startsWith("\"") && caaValue.endsWith("\"")) {
            caaValue = caaValue.substring(1, caaValue.length() - 1);
        }
        if (caaValue.isEmpty()) {
            throw new DnsValueException("value", "dns_caa_format");
        }
        return new CAARecord(name, DClass.IN, ttl, flags, tag, caaValue);
    }

    /** Splits TXT data into wire strings of at most 255 bytes without breaking UTF-8 sequences. */
    private static @NonNull List<String> chunkTxt(@NonNull String value) throws DnsValueException {
        if (value.isEmpty()) {
            throw new DnsValueException("value", "dns_value_required");
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            String glyph = new String(Character.toChars(codePoint));
            int glyphBytes = glyph.getBytes(StandardCharsets.UTF_8).length;
            if (currentBytes + glyphBytes > 255) {
                chunks.add(current.toString());
                current = new StringBuilder();
                currentBytes = 0;
            }
            current.append(glyph);
            currentBytes += glyphBytes;
            i += Character.charCount(codePoint);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }
}
