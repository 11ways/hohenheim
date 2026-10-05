package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.zenit.common.refusal.RefusalRecoveryDrift;
import org.junit.jupiter.api.Test;

/**
 * Hohenheim's refusal reasons keep their retry answer derived from the recovery each declares.
 *
 * @author Jelle De Loecker
 * @since 0.9.0
 */
class HohenheimRefusalReasonTest {

    @Test
    void everyReasonKeepsItsRetryDerivedFromItsRecovery() {
        // 1. The set passes the shared drift check, which zenit's own test never loads it for.
        RefusalRecoveryDrift.requireDerived(HohenheimRefusalReason.class);
    }
}
