package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.activity.OperationSentences;
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
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.PatchBase;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    /** The argument naming the host account the service runs as, by its code (SU12). */
    private static final String HOST_ACCOUNT = "host_account";

    /** The installation every write of {@link #SET} goes through, as the panel's form saves it. */
    private static final SpamserviceInstallationResource WRITER = new SpamserviceInstallationResource();

    /**
     * Saves the installation as its form does, then brings the running service in line in the background; a patch,
     * so a setting left out stays as it is. Its host account is named by code.
     */
    public static final Operation<Void, OperationInput.Values, String> SET =
            Operation.declare(HohenheimIds.id("set_spamservice_installation"))
        .happened(OperationSentences.of("set_spamservice_installation"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("set_spamservice_installation"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("set_spamservice_installation_description"))
        .noSubject()
        .noun(SpamserviceInstallationModel.REF)
        .gate(HohenheimSources.OPERATOR)
        .input(OperationInput.derived(FormSpec.builder()
            .add(KindSettingsWrites.entryOf(SpamserviceInstallationModel.ENABLED))
            .add(KindSettingsWrites.entryOf(SpamserviceInstallationModel.PORT))
            .add(OperationForms.recordId(HOST_ACCOUNT, HohenheimMicrocopy.HELP.of("spam_service_host_account"),
                SystemUserModel.MODEL_ID, false))
            .add(KindSettingsWrites.entryOf(SpamserviceInstallationModel.MAX_HEAP_MB))
            .build()))
        .result(String.class)
        .patchable()
        .facts(OperationFact.IDEMPOTENT)
        .command(OperationCommand.perVerb().onDatasource("default"))
        .register();

    static {
        SpamserviceOperations.init();
        // No stored row is the base: the installation is one, so a change writes what the call sent.
        OperationHandlers.attach(SET)
            .patchBase(PatchBase.ofValues(SET, (none, access) -> Map.of()))
            .handle(call -> set(Objects.requireNonNull(call.input()), call.patched(),
                Objects.requireNonNull(call.access(), "the installation is set by its caller")));
    }

    /** @return the settings written, as a change of a record says them */
    private static @NonNull String set(OperationInput.@NonNull Values input, @Nullable Set<String> patched,
                                       @NonNull AccessContext caller) {
        Set<String> sent = new LinkedHashSet<>(patched != null ? patched : input.coerced().keySet());
        Map<String, Object> written = new LinkedHashMap<>();
        for (String name : sent) {
            Object value = input.get(name);
            if (HOST_ACCOUNT.equals(name)) {
                written.put(SpamserviceInstallationModel.SYSTEM_USER_ID.getInputName(), RawValues.positiveInt(value));
            } else {
                written.put(name, value);
            }
        }
        Row loaded = WRITER.loadOrCreate(caller);
        WRITER.inMutationTransaction(() -> WRITER.persist(loaded, written, caller));
        return KindSettingsWrites.changedLine("spam service", String.join(", ", sent));
    }

    @Override
    public void persist(@NonNull Row loaded, @NonNull Map<String, Object> coerced, @NonNull AccessContext context) {
        super.persist(loaded, coerced, context);
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
                    (request, status) -> CmsActionResult.refreshWithToast(
                        HohenheimMicrocopy.SPAMSERVICE.of("test_ok").withArg("status", status)))
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
