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
 * The host's live contact state: a status dot, the state word and the last-contact
 * relative time.
 *
 * AIDEV-NOTE: deliberately NOT folded into {@code zenitwidget:status}. That widget
 * renders BADGES, and this cell's whole point is that it is not one -- the dot carries
 * the verdict and the relative time carries when it was last true. Reducing it to a pill
 * would drop the timestamp, which is the half an operator reads.
 *
 * @author Jelle De Loecker
 * @since  0.5.0
 */
@ZenitAutoLoad
public final class HostStateWidget implements WidgetType {

    public static final Identifier ID = Identifier.of("hohenheim", "host_state");
    public static final Identifier DISPLAY_TEMPLATE =
        Identifier.of("hohenheim", "cms/widget-host-state");

    public static final HostStateWidget INSTANCE = new HostStateWidget();

    static {
        WidgetRegistry.INSTANCE.register(INSTANCE);
    }

    private final FormSpec configSpec = FormSpec.builder().build();

    private HostStateWidget() {}

    @Override
    public @NonNull Identifier id() {
        return ID;
    }

    @Override
    public @NonNull Microcopy label() {
        return Microcopy.of("state").withFilter("scope", "server_overview");
    }

    @Override
    public @NonNull FormSpec configSpec() {
        return this.configSpec;
    }

    @Override
    public @NonNull Icon icon() {
        return Icon.of("tower-broadcast");
    }

    @Override
    public @NonNull Identifier displayTemplateId() {
        return DISPLAY_TEMPLATE;
    }
}
