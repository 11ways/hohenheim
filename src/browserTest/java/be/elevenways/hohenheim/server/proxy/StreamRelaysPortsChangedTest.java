package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.upstream.kinds.StreamUpstreamKind;
import be.elevenways.hohenheim.test.FreePorts;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.test.StreamSiteRows;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.session.InMemorySessionStore;
import be.elevenways.zenit.test.support.ServedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proxy's stream relays tell the host's app firewall when the ports they serve the public change, and only then:
 * the change that sends the isolation sweep over every app network of the host at once.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class StreamRelaysPortsChangedTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    @Test
    void aStreamPortThatStartsOrStopsBeingServedIsToldOnceAndAnythingElseIsNot() throws Exception {
        AtomicInteger told = new AtomicInteger();
        StreamRelays relays = new StreamRelays(new InMemorySessionStore(60), told::incrementAndGet);
        int port = FreePorts.tcpAndUdp();
        try {
            // 1. The first reconcile tells nothing: the boot run of the sweep applies what is served then.
            relays.reconcile();
            assertThat(told).as("step 1: nothing told at the first reconcile").hasValue(0);

            // 2. A stream comes online on a port: told once, and the port really listens.
            int site = StreamSiteRows.save("relays-told-mail", Map.of(String.valueOf(port), "587/tcp"), true);
            relays.reconcile();
            assertThat(told).as("step 2: THE ASSERTION, a newly served port is told").hasValue(1);
            assertThat(relays.tcpAddress(site, port)).as("step 2: and listens").isNotNull();

            // 3. A reload that serves the same public ports (another port upstream, the same reconcile again) tells
            //    nothing: an unrelated site edit never sends the sweep.
            Row row = Models.get(SiteModel.class).findById(site);
            row.set(SiteModel.SETTINGS, Map.of(StreamUpstreamKind.FORWARD_HOST.getName(), "10.0.0.20",
                StreamUpstreamKind.PORTS.getName(), Map.of(String.valueOf(port), "2587/tcp")));
            Models.get(SiteModel.class).save(row);
            relays.reconcile();
            relays.reconcile();
            assertThat(told).as("step 3: the same public ports tell nothing").hasValue(1);

            // 4. The stream is switched off: told again, and the port is closed.
            StreamSiteRows.switchTo(site, false);
            relays.reconcile();
            assertThat(told).as("step 4: THE ASSERTION, a port no longer served is told").hasValue(2);
            assertThat(relays.tcpAddress(site, port)).as("step 4: and no longer listens").isNull();
        } finally {
            relays.closeAll();
        }
    }
}
