package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.DnsDyndnsCredentialModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.dns.AxfrResponder;
import be.elevenways.hohenheim.server.dns.DnsFederationKeys;
import be.elevenways.hohenheim.server.dns.DnsResponder;
import be.elevenways.hohenheim.server.dns.DnsSecondaryFreshness;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.dns.DnsZoneSnapshot;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.hohenheim.server.dns.GeneratedDnsRecords;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.thread.JobRunnerGroup;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.HistoryTestSupport;
import be.elevenways.zenit.test.support.StubHost;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.Type;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static be.elevenways.hohenheim.test.DnsFixtures.acmeRow;
import static be.elevenways.hohenheim.test.DnsFixtures.apiPeer;
import static be.elevenways.hohenheim.test.DnsFixtures.createZone;
import static be.elevenways.hohenheim.test.DnsFixtures.linkZonePeer;
import static be.elevenways.hohenheim.test.DnsFixtures.record;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A zone moved by dns_zone_take_over from the Hohenheim keeping its records to the one holding its copy, and a
 * dynamic DNS update reaching a server that only copies its zone forwarded to the one keeping it.
 *
 * Both servers are this app: the owner is a primary zone under one origin, the copy a secondary of another origin
 * whose owner is a relay at a public-looking https name. The relay hands the copy's peer calls to the owner's real
 * routes, the origin and the transfer key name rewritten (one database holds both servers' peer rows), and before a
 * hand-over it serves the copy's records, renamed, on a loopback nameserver the owner pulls from: the owner's real
 * pull, checks and flip run against what the taker really serves.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class DnsZoneTakeOverTest extends HohenheimHttpTestBase {

    private static final String SECRET = "c2VjcmV0LXRzaWcta2V5LWZvci1ob2hlbmhlaW0tdGVzdA==";
    private static final String WWW = "192.0.2.80";

    @Test
    void aZoneMovesToTheServerHoldingItsCopyAndEveryNameKeepsItsAnswer() throws Exception {
        String tag = HohenheimMcpTest.letters();
        String ownerOrigin = "owner-" + tag + ".move.test";
        String copyOrigin = "copy-" + tag + ".move.test";
        // The two halves of one shared transfer key, one per server's peer row.
        String takerKey = "xfer-taker-" + tag;
        String ownerKey = "xfer-owner-" + tag;
        int serial = 2026112601;

        int ownerZone = createZone(ownerOrigin, DnsZoneModel.ROLE_PRIMARY, null);
        record(ownerZone, "www", DnsRecordModel.TYPE_A, WWW);
        int dynamic = record(ownerZone, "dyn", DnsRecordModel.TYPE_A, "198.51.100.1");
        String token = DynamicDnsService.mintFor(dynamic);
        acmeRow(ownerZone);
        setSerial(ownerZone, serial);
        DnsZoneStore.INSTANCE.reload();
        int ownerRows = rows(ownerZone);

        Row admin = TenantConduits.operatorUser();
        String peerKey = ApiKeyService.create(admin.get(UserModel.ID), "dns-move-" + tag, List.of("hohenheim.*"),
            null).plaintext();
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        DnsZoneStore source = DnsZoneStore.createDetached();
        TSIG sourceKey = new TSIG(TSIG.HMAC_SHA256, ownerKey + ".", SECRET);
        Name sourceKeyName = Name.fromString(ownerKey + ".");
        DnsServer sourceDns = new DnsServer(new DnsResponder(source),
            new AxfrResponder(source, (zoneId, requested) -> requested.equals(sourceKeyName) ? sourceKey : null));
        sourceDns.start("127.0.0.1", 0);
        AtomicInteger servedBump = new AtomicInteger(1);
        String copyPath = "/api/dns/zones/" + copyOrigin;
        String ownerPath = "/api/dns/zones/" + ownerOrigin;
        try (HistoryTestSupport.Scope recording = HistoryTestSupport.optionalRecording();
             StubHost relay = StubHost.startSecure("owner-" + tag + ".move.peer.test");
             StubHost gone = StubHost.startSecure("gone-" + tag + ".move.peer.test")) {
            relay.serveBy(copyPath + "/state", request -> renamed(DnsRecordForwardingTest.relayed(
                requestTo(ownerPath + "/state").header("Authorization", "Bearer " + peerKey).GET()),
                ownerOrigin, copyOrigin, ownerKey, takerKey));
            relay.serveBy(copyPath + "/hand-over", request -> {
                serve(source, copyOrigin, ownerOrigin, servedBump.get() - 1);
                Map<String, String> form = DnsFixtures.formFields(request.body());
                return renamed(DnsRecordForwardingTest.relayed(requestTo(ownerPath + "/hand-over")
                    .header("Authorization", "Bearer " + peerKey)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(ApiSupport.form("key_name", ownerKey,
                        "from_serial", form.get("from_serial"), "serial", form.get("serial"),
                        "server", form.get("server"))))), ownerOrigin, copyOrigin, ownerKey, takerKey);
            });
            // The copy's server: its peer for the owner (the relay), holding its half of the key.
            String ownerName = "move-owner-" + tag;
            int ownerPeer = apiPeer(ownerName, relay.url(""));
            key(ownerPeer, takerKey, null, null);
            // The owner's peer for the copy's server: its half of the key, where it transfers from, a key to call back.
            String takerName = "move-taker-" + tag;
            int takerPeer = apiPeer(takerName, "https://taker-" + tag + ".move.peer.test");
            key(takerPeer, ownerKey, "127.0.0.1", sourceDns.getUdpPort());
            int ownerLink = linkZonePeer(ownerZone, takerPeer);
            int copyZone = createZone(copyOrigin, DnsZoneModel.ROLE_SECONDARY, ownerPeer);
            currentCopy(copyZone, serial);
            DnsZoneStore.INSTANCE.putSecondarySnapshot(DnsZoneStore.snapshotFromTransfer(copyZone, copyOrigin,
                renamed(Objects.requireNonNull(DnsZoneStore.INSTANCE.getZone(ownerOrigin)), ownerOrigin, copyOrigin,
                    0)));
            String copyCode = HohenheimMcpTest.code(DnsZoneModel.MODEL_ID, copyZone, TenantConduits.operator());
            McpHttpSession operator = HohenheimMcpTest.presetSession(server, endpoint, admin.get(UserModel.ID),
                "Test Admin");

            // 1. Refusals, each in its own words, nothing changed: someone who is no administrator, a primary zone,
            //    a copy behind the owner, a signed zone, an owner not listing this server, an owner unable to call
            //    back, records tied to the owner, an older owner, an owner that cannot be reached.
            UUID customerId = ApiSupport.user("dns-move-" + tag + "@move.test", "Move Customer");
            McpHttpSession customer = HohenheimMcpTest.presetSession(server, endpoint, customerId, "Move Customer");
            assertRefused(customer.call("dns_zone_take_over", Map.of("dns_zone", copyCode)), "step 1a", null);
            assertRefused(operator.call("dns_zone_take_over", Map.of("dns_zone", ownerOrigin)), "step 1b", null);
            setSerial(ownerZone, serial + 5);
            assertRefused(take(operator, copyCode), "step 1c", "not current");
            setSerial(ownerZone, serial);
            assign(ownerZone, DnsZoneModel.DNSSEC_ENABLED, true);
            assertRefused(take(operator, copyCode), "step 1d", "DNSSEC");
            assign(ownerZone, DnsZoneModel.DNSSEC_ENABLED, false);
            Models.get(DnsZonePeerModel.class).delete(ownerLink);
            assertRefused(take(operator, copyCode), "step 1e", "does not list this Hohenheim");
            linkZonePeer(ownerZone, takerPeer);
            Row taker = Models.get(DnsPeerModel.class).findById(takerPeer);
            taker.set(DnsPeerModel.API_KEY, null);
            Models.get(DnsPeerModel.class).save(taker);
            assertRefused(take(operator, copyCode), "step 1f", "holds no API key");
            taker.set(DnsPeerModel.API_KEY, "test-peer-key");
            Models.get(DnsPeerModel.class).save(taker);
            int[] tied = new int[1];
            GeneratedDnsRecords.as(new GeneratedDnsRecords.Attribution("preview", "hohenheim:preview_deployment", 1),
                () -> tied[0] = record(ownerZone, "preview", DnsRecordModel.TYPE_A, "192.0.2.90"));
            assertRefused(take(operator, copyCode), "step 1g", "made itself");
            GeneratedDnsRecords.sweeping(() -> Models.get(DnsRecordModel.class).delete(tied[0]));
            relay.serve(copyPath + "/state", StubHost.Answer.status(404, Map.of()));
            assertRefused(take(operator, copyCode), "step 1h", "older Hohenheim");
            Row owner = Models.get(DnsPeerModel.class).findById(ownerPeer);
            String relayUrl = owner.get(DnsPeerModel.BASE_URL);
            owner.set(DnsPeerModel.BASE_URL, gone.url(""));
            Models.get(DnsPeerModel.class).save(owner);
            gone.close();
            assertRefused(take(operator, copyCode), "step 1i", "could not be asked");
            owner.set(DnsPeerModel.BASE_URL, relayUrl);
            Models.get(DnsPeerModel.class).save(owner);
            relay.serveBy(copyPath + "/state", request -> renamed(DnsRecordForwardingTest.relayed(
                requestTo(ownerPath + "/state").header("Authorization", "Bearer " + peerKey).GET()),
                ownerOrigin, copyOrigin, ownerKey, takerKey));
            assertUnmoved(copyZone, copyOrigin, ownerZone, ownerRows, serial, "step 1");

            // 2. The owner refuses the copy it pulls (it serves another serial): the move is undone here, the owner
            //    left exactly as it was, and every name answers throughout.
            servedBump.set(2);
            McpHttpSession.Answer undone = answeredWhileAsking(() -> take(operator, copyCode),
                List.of("www." + copyOrigin, "www." + ownerOrigin), "step 2");
            assertThat(undone.error()).as("step 2: refused " + undone.text()).isTrue();
            assertThat(undone.text()).as("step 2: undone, naming the owner").contains("undone").contains(ownerName);
            assertUnmoved(copyZone, copyOrigin, ownerZone, ownerRows, serial, "step 2");
            assertThat(lastFact(copyZone, HohenheimActivityAction.TAKE_OVER_UNDONE)).as("step 2: in the history")
                .containsEntry(HohenheimActivityAction.SERVER_KEY, ownerName);

            // 3. The move: the copy's server keeps the records at the next serial, the owner copies the zone from it,
            //    and every name answers throughout.
            servedBump.set(1);
            McpHttpSession.Answer moved = answeredWhileAsking(() -> take(operator, copyCode),
                List.of("www." + copyOrigin, "www." + ownerOrigin), "step 3");
            assertThat(moved.error()).as("step 3: " + moved.text()).isFalse();
            assertThat(moved.text()).as("step 3: where the records are now").contains("kept here now")
                .contains(String.valueOf(serial + 1)).contains(ownerName);
            Row taken = zone(copyZone);
            assertThat(DnsZoneModel.roleOf(taken)).as("step 3: primary here").isEqualTo(DnsZoneModel.ROLE_PRIMARY);
            assertThat(taken.get(DnsZoneModel.SERIAL)).as("step 3: the next serial").isEqualTo(serial + 1);
            assertThat(taken.get(DnsZoneModel.TRANSFER_STATUS)).as("step 3: no copy's transfer left").isNull();
            assertThat(rows(copyZone)).as("step 3: every record row here").isEqualTo(ownerRows);
            Row acme = Models.get(DnsRecordModel.class).find().noCache().where(DnsRecordModel.ZONE_ID.eq(copyZone))
                .where(DnsRecordModel.NAME.eq("_acme-challenge")).first();
            assertThat(acme.get(DnsRecordModel.MANAGED_BY)).as("step 3: a certificate proof stays machine-owned")
                .isEqualTo(DnsRecordModel.MANAGED_BY_ACME);
            assertThat(Models.get(DnsZonePeerModel.class).find().noCache()
                .where(DnsZonePeerModel.ZONE_ID.eq(copyZone)).where(DnsZonePeerModel.PEER_ID.eq(ownerPeer)).first())
                .as("step 3: the old owner is its secondary").isNotNull();
            assertThat(lastFact(copyZone, HohenheimActivityAction.TAKEN_OVER)).as("step 3: the taker's history")
                .containsEntry(HohenheimActivityAction.SERVER_KEY, ownerName);
            Row handed = zone(ownerZone);
            assertThat(DnsZoneModel.roleOf(handed)).as("step 3: the owner copies it now")
                .isEqualTo(DnsZoneModel.ROLE_SECONDARY);
            assertThat(handed.get(DnsZoneModel.PRIMARY_PEER_ID)).as("step 3: from the taker").isEqualTo(takerPeer);
            assertThat(handed.get(DnsZoneModel.TRANSFER_STATUS)).as("step 3: its copy current")
                .isEqualTo(DnsZoneModel.TRANSFER_OK);
            assertThat(rows(ownerZone)).as("step 3: the owner's rows gone").isZero();
            assertThat(DnsZoneStore.INSTANCE.secondarySnapshot(ownerOrigin)).as("step 3: the owner serves the copy")
                .isNotNull().extracting(DnsZoneSnapshot::getSerial).isEqualTo((long) serial + 1);
            assertThat(Models.get(DnsZonePeerModel.class).find().noCache()
                .where(DnsZonePeerModel.ZONE_ID.eq(ownerZone)).count()).as("step 3: no link to the new primary")
                .isZero();
            assertThat(lastFact(ownerZone, HohenheimActivityAction.HANDED_OVER)).as("step 3: the owner's history")
                .containsEntry(HohenheimActivityAction.SERVER_KEY, DnsFederationKeys.localName());
            DnsSecondaryFreshness.probeZone(zone(copyZone));
            assertThat(Models.get(DnsZonePeerModel.class).find().noCache()
                .where(DnsZonePeerModel.ZONE_ID.eq(copyZone)).first().get(DnsZonePeerModel.STALE_ALERTED_AT))
                .as("step 3: a probe of the old owner raises no stale alert").isNull();

            // 4. The dynamic DNS token moved by its digest: a router's update reaches the record where it is kept.
            Row moving = Models.get(DnsRecordModel.class).find().noCache().where(DnsRecordModel.ZONE_ID.eq(copyZone))
                .where(DnsRecordModel.NAME.eq("dyn")).first();
            assertThat(Models.get(DnsDyndnsCredentialModel.class).find().noCache()
                .where(DnsDyndnsCredentialModel.RECORD_ID.eq(moving.get(DnsRecordModel.ID))).first()
                .get(DnsDyndnsCredentialModel.TOKEN_DIGEST)).as("step 4: the digest, never a token")
                .isEqualTo(DynamicDnsService.digest(token));
            HttpResponse<String> updated = this.nicUpdate(token, "dyn." + copyOrigin, "198.51.100.2");
            assertThat(updated.body()).as("step 4: answered").isEqualTo("good 198.51.100.2");
        } finally {
            sourceDns.stop();
            DnsZoneStore.INSTANCE.removeSecondarySnapshot(ownerOrigin);
            DnsZoneStore.INSTANCE.removeSecondarySnapshot(copyOrigin);
        }
    }

    @Test
    void aDynamicUpdateForACopiedZoneIsSentToTheServerKeepingIt() throws Exception {
        String tag = HohenheimMcpTest.letters();
        String origin = "dyn-" + tag + ".move.test";
        String token = DynamicDnsService.mintToken();
        try (StubHost relay = StubHost.startSecure("dyn-" + tag + ".move.peer.test")) {
            relay.serve("/nic/update", StubHost.Answer.ok("text/plain", "good 198.51.100.7"));
            int peer = apiPeer("dyn-owner-" + tag, relay.url(""));
            createZone(origin, DnsZoneModel.ROLE_SECONDARY, peer);

            // 1. A token no record here holds, for a name in a zone this server copies: sent on, the client's own
            //    address with it, the owner's answer passed back.
            HttpResponse<String> forwarded = this.nicUpdate(token, "home." + origin, "198.51.100.7");
            assertThat(forwarded.body()).as("step 1: the owner's answer").isEqualTo("good 198.51.100.7");
            StubHost.Request sent = Objects.requireNonNull(relay.last("/nic/update"), "step 1: sent on");
            assertThat(sent.query()).as("step 1: the name and the client's address").contains("home." + origin)
                .contains("myip=198.51.100.7");
            assertThat(new String(Base64.getDecoder().decode(Objects.requireNonNull(sent.headers()
                .get("Authorization")).substring("Basic ".length())), StandardCharsets.UTF_8))
                .as("step 1: the token as the Basic password").endsWith(":" + token);

            // 2. Without myip the address this server saw goes, never its own.
            this.nicUpdate(token, "home." + origin, null);
            assertThat(Objects.requireNonNull(relay.last("/nic/update")).query()).as("step 2: the caller's address")
                .contains("myip=");

            // 3. A name no copied zone holds: refused here, nothing sent.
            int hits = relay.hits("/nic/update");
            assertThat(this.nicUpdate(token, "home.nowhere-" + tag + ".test", "198.51.100.7").body())
                .as("step 3: refused").isEqualTo("badauth");
            assertThat(relay.hits("/nic/update")).as("step 3: nothing sent").isEqualTo(hits);
        }
    }

    /** @return the tool's answer to taking the zone over */
    private static McpHttpSession.Answer take(McpHttpSession session, String zone) {
        return session.call("dns_zone_take_over", Map.of("dns_zone", zone));
    }

    private static void assertRefused(McpHttpSession.Answer answer, String step, @Nullable String says) {
        assertThat(answer.error()).as(step + ": refused " + answer.text()).isTrue();
        if (says != null) assertThat(answer.text()).as(step + ": in its words").contains(says);
    }

    /** Asserts the move left both servers as they were: the copy a copy, the owner keeping its rows. */
    private static void assertUnmoved(int copyZone, String copyOrigin, int ownerZone, int ownerRows, int serial,
                                      String step) {
        Row copy = zone(copyZone);
        assertThat(DnsZoneModel.roleOf(copy)).as(step + ": still a copy").isEqualTo(DnsZoneModel.ROLE_SECONDARY);
        assertThat(copy.get(DnsZoneModel.SERIAL)).as(step + ": its serial").isEqualTo(serial);
        assertThat(copy.get(DnsZoneModel.TRANSFER_STATUS)).as(step + ": current").isEqualTo(DnsZoneModel.TRANSFER_OK);
        assertThat(rows(copyZone)).as(step + ": no rows beside the copy").isZero();
        assertThat(DnsZoneStore.INSTANCE.secondarySnapshot(copyOrigin)).as(step + ": the copy answers")
            .isNotNull().extracting(DnsZoneSnapshot::getSerial).isEqualTo((long) serial);
        Row owner = zone(ownerZone);
        assertThat(DnsZoneModel.roleOf(owner)).as(step + ": the owner keeps it").isEqualTo(DnsZoneModel.ROLE_PRIMARY);
        assertThat(owner.get(DnsZoneModel.SERIAL)).as(step + ": at its serial").isEqualTo(serial);
        assertThat(rows(ownerZone)).as(step + ": with its rows").isEqualTo(ownerRows);
        assertThat(DnsZoneStore.INSTANCE.secondarySnapshot(Objects.requireNonNull(owner.get(DnsZoneModel.ORIGIN))))
            .as(step + ": serving its own rows").isNull();
    }

    /**
     * Runs the call while asking the serving view, without pause, the A record of every name: each answer, before,
     * during and after, must be the name's address.
     *
     * @return the call's answer
     */
    private static McpHttpSession.Answer answeredWhileAsking(Supplier<McpHttpSession.Answer> call,
                                                            List<String> names, String step) throws Exception {
        DnsResponder responder = new DnsResponder(DnsZoneStore.INSTANCE);
        Queue<String> wrong = new ConcurrentLinkedQueue<>();
        AtomicInteger asked = new AtomicInteger();
        AtomicBoolean asking = new AtomicBoolean(true);
        List<Message> queries = new ArrayList<>();
        for (String name : names) {
            queries.add(Message.newQuery(Record.newRecord(Name.fromString(name + "."), Type.A, DClass.IN)));
        }
        JobRunner loop = JobRunner.create("dns-move-queries");
        loop.startBlockingThread(() -> {
            while (asking.get()) {
                for (Message query : queries) {
                    Message answer = responder.respond(query.clone());
                    asked.incrementAndGet();
                    if (answer == null || answer.getRcode() != Rcode.NOERROR || answer.getSection(Section.ANSWER)
                            .stream().noneMatch(r -> r instanceof ARecord a
                                && a.getAddress().getHostAddress().equals(WWW))) {
                        wrong.add(query.getQuestion().getName() + " "
                            + (answer == null ? "no answer" : Rcode.string(answer.getRcode())));
                    }
                }
            }
        });
        McpHttpSession.Answer answer;
        try {
            answer = call.get();
        } finally {
            asking.set(false);
            loop.stop(JobRunnerGroup.StopMode.DRAIN, 5_000);
        }
        assertThat(asked.get()).as(step + ": the names were asked throughout").isGreaterThan(names.size());
        assertThat(wrong).as(step + ": every answer the name's address").isEmpty();
        return answer;
    }

    /** Serves on the source nameserver the records the taker serves for its origin, renamed to the owner's. */
    private static void serve(DnsZoneStore source, String from, String to, int bump) {
        try {
            source.injectPrimarySnapshot(DnsZoneStore.snapshotFromTransfer(9004, to,
                renamed(Objects.requireNonNull(DnsZoneStore.INSTANCE.getZone(from), "the taker serves " + from), from,
                    to, bump)));
        } catch (Exception unbuilt) {
            throw new IllegalStateException("the renamed copy could not be built", unbuilt);
        }
    }

    /** @return the snapshot's records under another origin, its serial raised by {@code bump} */
    private static List<Record> renamed(DnsZoneSnapshot snapshot, String from, String to, int bump) throws Exception {
        List<Record> records = new ArrayList<>();
        SOARecord soa = snapshot.getSoa();
        records.add(new SOARecord(Name.fromString(to + "."), DClass.IN, soa.getTTL(), soa.getHost(), soa.getAdmin(),
            soa.getSerial() + bump, soa.getRefresh(), soa.getRetry(), soa.getExpire(), soa.getMinimum()));
        for (Record record : snapshot.allRecordsExceptSoa()) {
            String name = record.getName().toString();
            records.add(record.withName(Name.fromString(name.substring(0, name.length() - from.length() - 1) + to
                + ".")));
        }
        return records;
    }

    /** @return the owner's answer as the copy's server would read it: its origin and its half of the key renamed */
    private static StubHost.Answer renamed(StubHost.Answer answer, String ownerOrigin, String copyOrigin,
                                           String ownerKey, String takerKey) {
        String body = new String(answer.body(), StandardCharsets.UTF_8).replace(ownerOrigin, copyOrigin)
            .replace(ownerKey, takerKey);
        return new StubHost.Answer(answer.status(), answer.contentType(), body.getBytes(StandardCharsets.UTF_8),
            Map.of());
    }

    private HttpResponse<String> nicUpdate(String token, String hostname, @Nullable String myip) throws Exception {
        String basic = Base64.getEncoder().encodeToString(("router:" + token).getBytes(StandardCharsets.UTF_8));
        String query = "?hostname=" + URLEncoder.encode(hostname, StandardCharsets.UTF_8)
            + (myip == null ? "" : "&myip=" + URLEncoder.encode(myip, StandardCharsets.UTF_8));
        return sendRequest(requestTo("/nic/update" + query).header("Authorization", "Basic " + basic));
    }

    /** Gives the peer its half of the shared transfer key and, when given, where it transfers from. */
    private static void key(int peerId, String keyName, @Nullable String host, @Nullable Integer port) {
        Row peer = Models.get(DnsPeerModel.class).findById(peerId);
        peer.set(DnsPeerModel.TSIG_KEY_NAME, keyName);
        peer.set(DnsPeerModel.TSIG_ALGORITHM, "hmac-sha256");
        peer.set(DnsPeerModel.TSIG_SECRET, SECRET);
        peer.set(DnsPeerModel.TRANSFER_HOST, host);
        peer.set(DnsPeerModel.TRANSFER_PORT, port);
        Models.get(DnsPeerModel.class).save(peer);
    }

    /** Marks the copy transferred and current at the serial, as a pull leaves it. */
    private static void currentCopy(int zoneId, int serial) {
        Models.get(DnsZoneModel.class).find().where(DnsZoneModel.ID.eq(zoneId)).assign(DnsZoneModel.SERIAL, serial)
            .assign(DnsZoneModel.TRANSFER_STATUS, DnsZoneModel.TRANSFER_OK)
            .assign(DnsZoneModel.LAST_TRANSFER_AT, Now.instant()).updateAll();
    }

    private static void setSerial(int zoneId, int serial) {
        assign(zoneId, DnsZoneModel.SERIAL, serial);
    }

    private static void assign(int zoneId, Field<?, ?> field, Object value) {
        Models.get(DnsZoneModel.class).find().where(DnsZoneModel.ID.eq(zoneId)).assign(field, value).updateAll();
    }

    private static Row zone(int zoneId) {
        return Models.get(DnsZoneModel.class).find().noCache().where(DnsZoneModel.ID.eq(zoneId)).first();
    }

    private static int rows(int zoneId) {
        return (int) Models.get(DnsRecordModel.class).find().noCache().where(DnsRecordModel.ZONE_ID.eq(zoneId)).count();
    }

    /** @return the body of the zone's last fact of that kind */
    private static Map<String, Object> lastFact(int zoneId, HohenheimActivityAction kind) {
        List<RecordedChanges.Recorded> facts = RecordedChanges.withFact(Models.get(DnsZoneModel.class), zoneId, kind);
        assertThat(facts).as(kind + " is in the zone's history").isNotEmpty();
        return Objects.requireNonNull(facts.getLast().fact(kind));
    }
}
