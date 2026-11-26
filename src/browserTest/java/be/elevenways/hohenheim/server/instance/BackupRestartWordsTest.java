package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.refusal.StoredRefusalWords;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A backup whose capture stopped the app that then did not start again keeps that as words each reader reads in their
 * own language, its reason the deploy vocabulary's, never a stored English sentence or the daemon's own text; a row
 * stored before reads as it was stored.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class BackupRestartWordsTest {

    @Test
    void aBackupWhoseAppDidNotStartAgainKeepsWordsEachReaderReads() {
        // 1. The row keeps the words unresolved, never a sentence.
        String stored = InstanceBackups.stored(InstanceBackups.notRestarted(
            new IllegalStateException("token=hunter2 the daemon's own words")));
        assertThat(StoredRefusalWords.read(stored)).as("step 1: words, revived").isNotNull();
        assertThat(stored).as("step 1: no daemon text kept").doesNotContain("hunter2");

        // 2. Each reader reads them in their own language, the word "app", the reason the deploy vocabulary's.
        Microcopy words = HohenheimViolations.storedMessage(stored);
        MessageResolver catalogs = new ShippedCatalogs();
        String english = words.resolve(LocaleChain.ofTags("en"), catalogs);
        assertThat(english).as("step 2: in English")
            .startsWith("The app could not be started again after the capture: ")
            .contains("does not recognise the reason").doesNotContain("workload").doesNotContain("hunter2");
        assertThat(words.resolve(LocaleChain.ofTags("nl"), catalogs)).as("step 2: in Dutch")
            .startsWith("De app kon na de opname niet opnieuw gestart worden: ");

        // 3. A row stored as a sentence before reads as it was stored.
        String older = "The workload could not be started again after the capture: gone";
        assertThat(HohenheimViolations.storedMessage(older).resolve(LocaleChain.ofTags("nl"), catalogs))
            .as("step 3: an older row as stored").isEqualTo(older);
    }
}
