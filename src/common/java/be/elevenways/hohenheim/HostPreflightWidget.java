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
 * The stored preflight report: kernel-truth isolation, every check with its own stamp, and the measured facts.
 *
 * App-local because the markup is about hohenheim's own evidence and nothing generic
 * renders it; "the page is a tree" never meant "every card is a built-in".
 *
 * @author Jelle De Loecker
 * @since  0.5.0
 */
@ZenitAutoLoad
public final class HostPreflightWidget implements WidgetType {

    public static final Identifier ID = Identifier.of("hohenheim", "host_preflight");
    public static final Identifier DISPLAY_TEMPLATE =
        Identifier.of("hohenheim", "cms/widget-host-preflight");

    public static final HostPreflightWidget INSTANCE = new HostPreflightWidget();

    static {
        WidgetRegistry.INSTANCE.register(INSTANCE);
    }

    private final FormSpec configSpec = FormSpec.builder().build();

    private HostPreflightWidget() {}

    @Override
    public @NonNull Identifier id() {
        return ID;
    }

    @Override
    public @NonNull Microcopy label() {
        return Microcopy.of("preflight_report").withFilter("scope", "server_overview");
    }

    @Override
    public @NonNull FormSpec configSpec() {
        return this.configSpec;
    }

    @Override
    public @NonNull Icon icon() {
        return Icon.of("stethoscope");
    }

    @Override
    public @NonNull Identifier displayTemplateId() {
        return DISPLAY_TEMPLATE;
    }
}
