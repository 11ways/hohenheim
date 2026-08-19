package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE binding between the two declaring sites of the database-engine vocabulary: the
 * stored tokens on {@link DatabaseModel#ENGINE} (common, what the form offers and the
 * column holds) and {@link ManagedDatabase.Engine} (server, what an image, port, data
 * path and footprint are declared on).
 *
 * AIDEV-NOTE: the two halves cannot see each other's TYPE -- common code cannot reference
 * the server enum -- so nothing but this test fails when one side grows a member. It binds
 * ACROSS the two sites deliberately: comparing the enum to constants sitting beside it in
 * the same class would be circular and would prove nothing.
 */
class DatabaseEngineVocabularyTest {

    @Test
    void everyStoredEngineTokenHasARuntimeEngineAndTheReverse() {
        // 1. Every token the model offers resolves to a runtime engine, through the ONE
        //    lookup the product uses -- never valueOf, which binds to the enum's name.
        List<String> tokens = new ArrayList<>(DatabaseModel.ENGINE.getValues().keySet());
        for (String token : tokens) {
            assertThat(ManagedDatabase.Engine.forToken(token))
                .as("step 1: engine token '" + token + "' names no ManagedDatabase.Engine"
                    + " -- declare one, or stop offering the token")
                .isNotNull();
        }

        // 2. And the reverse: an engine nobody can store is an engine nobody can reach.
        List<String> declared = new ArrayList<>();
        for (ManagedDatabase.Engine engine : ManagedDatabase.Engine.values()) {
            declared.add(engine.token());
        }
        assertThat(declared)
            .as("step 2: the runtime engines and the stored vocabulary are the same set")
            .containsExactlyInAnyOrderElementsOf(tokens);

        // 3. The lookup fails CLOSED: an unknown token is null, not a default engine that
        //    would run the wrong image on the wrong port.
        assertThat(ManagedDatabase.Engine.forToken("mariadb"))
            .as("step 3: an unknown token resolves to nothing")
            .isNull();
        assertThat(ManagedDatabase.Engine.forToken(null))
            .as("step 3: and neither does an absent one")
            .isNull();
        assertThat(ManagedDatabase.Engine.forToken("POSTGRES"))
            .as("step 3: the token is the stored spelling, not the enum name")
            .isNull();
    }
}
