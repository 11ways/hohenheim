package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.HeaderAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowSingleton;
import be.elevenways.zenit.common.edit.FormSpec;
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

    public static final String SLUG = "spamservice-installation";
    private final FormSpec formSpec = FormSpec.builder()
        .add(SpamserviceInstallationModel.ENABLED)
        .add(SpamserviceInstallationModel.PORT)
        .add(RelationPick.of(SpamserviceInstallationModel.SYSTEM_USER)
            .source(HohenheimSources.SPAMSERVICE_SYSTEM_USERS).build())
        .add(SpamserviceInstallationModel.MAX_HEAP_MB)
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "spamservice_installation"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("installation").withFilter("scope", "spamservice"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Model model() { return Models.get(SpamserviceInstallationModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 10; }
    @Override public @NonNull Icon icon() { return Icon.of("download"); }

    @Override
    public void persist(@NonNull Map<String, Object> coerced, @NonNull AccessContext context) {
        super.persist(coerced, context);
        SpamserviceManager.get().reconcile();
    }

    @Override
    public @NonNull List<HeaderAction> headerActions() {
        return List.of(
            action("start", "play", manager -> manager.start()),
            action("stop", "stop", manager -> manager.stop()),
            action("restart", "rotate", manager -> manager.restart()),
            HeaderAction.Invoke.builder(Identifier.of("hohenheim", "spamservice_test"))
                .label(Microcopy.of("test").withFilter("scope", "spamservice"))
                .description(Microcopy.of("test_hint").withFilter("scope", "spamservice"))
                .icon(Icon.of("stethoscope"))
                .handler(context -> {
                    var status = SpamserviceManager.get().requireClient().status();
                    return CmsActionResult.refreshWithToast(Microcopy.of("test_ok")
                        .withFilter("scope", "spamservice").withArg("status", status.status()));
                }).build());
    }

    private static HeaderAction action(String name, String icon, ManagerAction action) {
        HeaderAction.Invoke.Builder builder = HeaderAction.Invoke.builder(
                Identifier.of("hohenheim", "spamservice_" + name))
            .label(Microcopy.of(name).withFilter("scope", "spamservice"))
            .description(Microcopy.of(name + "_hint").withFilter("scope", "spamservice"))
            .icon(Icon.of(icon))
            .handler(context -> {
                action.run(SpamserviceManager.get());
                return CmsActionResult.refreshWithToast(
                    Microcopy.of(name + "_ok").withFilter("scope", "spamservice"));
            });
        if ("stop".equals(name) || "restart".equals(name)) {
            builder.style(ActionStyle.DESTRUCTIVE).confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of(name).withFilter("scope", "spamservice"))
                .body(Microcopy.of(name + "_confirm").withFilter("scope", "spamservice")).build());
        }
        return builder.build();
    }

    @FunctionalInterface
    private interface ManagerAction {
        void run(SpamserviceManager manager);
    }
}
