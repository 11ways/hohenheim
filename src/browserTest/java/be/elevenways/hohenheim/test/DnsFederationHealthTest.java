package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.dns.DelegationVerdict;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.dns.AxfrResponder;
import be.elevenways.hohenheim.server.dns.DelegationCheck;
import be.elevenways.hohenheim.server.dns.DelegationLookup;
import be.elevenways.hohenheim.server.dns.DnsDelegationHealth;
import be.elevenways.hohenheim.server.dns.DnsNotifier;
import be.elevenways.hohenheim.server.dns.DnsResponder;
import be.elevenways.hohenheim.server.dns.DnsSecondaryFreshness;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.dns.DnsZoneSnapshot;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.zenit.comms.CommsChannel;
import be.elevenways.zenit.comms.server.Comms;
import be.elevenways.zenit.comms.server.CommsDeliveryModel;
import be.elevenways.zenit.comms.server.CommsDispatcher;
import be.elevenways.zenit.comms.server.transport.TransportTypes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.ZoneTransferIn;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.DnsFixtures.createZone;
import static be.elevenways.hohenheim.test.DnsFixtures.linkZonePeer;
import static be.elevenways.hohenheim.test.DnsFixtures.record;
import static be.elevenways.hohenheim.test.DnsFixtures.transferPeer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The primary's own view of its federation, over real sockets: which serial each linked
 * secondary serves (freshness, the stale window, the once-per-lag alert) and whether the
 * parent zone's delegation agrees with the apex NS RRset (the lame-delegation check).
 */
class DnsFederationHealthTest {

    private static final String ORIGIN = "health.example";
    private static final String PARENT = "example";
    private static final String KEY_SECRET = "c2VjcmV0LXRzaWcta2V5LWZvci1ob2hlbmhlaW0tdGVzdA==";

    private static DnsServer ownServer;
    private static int ownPort;
    private static int zoneId;

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();

        zoneId = createZone(ORIGIN, DnsZoneModel.ROLE_PRIMARY, null);
        record(zoneId, "@", DnsRecordModel.TYPE_NS, "ns1." + ORIGIN);
        record(zoneId, "ns1", DnsRecordModel.TYPE_A, "127.0.0.1");
        record(zoneId, "www", DnsRecordModel.TYPE_A, "192.0.2.10");
        DnsZoneStore.INSTANCE.reload();

