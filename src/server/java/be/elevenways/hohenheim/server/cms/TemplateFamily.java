package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The versions of one template the chooser shows as ONE card ("WordPress", not six PHP versions).
 *
 * AIDEV-NOTE: a family is read off the template NAME: "Family (variant)" names the same app in another version, the
 * spelling the starter seeders use (WordPressTemplateSeeder.templateName). It is presentation only: nothing stores or
 * enforces it, and a template named otherwise is a family of one. The current member is the last in name order, so the
 * newest version is offered first and the others stay one click away on the chosen card.
 *
 * @param name    the family's name: the template name without its "(variant)" suffix
 * @param members the family's templates in name order, never empty
 * @author Jelle De Loecker
 * @since  0.9.0
 */
record TemplateFamily(@NonNull String name, @NonNull List<Row> members) {

    /** @return the templates this viewer may create from, folded into families, in name order */
    static @NonNull List<TemplateFamily> of(@NonNull PanelRequest request) {
        Criteria scope = TenantScopes.INSTANCE_TEMPLATES.criteria(request.access());
        QueryBuilder<Row> query = Models.get(InstanceTemplateModel.class).find();
        Map<String, List<Row>> families = new LinkedHashMap<>();
        for (Row template : (scope == null ? query : query.where(scope))
                .orderBy(InstanceTemplateModel.NAME, SortOrder.ASC).all()) {
            String name = familyName(String.valueOf((Object) template.get(InstanceTemplateModel.NAME)));
            families.computeIfAbsent(name, ignored -> new ArrayList<>()).add(template);
        }
        List<TemplateFamily> folded = new ArrayList<>(families.size());
        families.forEach((name, members) -> folded.add(new TemplateFamily(name, List.copyOf(members))));
        return folded;
    }

    /** @return the name without a trailing " (variant)" */
    static @NonNull String familyName(@NonNull String templateName) {
        int open = templateName.lastIndexOf(" (");
        return open > 0 && templateName.endsWith(")") ? templateName.substring(0, open) : templateName;
    }

    /** @return the member the card opens: the newest version */
    @NonNull Row current() {
        return this.members.get(this.members.size() - 1);
    }

    /** @return the current member's description, empty when it has none */
    @NonNull String description() {
        Object description = this.current().get(InstanceTemplateModel.DESCRIPTION);
        return description == null ? "" : String.valueOf(description);
    }

    /** @return whether a template of this id is one of the members */
    boolean contains(@Nullable Object templateId) {
        for (Row member : this.members) {
            if (member.get(InstanceTemplateModel.ID).equals(templateId)) {
                return true;
            }
        }
        return false;
    }
}
