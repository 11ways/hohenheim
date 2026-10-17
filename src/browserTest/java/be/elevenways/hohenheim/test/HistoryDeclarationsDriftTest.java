package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.HistoryDeclarationsDrift;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hohenheim's history declaration drift check: every Hohenheim model declares its recording, and every model a
 * Hohenheim history reader reads records something.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class HistoryDeclarationsDriftTest extends HohenheimTestBase {

    @Test
    void everyModelDeclaresItsHistoryAndEveryReadModelRecords() {
        // 1. Every model under Hohenheim's packages declares required, optional or untracked history.
        assertThat(HistoryDeclarationsDrift.ownedModels(HistoryDeclarationsDriftTest.class,
                "be.elevenways.hohenheim."))
            .as("step 1: the walk really sees Hohenheim's models").hasSizeGreaterThan(40);
        HistoryDeclarationsDrift.assertDeclared("be.elevenways.hohenheim.");

        // 2. Hohenheim's admin resources offering a History tab, and the record pages' recent cards, read recording
        //    models. (A framework resource the panel composes is its own repo's drift check.)
        Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN), "admin panel");
        Set<Model> read = new LinkedHashSet<>();
        for (PanelEntry entry : admin.entries()) {
            if (entry instanceof PanelResource<?> resource && resource.tabs().history() && resource.model() != null
                    && resource.model().getClass().getName().startsWith("be.elevenways.hohenheim.")) {
                read.add(resource.model());
            }
        }
        assertThat(read).as("step 2: the walk really visited the History tabs").hasSizeGreaterThan(10);
        read.add(Models.get(InstanceModel.class));
        read.add(Models.get(DatabaseModel.class));
        read.add(Models.get(ServerModel.class));
        HistoryDeclarationsDrift.assertReadable(read);
    }
}
