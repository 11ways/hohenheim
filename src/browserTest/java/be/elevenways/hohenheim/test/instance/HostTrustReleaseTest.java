package be.elevenways.hohenheim.test.instance;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.ServerParts;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Removing an Incus host gives back the trust entry this controller holds on its daemon, and only that one; the
 * delete dialog names it and the command that removes it by hand.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class HostTrustReleaseTest {


    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    @Test
    void removingAnIncusHostGivesBackOnlyItsOwnTrustEntry() throws Exception {
        // 1. An https Incus host with its own client identity holds one trust entry: its certificate's fingerprint.
        Row host = Models.get(ServerModel.class).createEmptyRow();
        host.set(ServerModel.NAME, "trust-release-host");
        host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        host.set(ServerModel.INCUS_URL, "https://192.0.2.10:8443");
        Models.get(ServerModel.class).save(host);
        IncusTrust.rotateIdentity(host);
        String fingerprint = IncusTrust.ownEntryOf(host);
        assertThat(fingerprint).as("step 1: the entry is the client certificate's fingerprint")
            .isEqualTo(IncusTrust.fingerprintOf(host.get(HostTrustSlot.INCUS_TLS.clientPublic())));

        // 2. Its delete dialog names that entry and the command that removes it when the host cannot be reached.
        Microcopy body = ServerParts.deleteBody(host).body();
        assertThat(body.key()).as("step 2: the Incus host's dialog speaks of its trust entry")
            .isEqualTo("delete_confirm_incus_trust");
        assertThat(body.args().get("fingerprint")).as("step 2: naming its exact fingerprint").isEqualTo(fingerprint);

        // 3. A host that holds no trust entry (a Docker host) keeps the plain dialog.
        Row docker = Models.get(ServerModel.class).createEmptyRow();
        docker.set(ServerModel.NAME, "trust-release-docker");
        assertThat(IncusTrust.ownEntryOf(docker)).as("step 3: a Docker host holds no entry").isNull();
        assertThat(ServerParts.deleteBody(docker).body().key()).isEqualTo("delete_confirm");

        // 4. The release removes exactly the controller's own entry; another client's stays.
        FakeIncusTransport daemon = new FakeIncusTransport();
        daemon.certificates.put(fingerprint, "hohenheim-trust-release");
        daemon.certificates.put("f".repeat(64), "someone-else");
        IncusClient client = new IncusClient(daemon);
        assertThat(IncusTrust.releaseOwnEntry(client, fingerprint)).as("step 4: the entry was there").isTrue();
        assertThat(daemon.certificates).as("step 4: only the other client's entry is left")
            .containsOnlyKeys("f".repeat(64));

        // 5. An entry already gone is no error: nothing else is touched.
        assertThat(IncusTrust.releaseOwnEntry(client, fingerprint)).as("step 5: nothing left to remove").isFalse();
        assertThat(daemon.certificates).containsOnlyKeys("f".repeat(64));
    }
}
