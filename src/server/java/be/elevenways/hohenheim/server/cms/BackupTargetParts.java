package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.server.backup.BackupTargetKinds;
import be.elevenways.protoblast.common.dry.BlastDrySerializers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.LinkedHashMap;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * The backup targets' parts: full CRUD plus a live connection test, the target seam's health check against the real
 * destination, never a form-level syntax check.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class BackupTargetParts {

    /** This entry's slug, which the instance list names as a related page. */
    public static final String SLUG = "backup-targets";

    private static final SubjectType<Row> SUBJECT = SubjectType.record(BackupTargetModel.MODEL_ID);

    /** Runs the target's health check against its real destination. */
    public static final Operation<Row, Void, TestOutcome> TEST =
        Operation.declare(HohenheimIds.id("test_backup_target"))
            .happened(OperationSentences.of("test_backup_target"))
            .label(Microcopy.of("test_connection").withFilter("scope", "backup_target"))
            .icon(Icon.of("plug-circle-check"))
            .one(SUBJECT)
            .gate(OperationGate.open())
            .result(TestOutcome.class)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(CmsCommands.EXTERNAL)
            .register();

    /**
     * One health check's outcome.
     *
     * @param name    the target's name
     * @param failure the check's own reason, null when it passed
     */
    public record TestOutcome(@Nullable String name, @Nullable String failure) {
    }

    static {
        // The test is a command operation: its outcome is stored as the receipt, so it must serialize, or the
        // invoke fails after the check ran and the operator reads a generic "action failed".
        BlastDrySerializers.addCustomRegistration(registry -> {
            registry.registerSerializer(TestOutcome.class, (outcome, context) -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("name", outcome.name());
                map.put("failure", outcome.failure());
                return map;
            });
            registry.registerReviver(TestOutcome.class,
                (data, context) -> new TestOutcome(data.getString("name"), data.getString("failure")));
        });
        OperationHandlers.attach(TEST).handle(call -> {
            Row target = call.subject();
            String name = target.get(BackupTargetModel.NAME);
            try {
                BackupTargetKinds.targetOf(target).healthCheck();
            } catch (IOException | Violations unhealthy) {
                // Violations too: a destination host that is unconfirmed or quarantined is exactly what this test
                // exists to surface, and an escaping refusal would render as a generic failure instead.
                return new TestOutcome(name, HohenheimViolations.reasonOf(unhealthy));
            }
            return new TestOutcome(name, null);
        });
    }

    private BackupTargetParts() {
    }

    /** @return the admin backup target resource */
    public static @NonNull PanelResource<Row> admin() {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(BackupTargetModel.NAME).filterable().copyable().build())
            .column(ColumnSpec.fromField(BackupTargetModel.KIND).filterable().build())
            .column(ColumnSpec.fromField(BackupTargetModel.CREATED_AT).build())
            .build();
        FormSpec form = FormSpec.builder()
            .add(BackupTargetModel.NAME)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(BackupTargetModel.KIND))
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(BackupTargetModel.SETTINGS))
            .build();
        return PanelResource.builder(HohenheimIds.id("backup_target"), SLUG, SUBJECT)
            .label(Microcopy.of("plural").withFilter("scope", "backup_target"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "backup_target"))
            // A sidebar entry of its own in System, beside Settings, whose control-plane backup setting names one of
            // these targets: without it the page was reachable only from the instance list's related pages.
            .description(CmsSupport.navHint("backup_target"))
            .icon(Icon.of("box-archive"))
            .navGroup(NavGroup.SYSTEM)
            .navOrder(94)
            .reads(ResourceReads.rows())
            // The name is the only text a target carries; the credentials live in a secret settings blob.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(BackupTargetModel.NAME).build())
            .form(ResourceForm.<Row>of(form).build())
            .writes(ResourceMutations.rows().create().update().delete().build())
            .actions(List.of(PanelAction.<Row, TestOutcome>places(TEST, ActionPlacement.ROW,
                    (request, result) -> testWords(Objects.requireNonNull(result.value(), "a test answers")))
                .label(Microcopy.of("test_connection").withFilter("scope", "backup_target"))
                .icon(Icon.of("plug-circle-check"))
                .build()))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    private static @NonNull CmsActionResult testWords(@NonNull TestOutcome outcome) {
        if (outcome.failure() != null) {
            return CmsActionResult.errorToast(Microcopy.of("test_failed").withFilter("scope", "backup_target")
                .withArg("reason", outcome.failure()));
        }
        return CmsActionResult.toast(Microcopy.of("test_ok").withFilter("scope", "backup_target")
            .withArg("name", outcome.name()));
    }
}
