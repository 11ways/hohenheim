package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.ManagedClient;
import be.elevenways.spamservice.client.ManagedClientInput;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.cms.server.page.ResourcePageEndpoints;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
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

/** Canonical Hohenheim CRUD surface for remote Spamservice clients. */
public final class SpamserviceClientsResource extends SpamserviceRemoteResource<ManagedClient> {

    public static final String SLUG = "spamservice-clients";
    private static final Schema SCHEMA = new Schema();
    private static final StringField NAME = SCHEMA.addField(StringField.builder("name").required()
        .label(Microcopy.of("name").withFilter("scope", "spamservice_client")).build());
    private static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled").defaultValue(true)
        .label(Microcopy.of("enabled").withFilter("scope", "spamservice_client")).build());
    private static final BooleanField TRUSTED = SCHEMA.addField(BooleanField.builder("trusted").defaultValue(false)
        .label(Microcopy.of("trusted").withFilter("scope", "spamservice_client")).build());
    private static final BooleanField PROVISIONER = SCHEMA.addField(BooleanField.builder("provisioner").defaultValue(false)
        .label(Microcopy.of("provisioner").withFilter("scope", "spamservice_client")).build());
    private static final BooleanField MANAGER = SCHEMA.addField(BooleanField.builder("manager").defaultValue(false)
        .label(Microcopy.of("manager").withFilter("scope", "spamservice_client")).build());
    private static final StringField EXTERNAL_ID = SCHEMA.addField(StringField.builder("external_id")
        .label(Microcopy.of("external_id").withFilter("scope", "spamservice_client")).build());
    private static final UuidField OWNER_ID = SCHEMA.addField(UuidField.builder("provisioned_by_client_id")
        .label(Microcopy.of("owner").withFilter("scope", "spamservice_client")).build());
    private static final StringField ALLOWED_LANGUAGES = SCHEMA.addField(StringField.builder("allowed_languages")
        .label(Microcopy.of("allowed_languages").withFilter("scope", "spamservice_client")).build());
    private static final IntegerField SPAM_THRESHOLD = SCHEMA.addField(IntegerField.builder("spam_threshold")
        .defaultValue(50).label(Microcopy.of("spam_threshold").withFilter("scope", "spamservice_client")).build());
    private static final TextField NOTES = SCHEMA.addField(TextField.builder("notes")
        .label(Microcopy.of("notes").withFilter("scope", "spamservice_client")).build());

    private final FormSpec formSpec = FormSpec.builder()
        .add(NAME).add(ENABLED).add(TRUSTED).add(PROVISIONER).add(MANAGER)
        .add(EXTERNAL_ID).add(OWNER_ID).add(ALLOWED_LANGUAGES).add(SPAM_THRESHOLD).add(NOTES).build();

    public SpamserviceClientsResource() {}

    SpamserviceClientsResource(Supplier<SpamserviceClient> clientSupplier) {
        super(clientSupplier);
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "spamservice_client"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "spamservice_client"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Schema schema() { return SCHEMA; }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 30; }
    @Override public @NonNull Icon icon() { return Icon.of("users"); }

    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        return List.of(
            ResourceFieldBinding.of("external_id", FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of("provisioned_by_client_id", FieldAccess.alwaysReadonly()));
    }

    @Override
    public @NonNull TableSpec<ManagedClient> tableSpec() {
        return TableSpec.<ManagedClient>builder()
            .column(ColumnSpec.fromField(NAME).build())
            .column(ColumnSpec.fromField(ENABLED).filterable().build())
            .column(ColumnSpec.fromField(TRUSTED).build())
            .column(ColumnSpec.fromField(PROVISIONER).build())
            .column(ColumnSpec.fromField(MANAGER).build())
            .column(ColumnSpec.fromField(EXTERNAL_ID).build())
            .column(ColumnSpec.fromField(SPAM_THRESHOLD).build())
            .filter(FilterSpec.global("q", Microcopy.of("search").withFilter("scope", "spamservice"),
                FilterSpec.Kind.TEXT).build())
            .filter(FilterSpec.forField(ENABLED, FilterSpec.Kind.BOOLEAN).build())
            .build();
    }

    @Override
    protected @NonNull PageResult<ManagedClient> fetchPage(@NonNull SpamserviceClient client,
                                                            TableView.@NonNull Applied<ManagedClient> applied) {
        return client.clients(applied.page(), applied.schema().pageSize(), textFilter(applied, "q"),
            booleanFilter(applied, "enabled"));
    }

    @Override public @NonNull String rowKey(@NonNull ManagedClient row) { return row.id(); }
    @Override public @Nullable Object parsePrimaryKey(@NonNull String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
    @Override public @Nullable ManagedClient loadRow(@NonNull Object key, @NonNull AccessContext context) {
        return this.requireClient().client(key.toString());
    }

    @Override
    public @NonNull Map<String, Object> valuesFromRow(@NonNull ManagedClient row) {
        return Map.ofEntries(
            Map.entry("name", row.name()), Map.entry("enabled", row.enabled()),
            Map.entry("trusted", row.trusted()), Map.entry("provisioner", row.provisioner()),
            Map.entry("manager", row.manager()), Map.entry("external_id", value(row.externalId())),
            Map.entry("provisioned_by_client_id", uuidValue(row.provisionedByClientId())),
            Map.entry("allowed_languages", value(row.allowedLanguages())),
            Map.entry("spam_threshold", row.spamThreshold()), Map.entry("notes", value(row.notes())));
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> values, @NonNull AccessContext context) {
        return this.requireClient().createClient(input(values)).id();
    }

    @Override
    public void updateRow(@NonNull ManagedClient existing, @NonNull Map<String, Object> values,
                          @NonNull AccessContext context) {
        this.requireClient().updateClient(existing.id(), input(values), existing.revision());
    }

    @Override
    public void deleteRow(@NonNull ManagedClient existing, @NonNull AccessContext context) {
        this.requireClient().deleteClient(existing.id());
    }

    @Override
    public @Nullable Object cellValue(@NonNull ManagedClient row, @NonNull ColumnSpec column) {
        return switch (column.name()) {
            case "name" -> row.name();
            case "enabled" -> row.enabled();
            case "trusted" -> row.trusted();
            case "provisioner" -> row.provisioner();
            case "manager" -> row.manager();
            case "external_id" -> row.externalId();
            case "spam_threshold" -> row.spamThreshold();
            default -> null;
        };
    }

    @Override
    public @NonNull List<RowAction<ManagedClient>> rowActions() {
        return List.of(RowAction.Url.<ManagedClient>builder(Identifier.of("hohenheim", "spamservice_client_keys"))
            .label(Microcopy.of("keys").withFilter("scope", "spamservice_client"))
            .description(Microcopy.of("keys_hint").withFilter("scope", "spamservice_client"))
            .icon(Icon.of("key"))
            .url(row -> new Uri(ResourcePageEndpoints.RECORD_SUBPAGE
                .with(ResourcePageEndpoints.PANEL_PARAM, "admin")
                .with(ResourcePageEndpoints.RESOURCE_PARAM, SLUG)
                .with(ResourcePageEndpoints.RESOURCE_ID_PARAM, row.id())
                .with(ResourcePageEndpoints.SUBPAGE_PARAM, SpamserviceClientKeysPage.SLUG)
                .toUrl()))
            .build());
    }

    @Override
    public @NonNull List<RecordScopedPage<ManagedClient>> subpages() {
        return List.of(new SpamserviceClientKeysPage());
    }

    @Override public @Nullable String recordTitle(@NonNull ManagedClient row) { return row.name(); }

    private static ManagedClientInput input(Map<String, Object> values) {
        return new ManagedClientInput(String.valueOf(values.getOrDefault("name", "")),
            bool(values, "enabled"), bool(values, "trusted"), bool(values, "provisioner"),
            bool(values, "manager"), nullable(values, "allowed_languages"),
            integer(values, "spam_threshold", 50), nullable(values, "notes"));
    }

    private static boolean bool(Map<String, Object> values, String name) {
        return Boolean.TRUE.equals(values.get(name));
    }

    private static int integer(Map<String, Object> values, String name, int fallback) {
        return values.get(name) instanceof Number number ? number.intValue() : fallback;
    }

    private static @Nullable String nullable(Map<String, Object> values, String name) {
        Object value = values.get(name);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private static String value(@Nullable String value) { return value != null ? value : ""; }

    private static Object uuidValue(@Nullable String value) {
        return value == null || value.isBlank() ? "" : UUID.fromString(value);
    }
}
