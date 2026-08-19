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
 * Every port claim an instance holds, joined to its host's declared address.
 *
 * App-local because the table is about hohenheim's port ledger and nothing generic
 * renders it; the record's front door is a widget tree, which does not mean every
 * card on it has to be a framework built-in.
 *
 * @author Jelle De Loecker
 * @since  0.5.0
 */
@ZenitAutoLoad
public final class InstanceEndpointsWidget implements WidgetType {

    public static final Identifier ID = Identifier.of("hohenheim", "instance_endpoints");
    public static final Identifier DISPLAY_TEMPLATE =
        Identifier.of("hohenheim", "cms/widget-instance-endpoints");

    public static final InstanceEndpointsWidget INSTANCE = new InstanceEndpointsWidget();

    static {
        WidgetRegistry.INSTANCE.register(INSTANCE);
    }

    private final FormSpec configSpec = FormSpec.builder().build();

    private InstanceEndpointsWidget() {}

    @Override
    public @NonNull Identifier id() {
        return ID;
    }

    @Override
    public @NonNull Microcopy label() {
        return Microcopy.of("endpoint").withFilter("scope", "instance_overview");
    }

    @Override
    public @NonNull FormSpec configSpec() {
        return this.configSpec;
    }

    @Override
    public @NonNull Icon icon() {
        return Icon.of("plug");
    }

    @Override
    public @NonNull Identifier displayTemplateId() {
        return DISPLAY_TEMPLATE;
    }
}
