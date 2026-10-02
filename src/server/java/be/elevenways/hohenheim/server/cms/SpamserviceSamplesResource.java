package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.SampleSummary;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Read-only remote samples, a store entry over the management API whose rows open their Analysis tab, with the
 * verdict and rescore calls as row operations.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SpamserviceSamplesResource {

    public static final String SLUG = "spamservice-samples";
    static final Identifier ID = HohenheimIds.id("spamservice_sample");
    static final SubjectType<SampleSummary> SAMPLE = SubjectType.of(ID, SampleSummary.class, SampleSummary::id);

    private static final UuidField CLIENT_ID = UuidField.builder("client_id").label(words("client")).build();
    private static final StringField IP = StringField.builder("ip").label(words("ip")).build();
    private static final BooleanField SPAM = BooleanField.builder("spam").label(words("spam")).build();
    private static final IntegerField SCORE = IntegerField.builder("score").label(words("score")).build();
    private static final BooleanField CONFIRMED = BooleanField.builder("confirmed").label(words("confirmed")).build();
    private static final StringField FLAGS = StringField.builder("flags").label(words("flags")).build();
    private static final StringField LANGUAGES = StringField.builder("languages").label(words("languages")).build();
    private static final DateTimeField CREATED_AT = DateTimeField.builder("created_at").label(words("created_at"))
        .build();

    /** The fields the management API answers for one sample summary. */
    private static final List<Field<?, ?>> FIELDS = List.of(CLIENT_ID, IP, SPAM, SCORE, CONFIRMED, FLAGS,
        LANGUAGES, CREATED_AT);

    public static final Operation<SampleSummary, Void, Void> MARK_SPAM = Operation.declare(
            HohenheimIds.id("spamservice_mark_spam"))
        .label(words("mark_spam"))
        .icon(Icon.of("triangle-exclamation"))
        .one(SAMPLE)
        .gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .register();

    public static final Operation<SampleSummary, Void, Void> MARK_HAM = Operation.declare(
            HohenheimIds.id("spamservice_mark_ham"))
        .label(words("mark_ham"))
        .icon(Icon.of("check"))
        .one(SAMPLE)
        .gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .register();

    /** Answers the new score as its text. */
    public static final Operation<SampleSummary, Void, String> RESCORE = Operation.declare(
            HohenheimIds.id("spamservice_rescore"))
        .label(words("rescore"))
        .icon(Icon.of("rotate"))
        .one(SAMPLE)
        .gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .result(String.class)
        .register();

    static {
        OperationHandlers.loader(SAMPLE, key -> load(SpamserviceRemoteStore.MANAGED, key));
        OperationHandlers.attach(MARK_SPAM).handle(call -> markSpam(SpamserviceRemoteStore.MANAGED, call.subject()));
        OperationHandlers.attach(MARK_HAM).handle(call -> {
            SpamserviceRemoteStore.require(SpamserviceRemoteStore.MANAGED).markHam(call.subject().id());
            return null;
        });
        OperationHandlers.attach(RESCORE).handle(call -> String.valueOf(
            SpamserviceRemoteStore.require(SpamserviceRemoteStore.MANAGED).rescore(call.subject().id())
                .summary().score()));
    }

    private SpamserviceSamplesResource() {
    }

    /** @return the entry over the managed runtime's client */
    public static @NonNull PanelResource<SampleSummary> create() {
        return create(SpamserviceRemoteStore.MANAGED);
    }

    static @NonNull PanelResource<SampleSummary> create(@NonNull Supplier<SpamserviceClient> clients) {
        SpamserviceRemoteStore.requireNonNull(clients);
        TableSpec<SampleSummary> table = TableSpec.<SampleSummary>builder()
            .column(ColumnSpec.fromField(CREATED_AT).build())
            .column(ColumnSpec.fromField(CLIENT_ID).filterable().build())
            .column(ColumnSpec.fromField(SPAM).filterable().build()).column(ColumnSpec.fromField(SCORE).build())
            .column(ColumnSpec.fromField(IP).filterable().copyable().build())
            .column(ColumnSpec.fromField(CONFIRMED).filterable().build())
            .filter(FilterSpec.forField(CLIENT_ID, FilterSpec.Kind.TEXT).build())
            .filter(FilterSpec.forField(SPAM, FilterSpec.Kind.BOOLEAN).build())
            .filter(FilterSpec.forField(CONFIRMED, FilterSpec.Kind.BOOLEAN).build())
            .filter(FilterSpec.forField(IP, FilterSpec.Kind.TEXT).build())
            .defaultSort(SortSpec.desc("created_at")).build();
        return PanelResource.builder(ID, SLUG, SAMPLE)
            .label(words("plural"))
            .recordLabel(words("singular"))
            .navGroup(HohenheimPanel.SECURITY_GROUP)
            .navOrder(20)
            .showInNav(false)
            .icon(Icon.of("file-lines"))
            .reads(ResourceReads.<SampleSummary>typed(SampleSummary::id)
                .load((key, access) -> load(clients, key))
                .values(SpamserviceSamplesResource::values)
                .cells(SpamserviceSamplesResource::cell)
                .build()
                // Without this the analysis tab is headed by the sample's UUID.
                .title(SampleSummary::ip))
            .list(ResourceList.store(table, SpamserviceRemoteStore.pages(ID, clients, FIELDS, List.of(),
                    (client, applied, access) -> client.samples(applied.page(), applied.schema().pageSize(),
                        SpamserviceRemoteStore.textFilter(applied, "client_id"),
                        SpamserviceRemoteStore.booleanFilter(applied, "spam"),
                        SpamserviceRemoteStore.booleanFilter(applied, "confirmed"),
                        SpamserviceRemoteStore.textFilter(applied, "ip"))))
                .chrome(ListChrome.MINIMAL)
                .notice(SpamserviceRemoteStore.notice(ID, clients))
                .rowLinkToTab(SpamserviceSampleAnalysisPage.SLUG)
                .build())
            .form(ResourceForm.<SampleSummary>of(FormSpec.builder()
                .add(CLIENT_ID).add(IP).add(SPAM).add(SCORE).add(CONFIRMED).add(FLAGS).add(LANGUAGES).add(CREATED_AT)
                .build()).build())
            .tabs(ResourceTabs.of(List.of(new SpamserviceSampleAnalysisPage(clients))))
            .actions(List.of(
                PanelAction.<SampleSummary, Void>places(MARK_SPAM, ActionPlacement.ROW,
                        (request, result) -> CmsActionResult.refreshWithToast(words("marked_spam")))
                    .label(words("mark_spam"))
                    .icon(Icon.of("triangle-exclamation"))
                    .build(),
                PanelAction.<SampleSummary, Void>places(MARK_HAM, ActionPlacement.ROW,
                        (request, result) -> CmsActionResult.refreshWithToast(words("marked_ham")))
                    .label(words("mark_ham"))
                    .icon(Icon.of("check"))
                    .build(),
                PanelAction.<SampleSummary, String>places(RESCORE, ActionPlacement.ROW,
                        (request, result) -> CmsActionResult.refreshWithToast(words("rescored")
                            .withArg("score", result.value())))
                    .label(words("rescore"))
                    .icon(Icon.of("rotate"))
                    .hiddenWhen(SampleSummary::confirmed)
                    .build()))
            .build();
    }

    /** The mark-spam operation's remote call: the management API's strict verdict endpoint. */
    static @Nullable Void markSpam(@NonNull Supplier<SpamserviceClient> clients, @NonNull SampleSummary sample) {
        SpamserviceRemoteStore.require(clients).markSpam(sample.id());
        return null;
    }

    private static @Nullable SampleSummary load(@NonNull Supplier<SpamserviceClient> clients, @NonNull String key) {
        UUID id = SpamserviceRemoteStore.uuidOrNull(key);
        return id == null ? null : SpamserviceRemoteStore.require(clients).sample(id.toString()).summary();
    }

    private static @NonNull Map<String, Object> values(@NonNull SampleSummary row) {
        return Map.of("client_id", SpamserviceRemoteStore.uuidOrBlank(row.clientId()),
            "ip", SpamserviceRemoteStore.orBlank(row.ip()), "spam", row.spam(), "score", row.score(),
            "confirmed", row.confirmed(), "flags", SpamserviceRemoteStore.orBlank(row.flags()),
            "languages", SpamserviceRemoteStore.orBlank(row.languages()),
            "created_at", SpamserviceRemoteStore.orBlank(row.createdAt()));
    }

    private static @Nullable Object cell(@NonNull SampleSummary row, @NonNull ColumnSpec column) {
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

    private static @NonNull Microcopy words(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "spamservice_sample");
    }
}
