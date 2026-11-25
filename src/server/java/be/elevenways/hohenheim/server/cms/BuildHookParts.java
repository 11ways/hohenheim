package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimVerbs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.BuildHookModel;
import be.elevenways.hohenheim.model.HohenheimPrincipalKind;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.source.BuildHooks;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.CmsTemplateIds;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.cms.common.resource.CellMapper;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SecretResult;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.TextBlockWidget;
import be.elevenways.zenit.server.operation.RecordCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Build hook parts: the create, regenerate and revoke operations, the operator's hook list and the owner's /manage
 * twin, and the application's Build hooks tab as the framework's child list over them.
 *
 * AIDEV-NOTE: every verb demands MANAGE on the hook's application, the owner's capability (an operator holds it
 * through the admin walk): a hook is a standing delegation of the application's deploy to whoever holds its URL. The
 * create asks it through its gate's parent capability; regenerate and revoke through their authorizers off the request
 * memo (the offer, once per row) and again, freshly, in their handlers. Create and regenerate answer the URL as their
 * one-time SecretResult, so zenit-cms shows it once in their toast; the list only ever shows the masked preview.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class BuildHookParts {

    /** The hooks of some application; the /manage scope narrows this same base per principal. */
    public static final RowScope ROWS = RowScope.within(() -> BuildHookModel.INSTANCE_ID.isNotNull());

    /** The virtual column drawing the outcome of the newest release the hook started. */
    private static final String LAST_DEPLOY_COLUMN = "last_deploy";

    /** The last-deploy pill's key for a hook that started no release yet. */
    private static final String NO_DEPLOY = "none";

    private static final SubjectType<Row> SUBJECT = SubjectType.record(BuildHookModel.REF);

    /** Why the create and regenerate are kept from assistants. */
    private static final String SHOWS_TOKEN = "it answers the hook's new token once, and an assistant is never sent a"
        + " secret";

    private static final OperationCommand COMMAND = OperationCommand.perSubject();

    /** The hook form: its application is the fixed parent (named, never posted from elsewhere), its name typed. */
    private static final FormSpec FORM = FormSpec.builder()
        .add(RelationPick.of(BuildHookModel.INSTANCE_ID, InstanceModel.MODEL_ID).build())
        .add(BuildHookModel.NAME)
        .build();

    /** The posted hook form, one component per form entry as the operation boot check requires. */
    public record HookInput(@Nullable Integer instance_id, @Nullable String name) {}

    /** Creates a hook on its application and answers the hook's id with its URL, once. */
    public static final Operation<Void, HookInput, SecretResult<UUID>> CREATE =
            Operation.declare(HohenheimIds.id("build_hook_create"))
        .happened(OperationSentences.of("build_hook_create"))
        .label(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_create"))
        .noSubject()
        .notForAssistants(SHOWS_TOKEN)
        .gate(OperationGate.open()
            .parentCapability(BuildHookModel.INSTANCE_ID.getName(), InstanceModel.MODEL_ID,
                HohenheimCapabilities.MANAGE))
        .input(OperationInput.of(FORM, HookInput.class, values -> new HookInput(
            values.get(BuildHookModel.INSTANCE_ID), values.get(BuildHookModel.NAME))))
        .result(SecretResult.<UUID>type())
        .command(COMMAND.onDatasource("default"))
        .register();

    /** Replaces a live hook's URL, the old one dead at once, and answers the new one, once. */
    public static final Operation<Row, Void, SecretResult<UUID>> REGENERATE =
            Operation.declare(HohenheimIds.id("build_hook_regenerate"))
        .happened(OperationSentences.of("build_hook_regenerate"))
        .label(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_regenerate"))
        .icon(Icon.of("rotate"))
        .one(SUBJECT)
        .notForAssistants(SHOWS_TOKEN)
        .gate(OperationGate.open())
        .result(SecretResult.<UUID>type())
        .command(COMMAND)
        .register();

    /** Revokes a hook: its URL answers like any unknown one from then on. */
    public static final Operation<Row, Void, Void> REVOKE =
            Operation.declare(HohenheimIds.id("build_hook_revoke"))
        .happened(OperationSentences.of("build_hook_revoke"))
        .label(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_revoke"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("build_hook_revoke_description"))
        .icon(Icon.of("xmark"))
        .one(SUBJECT)
        .verb(HohenheimVerbs.REVOKE)
        .gate(OperationGate.open())
        .command(COMMAND)
        .register();

    /** The argument naming the app a tool's hook deploys, by its code. */
    public static final String APP = "app";

    /** The app a tool's hook is made for, named by its code; written into the hook's own column. */
    private static final FormEntry APP_ENTRY = OperationForms.recordId(APP,
        HohenheimMicrocopy.HELP.of("build_hook_app"), InstanceModel.MODEL_ID, BuildHookModel.INSTANCE_ID);

    /**
     * A tool's hook: made on an app named by its code, its URL never answered but parked for the caller's account on
     * the app's Build hooks tab ({@link TokenDisclosures}), where the panel's create shows it in its toast.
     */
    public static final Operation<Void, OperationInput.Values, UUID> MAKE =
            Operation.declare(HohenheimIds.id("make_build_hook"))
        .happened(OperationSentences.of("make_build_hook"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("make_build_hook"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("make_build_hook_description"))
        .noSubject()
        .gate(OperationGate.open().parentCapability(APP, InstanceModel.MODEL_ID, HohenheimCapabilities.MANAGE))
        .input(OperationInput.derived(FormSpec.builder().add(APP_ENTRY).add(BuildHookModel.NAME).build()))
        .result(UUID.class)
        .createdRecord(BuildHookModel.MODEL_ID, hook -> hook)
        .command(COMMAND.onDatasource("default"))
        .register();

    /** A tool's new URL of a live hook, the old one dead at once, parked as {@link #MAKE} parks it. */
    public static final Operation<Row, Void, UUID> REPLACE_URL =
            Operation.declare(HohenheimIds.id("replace_build_hook_url"))
        .happened(OperationSentences.of("replace_build_hook_url"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("replace_build_hook_url"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("replace_build_hook_url_description"))
        .one(SUBJECT)
        .gate(OperationGate.open())
        .result(UUID.class)
        .command(COMMAND)
        .register();

    static {
        OperationHandlers.attach(CREATE).handle(call -> {
            HookInput input = Objects.requireNonNull(call.input(), "the hook form is the input");
            return BuildHooks.mint(input.instance_id(), input.name());
        });
        OperationHandlers.attach(REGENERATE)
            .applies(hook -> hook.get(BuildHookModel.REVOKED_AT) == null)
            .authorize((hook, input, access) -> manageRefusal(managedBy(hook, access)))
            .handle(call -> {
                requireManage(call);
                return BuildHooks.regenerate(call.subject());
            });
        OperationHandlers.attach(REVOKE)
            .applies(hook -> hook.get(BuildHookModel.REVOKED_AT) == null)
            .authorize((hook, input, access) -> manageRefusal(managedBy(hook, access)))
            .handle(call -> {
                requireManage(call);
                BuildHooks.revoke(call.subject());
                return null;
            });
        OperationHandlers.attach(MAKE).handle(call -> {
            OperationInput.Values input = Objects.requireNonNull(call.input(), "a hook's app and name are the input");
            AccessContext caller = Objects.requireNonNull(call.access(), "a hook is made by its caller");
            requireShownTo(caller);
            Object app = input.get(APP);
            Integer instanceId = app == null ? null : Integer.valueOf(String.valueOf(app));
            SecretResult<UUID> made;
            try {
                made = BuildHooks.mint(instanceId, (String) input.get(BuildHookModel.NAME.getInputName()));
            } catch (Violations refused) {
                throw refused.renamed(Map.of(BuildHookModel.INSTANCE_ID.getName(), APP));
            }
            park(caller, Objects.requireNonNull(instanceId), made);
            return made.key();
        });
        OperationHandlers.attach(REPLACE_URL)
            .applies(hook -> hook.get(BuildHookModel.REVOKED_AT) == null)
            .authorize((hook, input, access) -> manageRefusal(managedBy(hook, access)))
            .handle(call -> {
                requireManage(call);
                AccessContext caller = Objects.requireNonNull(call.access());
                requireShownTo(caller);
                SecretResult<UUID> renewed = BuildHooks.regenerate(call.subject());
                park(caller, call.subject().get(BuildHookModel.INSTANCE_ID), renewed);
                return renewed.key();
            });
    }

    private BuildHookParts() {
    }

    /** @return the operator's hooks, with the history tab */
    public static @NonNull PanelResource<Row> admin() {
        return base(HohenheimIds.id("build_hook"))
            .scope(ROWS)
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return the owner's hooks: those of applications it manages; the admin history stays off the delegated surface */
    public static @NonNull PanelResource<Row> manage() {
        return ManageTwin.reached(base(ManageTwin.id("build_hook")), TenantScopes.BUILD_HOOKS,
                ResourceTabs.<Row>none().withContributions())
            .build();
    }

    /**
     * The Build hooks tab of an application that deploys through the release engine: the hook list of the hosting
     * panel narrowed to it; any other kind has no deploy a hook could start, so the tab is absent.
     *
     * @param operator whether the tab is the operator's, over {@link #admin}, or an owner's
     */
    public static @NonNull ChildList<Row> tab(boolean operator) {
        return ChildList.<Row>of(operator ? admin() : manage())
            .icon(Icon.of("link"))
            .secondary()
            .lead(HohenheimMicrocopy.BUILD_HOOK.of("build_hooks_lead"))
            .visibleWhen((instance, access) -> BuildHooks.takesHooks(instance))
            // A URL a tool made for this viewer's account is shown here once, as the panel's toast shows its own.
            .widgets((instance, access) -> waiting(instance.get(InstanceModel.ID), access))
            .hide(HohenheimSlugs.BUILD_HOOKS, BuildHookModel.INSTANCE_ID.getName());
    }

    /**
     * Refuses a call whose made URL would be shown to no account.
     *
     * @throws Violations before anything is made
     */
    private static void requireShownTo(@NonNull AccessContext caller) {
        if (!TokenDisclosures.accountOf(caller)) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("build_hook_no_account"));
        }
    }

    /** Parks a made URL, named by its hook, for the caller's account on the app's Build hooks tab. */
    private static void park(@NonNull AccessContext caller, int instanceId, @NonNull SecretResult<UUID> made) {
        Row hook = Objects.requireNonNull(Models.get(BuildHookModel.class).findById(made.key()), "the hook made");
        String code = RecordCodes.codeUnjudged(BuildHookModel.MODEL_ID, String.valueOf(made.key()));
        Objects.requireNonNull(TokenDisclosures.BUILD_HOOKS.park(caller, instanceId, code + " "
            + hook.get(BuildHookModel.NAME) + ": " + made.secret().reveal()), "an account stands behind the caller");
    }

    /** @return the URLs a tool made for the viewer's account on the app, once, as the tab's band; null for none */
    private static @Nullable WidgetTree waiting(@Nullable Integer instanceId, @NonNull AccessContext viewer) {
        String urls = instanceId == null ? null : claimWaiting(instanceId, viewer);
        return urls == null ? null : new WidgetTree(List.of(TextBlockWidget.of(
            HohenheimMicrocopy.BUILD_HOOK.of("build_hook_urls_waiting").withArg("urls", urls))));
    }

    /**
     * The URLs a tool made for the viewer's account on the app, claimed once, as its Build hooks tab shows them.
     *
     * @return one line per hook, its code, name and URL; null for none
     */
    public static @Nullable String claimWaiting(int instanceId, @NonNull AccessContext viewer) {
        return TokenDisclosures.BUILD_HOOKS.claim(viewer, instanceId);
    }

    /** The parts both twins share: identity, the fixed parent, the form, the list, its reads, writes and actions. */
    private static PanelResource.@NonNull Builder<Row> base(@NonNull Identifier id) {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(BuildHookModel.NAME).subtext(BuildHookModel.URL_PREVIEW.getName()).build())
            .column(ColumnSpec.fromField(BuildHookModel.URL_PREVIEW).hidden().build())
            .column(ColumnSpec.fromField(BuildHookModel.INSTANCE_ID)
                .relation(RelationPick.of(BuildHookModel.INSTANCE_ID, InstanceModel.MODEL_ID).build()).build())
            .column(ColumnSpec.fromField(BuildHookModel.LAST_TRIGGERED_AT).build())
            .column(ColumnSpec.virtual(LAST_DEPLOY_COLUMN, HohenheimMicrocopy.BUILD_HOOK.of("build_hook_last_deploy"))
                .renderer(CmsTemplateIds.CELL_ENUM_BADGE).build())
            .column(ColumnSpec.fromField(BuildHookModel.REVOKED_AT).build())
            .column(ColumnSpec.fromField(BuildHookModel.CREATED_AT).hidden().build())
            .defaultSort(SortSpec.desc(BuildHookModel.CREATED_AT.getName()))
            .build();
        return PanelResource.builder(id, HohenheimSlugs.BUILD_HOOKS, SUBJECT)
            .label(HohenheimMicrocopy.BUILD_HOOK.of("build_hooks_plural"))
            .recordLabel(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_singular"))
            .description(CmsSupport.navHint(HohenheimMicrocopy.BUILD_HOOK))
            .navGroup(HohenheimPanel.DEPLOY_GROUP).navOrder(22).icon(Icon.of("link")).showInNav(false)
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, BuildHookModel.INSTANCE_ID)
                .tab(HohenheimSlugs.Tab.BUILD_HOOKS).fixed())
            // The outcome of the newest release each listed hook started, read for the whole page at once.
            .reads(ResourceReads.rows().pageBatch((hooks, access) -> lastDeployCells(hooks)))
            .form(ResourceForm.<Row>of(FORM)
                // A hook is seen where it lives, beside its siblings: the toast showing its URL lands there too.
                .createdRecordUrl((hook, request) -> CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.INSTANCES,
                    hook.get(BuildHookModel.INSTANCE_ID), HohenheimSlugs.Tab.BUILD_HOOKS))
                .build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL)
                .emptyDescription(HohenheimMicrocopy.BUILD_HOOK.of("build_hooks_empty"))
                // The one-record read; a listed page answers from the page batch above.
                .computed(Objects.requireNonNull(table.column(LAST_DEPLOY_COLUMN)),
                    (hook, request) -> lastDeployCells(List.of(hook))
                        .cell(hook, Objects.requireNonNull(table.column(LAST_DEPLOY_COLUMN))))
                .build())
            .writes(ResourceMutations.rows().create(CREATE).build())
            // The MANAGE capability the create's gate asks, asked earlier so the tab offers only what it allows.
            .authority(ResourceAuthority.<Row>builder()
                .createUnder((instance, access) -> instance instanceof Integer instanceId
                    && HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID, instanceId,
                        HohenheimCapabilities.MANAGE))
                .build())
            .actions(List.of(regenerate(), revoke()));
    }

    /** Regenerate: confirmed, since the old URL stops working; the new URL rides its toast once. */
    private static @NonNull PanelAction<Row> regenerate() {
        return PanelAction.<Row, SecretResult<UUID>>places(REGENERATE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(
                        HohenheimMicrocopy.BUILD_HOOK.of("build_hook_regenerated"))
                    .withSecretArg("url", result.secret().reveal()))
            .hiddenWhen(hook -> hook.get(BuildHookModel.REVOKED_AT) != null)
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_regenerate"),
                HohenheimMicrocopy.BUILD_HOOK.of("build_hook_regenerate_confirm"), ActionStyle.DEFAULT))
            .build();
    }

    /** Revoke: confirmed and terminal; the row stays so the releases the hook started keep naming it. */
    private static @NonNull PanelAction<Row> revoke() {
        return PanelAction.<Row, Void>places(REVOKE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(
                    HohenheimMicrocopy.BUILD_HOOK.of("build_hook_revoked_toast")))
            .style(ActionStyle.DESTRUCTIVE)
            .hiddenWhen(hook -> hook.get(BuildHookModel.REVOKED_AT) != null)
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_revoke"),
                HohenheimMicrocopy.BUILD_HOOK.of("build_hook_revoke_confirm"), ActionStyle.DESTRUCTIVE))
            .build();
    }

    /**
     * The newest release each hook of the page started, read in one grouped query over the attribution the release
     * engine stamps (the hook as the operation's principal); a hook that started none answers "None yet", never null,
     * so no row falls back to a read of its own.
     */
    private static @NonNull CellMapper<Row> lastDeployCells(@NonNull List<Row> hooks) {
        List<UUID> ids = new ArrayList<>(hooks.size());
        for (Row hook : hooks) {
            ids.add(hook.get(BuildHookModel.ID));
        }
        Map<UUID, Row> newest = Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.TRIGGERED_BY_KIND.eq(HohenheimPrincipalKind.BUILD_HOOK.id().toString()))
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC).firstOfEach(ReleaseOperationModel.TRIGGERED_BY, ids);
        return (hook, column) -> {
            if (!LAST_DEPLOY_COLUMN.equals(column.name())) {
                return null;
            }
            Row operation = newest.get((UUID) hook.get(BuildHookModel.ID));
            Object status = operation == null ? null : operation.get(ReleaseOperationModel.STATUS);
            return status != null ? EnumBadgeState.of(ReleaseOperationModel.STATUS, status)
                : new EnumBadgeState(NO_DEPLOY, HohenheimMicrocopy.BUILD_HOOK.of("build_hook_no_deploy"), null,
                    "minus", BadgeVariant.SECONDARY, null, true);
        };
    }

    /**
     * Whether the caller may shape this hook: MANAGE on its application, the render face.
     *
     * AIDEV-NOTE: reachesRecord answers off the request memo, because this runs once per RENDERED ROW; the handlers
     * ask {@link #requireManage}, the FRESH walk, before they write.
     */
    private static boolean managedBy(@NonNull Row hook, @NonNull AccessContext access) {
        return HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID, hook.get(BuildHookModel.INSTANCE_ID),
            HohenheimCapabilities.MANAGE);
    }

    private static @Nullable DomainRefusal manageRefusal(boolean managed) {
        return managed ? null : new DomainRefusal(ZenitRefusalReason.FORBIDDEN,
            "shaping a build hook demands MANAGE on its application");
    }

    /** @throws DomainRefusal FORBIDDEN unless the caller holds MANAGE on the hook's application now */
    private static void requireManage(@NonNull OperationCall<Row, Void> call) {
        AccessContext access = Objects.requireNonNull(call.access(), "a build hook operation has its caller");
        if (!HohenheimAccess.hasInstanceCapability(access, call.subject().get(BuildHookModel.INSTANCE_ID),
                HohenheimCapabilities.MANAGE)) {
            throw new DomainRefusal(ZenitRefusalReason.FORBIDDEN,
                "shaping a build hook demands MANAGE on its application");
        }
    }
}
