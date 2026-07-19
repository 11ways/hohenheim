package be.elevenways.hohenheim.server.cms;


import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.zenit.common.edit.Array;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Webhook notification channels (Slack, Discord, generic JSON) with a
 * test-send row action.
 */
public final class NotificationChannelResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(NotificationChannelModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(NotificationChannelModel.FORMAT))
        .add(NotificationChannelModel.URL)
        .add(Array.of(NotificationChannelModel.EVENTS, NotificationChannelModel.EVENTS.getItemField())
            .options(OptionSource.of(NotificationEvents.ALL.stream()
                .map(event -> FieldOption.of(event,
                    Microcopy.of(event).withFilter("scope", "notification_event")))
                .toList()))
            .build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "notification_channel"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "notification_channel"); }
    @Override public @NonNull String slug() { return "notifications"; }
    @Override public @NonNull Model model() { return Models.get(NotificationChannelModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 30; }
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
        validate(values);
        values.put("kind", NotificationChannelModel.KIND_WEBHOOK);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validate(values);
        values.put("kind", NotificationChannelModel.KIND_WEBHOOK);
        super.updateRow(existing, values, accessContext);
    }

    private static void validate(@NonNull Map<String, Object> coerced) {
        Object urlValue = coerced.get("url");
        String url = urlValue != null ? String.valueOf(urlValue).trim() : "";
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
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
                boolean delivered = Alerts.testChannel(row,
                    "Hohenheim test", "If you can read this, the channel works.");
                ActivityLog.record(this.model(), row.get(NotificationChannelModel.ID), "tested", name);
                return delivered
                    ? CmsActionResult.refreshWithToast(Microcopy.of("test_ok").withFilter("scope", "notification_channel"))
                    : CmsActionResult.errorToast(Microcopy.of("test_failed").withFilter("scope", "notification_channel"));
            })
            .build());
        return actions;
    }
}
