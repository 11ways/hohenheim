package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetType;
import be.elevenways.zenit.widget.common.builtin.RecordsWidget;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * An app overview's Recent card: the framework's records list inside a card, so its config IS the records widget's
 * own (source, rules, sort, limit) and validates against that one declaration.
 *
 * AIDEV-NOTE: not a {@link DisplayWidget}: that shape is configless, and this card's config is the records widget's.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AppRecentWidget implements WidgetType {

    private final Identifier id = HohenheimIds.id("app_recent");

    @Override
    public @NonNull Identifier id() {
        return this.id;
    }

    @Override
    public @NonNull Microcopy label() {
        return Microcopy.of("recent").withFilter("scope", "app_overview");
    }

    @Override
    public @NonNull Icon icon() {
        return Icon.of("clock-rotate-left");
    }

    @Override
    public @NonNull FormSpec configSpec() {
        return RecordsWidget.INSTANCE.configSpec();
    }

    @Override
    public @NonNull Identifier displayTemplateId() {
        return HohenheimTemplateIds.WIDGET_APP_RECENT;
    }
}
