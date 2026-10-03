package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.CreatedClientKey;
import be.elevenways.spamservice.client.ManagedClientKey;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.flash.FlashLevel;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.flash.Flash;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A client's keys, a store child of the client listed on its Keys tab, with one-shot raw-key disclosure.
 *
 * AIDEV-NOTE: the management API lists keys per client only, so the child lists under its client through
 * {@code ChildStorePages} and creates through {@code createUnder} (C-3); outside a client the list asks nothing.
 * Enable and revoke are operations over the same remote calls the legacy row actions made.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SpamserviceClientKeysResource {
    private static final LeaseKeys KEYS = LeaseKeys.declare(HohenheimIds.id("spamservice_key_command"));
    private static final OperationCommand COMMAND = OperationCommand.serializedBy(KEYS,
        invocation -> invocation.subjectKeys().get(0)).onDatasource("default")
        .execution(CommandExecution.OUTSIDE_TRANSACTION);

    public static final String SLUG = "spamservice-keys";

    /** The client's tab the keys list on. */
    static final String TAB = "keys";

    static final Identifier ID = HohenheimIds.id("spamservice_key");

    /** A key is addressed as {@code <client id>~<key id>}: the management API reads keys per client only. */
    static final SubjectType<ManagedClientKey> KEY = SubjectType.of(ID, ManagedClientKey.class,
        SpamserviceClientKeysResource::keyOf);

    private static final UuidField CLIENT_ID = UuidField.builder("client_id").required()
        .label(words("client")).build();
    private static final StringField NAME = StringField.builder("name").required()
        .label(words("name")).build();
    private static final StringField RAW_KEY = StringField.builder("key").visibleIn(EditView.CREATE)
        .secret()
        .label(words("raw_key"))
        .help(words("raw_key_help")).build();
    private static final BooleanField ACTIVE = BooleanField.builder("active").defaultValue(true)
        .label(words("active")).build();
    private static final DateTimeField LAST_USED = DateTimeField.builder("last_used")
        .label(words("last_used")).build();
    private static final DateTimeField CREATED_AT = DateTimeField.builder("created_at")
        .label(words("created_at")).build();

    /** The fields the management API answers for one key; the raw key is never among them. */
    private static final List<Field<?, ?>> FIELDS = List.of(CLIENT_ID, NAME, ACTIVE, LAST_USED, CREATED_AT);

    /** The largest page the management API serves ({@code ManagementService.MAX_PAGE_SIZE}). */
    private static final int REMOTE_PAGE_SIZE = 200;

    public static final Operation<ManagedClientKey, Void, Void> ENABLE = Operation.declare(
            HohenheimIds.id("spamservice_key_enable"))
        .label(words("enable"))
        .icon(Icon.of("check"))
        .one(KEY)
        .gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .command(COMMAND)
        .register();

    public static final Operation<ManagedClientKey, Void, Void> REVOKE = Operation.declare(
            HohenheimIds.id("spamservice_key_revoke"))
        .label(words("revoke"))
        .icon(Icon.of("xmark"))
        .one(KEY)
        .gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .command(COMMAND)
        .register();

    static {
        OperationHandlers.loader(KEY, key -> load(SpamserviceRemoteStore.MANAGED, key));
        OperationHandlers.attach(ENABLE).handle(call -> {
            SpamserviceRemoteStore.require(SpamserviceRemoteStore.MANAGED).updateKey(call.subject().id(), null, true);
            return null;
        });
        OperationHandlers.attach(REVOKE).handle(call -> {
            SpamserviceRemoteStore.require(SpamserviceRemoteStore.MANAGED).revokeKey(call.subject().id());
            return null;
        });
    }

    private SpamserviceClientKeysResource() {
    }

    /** @return the entry over the managed runtime's client */
    public static @NonNull PanelResource<ManagedClientKey> create() {
        return create(SpamserviceRemoteStore.MANAGED);
    }

    static @NonNull PanelResource<ManagedClientKey> create(@NonNull Supplier<SpamserviceClient> clients) {
        SpamserviceRemoteStore.requireNonNull(clients);
        TableSpec<ManagedClientKey> table = TableSpec.<ManagedClientKey>builder()
            .column(ColumnSpec.fromField(NAME).build())
            .column(ColumnSpec.fromField(ACTIVE).build())
            .column(ColumnSpec.fromField(LAST_USED).build())
            .column(ColumnSpec.fromField(CREATED_AT).build())
            .build();
        FormSpec form = FormSpec.builder()
            .add(CLIENT_ID).add(NAME).add(RAW_KEY).add(ACTIVE).add(LAST_USED).add(CREATED_AT).build();
        return PanelResource.builder(ID, SLUG, KEY)
            .label(words("plural"))
            .recordLabel(words("singular"))
            .navGroup(HohenheimPanel.SECURITY_GROUP)
            .navOrder(40)
            .showInNav(false)
            .icon(Icon.of("key"))
            .parent(ResourceParent.<ManagedClientKey>of(SpamserviceClientsResource.SLUG, ManagedClientKey::clientId)
                .tab(TAB))
            .reads(ResourceReads.<ManagedClientKey>typed(SpamserviceClientKeysResource::keyOf)
                .load((key, access) -> load(clients, key))
                .values(SpamserviceClientKeysResource::values)
                .cells(SpamserviceClientKeysResource::cell)
                .build()
                .title(ManagedClientKey::name))
            // Outside a client the list asks the service nothing, as the unscoped list always did.
            .list(ResourceList.store(table, SpamserviceRemoteStore.nothing(FIELDS),
                    SpamserviceRemoteStore.pagesUnder(ID, clients, (client, parentKey, applied, access) -> {
                        UUID clientId = SpamserviceRemoteStore.uuidOrNull(String.valueOf(parentKey));
                        return clientId != null
                            ? client.keys(clientId.toString(), applied.page(), applied.schema().pageSize())
                            : new PageResult<>(List.of(), applied.page(), applied.schema().pageSize(), 0);
                    }))
                .chrome(ListChrome.MINIMAL)
                .notice(SpamserviceRemoteStore.notice(ID, clients))
                .build())
            .form(ResourceForm.<ManagedClientKey>of(form)
                .bindings(List.of(
                    ResourceFieldBinding.of("last_used", FieldAccess.alwaysReadonly()),
                    ResourceFieldBinding.of("created_at", FieldAccess.alwaysReadonly())))
                .createDefaults(request -> createDefaults(request))
                .build())
            .writes(ResourceMutations.<ManagedClientKey>store()
                // The form's own client pick keeps the standalone create the entry always offered.
                .create((values, access) -> mint(clients,
                    SpamserviceRemoteStore.requiredText(values, "client_id", ""), values, access))
                .createUnder((parentKey, values, access) -> mint(clients, String.valueOf(parentKey), values, access))
                .update((existing, values, access) -> update(clients, existing, values))
                .delete((existing, access) -> SpamserviceRemoteStore.require(clients).revokeKey(existing.id()))
                .build())
            .actions(List.of(
                PanelAction.<ManagedClientKey, Void>places(ENABLE, ActionPlacement.ROW,
                        (request, result) -> CmsActionResult.refreshWithToast(words("key_enabled")))
                    .label(words("enable"))
                    .icon(Icon.of("check"))
                    .hiddenWhen(ManagedClientKey::active)
                    .build(),
                PanelAction.<ManagedClientKey, Void>places(REVOKE, ActionPlacement.ROW,
                        (request, result) -> CmsActionResult.refreshWithToast(words("key_revoked")))
                    .label(words("revoke"))
                    .icon(Icon.of("xmark"))
                    .hiddenWhen(key -> !key.active())
                    .confirmation(ConfirmationSpec.builder()
                        .title(words("revoke"))
                        .body(words("revoke_confirm")).build())
                    .build()))
            .build();
    }

    /** The client the create link names (the tab's {@code parent}), preselected like the scoped create always did. */
    private static @NonNull Map<String, Object> createDefaults(@NonNull PanelRequest request) {
        return createDefaults(request.conduit().getParameter(CmsEndpoints.PARENT_PARAM));
    }

    /** @param parent the create link's parent key; a malformed one is no prefill, never an exception */
    static @NonNull Map<String, Object> createDefaults(@Nullable String parent) {
        Map<String, Object> values = new LinkedHashMap<>();
        UUID clientId = parent != null ? SpamserviceRemoteStore.uuidOrNull(parent) : null;
        if (clientId != null) {
            values.put("client_id", clientId);
        }
        values.put("active", true);
        return Map.copyOf(values);
    }

    /**
     * One key of one client, found by walking that client's key pages.
     *
     * AIDEV-NOTE: the management API has no single-key read, and this used to look at the FIRST page of 200 only --
     * so a client's 201st key listed fine and then 404ed on edit, enable and revoke. The walk is bounded by the total
     * the service reports.
     */
    private static @Nullable ManagedClientKey load(@NonNull Supplier<SpamserviceClient> clients, @NonNull String key) {
        int separator = key.indexOf('~');
        if (separator <= 0 || separator >= key.length() - 1) {
            return null;
        }
        UUID clientId = SpamserviceRemoteStore.uuidOrNull(key.substring(0, separator));
        UUID keyId = SpamserviceRemoteStore.uuidOrNull(key.substring(separator + 1));
        if (clientId == null || keyId == null) {
            return null;
        }
        SpamserviceClient client = SpamserviceRemoteStore.require(clients);
        String wanted = keyId.toString();
        for (int page = 1; ; page++) {
            PageResult<ManagedClientKey> keys = client.keys(clientId.toString(), page, REMOTE_PAGE_SIZE);
            for (ManagedClientKey item : keys.items()) {
                if (item.id().equals(wanted)) {
                    return item;
                }
            }
            if (keys.items().isEmpty() || (long) page * REMOTE_PAGE_SIZE >= keys.total()) {
                return null;
            }
        }
    }

    /** Creates the key under its client; a generated key is a one-shot disclosure through the request's flash. */
    private static @NonNull Object mint(@NonNull Supplier<SpamserviceClient> clients, @NonNull String clientId,
                                        @NonNull Map<String, Object> values, @NonNull AccessContext access) {
        String name = SpamserviceRemoteStore.requiredText(values, "name", "key");
        String raw = Texts.trimmedOrNull(values.get("key"));
        UUID parsed = SpamserviceRemoteStore.uuidOrNull(clientId);
        CreatedClientKey created = SpamserviceRemoteStore.require(clients)
            .createKey(parsed != null ? parsed.toString() : clientId, name, raw);
        Microcopy message = created.generated()
            ? words("key_created").withArg("key", created.key())
            : words("key_adopted");
        if (access.conduit() != null) {
            // AIDEV-NOTE: the generated key is a one-shot disclosure; the secret-arg variant parks it in
            // SecretDisclosures so only a single-use handle rides the session (an adopted key was operator-entered,
            // not disclosed).
            Flash.stash(access.conduit(), message, FlashLevel.SUCCESS,
                created.generated() ? Set.of("key") : Set.of());
        }
        return created.clientId() + "~" + created.id();
    }

    /**
     * Rename and enable/disable, each sent only when this write actually carries it.
     *
     * AIDEV-NOTE: the remote call already reads null as "leave alone" (enable and revoke rely on it), but the name was
     * passed through String.valueOf, so a write that did not carry the key -- the inline cell lane hands the update a
     * map holding EXACTLY ONE entry -- renamed the key to the literal text "null".
     */
    private static void update(@NonNull Supplier<SpamserviceClient> clients, @NonNull ManagedClientKey existing,
                               @NonNull Map<String, Object> values) {
        Object name = values.get("name");
        SpamserviceRemoteStore.require(clients).updateKey(existing.id(),
            values.containsKey("name") && name != null ? String.valueOf(name) : null,
            values.get("active") instanceof Boolean active ? active : null);
    }

    private static @NonNull String keyOf(@NonNull ManagedClientKey key) {
        return key.clientId() + "~" + key.id();
    }

    private static @NonNull Map<String, Object> values(@NonNull ManagedClientKey row) {
        return Map.of("client_id", UUID.fromString(row.clientId()), "name", row.name(), "key", "",
            "active", row.active(), "last_used", SpamserviceRemoteStore.orBlank(row.lastUsed()),
            "created_at", SpamserviceRemoteStore.orBlank(row.createdAt()));
    }

    private static @Nullable Object cell(@NonNull ManagedClientKey row, @NonNull ColumnSpec column) {
        return switch (column.name()) {
            case "name" -> row.name();
            case "active" -> row.active();
            case "last_used" -> row.lastUsed();
            case "created_at" -> row.createdAt();
            default -> null;
        };
    }

    private static @NonNull Microcopy words(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "spamservice_key");
    }
}
