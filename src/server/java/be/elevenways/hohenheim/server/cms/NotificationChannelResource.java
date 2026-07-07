package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.notification.NotificationService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Webhook notification channels (Slack, Discord, generic JSON) with a
 * test-send row action.
 */
public final class NotificationChannelResource extends HohenheimRowResource {

    private static final List<FieldOption<String>> FORMAT_OPTIONS = List.of(
        FieldOption.of(NotificationService.FORMAT_SLACK, "Slack"),
        FieldOption.of(NotificationService.FORMAT_DISCORD, "Discord"),
        FieldOption.of(NotificationService.FORMAT_GENERIC, "Generic JSON"));

    private final NotificationService notifications = new NotificationService();

    private final FormSpec formSpec = FormSpec.builder()
        .add(NotificationChannelModel.NAME)
        .add(Select.of(NotificationChannelModel.FORMAT).options(OptionSource.of(FORMAT_OPTIONS)).build())
        .add(NotificationChannelModel.URL)
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "notification_channel"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.notification_channel.plural"); }
    @Override public @NonNull String slug() { return "notifications"; }
    @Override public @NonNull Model model() { return Models.get(NotificationChannelModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 30; }
    @Override public @NonNull Icon icon() { return Icon.of("bell"); }

    @Override protected @NonNull String auditResourceType() { return AuditLogModel.RESOURCE_NOTIFICATION_CHANNEL; }

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
    @Override protected boolean reloadsProxy() { return false; }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = mutable(coerced);
        validate(values);
        values.put("kind", NotificationService.KIND_WEBHOOK);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = mutable(coerced);
        validate(values);
        values.put("kind", NotificationService.KIND_WEBHOOK);
        super.updateRow(existing, values, accessContext);
    }

    private static void validate(@NonNull Map<String, Object> coerced) {
        Object urlValue = coerced.get("url");
        String url = urlValue != null ? String.valueOf(urlValue).trim() : "";
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalStateException("URL must start with http:// or https://");
        }
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "test_channel"))
            .label("hohenheim.notification_channel.test")
            .icon(Icon.of("paper-plane"))
            .handler((row, ctx) -> {
                String name = row.get(NotificationChannelModel.NAME);
                boolean delivered = this.notifications.sendTo(name,
                    "Hohenheim test", "If you can read this, the channel works.");
                CmsSupport.audit(ctx.access(), AuditLogModel.ACTION_TESTED,
                    AuditLogModel.RESOURCE_NOTIFICATION_CHANNEL, name, name);
                return delivered
                    ? CmsActionResult.refreshWithToast(Microcopy.of("hohenheim.notification_channel.test_ok"))
                    : CmsActionResult.errorToast(Microcopy.of("hohenheim.notification_channel.test_failed"));
            })
            .build());
        return actions;
    }
}
