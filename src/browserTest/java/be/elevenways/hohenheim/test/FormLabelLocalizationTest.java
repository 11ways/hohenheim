package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.panel.PartsForms;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.FormEntryLabels;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every field label on a Hohenheim form resolves from a shipped catalog in Dutch, never its humanized English fallback.
 *
 * AIDEV-NOTE: a field without a declared label is looked up by its bare name, unscoped, while Hohenheim ships its
 * field words under {@code hohenheim_field}; the lookup misses and the form prints the humanized name, which is
 * English in every locale (DD4: the Dutch host form read "Name" and "SSH target" beside "SSH-doel" in nl.json). The
 * walk reads the label the form renders ({@link FormEntryLabels#labelOf}) off the panels' own resources, so a field
 * added tomorrow is judged without a list here.
 *
 * AIDEV-NOTE: a field a framework model declares (zenit's RecordSchedule*Model on the schedule forms) is judged by
 * that framework, not here: those declare no label and ship no words, the gap DD4 recorded in the plan (section 41).
 */
class FormLabelLocalizationTest extends HohenheimTestBase {

    private static final LocaleChain DUTCH = LocaleChain.ofTags("nl");

    @Test
    void everyFormFieldLabelResolvesInDutch() {
        ShippedCatalogs catalogs = new ShippedCatalogs();
        Set<String> misses = new TreeSet<>();
        int judged = 0;

        // 1. Walk every Hohenheim resource's form on both panels, nested and records sub-forms included.
        for (String slug : new String[]{HohenheimSlugs.ADMIN, HohenheimSlugs.MANAGE}) {
            Panel panel = Objects.requireNonNull(PanelRegistry.getBySlug(slug), slug);
            for (PanelEntry entry : panel.entries()) {
                if (!(entry instanceof PanelResource<?> resource) || !"hohenheim".equals(resource.id().getNamespace())
                        || resource.form() == null) {
                    continue;
                }
                for (FormEntry field : PartsForms.formSpec(resource).staticEntries()) {
                    Microcopy label = FormEntryLabels.labelOf(field);
                    if (label.isLiteral() || !declaredHere(field)) {
                        continue;
                    }
                    judged++;
                    // 2. A label resolves when a Dutch catalog entry answers its key AND its filters.
                    if (catalogs.resolveSource(label.key(), DUTCH, label.filters()) == null) {
                        misses.add(slug + "/" + resource.slug() + ": " + field.name() + " -> " + label.key()
                            + label.filters().asMap());
                    }
                }
            }
        }

        assertThat(judged).as("step 1: the walk reached the forms (a zero count is vacuous)").isGreaterThan(100);
        assertThat(misses).as("step 2: form labels a Dutch reader sees in English (declare a hohenheim_field label "
            + "with an nl entry):\n" + String.join("\n", misses)).isEmpty();
    }

    /** @return whether Hohenheim declares the entry's field: a field of no model, or of a Hohenheim model */
    private static boolean declaredHere(@NonNull FormEntry entry) {
        Field<?, ?> field = entry.field();
        Schema schema = field == null ? null : field.getParentSchema();
        Model owner = schema == null ? null : Models.ownerOf(schema);
        return owner == null || owner.getClass().getPackageName().startsWith("be.elevenways.hohenheim.");
    }
}
