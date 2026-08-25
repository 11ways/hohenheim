package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.ServiceStatus;
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
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("download"); }

    @Override
    public void persist(@NonNull Map<String, Object> coerced, @NonNull AccessContext context) {
        super.persist(coerced, context);
        SpamserviceManager.get().reconcile();
    }

    /**
     * AIDEV-NOTE: every lifecycle action here DECLARES why it cannot run, because all four
     * of them depend on runtime state the form itself does not show. zenit-cms renders the
     * declared reason as a disabled control carrying that text AND refuses the POST with it,
     * so a Stop button beside a service that was never started explains itself instead of
     * answering the generic "the action failed".
     */
    @Override
    public @NonNull List<HeaderAction> headerActions() {
        return List.of(
            action("start", "play", SpamserviceInstallationResource::configurationReason,
                manager -> manager.start()),
            action("stop", "stop", SpamserviceInstallationResource::runningReason,
                manager -> manager.stop()),
            action("restart", "rotate", SpamserviceInstallationResource::configurationReason,
                manager -> manager.restart()),
            HeaderAction.Invoke.builder(Identifier.of("hohenheim", "spamservice_test"))
                .label(Microcopy.of("test").withFilter("scope", "spamservice"))
                .description(Microcopy.of("test_hint").withFilter("scope", "spamservice"))
                .icon(Icon.of("stethoscope"))
                .unavailableWhen(context -> connectedReason(SpamserviceManager.get()))
                .handler(context -> testConnection()).build());
    }

    /**
     * Calls the management status endpoint and NAMES whatever went wrong.
     *
     * @return a success toast carrying the reported status, or an error toast carrying the
     *         service's own failure text
     */
    private static @NonNull CmsActionResult testConnection() {
        SpamserviceManager manager = SpamserviceManager.get();
        Microcopy unavailable = connectedReason(manager);
        if (unavailable != null) {
            return CmsActionResult.errorToast(unavailable);
        }
        try {
            ServiceStatus status = manager.requireClient().status();
            return CmsActionResult.refreshWithToast(Microcopy.of("test_ok")
                .withFilter("scope", "spamservice").withArg("status", status.status()));
        } catch (RuntimeException failure) {
            // The clientlib's message IS the diagnosis (connection refused, 401, a body the
            // service refused); swallowing it into cms.action.failed is what left the operator
            // with nothing to act on.
            return CmsActionResult.errorToast(copy("test_failed").withArg("reason", reasonOf(failure)));
        }
    }

    /** Why the installation itself cannot act, or null when it is configured and enabled. */
    private static @Nullable Microcopy configurationReason(@NonNull SpamserviceManager manager) {
        SpamserviceManager.Snapshot snapshot = manager.snapshot();
        if (!snapshot.configured()) {
            return copy("not_configured");
        }
        if (!snapshot.enabled()) {
            return copy("not_enabled");
        }
        return null;
    }

    /** Why nothing can be stopped, or null when a managed process is actually alive. */
    private static @Nullable Microcopy runningReason(@NonNull SpamserviceManager manager) {
        Microcopy configuration = configurationReason(manager);
        if (configuration != null) {
            return configuration;
        }
        return manager.snapshot().pid() == null ? copy("not_running") : null;
    }

    /** Why the management API cannot be called, or null when a client is installed. */
    private static @Nullable Microcopy connectedReason(@NonNull SpamserviceManager manager) {
        Microcopy running = runningReason(manager);
        if (running != null) {
            return running;
        }
        return manager.client() == null ? copy("not_connected") : null;
    }

    private static @NonNull Microcopy copy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "spamservice");
    }

    private static @NonNull String reasonOf(@NonNull Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static HeaderAction action(String name, String icon,
                                       Function<SpamserviceManager, Microcopy> unavailableWhen,
                                       ManagerAction action) {
        HeaderAction.Invoke.Builder builder = HeaderAction.Invoke.builder(
                Identifier.of("hohenheim", "spamservice_" + name))
            .label(Microcopy.of(name).withFilter("scope", "spamservice"))
            .description(Microcopy.of(name + "_hint").withFilter("scope", "spamservice"))
            .icon(Icon.of(icon))
            .unavailableWhen(context -> unavailableWhen.apply(SpamserviceManager.get()))
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
