package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.security.BanStateCell;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.hohenheim.server.security.HohenheimSecurity;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.RowWriteCall;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.FilterState;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.security.KnownSecurityEvents;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The IP bans' parts: the list is the audit trail (rows are never edited or deleted), the create form is the manual
 * "Ban an IP" flow (validated against private and own addresses, a duration choice including permanent), and lifting
 * is a confirmed operation.
 *
 * AIDEV-NOTE: there is deliberately no update or delete writer, and no inline cell. Ban rows are an audit trail, and
 * BanService programs nftables at create and at lift, so a row edited underneath it would leave the kernel enforcing
 * something the record no longer says.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class BanParts {

    /** The IP ban entry's slug, which the panel's clusters name. */
    public static final String SLUG = "bans";

    /** The list's state column: enforced, lifted or expired, derived from the stored facts. */
    static final String STATE_COLUMN = "state";

    /** The form-only duration entry's name; it backs no column. */
    private static final String DURATION_NAME = "duration";

    private static final SubjectType<Row> SUBJECT = SubjectType.record(BanModel.MODEL_ID);

    /** Duration choices for a manual ban; "permanent" maps to a null TTL. */
    private static final EnumField DURATION = EnumField.builder(DURATION_NAME)
        .value("1h", v -> v.displayName("1 hour")
            .label(Microcopy.of("duration_1h").withFilter("scope", "ban")))
        .value("24h", v -> v.displayName("24 hours")
            .label(Microcopy.of("duration_24h").withFilter("scope", "ban")))
        .value("7d", v -> v.displayName("7 days")
            .label(Microcopy.of("duration_7d").withFilter("scope", "ban")))
        .value("30d", v -> v.displayName("30 days")
            .label(Microcopy.of("duration_30d").withFilter("scope", "ban")))
        .value("permanent", v -> v.displayName("Permanent")
            .label(Microcopy.of("duration_permanent").withFilter("scope", "ban")))
        .defaultValue("24h")
        .label(HohenheimFormCopy.label("ban_duration"))
        .build();

    /** The entries a RECORD shows (read-only) and the create form does not. */
    private static final List<String> STORED_STATE = List.of(
        BanModel.EXPIRES_AT.getName(), BanModel.ACTIVE.getName(),
        BanModel.LIFTED_AT.getName(), BanModel.LIFTED_BY.getName());

    /** Lifts an enforced ban: the kernel set drops the address and the row records who lifted it and when. */
    public static final Operation<Row, Void, Void> LIFT = Operation.declare(HohenheimIds.id("lift_ban"))
        .happened(OperationSentences.of("lift_ban"))
        .label(Microcopy.of("lift").withFilter("scope", "ban"))
        .icon(Icon.of("unlock"))
        .one(SUBJECT)
        .gate(OperationGate.open())
        // Placed as a row action: a resubmitted click answers from the receipt instead of lifting twice.
        .command(OperationCommand.perSubject().execution(CommandExecution.OUTSIDE_TRANSACTION))
        .register();

    static {
        OperationHandlers.attach(LIFT)
            .applies(ban -> Boolean.TRUE.equals(ban.get(BanModel.ACTIVE)))
            .handle(call -> {
                BanService.INSTANCE.lift(call.subject(),
                    Objects.requireNonNull(call.access(), "an operator lifts a ban").principal().displayName());
                return null;
            });
    }

    private BanParts() {
    }

    /** @return the admin ban resource */
    public static @NonNull PanelResource<Row> admin() {
        // AIDEV-NOTE: the classification columns are filters too: "which active auto-bans came from the login probe"
        // was a question only answerable by paging.
        TableSpec<Row> table = TableSpec.<Row>builder()
            // The Access-Blocked board reads a ban as the address and why, who blocked it, and until when; which
            // traffic it refuses, the event that tripped it and when it began stay in the picker and the filters.
            .column(ColumnSpec.fromField(BanModel.IP).label(banText("address_column"))
                .filterable().subtext("reason").copyable().build())
            .column(ColumnSpec.fromField(BanModel.REASON).hidden().build())
            .column(ColumnSpec.fromField(BanModel.SOURCE).label(banText("by_column")).filterable().build())
            // WHICH traffic the ban refuses: an SSH ban and a web ban are different rows.
            .column(ColumnSpec.fromField(BanModel.SCOPE).filterable().hidden().build())
            // ONE state badge (active / lifted / expired); the `active` filter keeps answering "still enforced?".
            .column(ColumnSpec.virtual(STATE_COLUMN, Microcopy.of("state").withFilter("scope", "ban"))
                .renderer(HohenheimTemplateIds.CELL_BAN_STATE).build())
            .column(ColumnSpec.fromField(BanModel.ACTIVE).hidden().build())
            // A block without an expiry holds until someone lifts it: the empty cell says so, not "None".
            .column(ColumnSpec.fromField(BanModel.EXPIRES_AT).label(banText("until_column"))
                .absent(banText("until_lifted")).build())
            .column(ColumnSpec.fromField(BanModel.EVENT_TYPE).filterable().hidden().build())
            .column(ColumnSpec.fromField(BanModel.CREATED_AT).hidden().build())
            .filter(FilterSpec.leaf(BanModel.IP, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(BanModel.IP)).build())
            .filter(FilterSpec.leaf(BanModel.SOURCE, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(BanModel.SOURCE)).build())
            .filter(FilterSpec.leaf(BanModel.SCOPE, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(BanModel.SCOPE)).build())
            .filter(FilterSpec.leaf(BanModel.ACTIVE, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE)
                .label(FieldLabels.labelFor(BanModel.ACTIVE)).build())
            .filter(FilterSpec.leaf(BanModel.EVENT_TYPE, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(BanModel.EVENT_TYPE)).build())
            .defaultSort(SortSpec.desc("created_at"))
            .build();
        FormSpec form = FormSpec.builder()
            .createTitle(banText("create_title"))
            .add(BanModel.IP)
            .add(BanModel.REASON)
            // The DERIVED entry, never a bare Plain: an EnumField in Plain renders as free text and is outside the
            // compact subset, so the quick-add bar could not offer it and every ban there took the 24h default.
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DURATION))
            // The STORED facts a ban record shows instead of the create-time choice.
            .add(BanModel.EXPIRES_AT)
            .add(BanModel.ACTIVE)
            .add(BanModel.LIFTED_AT)
            .add(BanModel.LIFTED_BY)
            .build();
        return PanelResource.builder(HohenheimIds.id("ban"), SLUG, SUBJECT)
            .label(Microcopy.of("plural").withFilter("scope", "ban"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "ban"))
            .description(Microcopy.of("nav_hint").withFilter("scope", "ban"))
            .icon(Icon.of("ban"))
            .navGroup(HohenheimPanel.SECURITY_GROUP)
            .navOrder(40)
            // The cause of an automatic ban in the reader's own words, off the ONE description registry the event
            // vocabulary declares into; an undescribed type keeps its dotted spelling.
            .reads(ResourceReads.rows().mapCells(BanParts::cell))
            .list(ResourceList.rows(table).chrome(CmsSupport.FILTERABLE_LIST).facets().ruleFilters()
                .search(BanModel.IP, BanModel.REASON)
                // Opens on what is blocked NOW: a default the reader removes to see lifted and expired bans.
                .defaultFilter(FilterState.empty().with(BanModel.ACTIVE.getName(), Boolean.TRUE.toString()),
                    filter -> BanModel.ACTIVE.getName().equals(filter) ? banText("blocked_now") : null)
                .computed(Objects.requireNonNull(table.column(STATE_COLUMN)), (ban, request) ->
                    BanStateCell.of(Boolean.TRUE.equals(ban.get(BanModel.ACTIVE)),
                        ban.get(BanModel.LIFTED_AT), ban.get(BanModel.EXPIRES_AT), Now.instant()))
                .build())
            // Blocking an address is the header's one action and its form (board Access-Blocked); the list carries no
            // quick-add bar beside it.
            .form(ResourceForm.<Row>of(form)
                .bindings(bindings())
                .build())
            .writes(ResourceMutations.rows().create(BanParts::create).build())
            .actions(List.of(PanelAction.<Row, Void>places(LIFT, ActionPlacement.ROW, (request, result) ->
                    CmsActionResult.refreshWithToast(Microcopy.of("lifted").withFilter("scope", "ban")))
                .label(Microcopy.of("lift").withFilter("scope", "ban"))
                .description(Microcopy.of("lift_hint").withFilter("scope", "ban"))
                .icon(Icon.of("unlock"))
                .confirmation(ConfirmationSpec.builder()
                    .title(Microcopy.of("lift_title").withFilter("scope", "ban"))
                    .body(Microcopy.of("lift_confirm").withFilter("scope", "ban"))
                    .build())
                .build()))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /**
     * Create asks for a duration; a record shows its expiry and lift state. The stored facts are HIDDEN on the create
     * form (outcomes, not inputs) and the duration choice is hidden on the record (its answer is the expiry).
     */
    private static @NonNull List<ResourceFieldBinding> bindings() {
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        bindings.add(ResourceFieldBinding.of(DURATION_NAME,
            FieldAccess.customRecordAware((ctx, record) -> record == null
                ? FieldAccess.Decision.EDITABLE : FieldAccess.Decision.HIDDEN)));
        for (String stored : STORED_STATE) {
            // Hidden only on the CREATE form, never on a record: the list still filters and sorts by these facts.
            bindings.add(ResourceFieldBinding.of(stored,
                FieldAccess.customRecordAware((ctx, record) -> record == null
                    ? FieldAccess.Decision.HIDDEN : FieldAccess.Decision.READONLY)
                    .acrossRecords(ctx -> FieldAccess.Decision.READONLY)));
        }
        return bindings;
    }

    private static @NonNull Microcopy banText(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "ban");
    }

    /**
     * A ban's cells in words: the event that tripped it by its description, and an automatic ban's reason stored as
     * the old score line read as that event instead ({@link HohenheimSecurity#legacyCause}).
     */
    private static @Nullable Object cell(@NonNull Row ban, @NonNull ColumnSpec column) {
        if (BanModel.EVENT_TYPE.getName().equals(column.name())) {
            return eventLabel(ban);
        }
        if (BanModel.REASON.getName().equals(column.name())
                && BanModel.SOURCE_AUTO.equals(ban.get(BanModel.SOURCE))) {
            Microcopy cause = HohenheimSecurity.legacyCause(ban.get(BanModel.REASON), ban.get(BanModel.EVENT_TYPE));
            return cause == null ? null : CmsSupport.resolvedText(cause);
        }
        return null;
    }

    private static @Nullable String eventLabel(@NonNull Row ban) {
        String type = ban.get(BanModel.EVENT_TYPE);
        Microcopy described = KnownSecurityEvents.descriptionOf(type);
        String label = described == null ? null : CmsSupport.resolvedText(described);
        return label != null ? label : type;
    }

    /** The manual "Ban an IP" flow: validate, then create through BanService, which programs the firewall. */
    private static @NonNull Object create(@NonNull RowWriteCall call) {
        Map<String, Object> values = call.values();
        String ip = values.get(BanModel.IP.getName()) instanceof String text ? text.trim() : "";
        String problem = ip.isEmpty() ? "empty ip" : BanService.protectionProblem(ip);
        if (problem != null) {
            throw Violations.ofField(BanModel.IP.getName(), ip,
                CmsSupport.violationText("ban_ip_refused").withArg("reason", problem));
        }
        String reason = values.get(BanModel.REASON.getName()) instanceof String text && !text.isBlank()
            ? text.trim() : null;
        Row ban = BanService.INSTANCE.createBan(ip, reason, BanModel.SOURCE_MANUAL, null,
            ttlOf(values.get(DURATION_NAME)));
        return Objects.requireNonNull(ban.get(BanModel.ID), "Ban save did not populate the id");
    }

    private static @Nullable Duration ttlOf(@Nullable Object duration) {
        return switch (duration instanceof String text ? text : "24h") {
            case "1h" -> Duration.ofHours(1);
            case "7d" -> Duration.ofDays(7);
            case "30d" -> Duration.ofDays(30);
            case "permanent" -> null;
            default -> Duration.ofHours(24);
        };
    }
}
