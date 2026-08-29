package be.elevenways.hohenheim.server.dns;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The production lookup: walks up from the zone until the system resolver returns an NS
 * RRset (the parent, {@code com} for {@code example.com}), then resolves those names.
 *
 * AIDEV-NOTE: only the DISCOVERY rides the system resolver. The delegation itself is read
 * from the parent's servers with recursion off ({@link DelegationCheck}), because a
 * recursive answer for our own zone would come from OUR nameservers and could never show
 * a registrar-side mismatch.
 */
public final class SystemDelegationLookup implements DelegationLookup {

    public static final SystemDelegationLookup INSTANCE = new SystemDelegationLookup();

    private SystemDelegationLookup() {}

    @Override
    public @NonNull List<InetSocketAddress> parentNameservers(@NonNull Name zone) throws Exception {
        Name parent = zone;
        while (parent.labels() > 1) {
            parent = new Name(parent, 1);
            Lookup lookup = new Lookup(parent, Type.NS);
            Record[] answers = lookup.run();
            if (lookup.getResult() != Lookup.SUCCESSFUL || answers == null || answers.length == 0) {
                continue;
            }
            List<InetSocketAddress> servers = new ArrayList<>();
            for (Record answer : answers) {
                if (answer instanceof NSRecord ns) {
                    servers.addAll(addressesOf(ns.getTarget()));
                }
            }
            return servers;
        }
        return List.of();
    }

    @Override
    public @NonNull List<InetSocketAddress> addressesOf(@NonNull Name nameserver) throws Exception {
        List<InetSocketAddress> addresses = new ArrayList<>();
        for (int type : new int[] {Type.A, Type.AAAA}) {
            Lookup lookup = new Lookup(nameserver, type);
            Record[] answers = lookup.run();
            if (answers == null) {
                continue;
            }
            for (Record answer : answers) {
                if (answer instanceof ARecord a) {
                    addresses.add(new InetSocketAddress(a.getAddress(), nameserverPort()));
                }
                else if (answer instanceof AAAARecord aaaa) {
                    addresses.add(new InetSocketAddress(aaaa.getAddress(), nameserverPort()));
                }
            }
        }
        return addresses;
    }

    @Override
    public int nameserverPort() {
        return 53;
    }
}
