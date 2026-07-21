package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetRegistry;
import be.elevenways.zenit.widget.common.WidgetType;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Dashboard attention panel: operational states that need an operator
 * (error certificates, down/degraded sites, failed databases, failed
 * latest deploys, failed task runs).
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
@ZenitAutoLoad
public final class AttentionWidget implements WidgetType {

    public static final Identifier ID = Identifier.of("hohenheim", "attention");
    public static final Identifier DISPLAY_TEMPLATE = Identifier.of("hohenheim", "cms/widget-attention");

    public static final AttentionWidget INSTANCE = new AttentionWidget();

    static {
        WidgetRegistry.INSTANCE.register(INSTANCE);
    }

    private final FormSpec configSpec = FormSpec.builder().build();

    private AttentionWidget() {}

    @Override
    public @NonNull Identifier id() {
        return ID;
    }

    @Override
    public @NonNull Microcopy label() {
        return Microcopy.of("attention").withFilter("scope", "dashboard");
    }

    @Override
    public @NonNull FormSpec configSpec() {
        return this.configSpec;
    }

    @Override
    public @NonNull Icon icon() {
        return Icon.of("bell");
    }

    @Override
    public @NonNull Identifier displayTemplateId() {
        return DISPLAY_TEMPLATE;
    }
}
