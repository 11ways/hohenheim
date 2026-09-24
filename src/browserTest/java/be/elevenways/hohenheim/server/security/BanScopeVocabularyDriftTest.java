package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.security.BanScope;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the stored {@code bans.scope} field and the token listing to {@link BanScope}, their
 * one declaring home: adding a scope is one enum member, never a second list to update.
 */
class BanScopeVocabularyDriftTest {

    @Test
    void theStoredScopeFieldAndTheTokenListAreDerivedFromTheEnum() {
        // 1. The token listing is every member, in declaration order.
        List<String> expected = new ArrayList<>();
        for (BanScope scope : BanScope.values()) {
            expected.add(scope.token());
        }
        assertThat(BanScope.tokens()).as("step 1: tokens() lists every member")
            .containsExactlyElementsOf(expected);

        // 2. The stored field declares exactly those tokens, each with its member's facts.
        assertThat(BanModel.SCOPE.getValues().keySet()).as("step 2: the field holds every token")
            .containsExactlyElementsOf(expected);
        for (BanScope scope : BanScope.values()) {
            EnumField.EnumValue value = BanModel.SCOPE.getValues().get(scope.token());
            assertThat(value.getDisplayName()).as("step 2: " + scope + " display name")
                .isEqualTo(scope.displayName());
            assertThat(value.getColor()).as("step 2: " + scope + " color").isEqualTo(scope.color());
            assertThat(value.getLabel()).as("step 2: " + scope + " label").isEqualTo(scope.label());
        }

        // 3. The stored tokens production rows carry keep their meaning.
        assertThat(BanScope.fromToken("web")).as("step 3: 'web' stays WEB").isEqualTo(BanScope.WEB);
        assertThat(BanScope.fromToken("ssh")).as("step 3: 'ssh' stays SSH").isEqualTo(BanScope.SSH);
    }
}
