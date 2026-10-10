package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowSingleton;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/** Local Spamservice installation singleton without exposing its controller key. */
public final class SpamserviceInstallationResource extends RowSingleton {

    private final FormSpec formSpec = FormSpec.builder()
        .add(SpamserviceInstallationModel.ENABLED)
        .add(SpamserviceInstallationModel.PORT)
        .add(RelationPick.of(SpamserviceInstallationModel.SYSTEM_USER)
            .source(HohenheimSources.SPAMSERVICE_SYSTEM_USERS).build())
        .add(SpamserviceInstallationModel.MAX_HEAP_MB)
        .build();

    @Override public @NonNull Identifier id() { return HohenheimIds.id("spamservice_installation"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.SPAMSERVICE.of("installation"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.SPAMSERVICE_INSTALLATION; }
    @Override public @NonNull Model model() { return Models.get(SpamserviceInstallationModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 10; }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull String standsUnder() { return HohenheimSlugs.SPAMSERVICE; }
    @Override public @NonNull Icon icon() { return Icon.of("download"); }

    static {
        SpamserviceOperations.init();
    }

    @Override
    public void persist(@NonNull Map<String, Object> coerced, @NonNull AccessContext context) {
        super.persist(coerced, context);
        SpamserviceManager.get().reconcile();
    }

    /**
     * The installation's lifecycle verbs on the edit page's toolbar; each one's availability is its operation's
     * ({@link SpamserviceOperations}), so a dead control explains itself and a POST is refused with the same words.
     */
    @Override
    public @NonNull List<PanelAction<Void>> actions() {
        return List.of(
            lifecycle(SpamserviceOperations.START, "start"),
            destructive(SpamserviceOperations.STOP, "stop"),
            destructive(SpamserviceOperations.RESTART, "restart"),
            PanelAction.<Void, String>places(SpamserviceOperations.TEST, ActionPlacement.HEADER,
                    (request, result) -> CmsActionResult.refreshWithToast(
                        HohenheimMicrocopy.SPAMSERVICE.of("test_ok").withArg("status", result.value())))
                .build());
    }

    private static @NonNull PanelAction<Void> lifecycle(@NonNull Operation<Void, Void, Void> operation,
                                                        @NonNull String name) {
        return toasting(operation, name).build();
    }

    private static @NonNull PanelAction<Void> destructive(@NonNull Operation<Void, Void, Void> operation,
                                                          @NonNull String name) {
        return toasting(operation, name)
            .style(ActionStyle.DESTRUCTIVE)
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.SPAMSERVICE.of(name),
                HohenheimMicrocopy.SPAMSERVICE.of(name + "_confirm"), ActionStyle.DESTRUCTIVE))
            .build();
    }

    /** A lifecycle verb's answer: the page refreshes with the verb's own {@code <name>_ok} toast. */
    private static PanelAction.@NonNull OperationBuilder<Void, Void> toasting(
            @NonNull Operation<Void, Void, Void> operation, @NonNull String name) {
        return PanelAction.<Void, Void>places(operation, ActionPlacement.HEADER,
            (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.SPAMSERVICE.of(name + "_ok")));
    }
}
