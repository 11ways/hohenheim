package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.RowSave;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.edit.Array;
import be.elevenways.zenit.common.edit.FieldFormEntryDefaults;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteLocales;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.comms.server.NotifyOutcome;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The webhook notification channels' parts (Slack, Discord, generic JSON) and their test send.
 *
 * AIDEV-NOTE: every row stores the kind "webhook": the before-save hook stamps it, as the legacy persist and update
 * did, and judges the url and the event vocabulary over the row the form applied, so a one-cell rename reads the
 * STORED url rather than refusing a field the operator never touched.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class NotificationChannelParts {

    /** The virtual column holding the subscribed events, and the name's subtext. */
    static final String EVENTS_COLUMN = "events";

    private static final SubjectType<Row> SUBJECT = SubjectType.record(NotificationChannelModel.MODEL_ID);

    /** Sends a test message through one channel; the outcome says whether it was delivered or only handed off. */
    public static final Operation<Row, Void, NotifyOutcome> TEST = Operation.declare(HohenheimIds.id("test_channel"))
        .label(Microcopy.of("test").withFilter("scope", "notification_channel"))
        .icon(Icon.of("paper-plane"))
        .one(SUBJECT)
        .gate(OperationGate.open())
        .result(NotifyOutcome.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .register();

    static {
        OperationHandlers.attach(TEST).handle(call -> {
            Row row = call.subject();
            // Outbound copy has no requesting user to follow: it speaks the server's default locale, like every
            // other alert.
            LocaleChain locales = LocaleChain.of(RouteLocales.get().getDefaultLocale());
            NotifyOutcome outcome = Alerts.testChannelOutcome(row,
                Microcopy.of("test_subject").withFilter("scope", "notification_channel")
                    .resolve(locales, Zenit.getMessageResolver()),
                Microcopy.of("test_body").withFilter("scope", "notification_channel")
                    .resolve(locales, Zenit.getMessageResolver()));
            ActivityLog.record(Models.get(NotificationChannelModel.class), row.get(NotificationChannelModel.ID),
                HohenheimActivityAction.TESTED, row.get(NotificationChannelModel.NAME));
            return outcome;
        });
    }

    private NotificationChannelParts() {
    }

    /** @return the admin notification channel resource */
    public static @NonNull PanelResource<Row> admin() {
        // AIDEV-NOTE: an explicit spec. The derived one led with KIND, which every row stores as "webhook": a
        // column of one repeated word. What an operator needs is WHAT each channel is subscribed to.
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(NotificationChannelModel.NAME).filterable().subtext(EVENTS_COLUMN).build())
            .column(ColumnSpec.virtual(EVENTS_COLUMN,
                Microcopy.of("events").withFilter("scope", "notification_channel")).hidden().build())
            .column(ColumnSpec.fromField(NotificationChannelModel.FORMAT).filterable().build())
            .column(ColumnSpec.fromField(NotificationChannelModel.CREATED_AT).build())
            .filter(FilterSpec.forField(NotificationChannelModel.NAME, FilterSpec.Kind.TEXT)
                .label(FieldLabels.labelFor(NotificationChannelModel.NAME)).build())
            .filter(FilterSpec.forField(NotificationChannelModel.FORMAT, FilterSpec.Kind.SELECT)
                .label(FieldLabels.labelFor(NotificationChannelModel.FORMAT)).build())
            .build();
        // AIDEV-NOTE: the format select is spelled out to keep it CLEARABLE although the field is required. A
        // non-clearable select refuses a blank AT COERCION, which aborts the submit before validation, so an empty
        // create form would name only the format. A blank option plus the Required validator names all three.
        FormSpec form = FormSpec.builder()
            .add(NotificationChannelModel.NAME)
            .add(Select.of(NotificationChannelModel.FORMAT)
                .options(FieldFormEntryDefaults.enumOptionSource(NotificationChannelModel.FORMAT))
                .clearable(true)
                .build())
            .add(NotificationChannelModel.URL)
            .add(Array.of(NotificationChannelModel.EVENTS, NotificationChannelModel.EVENTS.getItemField())
                // Derived from the vocabulary itself: an event cannot exist and be unofferable.
                .options(OptionSource.of(Arrays.stream(NotificationEvents.values())
                    .map(event -> FieldOption.of(event.token(), event.label()))
                    .toList()))
                .build())
            .build();
        return PanelResource.builder(HohenheimIds.id("notification_channel"), "notifications", SUBJECT)
            .label(Microcopy.of("plural").withFilter("scope", "notification_channel"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "notification_channel"))
            .description(Microcopy.of("nav_hint").withFilter("scope", "notification_channel"))
            .icon(Icon.of("bell"))
            // System, between the activity log (90) and the settings editor (95): where this installation talks
            // about ITSELF; the channels carry alerts, not traffic.
            .navGroup(NavGroup.SYSTEM)
            .navOrder(92)
            .reads(ResourceReads.rows())
            // The name only: the url is a bearer credential.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(NotificationChannelModel.NAME)
                .computed(Objects.requireNonNull(table.column(EVENTS_COLUMN)),
                    (channel, request) -> eventsOf(channel))
                .build())
            // The name only. FORMAT decides the payload shape a webhook receives, URL is the endpoint credential, and
            // an empty events list means RECEIVE EVERYTHING, which is also why there is no quick-add bar.
            .form(ResourceForm.<Row>of(form).inlineEditable(NotificationChannelModel.NAME).build())
            .writes(ResourceMutations.rows().create().update().delete()
                .beforeSave(NotificationChannelParts::stampAndValidate)
                .build())
            // A channel's delete has one consequence and it is silent: its events stop being delivered.
            .deleteConfirmation(DeleteConfirmation.of(DeleteConfirmation.body(
                Microcopy.of("delete_confirm").withFilter("scope", "notification_channel"))))
            .actions(List.of(PanelAction.<Row, NotifyOutcome>places(TEST, ActionPlacement.ROW,
                    (request, result) -> testWords(Objects.requireNonNull(result.value(), "a test answers")))
                .label(Microcopy.of("test").withFilter("scope", "notification_channel"))
                .icon(Icon.of("paper-plane"))
                .build()))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /**
     * The event summary under the name, each token through its own declared label; a token this build no longer
     * declares keeps its raw spelling.
     *
     * @return null when the channel takes every event (an empty subscription means "all")
     */
    private static @Nullable String eventsOf(@NonNull Row channel) {
        List<String> events = channel.get(NotificationChannelModel.EVENTS);
        if (events == null || events.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (String token : events) {
            NotificationEvents event = NotificationEvents.byToken(token);
            String label = event == null ? null : CmsSupport.resolvedText(event.label());
            labels.add(label != null ? label : token);
        }
        return String.join(", ", labels);
    }

    /** Stamps the webhook kind, then judges the url scheme and the event vocabulary of the row as it will be saved. */
    private static void stampAndValidate(@NonNull RowSave save) {
        Row row = save.row();
        row.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
        Object urlValue = row.get(NotificationChannelModel.URL);
        String url = urlValue != null ? String.valueOf(urlValue).trim() : "";
        // A BLANK url is the field's own Required validator to refuse; a format rule for an empty box only ever told
        // the operator the wrong thing.
        if (!url.isEmpty() && !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw Violations.ofField("url", null, CmsSupport.violationText("url_scheme"));
        }
        if (save.values().get(NotificationChannelModel.EVENTS.getName()) instanceof List<?> events) {
            for (Object event : events) {
                if (!NotificationEvents.isKnown(String.valueOf(event))) {
                    throw Violations.ofField("events", event, CmsSupport.violationText("unknown_event")
                        .withArg("event", String.valueOf(event))
                        .withArg("valid", String.join(", ", NotificationEvents.ALL)));
                }
            }
        }
    }

    private static @NonNull CmsActionResult testWords(@NonNull NotifyOutcome outcome) {
        return outcome.sent()
            ? CmsActionResult.refreshWithToast(testSucceeded(outcome))
            : CmsActionResult.errorToast(Microcopy.of("test_failed").withFilter("scope", "notification_channel")
                .withArg("reason", outcome.reasonOr(
                    Microcopy.of("test_failed_unknown").withFilter("scope", "notification_channel"))));
    }

    /**
     * The toast for a test send that went out, saying which of the two things happened.
     *
     * AIDEV-NOTE: {@code sent()} covers a relay HANDOFF too: "Test delivered" over a chain that only handed the
     * message to the comms hub told the operator their channel works while the hub's own row could still say it had
     * no transport for it.
     */
    static @NonNull Microcopy testSucceeded(@NonNull NotifyOutcome outcome) {
        return Microcopy.of(outcome.delivered() ? "test_ok" : "test_accepted")
            .withFilter("scope", "notification_channel");
    }
}
