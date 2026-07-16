package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetRegistry;
import be.elevenways.zenit.widget.common.WidgetType;
import org.checkerframework.checker.nullness.qual.NonNull;

/** First-run guidance shown while the installation has no sites. */
@ZenitAutoLoad
public final class OnboardingWidget implements WidgetType {

    public static final Identifier ID = Identifier.of("hohenheim", "onboarding");
    public static final Identifier DISPLAY_TEMPLATE = Identifier.of("hohenheim", "cms/widget-onboarding");
    public static final OnboardingWidget INSTANCE = new OnboardingWidget();

    static {
        WidgetRegistry.INSTANCE.register(INSTANCE);
    }

    private OnboardingWidget() {}

    @Override public @NonNull Identifier id() { return ID; }
    @Override public @NonNull Microcopy label() { return Microcopy.of("onboarding").withFilter("scope", "dashboard"); }
    @Override public @NonNull FormSpec configSpec() { return FormSpec.builder().build(); }
    @Override public @NonNull Icon icon() { return Icon.of("rocket"); }
    @Override public @NonNull Identifier displayTemplateId() { return DISPLAY_TEMPLATE; }
}
