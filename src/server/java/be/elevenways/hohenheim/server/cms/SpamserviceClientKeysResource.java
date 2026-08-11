package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.CreatedClientKey;
import be.elevenways.spamservice.client.ManagedClientKey;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.flash.FlashToast;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.cms.server.page.CmsPageContext;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Client-scoped key resource with one-shot raw-key disclosure. */
public final class SpamserviceClientKeysResource extends SpamserviceRemoteResource<ManagedClientKey> {

    public static final String SLUG = "spamservice-keys";

    /**
     * The client scoping query parameter, typed so links BIND it.
     *
     * AIDEV-NOTE: the reads below still use getQueryParam("client_id") -- the name
     * here is the single spelling both sides share, and binding it through a
     * ParameterDefinition is what keeps "?client_id=" out of the link builders.
     */
    static final ParameterDefinition<String> CLIENT_ID_QUERY = ParameterDefinition
        .builder(String.class).name("client_id").stringResolver(value -> value).build();
    private static final Schema SCHEMA = new Schema();
    private static final UuidField CLIENT_ID = SCHEMA.addField(UuidField.builder("client_id").required()
        .label(Microcopy.of("client").withFilter("scope", "spamservice_key")).build());
    private static final StringField NAME = SCHEMA.addField(StringField.builder("name").required()
        .label(Microcopy.of("name").withFilter("scope", "spamservice_key")).build());
    private static final StringField RAW_KEY = SCHEMA.addField(StringField.builder("key").visibleIn(EditView.CREATE)
        .secret()
        .label(Microcopy.of("raw_key").withFilter("scope", "spamservice_key"))
        .help(Microcopy.of("raw_key_help").withFilter("scope", "spamservice_key")).build());
    private static final BooleanField ACTIVE = SCHEMA.addField(BooleanField.builder("active").defaultValue(true)
        .label(Microcopy.of("active").withFilter("scope", "spamservice_key")).build());
    private static final DateTimeField LAST_USED = SCHEMA.addField(DateTimeField.builder("last_used")
        .label(Microcopy.of("last_used").withFilter("scope", "spamservice_key")).build());
    private static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder("created_at")
        .label(Microcopy.of("created_at").withFilter("scope", "spamservice_key")).build());

    private final FormSpec formSpec = FormSpec.builder()
        .add(CLIENT_ID).add(NAME).add(RAW_KEY).add(ACTIVE).add(LAST_USED).add(CREATED_AT).build();
    private final ThreadLocal<String> listedClientId = new ThreadLocal<>();

    public SpamserviceClientKeysResource() {}

    SpamserviceClientKeysResource(Supplier<SpamserviceClient> clientSupplier) { super(clientSupplier); }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "spamservice_key"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "spamservice_key"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Schema schema() { return SCHEMA; }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 40; }
    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("key"); }

    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        return List.of(
            ResourceFieldBinding.of("last_used", FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of("created_at", FieldAccess.alwaysReadonly()));
    }

    @Override
    public @NonNull TableSpec<ManagedClientKey> tableSpec() {
        return TableSpec.<ManagedClientKey>builder()
            .column(ColumnSpec.fromField(NAME).build())
            .column(ColumnSpec.fromField(ACTIVE).build())
            .column(ColumnSpec.fromField(LAST_USED).build())
            .column(ColumnSpec.fromField(CREATED_AT).build())
            .build();
    }

    @Override
    protected @NonNull PageResult<ManagedClientKey> fetchPage(
            @NonNull SpamserviceClient client, TableView.@NonNull Applied<ManagedClientKey> applied) {
        String clientId = accessClientId();
        return clientId != null
            ? client.keys(clientId, applied.page(), applied.schema().pageSize())
            : new PageResult<>(List.of(), applied.page(), applied.schema().pageSize(), 0);
    }

    @Override
    public @NonNull List<ManagedClientKey> listRows(TableView.@NonNull Applied<ManagedClientKey> applied,
                                                    @NonNull AccessContext context) {
        this.listedClientId.set(trimmed(context.conduit().getQueryParam("client_id")));
        try {
            return super.listRows(applied, context);
        } finally {
            this.listedClientId.remove();
        }
    }

    private @Nullable String accessClientId() { return this.listedClientId.get(); }

    @Override public @NonNull String rowKey(@NonNull ManagedClientKey row) { return row.clientId() + "~" + row.id(); }
    @Override public @Nullable Object parsePrimaryKey(@NonNull String raw) {
        int separator = raw.indexOf('~');
        return separator > 0 && separator < raw.length() - 1
            ? keyRef(raw.substring(0, separator), raw.substring(separator + 1)) : null;
    }
    @Override public @Nullable ManagedClientKey loadRow(@NonNull Object key, @NonNull AccessContext context) {
        if (!(key instanceof KeyRef ref)) return null;
        return this.requireClient().keys(ref.clientId().toString(), 1, 200).items().stream()
            .filter(item -> item.id().equals(ref.keyId().toString())).findFirst().orElse(null);
    }

    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        String clientId = trimmed(conduit.getQueryParam("client_id"));
        return clientId != null ? Map.of("client_id", UUID.fromString(clientId), "active", true) : Map.of("active", true);
    }

    @Override
    public @NonNull Map<String, Object> valuesFromRow(@NonNull ManagedClientKey row) {
        return Map.of("client_id", UUID.fromString(row.clientId()), "name", row.name(), "key", "", "active", row.active(),
            "last_used", value(row.lastUsed()), "created_at", value(row.createdAt()));
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> values, @NonNull AccessContext context) {
        String clientId = String.valueOf(values.getOrDefault("client_id", ""));
        String name = String.valueOf(values.getOrDefault("name", "key"));
        String raw = trimmed(values.get("key"));
        CreatedClientKey created = this.requireClient().createKey(clientId, name, raw);
        Microcopy message = created.generated()
            ? Microcopy.of("key_created").withFilter("scope", "spamservice_key").withArg("key", created.key())
            : Microcopy.of("key_adopted").withFilter("scope", "spamservice_key");
        if (context.conduit() != null) {
            // AIDEV-NOTE: the generated key is a one-shot disclosure; the secret-arg
            // variant parks it in SecretDisclosures so only a single-use handle
            // rides the session (an adopted key was operator-entered, not disclosed).
            CmsPageContext.stashFlashToast(context.conduit(),
                new FlashToast(message, CmsActionResult.Toast.Level.SUCCESS),
                created.generated() ? Set.of("key") : Set.of());
        }
        return created.clientId() + "~" + created.id();
    }

    @Override
    public void updateRow(@NonNull ManagedClientKey existing, @NonNull Map<String, Object> values,
                          @NonNull AccessContext context) {
        this.requireClient().updateKey(existing.id(), String.valueOf(values.get("name")),
            values.get("active") instanceof Boolean active ? active : null);
    }

    @Override
    public void deleteRow(@NonNull ManagedClientKey existing, @NonNull AccessContext context) {
        this.requireClient().revokeKey(existing.id());
    }

    @Override
    public @Nullable Object cellValue(@NonNull ManagedClientKey row, @NonNull ColumnSpec column) {
        return switch (column.name()) {
            case "name" -> row.name();
            case "active" -> row.active();
            case "last_used" -> row.lastUsed();
            case "created_at" -> row.createdAt();
            default -> null;
        };
    }

    @Override
    public @NonNull List<RowAction<ManagedClientKey>> rowActions() {
        return List.of(
            RowAction.Invoke.<ManagedClientKey>builder(Identifier.of("hohenheim", "spamservice_key_enable"))
                .label(Microcopy.of("enable").withFilter("scope", "spamservice_key"))
                .icon(Icon.of("check"))
                .visibleFor((key, context) -> !key.active())
                .handler((key, context) -> {
                    this.requireClient().updateKey(key.id(), null, true);
                    return CmsActionResult.refreshWithToast(
                        Microcopy.of("key_enabled").withFilter("scope", "spamservice_key"));
                }).build(),
            RowAction.Invoke.<ManagedClientKey>builder(Identifier.of("hohenheim", "spamservice_key_revoke"))
                .label(Microcopy.of("revoke").withFilter("scope", "spamservice_key"))
                .icon(Icon.of("xmark"))
                .visibleFor((key, context) -> key.active())
                .confirmation(ConfirmationSpec.builder()
                    .title(Microcopy.of("revoke").withFilter("scope", "spamservice_key"))
                    .body(Microcopy.of("revoke_confirm").withFilter("scope", "spamservice_key")).build())
                .handler((key, context) -> {
                    this.requireClient().revokeKey(key.id());
                    return CmsActionResult.refreshWithToast(
                        Microcopy.of("key_revoked").withFilter("scope", "spamservice_key"));
                }).build());
    }

    @Override public @NonNull ResourceParent<ManagedClientKey> parent() {
        return ResourceParent.of(SpamserviceClientsResource.SLUG, ManagedClientKey::clientId)
            .tab(SpamserviceClientKeysPage.SLUG);
    }

    @Override public @Nullable String recordTitle(@NonNull ManagedClientKey row) { return row.name(); }

    private static @Nullable String trimmed(@Nullable Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return String.valueOf(value).trim();
    }

    private static Object value(@Nullable Object value) { return value != null ? value : ""; }

    private static @Nullable KeyRef keyRef(String clientId, String keyId) {
        try {
            return new KeyRef(UUID.fromString(clientId), UUID.fromString(keyId));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private record KeyRef(UUID clientId, UUID keyId) {}
}
