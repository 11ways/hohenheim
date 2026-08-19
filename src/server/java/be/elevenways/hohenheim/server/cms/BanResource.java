package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * IP bans: the list is the audit trail (rows are never edited or deleted), the
 * create form is the manual "Ban an IP" flow (validated against private/own
 * IPs, duration choice incl. permanent), and lifting is a confirmed row action.
 */
public final class BanResource extends RowResource {

    private static final Identifier LIFT = Identifier.of("hohenheim", "lift_ban");

    /** The form-only duration entry's name; it backs no column. */
    private static final String DURATION_NAME = "duration";

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
        .label(Microcopy.of("ban_duration").withFilter("scope", "field"))
        .build();

    /**
     * The list's quick-add bar: THE manual "ban an IP" flow, which is three answers.
     *
     * AIDEV-NOTE: there is deliberately no inline counterpart. Ban rows are an audit
     * trail ({@code updatable() == false}), and that is not merely a policy: BanService
     * programs nftables at create and at lift, so a row edited underneath it would leave
     * the kernel enforcing something the record no longer says.
     */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(BanModel.IP.getName(), BanModel.REASON.getName(), DURATION_NAME);

    private final FormSpec formSpec = FormSpec.builder()
        .add(BanModel.IP)
        .add(BanModel.REASON)
        // AIDEV-NOTE: the DERIVED entry, never a bare Plain. An EnumField wrapped in
        // Plain renders as a free-text box on the full form and is outside the compact
        // subset entirely, so the quick-add bar could not offer it at all -- and every
        // ban created there would have silently taken the 24h default.
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DURATION))
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "ban"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "ban"); }
    @Override public @NonNull String slug() { return "bans"; }
    @Override public @NonNull Model model() { return Models.get(BanModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 40; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "ban");
    }
    @Override public @NonNull Icon icon() { return Icon.of("ban"); }

    /** Ban rows are an audit trail: created and lifted, never edited or deleted. */
    @Override public boolean updatable() { return false; }
    @Override public boolean deletable() { return false; }

    @Override
    public @Nullable QuickCreateSpec quickCreate() {
        return QUICK_CREATE;
    }

    // AIDEV-NOTE: this list had NO filters at all despite three columns that are pure
    // classification. "Which active auto-bans came from the login probe" was a question
    // only answerable by paging.
    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(BanModel.IP).filterable().subtext("reason").copyable().build())
        .column(ColumnSpec.fromField(BanModel.REASON).hidden().build())
        .column(ColumnSpec.fromField(BanModel.SOURCE).filterable().build())
        .column(ColumnSpec.fromField(BanModel.ACTIVE).filterable().build())
        .column(ColumnSpec.fromField(BanModel.EVENT_TYPE).filterable().build())
        .column(ColumnSpec.fromField(BanModel.EXPIRES_AT).build())
        .column(ColumnSpec.fromField(BanModel.CREATED_AT).build())
        .filter(FilterSpec.forField(BanModel.IP, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(BanModel.IP)).build())
        .filter(FilterSpec.forField(BanModel.SOURCE, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(BanModel.SOURCE)).build())
        .filter(FilterSpec.forField(BanModel.ACTIVE, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(BanModel.ACTIVE)).build())
        .filter(FilterSpec.forField(BanModel.EVENT_TYPE, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(BanModel.EVENT_TYPE)).build())
        .defaultSort(SortSpec.desc("created_at"))
        .build();

    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return CmsSupport.FILTERABLE_LIST; }

    /** The address is what an operator arrived with and what they leave with. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(BanModel.IP, BanModel.REASON);
    }

    /** The manual "Ban an IP" flow: validate, then create through BanService. */
    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        String ip = coerced.get("ip") instanceof String text ? text.trim() : "";
        String problem = ip.isEmpty() ? "empty ip" : BanService.protectionProblem(ip);
        if (problem != null) {
            throw Violations.ofField("ip", ip,
                CmsSupport.violationText("ban_ip_refused").withArg("reason", problem));
        }

        String reason = coerced.get("reason") instanceof String text && !text.isBlank()
            ? text.trim() : null;
        Duration ttl = ttlOf(coerced.get("duration"));

        Row ban = BanService.INSTANCE.createBan(ip, reason, BanModel.SOURCE_MANUAL, null, ttl);
        Integer id = ban.get(BanModel.ID);
        if (id == null) {
            throw new IllegalStateException("Ban save did not populate the id");
        }
        return id;
    }

    private static Duration ttlOf(Object duration) {
        return switch (duration instanceof String text ? text : "24h") {
            case "1h" -> Duration.ofHours(1);
            case "7d" -> Duration.ofDays(7);
            case "30d" -> Duration.ofDays(30);
            case "permanent" -> null;
            default -> Duration.ofHours(24);
        };
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        return List.of(RowAction.Invoke.<Row>builder(LIFT)
            .label(Microcopy.of("lift").withFilter("scope", "ban"))
            .description(Microcopy.of("lift_hint").withFilter("scope", "ban"))
            .icon(Icon.of("unlock"))
            .visibleFor((row, ctx) -> Boolean.TRUE.equals(row.get(BanModel.ACTIVE)))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("lift").withFilter("scope", "ban"))
                .body(Microcopy.of("lift_confirm").withFilter("scope", "ban"))
                .build())
            .handler((row, ctx) -> {
                BanService.INSTANCE.lift(row, ctx.access().principal().displayName());
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("lifted").withFilter("scope", "ban"));
            })
            .build());
    }
}
