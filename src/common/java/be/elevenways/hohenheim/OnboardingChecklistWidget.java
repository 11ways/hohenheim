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
 * Dashboard readiness checklist: the ordered steps between a fresh install and a deployed
 * workload, each one derived from the REAL gate rather than restating it.
 *
 * The dashboard omits the whole band once every step is done, so it retires itself.
 *
 * @author Jelle De Loecker
 * @since  0.5.0
 */
@ZenitAutoLoad
public final class OnboardingChecklistWidget implements WidgetType {

    public static final Identifier ID = Identifier.of("hohenheim", "onboarding_checklist");
    public static final Identifier DISPLAY_TEMPLATE = Identifier.of("hohenheim", "cms/widget-onboarding-checklist");

    public static final OnboardingChecklistWidget INSTANCE = new OnboardingChecklistWidget();

    static {
        WidgetRegistry.INSTANCE.register(INSTANCE);
    }

    private final FormSpec configSpec = FormSpec.builder().build();

    private OnboardingChecklistWidget() {}

    @Override
    public @NonNull Identifier id() {
        return ID;
    }

    @Override
    public @NonNull Microcopy label() {
        return Microcopy.of("checklist_title").withFilter("scope", "onboarding_checklist");
    }

    @Override
    public @NonNull FormSpec configSpec() {
        return this.configSpec;
    }

    @Override
    public @NonNull Icon icon() {
        return Icon.of("list-check");
    }

    @Override
    public @NonNull Identifier displayTemplateId() {
        return DISPLAY_TEMPLATE;
    }
}
