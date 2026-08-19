package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SampleSummary;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Read-only remote samples with strict verdict and rescore actions. */
public final class SpamserviceSamplesResource extends SpamserviceRemoteResource<SampleSummary> {

    public static final String SLUG = "spamservice-samples";
    private static final Schema SCHEMA = new Schema();
    private static final UuidField CLIENT_ID = SCHEMA.addField(UuidField.builder("client_id")
        .label(Microcopy.of("client").withFilter("scope", "spamservice_sample")).build());
    private static final StringField IP = SCHEMA.addField(StringField.builder("ip")
        .label(Microcopy.of("ip").withFilter("scope", "spamservice_sample")).build());
    private static final BooleanField SPAM = SCHEMA.addField(BooleanField.builder("spam")
        .label(Microcopy.of("spam").withFilter("scope", "spamservice_sample")).build());
    private static final IntegerField SCORE = SCHEMA.addField(IntegerField.builder("score")
        .label(Microcopy.of("score").withFilter("scope", "spamservice_sample")).build());
    private static final BooleanField CONFIRMED = SCHEMA.addField(BooleanField.builder("confirmed")
        .label(Microcopy.of("confirmed").withFilter("scope", "spamservice_sample")).build());
    private static final StringField FLAGS = SCHEMA.addField(StringField.builder("flags")
        .label(Microcopy.of("flags").withFilter("scope", "spamservice_sample")).build());
    private static final StringField LANGUAGES = SCHEMA.addField(StringField.builder("languages")
        .label(Microcopy.of("languages").withFilter("scope", "spamservice_sample")).build());
    private static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder("created_at")
        .label(Microcopy.of("created_at").withFilter("scope", "spamservice_sample")).build());
    private final FormSpec formSpec = FormSpec.builder()
        .add(CLIENT_ID).add(IP).add(SPAM).add(SCORE).add(CONFIRMED).add(FLAGS).add(LANGUAGES).add(CREATED_AT).build();

    public SpamserviceSamplesResource() {}

    SpamserviceSamplesResource(Supplier<SpamserviceClient> clientSupplier) { super(clientSupplier); }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "spamservice_sample"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "spamservice_sample"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Schema schema() { return SCHEMA; }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 20; }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("file-lines"); }
    @Override public boolean creatable() { return false; }
    @Override public boolean updatable() { return false; }
    @Override public boolean deletable() { return false; }

    @Override
    public @NonNull TableSpec<SampleSummary> tableSpec() {
        return TableSpec.<SampleSummary>builder()
            .column(ColumnSpec.fromField(CREATED_AT).build()).column(ColumnSpec.fromField(CLIENT_ID).filterable().build())
            .column(ColumnSpec.fromField(SPAM).filterable().build()).column(ColumnSpec.fromField(SCORE).build())
            .column(ColumnSpec.fromField(IP).filterable().build()).column(ColumnSpec.fromField(CONFIRMED).filterable().build())
            .filter(FilterSpec.forField(CLIENT_ID, FilterSpec.Kind.TEXT).build())
            .filter(FilterSpec.forField(SPAM, FilterSpec.Kind.BOOLEAN).build())
            .filter(FilterSpec.forField(CONFIRMED, FilterSpec.Kind.BOOLEAN).build())
            .filter(FilterSpec.forField(IP, FilterSpec.Kind.TEXT).build())
            .defaultSort(SortSpec.desc("created_at")).build();
    }

    @Override
    protected @NonNull PageResult<SampleSummary> fetchPage(@NonNull SpamserviceClient client,
                                                            TableView.@NonNull Applied<SampleSummary> applied) {
        return client.samples(applied.page(), applied.schema().pageSize(), textFilter(applied, "client_id"),
            booleanFilter(applied, "spam"), booleanFilter(applied, "confirmed"), textFilter(applied, "ip"));
    }

    @Override public @NonNull String rowKey(@NonNull SampleSummary row) { return row.id(); }
    @Override public @Nullable Object parsePrimaryKey(@NonNull String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
    @Override public @Nullable SampleSummary loadRow(@NonNull Object key, @NonNull AccessContext context) {
        return this.requireClient().sample(String.valueOf(key)).summary();
    }
    @Override public @NonNull Map<String, Object> valuesFromRow(@NonNull SampleSummary row) {
        return Map.of("client_id", uuidValue(row.clientId()), "ip", value(row.ip()), "spam", row.spam(),
            "score", row.score(), "confirmed", row.confirmed(), "flags", value(row.flags()),
            "languages", value(row.languages()), "created_at", value(row.createdAt()));
    }
    @Override public @NonNull Object persistRow(@NonNull Map<String, Object> values, @NonNull AccessContext context) {
        throw new UnsupportedOperationException();
    }
    @Override public void updateRow(@NonNull SampleSummary row, @NonNull Map<String, Object> values,
                                    @NonNull AccessContext context) { throw new UnsupportedOperationException(); }
    @Override public void deleteRow(@NonNull SampleSummary row, @NonNull AccessContext context) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable Object cellValue(@NonNull SampleSummary row, @NonNull ColumnSpec column) {
        return switch (column.name()) {
            case "client_id" -> row.clientId();
            case "ip" -> row.ip();
            case "spam" -> row.spam();
            case "score" -> row.score();
            case "confirmed" -> row.confirmed();
            case "flags" -> row.flags();
            case "languages" -> row.languages();
            case "created_at" -> row.createdAt();
            default -> null;
        };
    }

    @Override
    public @NonNull String rowUrl(@NonNull SampleSummary row) {
        // Resource.rowUrl is a String contract; toUrl() is the boundary.
        return CmsRoutes.subpage("admin", SLUG, row.id(), SpamserviceSampleAnalysisPage.SLUG).toUrl();
    }

    @Override public @NonNull List<RecordScopedPage<SampleSummary>> subpages() {
        return List.of(new SpamserviceSampleAnalysisPage(this));
    }

    @Override
    public @NonNull List<RowAction<SampleSummary>> rowActions() {
        return List.of(
            RowAction.Invoke.<SampleSummary>builder(Identifier.of("hohenheim", "spamservice_mark_spam"))
                .label(Microcopy.of("mark_spam").withFilter("scope", "spamservice_sample"))
                .icon(Icon.of("triangle-exclamation")).handler((row, context) -> {
                    this.requireClient().markSpam(row.id());
                    return CmsActionResult.refreshWithToast(
                        Microcopy.of("marked_spam").withFilter("scope", "spamservice_sample"));
                }).build(),
            RowAction.Invoke.<SampleSummary>builder(Identifier.of("hohenheim", "spamservice_mark_ham"))
                .label(Microcopy.of("mark_ham").withFilter("scope", "spamservice_sample"))
                .icon(Icon.of("check")).handler((row, context) -> {
                    this.requireClient().markHam(row.id());
                    return CmsActionResult.refreshWithToast(
                        Microcopy.of("marked_ham").withFilter("scope", "spamservice_sample"));
                }).build(),
            RowAction.Invoke.<SampleSummary>builder(Identifier.of("hohenheim", "spamservice_rescore"))
                .label(Microcopy.of("rescore").withFilter("scope", "spamservice_sample"))
                .icon(Icon.of("rotate")).visibleFor((row, context) -> !row.confirmed())
                .handler((row, context) -> {
                    var detail = this.requireClient().rescore(row.id());
                    return CmsActionResult.refreshWithToast(Microcopy.of("rescored")
                        .withFilter("scope", "spamservice_sample")
                        .withArg("score", String.valueOf(detail.summary().score())));
                }).build());
    }

    private static Object value(@Nullable Object value) { return value != null ? value : ""; }

    private static Object uuidValue(@Nullable String value) {
        return value == null || value.isBlank() ? "" : UUID.fromString(value);
    }
}
