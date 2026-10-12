package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every Hohenheim action that opens a confirmation dialog titles it with its own question, never the shell's generic
 * "Are you sure?".
 *
 * AIDEV-NOTE: a PAGE placement is left out: its confirmation titles only the no-script interstitial, while its own
 * page carries the heading. The framework's synthesized delete is zenit-cms's, titled there.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class ActionConfirmationTitleTest extends HohenheimTestBase {

    /** The generic title, read from its one home rather than spelled here. */
    private static final Microcopy GENERIC_TITLE = ConfirmationSpec.generic(Microcopy.literal("-"), false).title();

    @Test
    void everyDialogActionNamesItsOwnTitle() {
        Set<String> generic = new TreeSet<>();
        int judged = 0;

        // 1. Walk every Hohenheim resource's placed actions on both panels.
        for (String slug : new String[]{HohenheimSlugs.ADMIN, HohenheimSlugs.MANAGE}) {
            Panel panel = Objects.requireNonNull(PanelRegistry.getBySlug(slug), slug);
            for (PanelEntry entry : panel.entries()) {
                if (!(entry instanceof PanelResource<?> resource)
                        || !"hohenheim".equals(resource.id().getNamespace())) {
                    continue;
                }
                for (PanelAction<?> action : resource.actions()) {
                    ConfirmationSpec confirmation = action.confirmation();
                    if (confirmation == null || action.placement() == ActionPlacement.PAGE
                            || !"hohenheim".equals(action.id().getNamespace())) {
                        continue;
                    }
                    judged++;
                    // 2. Its dialog asks its own question.
                    if (GENERIC_TITLE.equals(confirmation.title())) {
                        generic.add(slug + "/" + resource.slug() + ": " + action.id());
                    }
                }
            }
        }

        assertThat(judged).as("step 1: the walk reached the dialogs (a zero count is vacuous)").isGreaterThan(20);
        assertThat(generic).as("step 2: dialogs titled \"Are you sure?\" (declare a title naming the act)").isEmpty();
    }
}
