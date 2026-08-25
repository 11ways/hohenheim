package be.elevenways.hohenheim.server.cms;


import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RowResource;
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
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteLocales;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.comms.server.NotifyOutcome;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Webhook notification channels (Slack, Discord, generic JSON) with a
 * test-send row action.
 */
public final class NotificationChannelResource extends RowResource {

    // AIDEV-NOTE: the format select is spelled out to keep it CLEARABLE even though the
    // field is required. The derived entry drops clearable for a required field, and a
    // non-clearable select refuses a blank AT COERCION -- which aborts the whole submit
    // before FormValidator runs, so an empty create form would answer "Choose one of the
    // offered options" for format and say NOTHING about the missing name and url. A blank
    // option plus the field's Required validator names all three at once. The options come
    // from the enum's own declared home, never a second list.
    private final FormSpec formSpec = FormSpec.builder()
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

    /** The virtual column holding the subscribed event tokens, and the name's subtext. */
    private static final String EVENTS_COLUMN = "events";

    // AIDEV-NOTE: an explicit spec. The derived one led with KIND, which every row stores
    // as "webhook" (persistRow stamps it) -- a column of one repeated word. What an
    // operator needs instead is WHAT each channel is subscribed to, which lived only
    // inside the edit form.
    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(NotificationChannelModel.NAME).filterable()
            .subtext(EVENTS_COLUMN).build())
        .column(ColumnSpec.virtual(EVENTS_COLUMN,
            Microcopy.of("events").withFilter("scope", "notification_channel")).hidden().build())
        .column(ColumnSpec.fromField(NotificationChannelModel.FORMAT).filterable().build())
        .column(ColumnSpec.fromField(NotificationChannelModel.CREATED_AT).build())
        .filter(FilterSpec.forField(NotificationChannelModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(NotificationChannelModel.NAME)).build())
        .filter(FilterSpec.forField(NotificationChannelModel.FORMAT, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(NotificationChannelModel.FORMAT)).build())
        .build();

    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /**
     * The event summary under the name, in the reader's own words.
     *
     * AIDEV-NOTE: the tokens are STORED data and were rendered raw here, so the subtext
     * read "cert_expiring" while the very same events read "Certificate expiring" in the
     * form's picker two clicks away. Each token is resolved through its own declared
     * member label; a token this build no longer declares keeps its raw spelling, which
     * is the honest rendering of a subscription nothing can route.
     *
     * @return the subscribed events, or null when the channel takes every event (an
     *         empty subscription means "all", and a second line saying nothing is worse
     *         than no second line)
     */
    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if (!EVENTS_COLUMN.equals(column.name())) {
            return super.cellValue(row, column);
        }
        List<String> events = row.get(NotificationChannelModel.EVENTS);
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

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "notification_channel"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "notification_channel"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "notification_channel"); }
    @Override public @NonNull String slug() { return "notifications"; }
    @Override public @NonNull Model model() { return Models.get(NotificationChannelModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }

    /** The name only -- the URL is a bearer credential. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(NotificationChannelModel.NAME);
    }

    // AIDEV-NOTE: System, between the activity log (90) and the settings editor (95): where
    // this installation talks about ITSELF. It is not a networking concern -- the channels
    // carry alerts, not traffic.
    @Override public @NonNull NavGroup navGroup() { return NavGroup.SYSTEM; }
    @Override public int navOrder() { return 92; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "notification_channel");
    }
    @Override public @NonNull Icon icon() { return Icon.of("bell"); }


    /** kind is staged by persist/update but is not a form entry; stamp it here. */
    @Override
    public @NonNull Row valuesToRow(@NonNull Map<String, Object> coerced) {
        Row row = super.valuesToRow(coerced);
        if (coerced.get("kind") instanceof String kind) {
            row.set(NotificationChannelModel.KIND, kind);
        }
        return row;
    }

    @Override
    public void applyValuesToRow(@NonNull Row row, @NonNull Map<String, Object> coerced) {
        super.applyValuesToRow(row, coerced);
        if (coerced.get("kind") instanceof String kind) {
            row.set(NotificationChannelModel.KIND, kind);
        }
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validate(values, null);
        values.put("kind", NotificationChannelModel.KIND_WEBHOOK);
        return super.persistRow(values, accessContext);
    }

    /**
     * The name only.
     *
     * AIDEV-NOTE: FORMAT is deliberately full-form only. It decides the PAYLOAD SHAPE a
     * webhook receives, and a Slack-shaped body posted to a Discord DSN is accepted by
     * nobody and reported by nothing -- delivery just stops, silently. URL is the endpoint
     * credential itself. And an empty {@code events} list means RECEIVE EVERYTHING, which
     * is why this resource has no quick-add bar either: a one-line create would wire an
     * alert firehose to a URL nobody re-read.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(NotificationChannelModel.NAME);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validate(values, existing);
        values.put("kind", NotificationChannelModel.KIND_WEBHOOK);
        super.updateRow(existing, values, accessContext);
    }

    /**
     * URL scheme and event vocabulary.
     *
     * AIDEV-NOTE: {@code existing} is the PARTIAL-WRITE fallback and it is load-bearing.
     * The inline cell lane hands updateRow a map holding EXACTLY ONE entry, so reading
     * {@code url} straight off the coerced map made a rename refuse with "url_scheme" --
     * a refusal naming a field the operator never touched. Absent key means leave-alone
     * everywhere in this pipeline; a validator must read the STORED value there, never
     * treat absence as blank.
     */
    private static void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Object urlValue = coerced.containsKey("url") ? coerced.get("url")
            : existing != null ? existing.get(NotificationChannelModel.URL) : null;
        String url = urlValue != null ? String.valueOf(urlValue).trim() : "";
        // A BLANK url is the field's own Required validator to refuse ("Url is required"),
        // and it already did so before this runs -- describing a format rule for an empty
        // box only ever told the operator the wrong thing.
        if (!url.isEmpty() && !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw Violations.ofField("url", null, CmsSupport.violationText("url_scheme"));
        }
        if (coerced.get("events") instanceof List<?> events) {
            for (Object event : events) {
                if (!NotificationEvents.isKnown(String.valueOf(event))) {
                    throw Violations.ofField("events", event,
                        CmsSupport.violationText("unknown_event")
                            .withArg("event", String.valueOf(event))
                            .withArg("valid", String.join(", ", NotificationEvents.ALL)));
                }
            }
        }
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "test_channel"))
            .label(Microcopy.of("test").withFilter("scope", "notification_channel"))
            .icon(Icon.of("paper-plane"))
            .handler((row, ctx) -> {
                String name = row.get(NotificationChannelModel.NAME);
                // Outbound copy has no requesting user to follow: it speaks the
                // server's default locale, like every other alert should.
                LocaleChain locales = LocaleChain.of(RouteLocales.get().getDefaultLocale());
                NotifyOutcome outcome = Alerts.testChannelOutcome(row,
                    Microcopy.of("test_subject").withFilter("scope", "notification_channel")
                        .resolve(locales, Zenit.getMessageResolver()),
                    Microcopy.of("test_body").withFilter("scope", "notification_channel")
                        .resolve(locales, Zenit.getMessageResolver()));
                ActivityLog.record(this.model(), row.get(NotificationChannelModel.ID), "tested", name);
                return outcome.sent()
                    ? CmsActionResult.refreshWithToast(Microcopy.of("test_ok").withFilter("scope", "notification_channel"))
                    : CmsActionResult.errorToast(Microcopy.of("test_failed").withFilter("scope", "notification_channel")
                        .withArg("reason", outcome.reasonOr("unknown error")));
            })
            .build());
        return actions;
    }
}
