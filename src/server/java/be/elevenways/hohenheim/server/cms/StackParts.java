package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.hohenheim.server.stack.StackServiceKind;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionRequest;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.cms.common.resource.RowSave;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.Array;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.ResultStep;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * The managed Docker stack entries from parts: the stack, its services and their config files. A record edits
 * desired state only; deploys, stops and rollbacks are placed operations ({@link StackOperations}), so saving a form
 * never restarts containers.
 *
 * AIDEV-NOTE: every create and update first passes ONE validate-and-canonicalize hook per entry (beforeSave): the row
 * holds the submitted values over the stored ones, and the canonical value (a trimmed name) is written back onto the
 * row so the stored value is the validated one. The write is still PARTIAL: a check reads what was submitted
 * ({@link RowSave#values()}) and only judges what it changes. A config file's path and mode are the model's own rule
 * (ContainerFileRules, installed on the stack file schema), so every writer answers to it.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class StackParts {

    /** The virtual column carrying a failed stack's reason (the status badge's subtext). */
    static final String LAST_FAILURE_COLUMN = "last_failure";

    /** The list's quick-add entry; the host rides along as a preset. */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(StackModel.NAME.getName())
        .presets(StackModel.SERVER_ID.getName());

    private StackParts() {
    }

    /** @return the operator's stacks: desired state on the form, every runtime verb a placed operation */
    public static @NonNull PanelResource<Row> stacks() {
        FormSpec form = FormSpec.builder()
            .add(StackModel.NAME)
            .add(StackModel.ENABLED)
            // See InstanceResource: a host is enrolled deliberately, never inline.
            .add(RelationPick.of(StackModel.SERVER_ID, ServerModel.MODEL_ID).creatable(false).build())
            .add(StackModel.REGISTRY_SERVER)
            .add(StackModel.REGISTRY_USER)
            .add(StackModel.REGISTRY_PASSWORD)
            .add(StackModel.DESCRIPTION)
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            // The name becomes network/container/volume name segments, so it is copied often.
            .column(ColumnSpec.fromField(StackModel.NAME).filterable().subtext("description").copyable().build())
            .column(ColumnSpec.fromField(StackModel.DESCRIPTION).hidden().build())
            .column(ColumnSpec.fromField(StackModel.SERVER_ID)
                .relation(RelationPick.of(StackModel.SERVER_ID, ServerModel.MODEL_ID).build()).build())
            // A FAILED stack names WHY under its badge: the newest deployment's error, so the operator never has to
            // open the Deployments tab to learn what to fix.
            .column(ColumnSpec.fromField(StackModel.STATUS).filterable().subtext(LAST_FAILURE_COLUMN).build())
            .column(ColumnSpec.virtual(LAST_FAILURE_COLUMN, HohenheimMicrocopy.STACK.of("last_failure")).hidden()
                .build())
            .column(ColumnSpec.fromField(StackModel.ENABLED).filterable().build())
            .filter(FilterSpec.leaf(StackModel.NAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(StackModel.NAME)).build())
            .filter(FilterSpec.leaf(StackModel.STATUS, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(StackModel.STATUS)).build())
            .filter(FilterSpec.leaf(StackModel.ENABLED, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE)
                .label(FieldLabels.labelFor(StackModel.ENABLED)).build())
            .build();
        return PanelResource.builder(HohenheimIds.id("stack"), HohenheimSlugs.STACKS, StackOperations.STACK)
            .label(HohenheimMicrocopy.STACK.of("plural"))
            .recordLabel(HohenheimMicrocopy.STACK.of("singular"))
            .description(HohenheimMicrocopy.STACK.of("nav_hint"))
            .icon(Icon.of("layer-group"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(40)
            // Reached through the Apps list, whose toolbar links this list (HohenheimPanel's sidebar note); its pages
            // mark Apps in the sidebar.
            .showInNav(false)
            .standsUnder(HohenheimSlugs.APPS)
            .reads(ResourceReads.rows())
            // Name and description are all a stack carries.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(StackModel.NAME, StackModel.DESCRIPTION)
                .computed(Objects.requireNonNull(table.column(LAST_FAILURE_COLUMN)),
                    (stack, request) -> StackFailures.reasonOf(stack))
                .build())
            .form(ResourceForm.<Row>of(form)
                // The server pick defaults to the local daemon (ensuring its row exists for the picker).
                .createDefaults(request -> {
                    Map<String, Object> values = new LinkedHashMap<>(form.defaultValues());
                    values.put(StackModel.SERVER_ID.getName(), ServerModel.localServerId());
                    return Map.copyOf(values);
                })
                // AIDEV-NOTE: a stack created from the quick-add bar is INERT -- enabled defaults off, so nothing is
                // deployed until an operator opens the record and arms it. That is what makes a one-field bar safe on
                // a resource whose enable switch arms a boot-time deploy. Its host is the local daemon, as on the form.
                .quickCreate(QUICK_CREATE)
                .quickCreatePresets(access -> Map.of(StackModel.SERVER_ID.getName(), ServerModel.localServerId()))
                // AIDEV-NOTE: the description only. ENABLED arms a boot-time deploy (StackInstances), so one click
                // would start containers; NAME is embedded in every container, network and volume name, so a rename
                // is gated behind a LIVE Docker round-trip that can refuse or be unverifiable -- a cell that can answer
                // "cannot prove this is safe" is a cell in the wrong place.
                .inlineEditable(StackModel.DESCRIPTION)
                // AIDEV-NOTE: the record's front door is the SERVICES tab, the whole fix for "created a stack, now
                // what": everything that makes a stack RUN lives there, and its empty state names the next step. It
                // re-points the list's title link too, deliberately.
                .landingTab(HohenheimSlugs.Tab.SERVICES)
                .build())
            .writes(ResourceMutations.rows().create().update()
                .delete(StackOperations.DELETE_STACK)
                .ownsWriteEnvelope(ResourceVerb.DELETE)
                .beforeSave(StackParts::validStack)
                .build())
            .deleteConfirmation(DeleteConfirmation.of(
                DeleteConfirmation.body(HohenheimMicrocopy.STACK.of("delete_confirm"))))
            .actions(stackActions())
            .tabs(ResourceTabs.<Row>of(List.of(new StackServicesPage(), new StackDeploymentsPage())).withHistory()
                .withContributions())
            .build();
    }

    /** @return the services of the stacks, nav-hidden and reached through a stack's Services tab */
    public static @NonNull PanelResource<Row> services() {
        RelationPick stack = RelationPick.of(StackServiceModel.STACK_ID, StackModel.MODEL_ID).build();
        FormSpec form = FormSpec.builder()
            .add(stack)
            .add(StackServiceModel.NAME)
            .add(StackServiceModel.ENABLED)
            .add(StackServiceModel.IMAGE)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(StackServiceModel.COMMAND))
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(StackServiceModel.ENVIRONMENT))
            // A CLOSED multi-select over the allow-list, not free text: the capabilities a service may declare are
            // enumerated in ContainerHardening, and the form offers exactly that set.
            .add(Array.of(StackServiceModel.CAPABILITIES, StringField.builder().name("capability").build())
                .tags()
                .options(OptionSource.of(capabilityOptions()))
                .build())
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(StackServiceModel.MOUNTS))
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(StackServiceModel.PORTS))
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(StackServiceModel.DEPENDS_ON))
            .add(StackServiceModel.HEALTH_CMD)
            .add(StackServiceModel.HEALTH_INTERVAL_SECONDS)
            .add(StackServiceModel.HEALTH_TIMEOUT_SECONDS)
            .add(StackServiceModel.HEALTH_RETRIES)
            .add(StackServiceModel.HEALTH_START_PERIOD_SECONDS)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(StackServiceModel.RESTART_POLICY))
            .add(StackServiceModel.MEMORY_LIMIT_MB)
            .add(StackServiceModel.CPU_LIMIT)
            // A service is an image, a name and how it is wired; the capability allow-list, the healthcheck schedule,
            // the restart policy and the resource ceilings are tuning a person reaches for once, so they fold.
            .section(FormSection.advanced(
                StackServiceModel.CAPABILITIES.getName(),
                StackServiceModel.HEALTH_CMD.getName(),
                StackServiceModel.HEALTH_INTERVAL_SECONDS.getName(),
                StackServiceModel.HEALTH_TIMEOUT_SECONDS.getName(),
                StackServiceModel.HEALTH_RETRIES.getName(),
                StackServiceModel.HEALTH_START_PERIOD_SECONDS.getName(),
                StackServiceModel.RESTART_POLICY.getName(),
                StackServiceModel.MEMORY_LIMIT_MB.getName(),
                StackServiceModel.CPU_LIMIT.getName()))
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(StackServiceModel.NAME).build())
            .column(ColumnSpec.fromField(StackServiceModel.IMAGE).copyable().build())
            .column(ColumnSpec.fromField(StackServiceModel.ENABLED).build())
            .column(ColumnSpec.fromField(StackServiceModel.STACK_ID).relation(stack).build())
            .build();
        return PanelResource.builder(HohenheimIds.id("stack_service"), HohenheimSlugs.STACK_SERVICES,
            StackOperations.SERVICE)
            .label(HohenheimMicrocopy.STACK_SERVICE.of("plural"))
            .recordLabel(HohenheimMicrocopy.STACK_SERVICE.of("singular"))
            .icon(Icon.of("cube"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(26)
            .showInNav(false)
            .parent(ResourceParent.of(HohenheimSlugs.STACKS, StackServiceModel.STACK_ID)
            .tab(HohenheimSlugs.Tab.SERVICES))
            .reads(ResourceReads.rows())
            // A service is found by its name or by the image it runs.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(StackServiceModel.NAME, StackServiceModel.IMAGE).build())
            // The Services tab links here with ?stack_id= so the pick is preselected.
            .form(ResourceForm.<Row>of(form)
                .createDefaults(request -> prefilled(form, StackServiceModel.STACK_ID.getName(),
                    CmsSupport.prefill(request.conduit(), HohenheimParams.STACK_ID_PREFILL)))
                .build())
            .writes(ResourceMutations.rows().create().update()
                .delete(StackOperations.DELETE_SERVICE)
                .ownsWriteEnvelope(ResourceVerb.DELETE)
                .beforeSave(StackParts::validService)
                .build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /**
     * @return the managed config files of the stack services, nav-hidden and reached through a stack's Services tab;
     *         content is encrypted at rest but visible to editors (encryption is not secrecy)
     */
    public static @NonNull PanelResource<Row> files() {
        RelationPick service = RelationPick.of(StackFileModel.STACK_SERVICE_ID, StackServiceModel.MODEL_ID).build();
        FormSpec form = FormSpec.builder()
            .add(service)
            .add(StackFileModel.CONTAINER_PATH)
            .add(StackFileModel.CONTENT)
            .add(StackFileModel.MODE)
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(StackFileModel.CONTAINER_PATH).subtext("mode").copyable().build())
            .column(ColumnSpec.fromField(StackFileModel.MODE).hidden().build())
            .column(ColumnSpec.fromField(StackFileModel.STACK_SERVICE_ID).relation(service).build())
            .build();
        return PanelResource.builder(HohenheimIds.id("stack_file"), HohenheimSlugs.STACK_FILES,
                SubjectType.record(StackFileModel.MODEL_ID))
            .label(HohenheimMicrocopy.STACK_FILE.of("plural"))
            .recordLabel(HohenheimMicrocopy.STACK_FILE.of("singular"))
            .icon(Icon.of("file-code"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(27)
            .showInNav(false)
            // A file reaches its stack through its service: a path parent, which the framework walks and queries.
            .parent(ResourceParent.path(HohenheimSlugs.STACKS,
                    new ResourceParent.Hop(StackFileModel.STACK_SERVICE_ID, StackServiceModel.MODEL_ID),
                    new ResourceParent.Hop(StackServiceModel.STACK_ID, StackModel.MODEL_ID))
                .tab(HohenheimSlugs.Tab.SERVICES))
            .reads(ResourceReads.rows())
            // The path only: CONTENT is encrypted at rest, so a search over it would match ciphertext.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(StackFileModel.CONTAINER_PATH).build())
            // The Services tab links here with ?stack_service_id= so the pick is preselected.
            .form(ResourceForm.<Row>of(form)
                .createDefaults(request -> prefilled(form, StackFileModel.STACK_SERVICE_ID.getName(),
                    CmsSupport.prefill(request.conduit(), HohenheimParams.STACK_SERVICE_ID_PREFILL)))
                .build())
            .writes(ResourceMutations.rows().create().update().delete().beforeSave(StackParts::validFile).build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** The form's defaults with the parent the tab linked from preselected, when it named one. */
    private static @NonNull Map<String, Object> prefilled(@NonNull FormSpec form, @NonNull String parent,
                                                          @Nullable Integer parentId) {
        Map<String, Object> values = new LinkedHashMap<>(form.defaultValues());
        if (parentId != null) {
            values.put(parent, parentId);
        }
        return Map.copyOf(values);
    }

    /** @return the stack a config file's service belongs to, null when the service is gone */
    // -- placed operations --------------------------------------------------------------------------------------------

    private static @NonNull List<PanelAction<Row>> stackActions() {
        ConfirmationSpec purge = ConfirmationSpec.of(HohenheimMicrocopy.STACK.of("purge_volumes"),
            HohenheimMicrocopy.STACK.of("purge_volumes_ok"),
            HohenheimMicrocopy.STACK.of("purge_volumes_confirm_generic"), ActionStyle.DESTRUCTIVE);
        return List.of(
            PanelAction.<Row, Void>places(StackOperations.DEPLOY, ActionPlacement.ROW, queued("deploy_queued"))
                .description(HohenheimMicrocopy.STACK.of("deploy_hint"))
                .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.STACK.of("deploy"),
                    HohenheimMicrocopy.STACK.of("deploy_confirm"), ActionStyle.DEFAULT))
                .build(),
            PanelAction.<Row, Void>places(StackOperations.STOP, ActionPlacement.ROW, queued("stop_queued"))
                .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.STACK.of("stop"),
                    HohenheimMicrocopy.STACK.of("stop_confirm"), ActionStyle.DEFAULT))
                .build(),
            PanelAction.<Row, Void>places(StackOperations.ROLLBACK, ActionPlacement.ROW, queued("rollback_queued"))
                .description(HohenheimMicrocopy.STACK.of("rollback_hint"))
                .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.STACK.of("rollback"),
                    HohenheimMicrocopy.STACK.of("rollback_confirm"), ActionStyle.DEFAULT))
                .build(),
            // The one stack operation that destroys data instead of processes, so it asks for the stack's OWN name
            // rather than a reflex click. External volumes are unowned and survive it. The static spec is the
            // record-less fallback the builder demands next to a dynamic confirmation.
            PanelAction.<Row, Void>places(StackOperations.PURGE_VOLUMES, ActionPlacement.ROW,
                    queued("purge_volumes_queued"))
                .description(HohenheimMicrocopy.STACK.of("purge_volumes_hint"))
                .style(ActionStyle.DESTRUCTIVE)
                .inlineInRow(false)
                .confirmation(purge)
                .dynamicConfirmation(stack -> purge.withBody(HohenheimMicrocopy.STACK
                    .of("purge_volumes_confirm").withArg("name", stack.get(StackModel.NAME)))
                    .withTypedConfirmation(stack.get(StackModel.NAME)))
                .build(),
            PanelAction.<Row, String>places(StackOperations.REFRESH, ActionPlacement.ROW,
                    (request, result) -> CmsActionResult.refreshWithToast(
                        HohenheimMicrocopy.STACK_STATUS.of(result.value())))
                .build(),
            // Disk reclaim is per DAEMON, not per stack, so it belongs on the page rather than on a row.
            PanelAction.<Row, Void>places(StackOperations.RECLAIM_IMAGES, ActionPlacement.HEADER,
                    queued("reclaim_images_started"))
                .description(HohenheimMicrocopy.STACK.of("reclaim_images_hint"))
                .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.STACK.of("reclaim_images"),
                    HohenheimMicrocopy.STACK.of("reclaim_images_confirm"), ActionStyle.DEFAULT))
                .build());
    }

    /** A queued operation's answer: the list refreshes and says the work was queued. */
    private static <R> @NonNull ResultStep<ActionRequest<Row>, R, CmsActionResult> queued(@NonNull String key) {
        return (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.STACK.of(key));
    }

    // -- the stack's write rule ---------------------------------------------------------------------------------------

    /**
     * Names become Docker resource names: the safe shape, uniqueness, and no rename while the stack has live resources.
     * The trimmed name is written back, so "web " passing on its trimmed copy is never stored raw.
     *
     * @throws Violations anchored on the name
     */
    private static void validStack(@NonNull RowSave save) {
        Row row = save.row();
        Row existing = save.isCreate() ? null : Models.get(StackModel.class).findById(row.get(StackModel.ID));
        String name = trimmed(row.get(StackModel.NAME));
        if (!StackModel.isValidName(name)) {
            throw Violations.ofField("name", name, HohenheimMicrocopy.VIOLATIONS.of("stack_name_format"));
        }
        if (save.values().containsKey(StackModel.NAME.getName())) {
            row.set(StackModel.NAME, name);
        }
        Row duplicate = Models.get(StackModel.class).findByName(name);
        if (duplicate != null
            && (existing == null || !duplicate.get(StackModel.ID).equals(existing.get(StackModel.ID)))) {
            throw Violations.ofField("name", name, HohenheimMicrocopy.VIOLATIONS.of("stack_name_taken"));
        }
        if (existing != null && !name.equals(existing.get(StackModel.NAME))) {
            // The name is embedded in every ownership label, container, network and volume name: renaming a stack
            // with LIVE resources would orphan ALL of them. The gate is the live container count, decided on the
            // stack's worker so it cannot interleave with a running operation.
            // AIDEV-NOTE: a deploy QUEUED after this check but before the save persists can still race the rename;
            // the worker check narrows the window, it cannot close it without moving the persist onto the worker.
            Integer stackId = existing.get(StackModel.ID);
            if (StackModel.STATUS_DEPLOYING.equals(existing.get(StackModel.STATUS))) {
                throw Violations.ofField("name", name, HohenheimMicrocopy.VIOLATIONS.of("stack_rename_deployed"));
            }
            try {
                if (stackId != null && StackRuntime.get().ownedContainerCount(stackId) > 0) {
                    throw Violations.ofField("name", name, HohenheimMicrocopy.VIOLATIONS.of("stack_rename_deployed"));
                }
            } catch (IOException dockerUnavailable) {
                // Cannot PROVE the rename is safe: refuse rather than orphan.
                throw Violations.ofField("name", name, HohenheimMicrocopy.VIOLATIONS.of("stack_rename_unverifiable"));
            }
        }
    }

    // -- the service's write rule -------------------------------------------------------------------------------------

    /**
     * Service names become container names and DNS aliases; ports must be real ports. The trimmed name is written back
     * onto the row and the canonical record values onto their records: validating a trimmed copy while persisting the
     * raw one lets "web " slip past the sibling check and become an invalid Docker name.
     *
     * @throws Violations anchored on the offending entry
     */
    private static void validService(@NonNull RowSave save) {
        Row row = save.row();
        Map<String, Object> submitted = save.values();
        Row existing = save.isCreate() ? null
            : Models.get(StackServiceModel.class).findById(row.get(StackServiceModel.ID));
        String name = trimmed(row.get(StackServiceModel.NAME));
        if (!StackModel.isValidName(name)) {
            throw Violations.ofField("name", name, HohenheimMicrocopy.VIOLATIONS.of("service_name_format"));
        }
        if (submitted.containsKey(StackServiceModel.NAME.getName())) {
            row.set(StackServiceModel.NAME, name);
        }

        Object stackIdValue = row.get(StackServiceModel.STACK_ID);
        if (!(stackIdValue instanceof Integer stackId)) {
            throw Violations.ofField("stack_id", stackIdValue, HohenheimMicrocopy.VIOLATIONS.of("stack_required"));
        }
        Integer existingId = existing != null ? existing.get(StackServiceModel.ID) : null;
        List<Row> siblings = Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.STACK_ID.eq(stackId)).all();
        for (Row sibling : siblings) {
            if (sibling.get(StackServiceModel.ID).equals(existingId)) {
                continue;
            }
            if (name.equals(trimmed(sibling.get(StackServiceModel.NAME)))) {
                throw Violations.ofField("name", name, HohenheimMicrocopy.VIOLATIONS.of("service_name_taken"));
            }
        }

        validMounts(submitted, existingId);
        validPorts(submitted, stackId, existingId);
        validDependsOn(submitted, name, siblings, existingId);
        validHealth(submitted);
        validCapabilities(submitted);

        // Renaming or disabling must not strand siblings' dependencies: their depends_on still names this service, and
        // the next deploy would fail resolving the graph (a broken graph even blocked deletion once).
        if (existing != null) {
            String oldName = trimmed(existing.get(StackServiceModel.NAME));
            boolean renamed = submitted.containsKey(StackServiceModel.NAME.getName()) && !oldName.equals(name);
            boolean disabled = submitted.containsKey(StackServiceModel.ENABLED.getName())
                && Boolean.FALSE.equals(submitted.get(StackServiceModel.ENABLED.getName()))
                && Boolean.TRUE.equals(existing.get(StackServiceModel.ENABLED));
            if (renamed || disabled) {
                refuseWhenDependedUpon(stackId, existingId, oldName);
            }
        }
    }

    /**
     * @throws Violations when any OTHER service of the stack declares a dependency on {@code name}
     */
    static void refuseWhenDependedUpon(int stackId, @Nullable Integer serviceId, @NonNull String name) {
        for (Row sibling : Models.get(StackServiceModel.class).find()
                .where(StackServiceModel.STACK_ID.eq(stackId)).all()) {
            if (sibling.get(StackServiceModel.ID).equals(serviceId)) {
                continue;
            }
            for (Row depends : sibling.getRecords(StackServiceModel.DEPENDS_ON)) {
                if (name.equals(trimmed(depends.get(StackServiceModel.DEPENDS_SERVICE)))) {
                    throw Violations.ofField("name", name,
                        HohenheimMicrocopy.VIOLATIONS.of("service_still_depended_upon")
                            .withArg("service", String.valueOf((Object) sibling.get(StackServiceModel.NAME))));
                }
            }
        }
    }

    /**
     * Mount names become volume-name segments; container paths must be absolute and must not shadow the service's
     * staged config files.
     */
    private static void validMounts(@NonNull Map<String, Object> submitted, @Nullable Integer serviceId) {
        List<Row> files = serviceId == null ? List.of()
            : Models.get(StackFileModel.class).find().where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).all();
        Set<String> paths = new HashSet<>();
        int index = -1;
        for (Row mount : recordsOf(submitted, StackServiceModel.MOUNTS.getName())) {
            index++;
            String mountName = trimmed(mount.get(StackServiceModel.MOUNT_NAME));
            String external = trimmed(mount.get(StackServiceModel.MOUNT_EXTERNAL));
            String path = trimmed(mount.get(StackServiceModel.MOUNT_PATH));
            if (mountName.isEmpty() && external.isEmpty() && path.isEmpty()) {
                continue;   // an untouched blank row the editor added; nothing to check
            }
            mount.set(StackServiceModel.MOUNT_NAME, mountName);
            mount.set(StackServiceModel.MOUNT_EXTERNAL, external);
            mount.set(StackServiceModel.MOUNT_PATH, path);
            boolean volume = !StackServiceModel.MOUNT_TMPFS.equals(trimmed(mount.get(StackServiceModel.MOUNT_TYPE)));
            // A named volume derives "hohenheim-stack-<stack>-<name>", so the name has to be a safe Docker name
            // segment; an external volume brings its own name instead.
            if (volume && external.isEmpty() && !StackModel.isValidName(mountName)) {
                throw Violations.ofField("mounts." + index + ".name", mountName,
                    HohenheimMicrocopy.VIOLATIONS.of("mount_name_format"));
            }
            if (!path.startsWith("/")) {
                throw Violations.ofField("mounts." + index + ".container_path", path,
                    HohenheimMicrocopy.VIOLATIONS.of("mount_path_absolute"));
            }
            if (!paths.add(path)) {
                throw Violations.ofField("mounts." + index + ".container_path", path,
                    HohenheimMicrocopy.VIOLATIONS.of("mount_path_taken"));
            }
            // The mirror of the config file's shadow refusal: adding the mount AFTER the file is refused exactly like
            // adding the file after the mount -- either order silently hides the staged file at container start.
            String prefix = path.endsWith("/") ? path : path + "/";
            for (Row file : files) {
                String filePath = trimmed(file.get(StackFileModel.CONTAINER_PATH));
                if (filePath.equals(path) || filePath.startsWith(prefix)) {
                    throw Violations.ofField("mounts." + index + ".container_path", path,
                        HohenheimMicrocopy.VIOLATIONS.of("mount_shadows_file").withArg("file", filePath));
                }
            }
        }
    }

    /**
     * Published host ports are a whole-HOST resource, arbitrated by the PORT LEDGER: the claim keys are checked against
     * {@code port_allocations}, which sees EVERY recording authority, not just sibling stack rows.
     *
     * AIDEV-NOTE: this read is the FRIENDLY refusal (field-pathed, names the holder) and ONLY that -- it is advisory,
     * not the arbiter. Since the stack tier lowered onto the instance runtime contract, exclusivity is decided at
     * DEPLOY, by the ledger's unique claim-key index, under the service's owned INSTANCE as the owner. That is why the
     * own-holder exemption below asks about the instance and not about the service record.
     */
    private static void validPorts(@NonNull Map<String, Object> submitted, int stackId, @Nullable Integer existingId) {
        Set<String> claimed = new HashSet<>();
        Row stack = Models.get(StackModel.class).findById(stackId);
        Integer stackServer = stack != null ? stack.get(StackModel.SERVER_ID) : null;
        int serverId = stackServer != null ? stackServer : ServerModel.localServerId();
        int index = -1;
        for (Row port : recordsOf(submitted, StackServiceModel.PORTS.getName())) {
            index++;
            Integer container = RawValues.parsedInt(port.get(StackServiceModel.PORT_CONTAINER));
            if (container == null && RawValues.parsedInt(port.get(StackServiceModel.PORT_HOST)) == null
                && trimmed(port.get(StackServiceModel.PORT_HOST_IP)).isEmpty()) {
                continue;   // an untouched blank row the editor added
            }
            if (container == null) {
                throw Violations.ofField("ports." + index + ".container_port", port.get(StackServiceModel.PORT_CONTAINER),
                    HohenheimMicrocopy.VIOLATIONS.of("port_container_required"));
            }
            for (String key : List.of(StackServiceModel.PORT_CONTAINER.getName(), StackServiceModel.PORT_HOST.getName())) {
                Integer value = RawValues.parsedInt(port.get(key));
                if (value != null && (value < 1 || value > 65535)) {
                    throw Violations.ofField("ports." + index + "." + key, value,
                        HohenheimMicrocopy.VIOLATIONS.of("port_range"));
                }
            }
            Integer host = RawValues.parsedInt(port.get(StackServiceModel.PORT_HOST));
            if (host == null) {
                continue;
            }
            Object hostIp = port.get(StackServiceModel.PORT_HOST_IP);
            Object protocol = port.get(StackServiceModel.PORT_PROTOCOL);
            if (!claimed.add(PortLedger.portClaim(hostIp, host, protocol))) {
                throw Violations.ofField("ports." + index + ".host_port", host,
                    HohenheimMicrocopy.VIOLATIONS.of("host_port_taken"));
            }
            // Sibling DECLARATIONS on the same host, which the ledger cannot see yet: a claim exists only from the
            // DEPLOY, so two services can be authored with the same host port and only collide much later. A
            // record-level uniqueness check (the duplicate-name shape), not a second arbiter.
            String claimKey = PortLedger.claimKeyOf(serverId, hostIp, host, protocol);
            String declaredBy = declaringSibling(serverId, existingId, claimKey);
            if (declaredBy != null) {
                throw Violations.ofField("ports." + index + ".host_port", host,
                    HohenheimMicrocopy.VIOLATIONS.of("port_held").withArg("holder", declaredBy));
            }
            Row holder = PortLedger.holderOf(claimKey);
            Row ownInstance = existingId != null ? StackInstances.owned(existingId) : null;
            if (holder != null && !(ownInstance != null && PortLedger.isOwnedBy(holder,
                    InstanceModel.MODEL_ID, ownInstance.get(InstanceModel.ID)))) {
                throw Violations.ofField("ports." + index + ".host_port", host,
                    HohenheimMicrocopy.VIOLATIONS.of("port_held").withArg("holder", PortLedger.describeHolder(holder)));
            }
        }
    }

    /**
     * The name of another stack service on the same host that already DECLARES this claim key, or null.
     *
     * @return "stack/service", which is what the operator has to go and change
     */
    private static @Nullable String declaringSibling(int serverId, @Nullable Integer existingId,
                                                     @NonNull String claimKey) {
        for (Row stack : Models.get(StackModel.class).find().all()) {
            Integer stackServer = stack.get(StackModel.SERVER_ID);
            if ((stackServer != null ? stackServer : ServerModel.localServerId()) != serverId) {
                continue;
            }
            for (Row service : Models.get(StackServiceModel.class).findByStackId(stack.get(StackModel.ID))) {
                if (service.get(StackServiceModel.ID).equals(existingId)) {
                    continue;
                }
                for (Row port : service.getRecords(StackServiceModel.PORTS)) {
                    Integer host = port.get(StackServiceModel.PORT_HOST);
                    if (host != null && claimKey.equals(PortLedger.claimKeyOf(serverId,
                            port.get(StackServiceModel.PORT_HOST_IP), host,
                            port.get(StackServiceModel.PORT_PROTOCOL)))) {
                        return stack.get(StackModel.NAME) + "/" + service.get(StackServiceModel.NAME);
                    }
                }
            }
        }
        return null;
    }

    /** A dependency naming no sibling service can never be satisfied at deploy time. */
    private static void validDependsOn(@NonNull Map<String, Object> submitted, @NonNull String name,
                                       @NonNull List<Row> siblings, @Nullable Integer existingId) {
        Set<String> known = new HashSet<>();
        known.add(name);
        for (Row sibling : siblings) {
            if (!sibling.get(StackServiceModel.ID).equals(existingId)) {
                known.add(String.valueOf((Object) sibling.get(StackServiceModel.NAME)));
            }
        }
        int index = -1;
        for (Row depends : recordsOf(submitted, StackServiceModel.DEPENDS_ON.getName())) {
            index++;
            String target = trimmed(depends.get(StackServiceModel.DEPENDS_SERVICE));
            if (target.isEmpty()) {
                continue;   // an untouched blank row the editor added
            }
            depends.set(StackServiceModel.DEPENDS_SERVICE, target);
            if (!known.contains(target)) {
                throw Violations.ofField("depends_on." + index + ".service", target,
                    HohenheimMicrocopy.VIOLATIONS.of("depends_unknown_service"));
            }
            if (target.equals(name)) {
                throw Violations.ofField("depends_on." + index + ".service", target,
                    HohenheimMicrocopy.VIOLATIONS.of("depends_on_self"));
            }
        }
    }

    /**
     * Healthcheck timings are periods: negatives are meaningless everywhere, and zero is refused for interval, timeout
     * and retries too (Docker rejects them at create, the deploy-time failure this exists to prevent). Only the start
     * period may legitimately be zero.
     */
    private static void validHealth(@NonNull Map<String, Object> submitted) {
        for (String key : List.of(StackServiceModel.HEALTH_INTERVAL_SECONDS.getName(),
                StackServiceModel.HEALTH_TIMEOUT_SECONDS.getName(), StackServiceModel.HEALTH_RETRIES.getName(),
                StackServiceModel.HEALTH_START_PERIOD_SECONDS.getName())) {
            if (!(submitted.get(key) instanceof Number number)) {
                continue;
            }
            boolean zeroAllowed = StackServiceModel.HEALTH_START_PERIOD_SECONDS.getName().equals(key);
            if (number.intValue() < 0 || (!zeroAllowed && number.intValue() == 0)) {
                throw Violations.ofField(key, number, HohenheimMicrocopy.VIOLATIONS.of("health_positive"));
            }
        }
    }

    /**
     * Refuses a capability the create funnel would refuse anyway, so the operator sees it on the form rather than in a
     * deployment log three clicks later.
     *
     * AIDEV-NOTE: a SECOND reader of the same allow-list, never a second list: ContainerHardening.declaring stays THE
     * authority (it runs on the deploy path), and this call only moves the moment of refusal earlier.
     */
    private static void validCapabilities(@NonNull Map<String, Object> submitted) {
        if (!(submitted.get(StackServiceModel.CAPABILITIES.getName()) instanceof List<?> declared)) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (Object entry : declared) {
            names.add(String.valueOf(entry));
        }
        try {
            ContainerHardening.declaring(StackServiceKind.HARDENING, "this service", names);
        } catch (IllegalArgumentException notDeclarable) {
            throw Violations.ofField(StackServiceModel.CAPABILITIES.getName(), names,
                HohenheimMicrocopy.VIOLATIONS.of("capability_not_declarable")
                    .withArg("capabilities", String.join(", ", ContainerHardening.DECLARABLE.keySet())));
        }
    }

    /** The allow-list as closed form options; the names are policy tokens, never localized. */
    private static @NonNull List<FieldOption<String>> capabilityOptions() {
        List<FieldOption<String>> options = new ArrayList<>();
        for (String capability : ContainerHardening.DECLARABLE.keySet()) {
            options.add(FieldOption.of(capability, Microcopy.literal(capability)));
        }
        return options;
    }

    // -- the config file's write rule ---------------------------------------------------------------------------------

    /**
     * A config file belongs to a service, its path is unique on that service and no mount covers it: files are staged
     * into the container BEFORE it starts, so a mount over the path silently hides the file the moment the container
     * runs. The path's own shape and the octal mode are ContainerFileRules', on the model.
     *
     * @throws Violations anchored on the offending entry
     */
    private static void validFile(@NonNull RowSave save) {
        Row row = save.row();
        String path = trimmed(row.get(StackFileModel.CONTAINER_PATH));
        Object serviceIdValue = row.get(StackFileModel.STACK_SERVICE_ID);
        if (!(serviceIdValue instanceof Integer serviceId)) {
            throw Violations.ofField("stack_service_id", serviceIdValue,
                HohenheimMicrocopy.VIOLATIONS.of("service_required"));
        }
        Integer existingId = save.isCreate() ? null : row.get(StackFileModel.ID);
        for (Row sibling : Models.get(StackFileModel.class).find()
                .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).all()) {
            if (sibling.get(StackFileModel.ID).equals(existingId)) {
                continue;
            }
            if (path.equals(trimmed(sibling.get(StackFileModel.CONTAINER_PATH)))) {
                throw Violations.ofField("container_path", path, HohenheimMicrocopy.VIOLATIONS.of("file_path_taken"));
            }
        }
        Row service = Models.get(StackServiceModel.class).findById(serviceId);
        if (service == null) {
            return;
        }
        for (Row mount : service.getRecords(StackServiceModel.MOUNTS)) {
            String mountPath = trimmed(mount.get(StackServiceModel.MOUNT_PATH));
            if (mountPath.isEmpty() || !mountPath.startsWith("/")) {
                continue;
            }
            String prefix = mountPath.endsWith("/") ? mountPath : mountPath + "/";
            if (path.equals(mountPath) || path.startsWith(prefix)) {
                throw Violations.ofField("container_path", path,
                    HohenheimMicrocopy.VIOLATIONS.of("file_path_shadowed"));
            }
        }
    }

    /**
     * Submitted sub-schema records.
     *
     * AIDEV-NOTE: a Records entry coerces to a List of ROWS (SubmittedValueCoercion.coerceRecords), never a List of
     * Maps -- reading them as maps silently yields nothing and every check over them becomes dead code. These are the
     * very rows the save stages, so a canonical value set on one is the stored one.
     */
    private static @NonNull List<Row> recordsOf(@NonNull Map<String, Object> submitted, @NonNull String key) {
        if (!(submitted.get(key) instanceof List<?> list)) {
            return List.of();
        }
        List<Row> records = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Row record) {
                records.add(record);
            }
        }
        return records;
    }
}
