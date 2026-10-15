package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.ports.PortPurpose;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Connect to card's lead follows its ports' purposes: players are named only where one of the ports is theirs.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class PortPurposeConnectLeadTest {

    @Test
    void theLeadSpeaksToPlayersOnlyWhereAPortIsTheirs() {
        // 1. A port for Other (a VM's web port) is no player's: the lead names clients alone.
        assertThat(PortPurpose.connectLead(List.of(PortPurpose.OTHER)).key())
            .as("step 1: an Other port's lead").isEqualTo("connect_hint_clients");

        // 2. Nor is a remote console, or no named port at all.
        assertThat(PortPurpose.connectLead(List.of(PortPurpose.RCON, PortPurpose.OTHER)).key())
            .as("step 2: console and Other ports").isEqualTo("connect_hint_clients");
        assertThat(PortPurpose.connectLead(List.of()).key()).as("step 2: no named port").isEqualTo("connect_hint_clients");

        // 3. A game, query or voice port is the players': one among others turns the lead to them.
        for (PortPurpose players : List.of(PortPurpose.GAME, PortPurpose.QUERY, PortPurpose.VOICE)) {
            assertThat(PortPurpose.connectLead(List.of(PortPurpose.OTHER, players)).key())
                .as("step 3: a %s port beside an Other one", players).isEqualTo("connect_hint");
        }
    }
}
