package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SecurityEventEntry;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.field.DateField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.LongField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Remotely paged, filterable, read-only Spamservice security events. */
public final class SpamserviceSecurityEventsResource extends SpamserviceRemoteResource<SecurityEventEntry> {

    public static final String SLUG = "spamservice-security-events";
    private static final Schema SCHEMA = new Schema();
    private static final UuidField CLIENT_ID = SCHEMA.addField(UuidField.builder("client_id")
        .label(Microcopy.of("client").withFilter("scope", "spamservice_event")).build());
    private static final StringField TYPE = SCHEMA.addField(StringField.builder("type")
        .label(Microcopy.of("type").withFilter("scope", "spamservice_event")).build());
    private static final StringField IP = SCHEMA.addField(StringField.builder("ip")
        .label(Microcopy.of("ip").withFilter("scope", "spamservice_event")).build());
    private static final DateField DAY = SCHEMA.addField(DateField.builder("day")
        .label(Microcopy.of("day").withFilter("scope", "spamservice_event")).build());
    private static final LongField COUNT = SCHEMA.addField(LongField.builder("count")
        .label(Microcopy.of("count").withFilter("scope", "spamservice_event")).build());
    private static final DateTimeField FIRST_AT = SCHEMA.addField(DateTimeField.builder("first_at")
        .label(Microcopy.of("first_at").withFilter("scope", "spamservice_event")).build());
    private static final DateTimeField LAST_AT = SCHEMA.addField(DateTimeField.builder("last_at")
        .label(Microcopy.of("last_at").withFilter("scope", "spamservice_event")).build());
    private static final TextField DETAIL = SCHEMA.addField(TextField.builder("last_detail")
        .label(Microcopy.of("detail").withFilter("scope", "spamservice_event")).build());
    private final FormSpec formSpec = FormSpec.builder()
        .add(CLIENT_ID).add(TYPE).add(IP).add(DAY).add(COUNT).add(FIRST_AT).add(LAST_AT).add(DETAIL).build();

    public SpamserviceSecurityEventsResource() {}

    SpamserviceSecurityEventsResource(Supplier<SpamserviceClient> clientSupplier) { super(clientSupplier); }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "spamservice_security_event"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "spamservice_event"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "spamservice_event"); }

    /** Type plus origin: the two facts that tell one aggregated event row from the next. */
    @Override public @Nullable String recordTitle(@NonNull SecurityEventEntry row) {
        return row.type() + " " + row.ip();
    }

    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Schema schema() { return SCHEMA; }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 50; }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("shield-halved"); }
    @Override public boolean creatable() { return false; }
    @Override public boolean updatable() { return false; }
    @Override public boolean deletable() { return false; }

    @Override
    public @NonNull TableSpec<SecurityEventEntry> tableSpec() {
        return TableSpec.<SecurityEventEntry>builder()
            // AIDEV-NOTE: last_detail was in the cellValue switch and in the form, but was
            // not a column -- so the one sentence saying WHAT was seen was invisible on the
            // surface operators actually watch.
            .column(ColumnSpec.fromField(TYPE).filterable().subtext("last_detail").build())
            .column(ColumnSpec.fromField(DETAIL).hidden().build())
            .column(ColumnSpec.fromField(IP).filterable().copyable().build())
            .column(ColumnSpec.fromField(DAY).build())
            .column(ColumnSpec.fromField(COUNT).build())
            .column(ColumnSpec.fromField(CLIENT_ID).filterable().build())
            .column(ColumnSpec.fromField(LAST_AT).build())
            .filter(FilterSpec.forField(TYPE, FilterSpec.Kind.TEXT).build())
            .filter(FilterSpec.forField(IP, FilterSpec.Kind.TEXT).build())
            .filter(FilterSpec.forField(CLIENT_ID, FilterSpec.Kind.TEXT).build())
            .filter(FilterSpec.global("from", Microcopy.of("from").withFilter("scope", "spamservice_event"),
                FilterSpec.Kind.DATE).build())
            .filter(FilterSpec.global("to", Microcopy.of("to").withFilter("scope", "spamservice_event"),
                FilterSpec.Kind.DATE).build())
            .defaultSort(SortSpec.desc("last_at")).build();
    }

    @Override
    protected @NonNull PageResult<SecurityEventEntry> fetchPage(@NonNull SpamserviceClient client,
                                                                 TableView.@NonNull Applied<SecurityEventEntry> applied) {
        return client.securityEvents(applied.page(), applied.schema().pageSize(),
            textFilter(applied, "client_id"), textFilter(applied, "type"), textFilter(applied, "ip"),
            textFilter(applied, "from"), textFilter(applied, "to"));
    }

    @Override public @NonNull String rowKey(@NonNull SecurityEventEntry row) { return row.id(); }
    @Override public @Nullable Object parsePrimaryKey(@NonNull String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
    @Override public @Nullable SecurityEventEntry loadRow(@NonNull Object key, @NonNull AccessContext context) {
        return this.requireClient().securityEvent(key.toString());
    }
    @Override public @NonNull Map<String, Object> valuesFromRow(@NonNull SecurityEventEntry row) {
        return Map.of("client_id", UUID.fromString(row.clientId()), "type", row.type(), "ip", row.ip(),
            "day", value(row.day()), "count", row.count(), "first_at", value(row.firstAt()),
            "last_at", value(row.lastAt()), "last_detail", value(row.lastDetail()));
    }
    @Override public @NonNull Object persistRow(@NonNull Map<String, Object> values, @NonNull AccessContext context) {
        throw new UnsupportedOperationException();
    }
    @Override public void updateRow(@NonNull SecurityEventEntry row, @NonNull Map<String, Object> values,
                                    @NonNull AccessContext context) { throw new UnsupportedOperationException(); }
    @Override public void deleteRow(@NonNull SecurityEventEntry row, @NonNull AccessContext context) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable Object cellValue(@NonNull SecurityEventEntry row, @NonNull ColumnSpec column) {
        return switch (column.name()) {
            case "client_id" -> row.clientId();
            case "type" -> row.type();
            case "ip" -> row.ip();
            case "day" -> row.day();
            case "count" -> row.count();
            case "first_at" -> row.firstAt();
            case "last_at" -> row.lastAt();
            case "last_detail" -> row.lastDetail();
            default -> null;
        };
    }

    private static Object value(@Nullable Object value) { return value != null ? value : ""; }
}
