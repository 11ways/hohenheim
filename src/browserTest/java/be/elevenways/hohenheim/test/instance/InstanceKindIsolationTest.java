package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;

import static org.assertj.core.api.Assertions.assertThat;

/** A fixture class cannot change the next class's picker vocabulary, and registration can recur after restoration. */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class InstanceKindIsolationTest {

    @Nested
    @Order(1)
    class Registers {
        @Test
        void declaresFixtureKinds() {
            FakeNativeDaemons.register();
            assertThat(InstanceKinds.getHandler(FakeNativeDaemons.FakeNativeKind.ID.toString()))
                .as("step 1: fixture kinds exist for their own class").isNotNull();
        }
    }

    @Nested
    @Order(2)
    class Restored {
        @Test
        void seesTheOriginalVocabularyAndCanRegisterAgain() {
            assertThat(InstanceKindRegistry.REGISTRY.get(FakeNativeDaemons.FakeNativeKind.ID))
                .as("step 2: the previous class did not leave its fake kind in the picker").isNull();
            FakeNativeDaemons.register();
            assertThat(InstanceKinds.getHandler(FakeNativeDaemons.FakeNativeKind.ID.toString()))
                .as("step 3: restoration did not leave an install-once flag preventing a fresh fixture").isNotNull();
        }
    }
}
