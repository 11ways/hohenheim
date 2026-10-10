package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.key.IdentityKey;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.CreatedClientKey;
import be.elevenways.spamservice.client.ManagedClientKey;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
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
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SecretResult;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A client's keys, a store child of the client listed on its Keys tab; the create answers the raw key as its one-time
 * {@link SecretResult}.
 *
 * AIDEV-NOTE: the management API lists keys per client only, so the child lists under its client through
 * {@code ChildStorePages} and creates through {@code createUnder}; outside a client the list asks nothing.
 * Enable and revoke are operations over the same remote calls the legacy row actions made.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SpamserviceClientKeysResource {
    private static final OperationCommand COMMAND = OperationCommand.perSubject().onDatasource("default")
        .execution(CommandExecution.OUTSIDE_TRANSACTION);

    static final Identifier ID = HohenheimIds.id("spamservice_key");

    /** A key is addressed as {@code <client id>~<key id>}: the management API reads keys per client only. */
    static final SubjectType<ManagedClientKey> KEY = SubjectType.of(ID, ManagedClientKey.class,
        SpamserviceClientKeysResource::keyOf);

    private static final UuidField CLIENT_ID = UuidField.builder("client_id").required()
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("client")).build();
    private static final StringField NAME = StringField.builder("name").required()
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("name")).build();
    private static final StringField RAW_KEY = StringField.builder("key").visibleIn(EditView.CREATE)
        .secret()
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("raw_key"))
        .help(HohenheimMicrocopy.SPAMSERVICE_KEY.of("raw_key_help")).build();
    private static final BooleanField ACTIVE = BooleanField.builder("active").defaultValue(true)
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("active")).build();
    private static final DateTimeField LAST_USED = DateTimeField.builder("last_used")
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("last_used")).build();
    private static final DateTimeField CREATED_AT = DateTimeField.builder("created_at")
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("created_at")).build();

    /** The fields the management API answers for one key; the raw key is never among them. */
    private static final List<Field<?, ?>> FIELDS = List.of(CLIENT_ID, NAME, ACTIVE, LAST_USED, CREATED_AT);

    /** The largest page the management API serves ({@code ManagementService.MAX_PAGE_SIZE}). */
    private static final int REMOTE_PAGE_SIZE = 200;

    private static final FormSpec FORM = FormSpec.builder()
        .add(CLIENT_ID).add(NAME).add(RAW_KEY).add(ACTIVE).add(LAST_USED).add(CREATED_AT).build();

    /**
     * The client the create writes through, attached by a caller holding its own (a host test); a create the CMS runs
     * attaches none and writes through the managed runtime's.
     */
    static final IdentityKey<Supplier<SpamserviceClient>> CLIENTS = IdentityKey.create("spamservice_key_clients");

    /** The key form, one component per form entry as the operation boot check requires. */
    public record KeyInput(@Nullable Object client_id, @Nullable String name, @Nullable Object key,
                           @Nullable Boolean active, @Nullable Instant last_used, @Nullable Instant created_at) {}

    /**
     * Creates one key under its client and answers its key with the raw value, once.
     *
     * AIDEV-NOTE: a receipted command, so a resubmitted create after a lost answer is refused as
     * SECRET_ALREADY_DISCLOSED instead of minting a second key; offered from the client's Keys tab with the parent
     * fixed into {@code client_id}.
     */
    public static final Operation<Void, KeyInput, SecretResult<String>> CREATE = Operation.declare(
            HohenheimIds.id("spamservice_key_create"))
        .happened(OperationSentences.of("spamservice_key_create"))
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("create_key"))
        .noSubject()
        .gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .input(OperationInput.of(FORM, KeyInput.class, values -> new KeyInput(values.get("client_id"),
            values.get("name") instanceof String name ? name : null, values.get("key"),
            values.get("active") instanceof Boolean active ? active : null,
            values.get("last_used") instanceof Instant used ? used : null,
            values.get("created_at") instanceof Instant created ? created : null)))
        .result(SecretResult.<String>type())
        .command(OperationCommand.perSubject().onDatasource("default")
            .execution(CommandExecution.OUTSIDE_TRANSACTION))
        .register();

    public static final Operation<ManagedClientKey, Void, Void> ENABLE = Operation.declare(
            HohenheimIds.id("spamservice_key_enable"))
        .happened(OperationSentences.of("spamservice_key_enable"))
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("enable"))
        .icon(Icon.of("check"))
        .one(KEY)
        .gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .command(COMMAND)
        .register();

    public static final Operation<ManagedClientKey, Void, Void> REVOKE = Operation.declare(
            HohenheimIds.id("spamservice_key_revoke"))
        .happened(OperationSentences.of("spamservice_key_revoke"))
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("revoke"))
        .icon(Icon.of("xmark"))
        .one(KEY)
        .gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .command(COMMAND)
        .register();

    static {
        OperationHandlers.loader(KEY, key -> load(SpamserviceRemoteStore.MANAGED, key));
        OperationHandlers.attach(CREATE).handle(call -> {
            Supplier<SpamserviceClient> attached = call.attachment(CLIENTS);
            KeyInput input = Objects.requireNonNull(call.input(), "the key form is the input");
            return mint(attached != null ? attached : SpamserviceRemoteStore.MANAGED,
                input.client_id() == null ? "" : String.valueOf(input.client_id()), input.name(), input.key());
        });
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
        return PanelResource.builder(ID, HohenheimSlugs.SPAMSERVICE_KEYS, KEY)
            .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("plural"))
            .recordLabel(HohenheimMicrocopy.SPAMSERVICE_KEY.of("singular"))
            .navGroup(HohenheimPanel.SECURITY_GROUP)
            .navOrder(40)
            .showInNav(false)
            .icon(Icon.of("key"))
            .parent(ResourceParent.<ManagedClientKey>of(HohenheimSlugs.SPAMSERVICE_CLIENTS, ManagedClientKey::clientId)
                .tab(HohenheimSlugs.Tab.KEYS))
            .reads(ResourceReads.<ManagedClientKey>typed(SpamserviceClientKeysResource::keyOf)
                .load((key, access) -> load(clients, key))
                .values(SpamserviceClientKeysResource::values)
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
            .form(ResourceForm.<ManagedClientKey>of(FORM)
                .bindings(List.of(
                    ResourceFieldBinding.of("last_used", FieldAccess.alwaysReadonly()),
                    ResourceFieldBinding.of("created_at", FieldAccess.alwaysReadonly())))
                .createDefaults(request -> createDefaults(request))
                .build())
            .writes(ResourceMutations.<ManagedClientKey>store()
                // From the Keys tab the client is the tab's parent; the standalone create keeps the form's own pick.
                .create(CREATE, "client_id")
                .update((existing, values, access) -> update(clients, existing, values))
                .delete((existing, access) -> SpamserviceRemoteStore.require(clients).revokeKey(existing.id()))
                .build())
            .actions(List.of(
                PanelAction.<ManagedClientKey, Void>places(ENABLE, ActionPlacement.ROW,
                        (request, result) -> CmsActionResult.refreshWithToast(
                            HohenheimMicrocopy.SPAMSERVICE_KEY.of("key_enabled")))
                    .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("enable"))
                    .icon(Icon.of("check"))
                    .hiddenWhen(ManagedClientKey::active)
                    .build(),
                PanelAction.<ManagedClientKey, Void>places(REVOKE, ActionPlacement.ROW,
                        (request, result) -> CmsActionResult.refreshWithToast(
                            HohenheimMicrocopy.SPAMSERVICE_KEY.of("key_revoked")))
                    .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("revoke"))
                    .icon(Icon.of("xmark"))
                    .hiddenWhen(key -> !key.active())
                    .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.SPAMSERVICE_KEY.of("revoke"),
                        HohenheimMicrocopy.SPAMSERVICE_KEY.of("revoke_confirm"), ActionStyle.DEFAULT))
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

    /**
     * Creates the key under its client and answers it with its raw value: a generated key, or an adopted one shown back
     * once (the operator typed it, so that exposes nothing new and one result shape keeps one retry answer).
     */
    static @NonNull SecretResult<String> mint(@NonNull Supplier<SpamserviceClient> clients, @NonNull String clientId,
                                              @Nullable String name, @Nullable Object key) {
        String raw = key instanceof Secret secret ? Texts.trimmedOrNull(secret.reveal()) : Texts.trimmedOrNull(key);
        UUID parsed = SpamserviceRemoteStore.uuidOrNull(clientId);
        CreatedClientKey created = SpamserviceRemoteStore.require(clients).createKey(
            parsed != null ? parsed.toString() : clientId, name == null || name.isBlank() ? "key" : name, raw);
        return new SecretResult<>(created.clientId() + "~" + created.id(), Secret.of(created.key()));
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
            "active", row.active(), "last_used", Objects.requireNonNullElse(row.lastUsed(), ""),
            "created_at", Objects.requireNonNullElse(row.createdAt(), ""));
    }
}
