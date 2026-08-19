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
 * Everything this host carries: the same three populations that block cordon, drain and delete.
 *
 * App-local because the markup is about hohenheim's own evidence and nothing generic
 * renders it; "the page is a tree" never meant "every card is a built-in".
 *
 * @author Jelle De Loecker
 * @since  0.5.0
 */
@ZenitAutoLoad
public final class HostWorkloadsWidget implements WidgetType {

    public static final Identifier ID = Identifier.of("hohenheim", "host_workloads");
    public static final Identifier DISPLAY_TEMPLATE =
        Identifier.of("hohenheim", "cms/widget-host-workloads");

    public static final HostWorkloadsWidget INSTANCE = new HostWorkloadsWidget();

    static {
        WidgetRegistry.INSTANCE.register(INSTANCE);
    }

    private final FormSpec configSpec = FormSpec.builder().build();

    private HostWorkloadsWidget() {}

    @Override
    public @NonNull Identifier id() {
        return ID;
    }

    @Override
    public @NonNull Microcopy label() {
        return Microcopy.of("workloads").withFilter("scope", "server_overview");
    }

    @Override
    public @NonNull FormSpec configSpec() {
        return this.configSpec;
    }

    @Override
    public @NonNull Icon icon() {
        return Icon.of("cubes");
    }

    @Override
    public @NonNull Identifier displayTemplateId() {
        return DISPLAY_TEMPLATE;
    }
}
