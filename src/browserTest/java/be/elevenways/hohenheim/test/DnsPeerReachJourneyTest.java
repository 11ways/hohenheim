package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static be.elevenways.hohenheim.test.DnsFixtures.createZone;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An operator finds the DNS peers from a zone's Secondaries tab, adds one, sees it listed and removes it again.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class DnsPeerReachJourneyTest extends HohenheimTestBase {

    private static final String ORIGIN = "peer-reach.example";
    private static final String PEER = "peer-reach-ns2";

    @Test
    void anOperatorReachesAddsSeesAndRemovesADnsPeerFromAZonesSecondaries() throws Exception {
        int zoneId = createZone(ORIGIN);
        String secondaries = "/admin/" + HohenheimSlugs.DNS_ZONES + "/" + zoneId + "/page/secondaries";
        String peers = "/admin/" + HohenheimSlugs.DNS_PEERS;
        try {
            // 1. A zone without secondaries is no dead end: its Secondaries tab leads to the DNS peers a secondary is
            //    made from, and following it opens their list with its New DNS peer.
            assertThat(adminGet(secondaries).body()).as("step 1: the tab links to the DNS peers")
                .contains("href=\"" + peers + "\"").contains("DNS peers");
            navigateToApp(secondaries);
            page.locator("[href='" + peers + "']").first().click();
            page.waitForURL(url -> url.endsWith(peers));
            assertThat(page.content()).as("step 1: THE ASSERTION -- the peers list opens, offering a new one")
                .contains("New DNS peer");

            // 2. The operator adds a peer through the list's create, and the list shows it.
            Map<String, Object> peer = new LinkedHashMap<>();
            peer.put(DnsPeerModel.NAME.getName(), PEER);
            peer.put(DnsPeerModel.PEER_TYPE.getName(), DnsPeerModel.TYPE_NAMESERVER);
            peer.put(DnsPeerModel.TRANSFER_HOST.getName(), "192.0.2.53");
            peer.put(DnsPeerModel.TRANSFER_PORT.getName(), 53);
            peer.put(DnsPeerModel.ENABLED.getName(), true);
            Object peerId = PanelResourceCalls.create("admin", HohenheimSlugs.DNS_PEERS, peer,
                TenantConduits.operator());
            assertThat(adminGet(peers).body()).as("step 2: the new peer is listed").contains(PEER);

            // 3. Removing it takes it off the list.
            PanelResourceCalls.delete("admin", HohenheimSlugs.DNS_PEERS, peerId, TenantConduits.operator());
            assertThat(Models.get(DnsPeerModel.class).findFirst(DnsPeerModel.NAME, PEER))
                .as("step 3: the peer is gone").isNull();
            assertThat(adminGet(peers).body()).as("step 3: and no longer listed").doesNotContain(PEER);
        } finally {
            Row left = Models.get(DnsPeerModel.class).findFirst(DnsPeerModel.NAME, PEER);
            if (left != null) {
                HardDeletes.row(Models.get(DnsPeerModel.class), left);
            }
            HardDeletes.byId(Models.get(DnsZoneModel.class), zoneId);
        }
    }
}
