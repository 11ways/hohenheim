package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.resource.ActivityHistoryPage;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.cms.common.resource.RevisionHistoryPage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No /manage resource offers the operator's activity or revision history, and the framework
 * 404s a subpage a resource does not offer -- so a tenant can neither see nor route to it.
 *
 * AIDEV-NOTE: ten Manage* resources inherited their admin base's subpages() and with it the
 * framework History tab: the admin audit trail of a record (who changed what, including
 * operator edits) rendered on the delegated surface. The check walks the DECLARED peers, so a
 * new Manage* resource that forgets its subpages() override fails here by name.
 */
class ManageHistoryHiddenTest extends HohenheimTestBase {

    @Test
    void noDelegatedResourceOffersTheOperatorHistory() {
        // 1. Counterfactual anchor: the ADMIN bases do offer history in this runtime, so an
        //    empty result below is the override working and not a runtime without history.
        List<Resource<?>> operatorBases = List.of(new GitProviderResource(),
            new ProtectedPathResource(), new AccessRuleResource(), new PreviewDeploymentResource());
        boolean operatorHasHistory = operatorBases.stream()
            .anyMatch(resource -> !historyPages(resource).isEmpty());
        assertThat(operatorHasHistory)
            .as("step 1: the operator resources offer a history page, or this proves nothing")
            .isTrue();

        // 2. Every resource the /manage panel declares offers none.
        List<String> checked = new ArrayList<>();
        for (PanelPeer peer : ManagePanel.declarePeers()) {
            if (!(peer instanceof Resource<?> resource)) {
                continue;
            }
            checked.add(resource.slug());
            assertThat(historyPages(resource))
                .as("step 2: /manage/%s must not offer the operator's activity or revision"
                    + " history", resource.slug())
                .isEmpty();
        }
        assertThat(checked)
            .as("step 2: the walk really visited the delegated resources")
            .hasSizeGreaterThan(10);
    }

    private static List<String> historyPages(Resource<?> resource) {
        List<String> found = new ArrayList<>();
        for (RecordScopedPage<?> page : resource.subpages()) {
            if (page instanceof ActivityHistoryPage || page instanceof RevisionHistoryPage<?>) {
                found.add(page.slug());
            }
        }
        return found;
    }
}
