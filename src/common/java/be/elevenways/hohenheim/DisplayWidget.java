package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetType;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE shape of a hohenheim display widget: a configless card whose content arrives as runtime widget data.
 *
 * AIDEV-NOTE: every app-local widget here is identity + label + icon + display template and nothing
 * else; eight hand-copied WidgetType classes spelled that out ~60 lines each. A widget that grows a
 * config spec or an editor is no longer this shape and gets a class of its own.
 *
 * @param id                the stored widget type id; stored widget trees reference it, so it never changes
 * @param label             the widget's name in a picker or editor
 * @param icon              the widget's icon
 * @param displayTemplateId the template that renders the card
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public record DisplayWidget(
        @NonNull Identifier id,
        @NonNull Microcopy label,
        @NonNull Icon icon,
        @NonNull Identifier displayTemplateId
) implements WidgetType {

    private static final FormSpec NO_CONFIG = FormSpec.builder().build();

    @Override
    public @NonNull FormSpec configSpec() {
        return NO_CONFIG;
    }
}
