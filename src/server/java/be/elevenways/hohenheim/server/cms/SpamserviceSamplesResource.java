package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
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
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.command.CommandExecution;
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
import java.util.Objects;
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
    private static final OperationCommand COMMAND = OperationCommand.perSubject().onDatasource("default")
        .execution(CommandExecution.OUTSIDE_TRANSACTION);

    static final Identifier ID = HohenheimIds.id("spamservice_sample");
    static final SubjectType<SampleSummary> SAMPLE = SubjectType.of(ID, SampleSummary.class, SampleSummary::id);

    private static final UuidField CLIENT_ID = UuidField.builder("client_id")
        .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("client")).build();
    private static final StringField IP = StringField.builder("ip")
        .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("ip")).build();
    private static final BooleanField SPAM = BooleanField.builder("spam")
        .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("spam")).build();
    private static final IntegerField SCORE = IntegerField.builder("score")
        .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("score")).build();
    private static final BooleanField CONFIRMED = BooleanField.builder("confirmed")
        .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("confirmed")).build();
    private static final StringField FLAGS = StringField.builder("flags")
        .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("flags")).build();
    private static final StringField LANGUAGES = StringField.builder("languages")
        .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("languages")).build();
    private static final DateTimeField CREATED_AT = DateTimeField.builder("created_at")
        .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("created_at"))
        .build();

    /** The fields the management API answers for one sample summary. */
    private static final List<Field<?, ?>> FIELDS = List.of(CLIENT_ID, IP, SPAM, SCORE, CONFIRMED, FLAGS,
        LANGUAGES, CREATED_AT);

    public static final Operation<SampleSummary, Void, Void> MARK_SPAM = Operation.declare(
            HohenheimIds.id("spamservice_mark_spam"))
        .happened(OperationSentences.of("spamservice_mark_spam"))
        .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("mark_spam"))
        .icon(Icon.of("triangle-exclamation"))
        .one(SAMPLE)
        .gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .command(COMMAND)
        .register();

    public static final Operation<SampleSummary, Void, Void> MARK_HAM = Operation.declare(
            HohenheimIds.id("spamservice_mark_ham"))
        .happened(OperationSentences.of("spamservice_mark_ham"))
        .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("mark_ham"))
        .icon(Icon.of("check"))
        .one(SAMPLE)
        .gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .command(COMMAND)
        .register();

    /** Answers the new score as its text. */
    public static final Operation<SampleSummary, Void, String> RESCORE = Operation.declare(
            HohenheimIds.id("spamservice_rescore"))
        .happened(OperationSentences.of("spamservice_rescore"))
        .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("rescore"))
        .icon(Icon.of("rotate"))
        .one(SAMPLE)
        .gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .result(String.class)
        .command(COMMAND)
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
            .filter(FilterSpec.leaf(CLIENT_ID, CoreTypes.EQUALS).build())
            .filter(FilterSpec.leaf(SPAM, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE).build())
            .filter(FilterSpec.leaf(CONFIRMED, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE).build())
            .filter(FilterSpec.leaf(IP, CoreTypes.CONTAINS).build())
            .defaultSort(SortSpec.desc("created_at")).build();
        return PanelResource.builder(ID, HohenheimSlugs.SPAMSERVICE_SAMPLES, SAMPLE)
            .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("plural"))
            .recordLabel(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("singular"))
            .navGroup(HohenheimPanel.SECURITY_GROUP)
            .navOrder(20)
            .showInNav(false)
            .standsUnder(HohenheimSlugs.SPAMSERVICE)
            .icon(Icon.of("file-lines"))
            .reads(ResourceReads.<SampleSummary>typed(SampleSummary::id)
                .load((key, access) -> load(clients, key))
                .values(SpamserviceSamplesResource::values)
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
                .rowLinkToTab(HohenheimSlugs.Tab.ANALYSIS)
                .build())
            .form(ResourceForm.<SampleSummary>of(FormSpec.builder()
                .add(CLIENT_ID).add(IP).add(SPAM).add(SCORE).add(CONFIRMED).add(FLAGS).add(LANGUAGES).add(CREATED_AT)
                .build()).build())
            .tabs(ResourceTabs.of(List.of(new SpamserviceSampleAnalysisPage(clients))))
            .actions(List.of(
                PanelAction.<SampleSummary, Void>places(MARK_SPAM, ActionPlacement.ROW,
                        (request, result) -> CmsActionResult.refreshWithToast(
                            HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("marked_spam")))
                    .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("mark_spam"))
                    .icon(Icon.of("triangle-exclamation"))
                    .build(),
                PanelAction.<SampleSummary, Void>places(MARK_HAM, ActionPlacement.ROW,
                        (request, result) -> CmsActionResult.refreshWithToast(
                            HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("marked_ham")))
                    .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("mark_ham"))
                    .icon(Icon.of("check"))
                    .build(),
                PanelAction.<SampleSummary, String>places(RESCORE, ActionPlacement.ROW,
                        (request, result) -> CmsActionResult.refreshWithToast(
                            HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("rescored")
                                .withArg("score", result.value())))
                    .label(HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("rescore"))
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
            "ip", Objects.requireNonNullElse(row.ip(), ""), "spam", row.spam(), "score", row.score(),
            "confirmed", row.confirmed(), "flags", Objects.requireNonNullElse(row.flags(), ""),
            "languages", Objects.requireNonNullElse(row.languages(), ""),
            "created_at", Objects.requireNonNullElse(row.createdAt(), ""));
    }
}
