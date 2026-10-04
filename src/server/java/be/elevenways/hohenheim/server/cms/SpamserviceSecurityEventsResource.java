package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.spamservice.client.SecurityEventEntry;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.RangeFilterValue;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.field.DateField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.LongField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Remotely paged, filterable, read-only Spamservice security events, a store entry over the management API.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SpamserviceSecurityEventsResource {

    public static final String SLUG = "spamservice-security-events";
    static final Identifier ID = HohenheimIds.id("spamservice_security_event");
    static final SubjectType<SecurityEventEntry> EVENT = SubjectType.of(ID, SecurityEventEntry.class,
        SecurityEventEntry::id);

    private static final UuidField CLIENT_ID = UuidField.builder("client_id").label(words("client")).build();
    private static final StringField TYPE = StringField.builder("type").label(words("type")).build();
    private static final StringField IP = StringField.builder("ip").label(words("ip")).build();
    private static final DateField DAY = DateField.builder("day").label(words("day")).build();
    private static final LongField COUNT = LongField.builder("count").label(words("count")).build();
    private static final DateTimeField FIRST_AT = DateTimeField.builder("first_at").label(words("first_at")).build();
    private static final DateTimeField LAST_AT = DateTimeField.builder("last_at").label(words("last_at")).build();
    private static final TextField DETAIL = TextField.builder("last_detail").label(words("detail")).build();

    /** The fields the management API answers for one security event. */
    private static final List<Field<?, ?>> FIELDS = List.of(CLIENT_ID, TYPE, IP, DAY, COUNT, FIRST_AT, LAST_AT,
        DETAIL);

    private SpamserviceSecurityEventsResource() {
    }

    /** @return the entry over the managed runtime's client */
    public static @NonNull PanelResource<SecurityEventEntry> create() {
        return create(SpamserviceRemoteStore.MANAGED);
    }

    static @NonNull PanelResource<SecurityEventEntry> create(@NonNull Supplier<SpamserviceClient> clients) {
        SpamserviceRemoteStore.requireNonNull(clients);
        TableSpec<SecurityEventEntry> table = TableSpec.<SecurityEventEntry>builder()
            // AIDEV-NOTE: last_detail was in the cell switch and in the form, but was not a column -- so the one
            // sentence saying WHAT was seen was invisible on the surface operators actually watch.
            .column(ColumnSpec.fromField(TYPE).filterable().subtext("last_detail").build())
            .column(ColumnSpec.fromField(DETAIL).hidden().build())
            .column(ColumnSpec.fromField(IP).filterable().copyable().build())
            .column(ColumnSpec.fromField(DAY).build())
            .column(ColumnSpec.fromField(COUNT).build())
            .column(ColumnSpec.fromField(CLIENT_ID).filterable().build())
            .column(ColumnSpec.fromField(LAST_AT).build())
            .filter(FilterSpec.leaf(TYPE, CoreTypes.CONTAINS).build())
            .filter(FilterSpec.leaf(IP, CoreTypes.CONTAINS).build())
            .filter(FilterSpec.leaf(CLIENT_ID, CoreTypes.EQUALS).build())
            // One temporal leaf over the day the API's from/to bounds compare (BETWEEN, or a lone GTE/LTE bound).
            .filter(FilterSpec.leaf(DAY, CoreTypes.BETWEEN, CoreTypes.GTE, CoreTypes.LTE).build())
            .defaultSort(SortSpec.desc("last_at")).build();
        return PanelResource.builder(ID, SLUG, EVENT)
            .label(words("plural"))
            .recordLabel(words("singular"))
            .navGroup(HohenheimPanel.SECURITY_GROUP)
            .navOrder(50)
            .showInNav(false)
            .icon(Icon.of("shield-halved"))
            .reads(ResourceReads.<SecurityEventEntry>typed(SecurityEventEntry::id)
                .load((key, access) -> {
                    UUID id = SpamserviceRemoteStore.uuidOrNull(key);
                    return id == null ? null : SpamserviceRemoteStore.require(clients).securityEvent(id.toString());
                })
                .values(SpamserviceSecurityEventsResource::values)
                .cells(SpamserviceSecurityEventsResource::cell)
                .build()
                // Type plus origin: the two facts that tell one aggregated event row from the next.
                .title(row -> row.type() + " " + row.ip()))
            .list(ResourceList.store(table, SpamserviceRemoteStore.pages(ID, clients, FIELDS, List.of(),
                    (client, applied, access) -> {
                        RangeFilterValue days = applied.filter().get("day") instanceof RangeFilterValue range
                            ? range : null;
                        return client.securityEvents(applied.page(), applied.schema().pageSize(),
                            SpamserviceRemoteStore.textFilter(applied, "client_id"),
                            SpamserviceRemoteStore.textFilter(applied, "type"),
                            SpamserviceRemoteStore.textFilter(applied, "ip"),
                            days == null ? null : Texts.trimmedOrNull(days.from()),
                            days == null ? null : Texts.trimmedOrNull(days.to()));
                    }))
                .chrome(ListChrome.MINIMAL)
                .notice(SpamserviceRemoteStore.notice(ID, clients))
                .build())
            .form(ResourceForm.<SecurityEventEntry>of(FormSpec.builder()
                .add(CLIENT_ID).add(TYPE).add(IP).add(DAY).add(COUNT).add(FIRST_AT).add(LAST_AT).add(DETAIL)
                .build()).build())
            .build();
    }

    private static @NonNull Map<String, Object> values(@NonNull SecurityEventEntry row) {
        return Map.of("client_id", UUID.fromString(row.clientId()), "type", row.type(), "ip", row.ip(),
            "day", SpamserviceRemoteStore.orBlank(row.day()), "count", row.count(),
            "first_at", SpamserviceRemoteStore.orBlank(row.firstAt()),
            "last_at", SpamserviceRemoteStore.orBlank(row.lastAt()),
            "last_detail", SpamserviceRemoteStore.orBlank(row.lastDetail()));
    }

    private static @Nullable Object cell(@NonNull SecurityEventEntry row, @NonNull ColumnSpec column) {
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

    private static @NonNull Microcopy words(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "spamservice_event");
    }
}