        ownServer = new DnsServer();
        ownServer.start("127.0.0.1", 0);
        ownPort = ownServer.getUdpPort();
        assertThat(ownPort).isGreaterThan(0);
    }

    @AfterAll
    static void shutdown() {
        if (ownServer != null) {
            ownServer.stop();
        }
    }

    /** An inline webhook dispatcher, so a queued alert is a delivery row right after send(). */
    @BeforeEach
    void inlineDispatcher() {
        Models.get(CommsDeliveryModel.class).find().delete();
        Comms.install(new CommsDispatcher(Map.of(
            CommsChannel.WEBHOOK, List.of(TransportTypes.create("webhook://default"))), 1, true));
    }

    @AfterEach
    void restoreDispatcher() {
        Comms.install(null);
    }

    /**
     * A secondary is probed for the serial it SERVES: current, then behind after a
     * primary edit, stale (item + one alert) once the lag outlives the window, and
     * current again (item and stamps cleared) when it catches up.
     */
    @Test
    void secondaryFreshnessFollowsWhatThePeerActuallyServes() throws Exception {
        long ourSerial = DnsZoneStore.INSTANCE.getZone(ORIGIN).getSerial();
        FakeNameserver secondary = FakeNameserver.serving(ORIGIN, ourSerial);
        Row channel = subscribe("stale-watch", NotificationEvents.DNS_SECONDARY_STALE);
        Models.get(CommsDeliveryModel.class).find().delete();
        try {
            int peerId = transferPeer("fresh-peer", "127.0.0.1", secondary.port());
            int linkId = linkZonePeer(zoneId, peerId);
            Row zone = zone();

            // 1. The peer serves our serial: current, nothing behind, no item.
            List<DnsSecondaryFreshness.Outcome> probed = DnsSecondaryFreshness.probeZone(zone);
            assertThat(probed).as("step 1: one linked peer probed").hasSize(1);
            assertThat(probed.get(0).current()).as("step 1: the peer is current").isTrue();
            Row link = link(linkId);
            assertThat(link.get(DnsZonePeerModel.SERVED_SERIAL))
                .as("step 1: the served serial is persisted").isEqualTo((int) ourSerial);
            assertThat(link.get(DnsZonePeerModel.BEHIND_SINCE)).as("step 1: not behind").isNull();
            assertThat(staleItems()).as("step 1: no attention item").isEmpty();

            // 2. A primary edit bumps our serial; the peer still serves the old one: behind,
            //    but inside the window, so no item and no alert yet.
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
            long bumped = DnsZoneStore.INSTANCE.getZone(ORIGIN).getSerial();
            assertThat(bumped).as("step 2: the serial advanced").isGreaterThan(ourSerial);
            probed = DnsSecondaryFreshness.probeZone(zone());
            assertThat(probed.get(0).current()).as("step 2: the peer is behind").isFalse();
            link = link(linkId);
            assertThat(link.get(DnsZonePeerModel.BEHIND_SINCE)).as("step 2: lag start stamped").isNotNull();
            assertThat(link.get(DnsZonePeerModel.STALE_ALERTED_AT)).as("step 2: not alerted").isNull();
            assertThat(staleItems()).as("step 2: a fresh lag is not an item").isEmpty();
            assertThat(deliveries()).as("step 2: no alert queued").isZero();

            // 3. The lag outlives the window: an attention item naming peer and zone, and
            //    exactly ONE alert, however many probes follow.
            link.set(DnsZonePeerModel.BEHIND_SINCE,
                Instant.now().minus(DnsSecondaryFreshness.STALE_AFTER).minus(Duration.ofMinutes(1)));
            Models.get(DnsZonePeerModel.class).save(link);
            DnsSecondaryFreshness.probeZone(zone());
            DnsSecondaryFreshness.probeZone(zone());
            link = link(linkId);
            assertThat(link.get(DnsZonePeerModel.STALE_ALERTED_AT)).as("step 3: alert stamped").isNotNull();
            assertThat(deliveries()).as("step 3: one alert for one lag").isEqualTo(1);
            List<AttentionItem> items = staleItems();
            assertThat(items).as("step 3: the stale secondary is an item").hasSize(1);
            assertThat(items.get(0).severity()).isEqualTo("warning");
            assertThat(items.get(0).title().key()).isEqualTo("dns_secondary_stale");
            assertThat(items.get(0).target()).as("step 3: the item links to the zone").isNotNull();

            // 4. The peer catches up: current again, stamps cleared, item gone.
            secondary.serve(ORIGIN, bumped);
            probed = DnsSecondaryFreshness.probeZone(zone());
            assertThat(probed.get(0).current()).as("step 4: the peer caught up").isTrue();
            link = link(linkId);
            assertThat(link.get(DnsZonePeerModel.BEHIND_SINCE)).as("step 4: lag cleared").isNull();
            assertThat(link.get(DnsZonePeerModel.STALE_ALERTED_AT)).as("step 4: alert stamp cleared").isNull();
            assertThat(staleItems()).as("step 4: no item").isEmpty();

            // 5. A peer that answers nothing at all records the error and starts a lag.
            int deadPeerId = transferPeer("dead-peer", "127.0.0.1", 1);
            int deadLinkId = linkZonePeer(zoneId, deadPeerId);
            DnsSecondaryFreshness.probeZone(zone());
            Row dead = link(deadLinkId);
            assertThat(dead.get(DnsZonePeerModel.PROBE_ERROR)).as("step 5: the error is recorded").isNotBlank();
            assertThat(dead.get(DnsZonePeerModel.SERVED_SERIAL)).as("step 5: nothing served").isNull();
            assertThat(dead.get(DnsZonePeerModel.BEHIND_SINCE)).as("step 5: silence is a lag").isNotNull();

            // 6. The primary's own trace: a TSIG AXFR it serves is stamped on the link of
            //    the peer holding that key, with the serial it carried.
            Row keyed = Models.get(DnsPeerModel.class).findById(peerId);
            keyed.set(DnsPeerModel.TSIG_KEY_NAME, "health-key");
            keyed.set(DnsPeerModel.TSIG_ALGORITHM, "hmac-sha256");
            keyed.set(DnsPeerModel.TSIG_SECRET, KEY_SECRET);
            Models.get(DnsPeerModel.class).save(keyed);
            ZoneTransferIn xfr = ZoneTransferIn.newAXFR(Name.fromString(ORIGIN + "."),
                "127.0.0.1", ownPort, new TSIG(TSIG.HMAC_SHA256, "health-key.", KEY_SECRET));
            xfr.run();
            assertThat(xfr.getAXFR()).as("step 6: the transfer streamed").isNotEmpty();
            link = link(linkId);
            assertThat(link.get(DnsZonePeerModel.LAST_AXFR_AT)).as("step 6: AXFR served stamped").isNotNull();
            assertThat(link.get(DnsZonePeerModel.LAST_AXFR_SERIAL))
                .as("step 6: with the serial it carried").isEqualTo((int) bumped);
            assertThat(link(deadLinkId).get(DnsZonePeerModel.LAST_AXFR_AT))
                .as("step 6: the other peer's link is untouched").isNull();

            // 7. And a NOTIFY it sends: the acked peer records the ack's rcode, the dead
            //    one records the timeout, each on its own link.
            new DnsNotifier().notifyZonePeersBlocking(zoneId);
            link = link(linkId);
            assertThat(link.get(DnsZonePeerModel.LAST_NOTIFY_AT)).as("step 7: NOTIFY stamped").isNotNull();
            assertThat(link.get(DnsZonePeerModel.LAST_NOTIFY_OUTCOME))
                .as("step 7: the fake secondary acks").isEqualTo("noerror");
            assertThat(link(deadLinkId).get(DnsZonePeerModel.LAST_NOTIFY_OUTCOME))
                .as("step 7: nobody listens on the dead port").isEqualTo("timeout");
        }
        finally {
            secondary.stop();
            Models.get(NotificationChannelModel.class).find().where(
                NotificationChannelModel.ID.eq(channel.get(NotificationChannelModel.ID))).delete();
            Models.get(DnsZonePeerModel.class).find().where(DnsZonePeerModel.ZONE_ID.eq(zoneId)).delete();
        }
    }

    /**
     * The delegation check judges the PARENT's view: every verdict of the vocabulary is
     * produced from a real referral, the worst one is persisted, the alert fires on the
     * transition only, and a corrected delegation clears everything.
     */
    @Test
    void delegationVerdictsComeFromTheParentAndAlertOnTransition() throws Exception {
        long ourSerial = DnsZoneStore.INSTANCE.getZone(ORIGIN).getSerial();
        // Map.of refuses null values, and "no glue" IS a null value here.
        Map<String, String> delegation = new HashMap<>();
        delegation.put("ns1." + ORIGIN, "127.0.0.1");
        delegation.put("ns2.elsewhere.test", null);
        FakeNameserver parent = FakeNameserver.parent(PARENT, ORIGIN, delegation);
        FakeNameserver staleNs = FakeNameserver.serving(ORIGIN, 1);
        Row channel = subscribe("delegation-watch", NotificationEvents.DNS_DELEGATION_BROKEN);
        Models.get(CommsDeliveryModel.class).find().delete();
        TestLookup lookup = new TestLookup(ownPort);
        lookup.parents = List.of(parent.address());
        lookup.addresses.put("ns2.elsewhere.test", List.of(staleNs.address()));
        DelegationCheck check = new DelegationCheck(lookup);
        try {
            // 1. The parent delegates to ns1 (glued, ours, serves our serial) AND to a
            //    foreign ns2 that serves a stale serial: two findings, worst persisted.
            DelegationCheck.Report report = DnsDelegationHealth.check(zone(), check);
            assertThat(report).as("step 1: the zone is served and listable").isNotNull();
            assertThat(report.findings()).extracting(DelegationCheck.Finding::verdict)
                .as("step 1: the foreign server is unlisted and stale")
                .containsExactlyInAnyOrder(DelegationVerdict.DELEGATED_NOT_LISTED,
                    DelegationVerdict.NS_STALE_SERIAL);
            assertThat(report.verdict()).as("step 1: the worst finding is the verdict")
                .isEqualTo(DelegationVerdict.NS_STALE_SERIAL);
            Row zone = zone();
            assertThat(zone.get(DnsZoneModel.DELEGATION_STATUS)).isEqualTo("ns_stale_serial");
            assertThat(zone.get(DnsZoneModel.DELEGATION_DETAIL))
                .contains("delegated_not_listed ns2.elsewhere.test")
                .contains("ns_stale_serial ns2.elsewhere.test serial 1 < " + ourSerial);
            assertThat(zone.get(DnsZoneModel.DELEGATION_CHECKED_AT)).isNotNull();
            assertThat(deliveries()).as("step 1: the transition into a defect alerts once").isEqualTo(1);
            List<AttentionItem> items = delegationItems();
            assertThat(items).as("step 1: one item for the zone").hasSize(1);
            assertThat(items.get(0).severity()).isEqualTo("warning");
            assertThat(items.get(0).detail().key()).as("step 1: the verdict label is the detail")
                .isEqualTo("ns_stale_serial");

            // 2. The same verdict again is NOT a second alert.
            DnsDelegationHealth.check(zone(), check);
            assertThat(deliveries()).as("step 2: no alert on an unchanged verdict").isEqualTo(1);

            // 3. An in-bailiwick NS without glue that resolves nowhere: missing glue AND
            //    lame, and our ns1 is now unlisted at the parent. Worst is lame (error).
            delegation.clear();
            delegation.put("ns3." + ORIGIN, null);
            parent.delegate(PARENT, ORIGIN, delegation);
            report = DnsDelegationHealth.check(zone(), check);
            assertThat(report.findings()).extracting(DelegationCheck.Finding::verdict)
                .containsExactlyInAnyOrder(DelegationVerdict.LISTED_NOT_DELEGATED,
                    DelegationVerdict.DELEGATED_NOT_LISTED, DelegationVerdict.MISSING_GLUE,
                    DelegationVerdict.NS_UNREACHABLE);
            assertThat(report.verdict()).isEqualTo(DelegationVerdict.NS_UNREACHABLE);
            assertThat(delegationItems().get(0).severity()).as("step 3: a lame delegation is an error")
                .isEqualTo("error");
            assertThat(deliveries()).as("step 3: a changed verdict alerts again").isEqualTo(2);

            // 4. No delegation at the parent at all (the registrar step still pending).
            parent.delegate(PARENT, ORIGIN, Map.of());
            report = DnsDelegationHealth.check(zone(), check);
            assertThat(report.verdict()).isEqualTo(DelegationVerdict.NOT_DELEGATED);

            // 5. The parent cannot be reached: inconclusive, never healthy.
            lookup.parents = List.of(new InetSocketAddress("127.0.0.1", 1));
            report = DnsDelegationHealth.check(zone(), check);
            assertThat(report.verdict()).isEqualTo(DelegationVerdict.PARENT_UNREACHABLE);

            // 6. The delegation is corrected: matches, no findings, item gone, and the
            //    stored detail is empty rather than yesterday's findings.
            lookup.parents = List.of(parent.address());
            parent.delegate(PARENT, ORIGIN, Map.of("ns1." + ORIGIN, "127.0.0.1"));
            report = DnsDelegationHealth.check(zone(), check);
            assertThat(report.verdict()).isEqualTo(DelegationVerdict.MATCHES);
            assertThat(report.findings()).isEmpty();
            zone = zone();
            assertThat(zone.get(DnsZoneModel.DELEGATION_STATUS)).isEqualTo("matches");
            assertThat(zone.get(DnsZoneModel.DELEGATION_DETAIL)).isNull();
            assertThat(delegationItems()).as("step 6: a matching delegation raises nothing").isEmpty();
            assertThat(deliveries()).as("step 6: recovery is not an alert").isEqualTo(4);

            // 7. A zone this instance does not serve cannot be judged.
            int unservedId = createZone("unserved.example", DnsZoneModel.ROLE_PRIMARY, null);
            Row unserved = Models.get(DnsZoneModel.class).findById(unservedId);
            unserved.set(DnsZoneModel.ENABLED, false);
            Models.get(DnsZoneModel.class).save(unserved);
            DnsZoneStore.INSTANCE.reload();
            assertThat(DnsDelegationHealth.check(unserved, check)).as("step 7: unserved").isNull();
        }
        finally {
            parent.stop();
            staleNs.stop();
            Models.get(NotificationChannelModel.class).find().where(
                NotificationChannelModel.ID.eq(channel.get(NotificationChannelModel.ID))).delete();
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A lookup that answers from maps, and glues onto this instance's own listener port. */
    private static final class TestLookup implements DelegationLookup {
        List<InetSocketAddress> parents = List.of();
        final Map<String, List<InetSocketAddress>> addresses = new HashMap<>();
        private final int port;

        TestLookup(int port) {
            this.port = port;
        }

        @Override
        public List<InetSocketAddress> parentNameservers(Name zone) {
            return this.parents;
        }

        @Override
        public List<InetSocketAddress> addressesOf(Name nameserver) {
            return this.addresses.getOrDefault(nameserver.toString(true), List.of());
        }

        @Override
        public int nameserverPort() {
            return this.port;
        }
    }

    /** A detached nameserver on loopback whose zone content can be swapped between steps. */
    private static final class FakeNameserver {
        private final DnsZoneStore store = DnsZoneStore.createDetached();
        private final DnsServer server;

        private FakeNameserver() throws Exception {
            this.server = new DnsServer(new DnsResponder(this.store),
                new AxfrResponder(this.store, (zone, requested) -> null));
            this.server.start("127.0.0.1", 0);
        }

        /** A server answering {@code origin} authoritatively at the given serial. */
        static FakeNameserver serving(String origin, long serial) throws Exception {
            FakeNameserver fake = new FakeNameserver();
            fake.serve(origin, serial);
            return fake;
        }

        /** A parent-zone server delegating {@code child} to the given NS names (value = glue A, or null). */
        static FakeNameserver parent(String parentOrigin, String child, Map<String, String> delegation)
                throws Exception {
            FakeNameserver fake = new FakeNameserver();
            fake.delegate(parentOrigin, child, delegation);
            return fake;
        }

        void serve(String origin, long serial) throws Exception {
            Name originName = Name.fromString(origin + ".");
            List<Record> records = new ArrayList<>();
            records.add(new SOARecord(originName, DClass.IN, 3600,
                Name.fromString("ns1." + origin + "."), Name.fromString("hostmaster." + origin + "."),
                serial, 7200, 3600, 1209600, 300));
            records.add(new NSRecord(originName, DClass.IN, 3600, Name.fromString("ns1." + origin + ".")));
            records.add(new ARecord(Name.fromString("www." + origin + "."), DClass.IN, 3600,
                InetAddress.getByName("198.51.100.20")));
            this.store.injectPrimarySnapshot(DnsZoneStore.snapshotFromTransfer(9100, origin, records));
        }

        void delegate(String parentOrigin, String child, Map<String, String> delegation) throws Exception {
            Name parentName = Name.fromString(parentOrigin + ".");
            Name childName = Name.fromString(child + ".");
            List<Record> records = new ArrayList<>();
            records.add(new SOARecord(parentName, DClass.IN, 3600,
                Name.fromString("a.parent.test."), Name.fromString("hostmaster.parent.test."),
                1, 7200, 3600, 1209600, 300));
            records.add(new NSRecord(parentName, DClass.IN, 3600, Name.fromString("a.parent.test.")));
            for (Map.Entry<String, String> entry : delegation.entrySet()) {
                Name ns = Name.fromString(entry.getKey() + ".");
                records.add(new NSRecord(childName, DClass.IN, 3600, ns));
                if (entry.getValue() != null) {
                    records.add(new ARecord(ns, DClass.IN, 3600, InetAddress.getByName(entry.getValue())));
                }
            }
            this.store.injectPrimarySnapshot(DnsZoneStore.snapshotFromTransfer(9200, parentOrigin, records));
        }

        int port() {
            return this.server.getUdpPort();
        }

        InetSocketAddress address() {
            return new InetSocketAddress("127.0.0.1", port());
        }

        void stop() {
            this.server.stop();
        }
    }

    private static Row zone() {
        return Models.get(DnsZoneModel.class).findById(zoneId);
    }

    private static Row link(int linkId) {
        return Models.get(DnsZonePeerModel.class).findById(linkId);
    }

    private static List<AttentionItem> staleItems() {
        List<AttentionItem> items = new ArrayList<>();
        AttentionCollector.staleDnsSecondaries(items);
        return items;
    }

    private static List<AttentionItem> delegationItems() {
        List<AttentionItem> items = new ArrayList<>();
        AttentionCollector.brokenDnsDelegations(items);
        return items;
    }

    private static long deliveries() {
        return Models.get(CommsDeliveryModel.class).find().count();
    }

    /** A webhook channel subscribed to exactly one event, so a queued delivery IS the alert. */
    private static Row subscribe(String name, NotificationEvents event) {
        NotificationChannelModel channels = Models.get(NotificationChannelModel.class);
        Row row = channels.createEmptyRow();
        row.set(NotificationChannelModel.NAME, name);
        row.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
        row.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_GENERIC);
        row.set(NotificationChannelModel.URL, "http://127.0.0.1:1/never");
        row.set(NotificationChannelModel.EVENTS, List.of(event.token()));
        channels.save(row);
        return row;
    }

}
