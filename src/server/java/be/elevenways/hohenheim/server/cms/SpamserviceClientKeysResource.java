package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.dry.BlastDrySerializers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.CreatedClientKey;
import be.elevenways.spamservice.client.ManagedClient;
import be.elevenways.spamservice.client.ManagedClientKey;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.server.panel.ChildCreates;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationCopy;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SecretResult;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.PatchBase;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.TextBlockWidget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A client's keys, a store child of the client listed on its Keys tab; the panel's create answers the raw key as its
 * one-time {@link SecretResult}, a tool's create parks it for its caller on that tab instead.
 *
 * AIDEV-NOTE: the management API lists keys per client only, so the child lists under its client through
 * {@code ChildStorePages} and creates through {@code createUnder}; outside a client the list asks nothing. Enable and
 * revoke are the row's operations over the same remote calls the legacy row actions made; the form's change, which a
 * tool runs too, also turns a key on and off.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@BlastAutoLoad
public final class SpamserviceClientKeysResource {

    /** Read by the autoload index, which loads the class and so runs the DRY registration below at boot. */
    public static final Object LOADED = new Object();

    private static final OperationCommand COMMAND = OperationCommand.perSubject().onDatasource("default")
        .execution(CommandExecution.OUTSIDE_TRANSACTION);

    static final Identifier ID = HohenheimIds.id("spamservice_key");

    /**
     * A key is addressed in the panel as {@code <client id>~<key id>}, under its client; a tool names it by the code
     * the spam service gave it (SPK4), which the service resolves ({@link SpamserviceRemoteStore#found}).
     */
    static final SubjectType<ManagedClientKey> KEY = SubjectType.of(ID, ManagedClientKey.class,
        SpamserviceClientKeysResource::keyOf).namedByAnyCode();

    /** Why the row's enable and revoke are kept from assistants. */
    private static final String CHANGED_INSTEAD = "the key's change turns it on and off as well (enabled), and a"
        + " tool runs that one";

    /** The argument naming the spam client a tool's new key belongs to, by its code. */
    public static final String SPAM_CLIENT = "spam_client";

    private static final UuidField CLIENT_ID = UuidField.builder("client_id").required()
        .visibleIn(EditView.CREATE)
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("client")).build();
    private static final StringField NAME = StringField.builder("name").required()
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("name"))
        .help(HohenheimMicrocopy.HELP.of("spam_key_name")).build();
    private static final StringField RAW_KEY = StringField.builder("key").visibleIn(EditView.CREATE)
        .secret()
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("raw_key"))
        .help(HohenheimMicrocopy.SPAMSERVICE_KEY.of("raw_key_help")).build();
    private static final BooleanField ENABLED = BooleanField.builder("enabled").defaultValue(true)
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("active"))
        .help(HohenheimMicrocopy.HELP.of("spam_key_enabled")).build();
    private static final DateTimeField LAST_USED = DateTimeField.builder("last_used")
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("last_used")).build();
    private static final DateTimeField CREATED_AT = DateTimeField.builder("created_at")
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("created_at")).build();

    /** The spam client a tool's new key belongs to, by the code the spam service gave it. */
    private static final StringField OWNER = StringField.builder(SPAM_CLIENT).required()
        .help(HohenheimMicrocopy.HELP.of("spam_key_spam_client")).build();

    /** The fields the management API answers for one key; the raw key is never among them. */
    private static final List<Field<?, ?>> FIELDS = List.of(CLIENT_ID, NAME, ENABLED, LAST_USED, CREATED_AT);

    private static final FormSpec FORM = FormSpec.builder()
        .add(CLIENT_ID).add(NAME).add(RAW_KEY).add(ENABLED).add(LAST_USED).add(CREATED_AT).build();

    /** What a create writes; a key's use and age are the service's. */
    private static final FormSpec CREATED = FormSpec.builder().add(CLIENT_ID).add(NAME).add(RAW_KEY).add(ENABLED)
        .build();

    /** What a change writes: a rename and the key turned on or off. */
    private static final FormSpec CHANGED = FormSpec.builder().add(NAME).add(ENABLED).build();

    /** The key form's create, one component per entry as the operation boot check requires. */
    public record KeyInput(@Nullable Object client_id, @Nullable String name, @Nullable Object key,
                           @Nullable Boolean enabled) {}

    /**
     * A key a tool made: its code, its client's, and whether its generated key waits on that client's Keys tab (an
     * adopted key is the caller's already).
     */
    public record MadeKey(@NonNull String code, @NonNull String client, boolean waiting) {}

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
        .notForAssistants("it answers the new key once, and an assistant is never sent a secret")
        .gate(HohenheimSources.OPERATOR)
        .input(OperationInput.of(CREATED, KeyInput.class, values -> new KeyInput(values.get("client_id"),
            values.get("name") instanceof String name ? name : null, values.get("key"),
            values.get("enabled") instanceof Boolean enabled ? enabled : null)))
        .result(SecretResult.<String>type())
        .command(OperationCommand.perSubject().onDatasource("default")
            .execution(CommandExecution.OUTSIDE_TRANSACTION))
        .register();

    /**
     * A tool's create: the key made under the spam client its code names, a generated key never answered but parked
     * for the caller's account on that client's Keys tab ({@link TokenDisclosures}), where the panel's create shows it
     * in its toast. A resent request answers the first result, which holds no key.
     *
     * AIDEV-NOTE: the noun is the spam service's installation, as for a client (SpamserviceClientsResource.CREATE):
     * a key is a record of the service alone.
     */
    public static final Operation<Void, OperationInput.Values, MadeKey> MAKE = Operation.declare(
            HohenheimIds.id("make_spamservice_key"))
        .happened(OperationSentences.of("make_spamservice_key"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("make_spamservice_key"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("make_spamservice_key_description"))
        .noSubject()
        .noun(SpamserviceInstallationModel.REF)
        .gate(HohenheimSources.OPERATOR)
        .input(OperationInput.derived(FormSpec.builder().add(OWNER).add(NAME).add(RAW_KEY).add(ENABLED).build()))
        .result(MadeKey.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    /**
     * Renames a key and turns it on or off, as its form saves it; only what a call sends is sent to the service, which
     * reads a field left out as "leave alone".
     *
     * AIDEV-NOTE: never a stored value for what the call does not carry: the name once went through String.valueOf,
     * so a write without it (the inline cell lane hands the update a map holding EXACTLY ONE entry) renamed the key to
     * the literal text "null".
     */
    public static final Operation<ManagedClientKey, OperationInput.Values, String> CHANGE = Operation.declare(
            HohenheimIds.id("spamservice_key_change"))
        .happened(OperationSentences.of("spamservice_key_change"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("spamservice_key_change"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("spamservice_key_change_description"))
        .one(KEY)
        .gate(HohenheimSources.OPERATOR)
        .input(OperationInput.derived(CHANGED))
        .result(String.class)
        .patchable()
        .facts(OperationFact.IDEMPOTENT, OperationFact.REACHES_OUTSIDE)
        .command(COMMAND)
        .register();

    public static final Operation<ManagedClientKey, Void, Void> ENABLE = Operation.declare(
            HohenheimIds.id("spamservice_key_enable"))
        .happened(OperationSentences.of("spamservice_key_enable"))
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("enable"))
        .icon(Icon.of("check"))
        .one(KEY)
        .notForAssistants(CHANGED_INSTEAD)
        .gate(HohenheimSources.OPERATOR)
        .command(COMMAND)
        .register();

    public static final Operation<ManagedClientKey, Void, Void> REVOKE = Operation.declare(
            HohenheimIds.id("spamservice_key_revoke"))
        .happened(OperationSentences.of("spamservice_key_revoke"))
        .label(HohenheimMicrocopy.SPAMSERVICE_KEY.of("revoke"))
        .icon(Icon.of("xmark"))
        .one(KEY)
        .notForAssistants(CHANGED_INSTEAD)
        .gate(HohenheimSources.OPERATOR)
        .command(COMMAND)
        .register();

    static {
        // A tool's create is a command operation: its result is stored as the receipt a resent request answers.
        BlastDrySerializers.addCustomRegistration(registry -> {
            registry.registerSerializer(MadeKey.class, (made, context) -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("code", made.code());
                map.put("client", made.client());
                map.put("waiting", made.waiting());
                return map;
            });
            registry.registerReviver(MadeKey.class, (data, context) -> new MadeKey(data.getString("code"),
                data.getString("client"), Boolean.TRUE.equals(data.getBoolean("waiting"))));
        });
        OperationHandlers.loader(KEY, key -> load(SpamserviceRemoteStore.MANAGED, key));
        OperationHandlers.attach(CREATE).handle(call -> {
            KeyInput input = Objects.requireNonNull(call.input(), "the key form is the input");
            return mint(SpamserviceRemoteStore.clientOf(call),
                input.client_id() == null ? "" : String.valueOf(input.client_id()), input.name(), input.key(),
                !Boolean.FALSE.equals(input.enabled()));
        });
        OperationHandlers.attach(MAKE).handle(call -> make(SpamserviceRemoteStore.clientOf(call),
            Objects.requireNonNull(call.input(), "a key's client and name are the input"),
            Objects.requireNonNull(call.access(), "a key is made by its caller")));
        OperationHandlers.attach(CHANGE)
            .patchBase(PatchBase.ofValues(CHANGE, (stored, access) -> Map.of(NAME.getName(),
                Objects.requireNonNull(stored).name(), ENABLED.getName(), stored.active())))
            .handle(call -> {
                ManagedClientKey stored = call.subject();
                OperationInput.Values input = Objects.requireNonNull(call.input());
                Set<String> sent = SpamserviceRemoteStore.sent(call, input);
                Object name = sent.contains(NAME.getName()) ? input.get(NAME.getName()) : null;
                Object enabled = sent.contains(ENABLED.getName()) ? input.get(ENABLED.getName()) : null;
                SpamserviceClient client = SpamserviceRemoteStore.clientOf(call);
                SpamserviceRemoteStore.answered(() -> {
                    client.updateKey(stored.id(), name != null ? String.valueOf(name) : null,
                        enabled instanceof Boolean flag ? flag : null);
                    return stored;
                });
                return KindSettingsWrites.changedLine(stored.code(), String.join(", ", sent));
            });
        OperationHandlers.attach(ENABLE).handle(call -> {
            SpamserviceClient client = SpamserviceRemoteStore.clientOf(call);
            SpamserviceRemoteStore.answered(() -> {
                client.updateKey(call.subject().id(), null, true);
                return call.subject();
            });
            return null;
        });
        OperationHandlers.attach(REVOKE).handle(call -> {
            SpamserviceClient client = SpamserviceRemoteStore.clientOf(call);
            SpamserviceRemoteStore.answered(() -> {
                client.revokeKey(call.subject().id());
                return call.subject();
            });
            return null;
        });
    }

    private SpamserviceClientKeysResource() {
    }

    /** @return the entry over the managed runtime's client */
    public static @NonNull PanelResource<ManagedClientKey> create() {
        return create(SpamserviceRemoteStore.MANAGED);
    }

    /**
     * @param clients the client the entry reads and lists through; its create and change are {@link #CREATE} and
     *                {@link #CHANGE}, which write through the managed runtime's unless a call attaches another
     */
    static @NonNull PanelResource<ManagedClientKey> create(@NonNull Supplier<SpamserviceClient> clients) {
        SpamserviceRemoteStore.requireNonNull(clients);
        TableSpec<ManagedClientKey> table = TableSpec.<ManagedClientKey>builder()
            .column(ColumnSpec.fromField(NAME).build())
            .column(ColumnSpec.fromField(ENABLED).build())
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
                .title(key -> Microcopy.literalOrNull(key.name())))
            // Outside a client the list asks the service nothing, as the unscoped list always did.
            .list(ResourceList.store(table, SpamserviceRemoteStore.nothing(FIELDS),
                    SpamserviceRemoteStore.pagesUnder(ID, clients, (client, parentKey, applied, access) -> {
                        UUID clientId = SpamserviceRemoteStore.uuidOrNull(String.valueOf(parentKey));
                        return clientId != null
                            ? client.keys(clientId.toString(), applied.page(), applied.schema().pageSize())
                            : new PageResult<>(List.of(), applied.page(), applied.schema().pageSize(), 0);
                    }), HohenheimSources.SPAMSERVICE_SYSTEM_USER_SOURCE.liveFeed())
                .chrome(ListChrome.MINIMAL)
                .notice(SpamserviceRemoteStore.notice(ID, clients))
                .build())
            .form(ResourceForm.<ManagedClientKey>of(FORM)
                .bindings(List.of(
                    ResourceFieldBinding.of("last_used", FieldAccess.alwaysReadonly()),
                    ResourceFieldBinding.of("created_at", FieldAccess.alwaysReadonly())))
                .drawnOnly("last_used", "created_at")
                .createDefaults((access, panel) -> SpamserviceClientKeysResource.createDefaults(access))
                .build())
            .writes(ResourceMutations.<ManagedClientKey>store()
                // From the Keys tab the client is the tab's parent; the standalone create keeps the form's own pick.
                .create(CREATE, "client_id")
                // A key's answers carry no version (the service keeps its own), so the form reviews none of its own.
                .update(CHANGE, key -> 0)
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
    private static @NonNull Map<String, Object> createDefaults(@NonNull AccessContext access) {
        return createDefaults(ChildCreates.requestedParent(access.conduit()));
    }

    /** @param parent the create link's parent key; a malformed one is no prefill, never an exception */
    static @NonNull Map<String, Object> createDefaults(@Nullable String parent) {
        Map<String, Object> values = new LinkedHashMap<>();
        UUID clientId = parent != null ? SpamserviceRemoteStore.uuidOrNull(parent) : null;
        if (clientId != null) {
            values.put("client_id", clientId);
        }
        values.put("enabled", true);
        return Map.copyOf(values);
    }

    /**
     * The key a panel address ({@code <client id>~<key id>}) or a tool's code or id names, by one read of it.
     *
     * AIDEV-NOTE: a panel address names a key of that client only, so a key of another client reads as absent; a
     * malformed address asks the service nothing.
     */
    private static @Nullable ManagedClientKey load(@NonNull Supplier<SpamserviceClient> clients, @NonNull String key) {
        int separator = key.indexOf('~');
        if (separator < 0) {
            String named = key.trim();
            return named.isEmpty() ? null : SpamserviceRemoteStore.found(clients, client -> client.key(named));
        }
        UUID clientId = SpamserviceRemoteStore.uuidOrNull(key.substring(0, separator));
        UUID keyId = SpamserviceRemoteStore.uuidOrNull(key.substring(separator + 1));
        if (clientId == null || keyId == null) {
            return null;
        }
        ManagedClientKey found = SpamserviceRemoteStore.found(clients, client -> client.key(keyId.toString()));
        return found != null && clientId.toString().equals(found.clientId()) ? found : null;
    }

    /**
     * Creates the key under its client and answers it with its raw value: a generated key, or an adopted one shown back
     * once (the operator typed it, so that exposes nothing new and one result shape keeps one retry answer).
     */
    static @NonNull SecretResult<String> mint(@NonNull SpamserviceClient client, @NonNull String clientId,
                                              @Nullable String name, @Nullable Object key, boolean enabled) {
        UUID parsed = SpamserviceRemoteStore.uuidOrNull(clientId);
        CreatedClientKey created = created(client, parsed != null ? parsed.toString() : clientId, name, key);
        turnedOff(client, created, enabled);
        return new SecretResult<>(created.clientId() + "~" + created.id(), Secret.of(created.key()));
    }

    /**
     * A tool's key, made under the client its code names; a generated one parked for the caller.
     *
     * @throws DomainRefusal NOT_FOUND on {@link #SPAM_CLIENT} for a code naming no client
     * @throws Violations    for a generated key no account would be shown
     */
    private static @NonNull MadeKey make(@NonNull SpamserviceClient client, OperationInput.@NonNull Values input,
                                         @NonNull AccessContext caller) {
        String named = String.valueOf(input.get(SPAM_CLIENT)).trim();
        Object given = input.get(RAW_KEY.getName());
        if (raw(given) == null && !TokenDisclosures.accountOf(caller)) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("spam_key_no_account"));
        }
        ManagedClient owner = SpamserviceRemoteStore.found(() -> client, each -> each.client(named));
        if (owner == null) {
            throw new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "no spam client named " + named, SPAM_CLIENT,
                OperationCopy.COPY.of("record_id_unknown").withArg("id", named));
        }
        CreatedClientKey created = created(client, owner.id(), (String) input.get(NAME.getName()), given);
        // Parked first: a key whose turning off fails is still shown to the one who made it.
        if (created.generated()) {
            Objects.requireNonNull(TokenDisclosures.SPAM_KEYS.park(caller, owner.id(), created.code() + " "
                + created.name() + ": " + created.key()), "an account stands behind the caller");
        }
        turnedOff(client, created, !Boolean.FALSE.equals(input.get(ENABLED.getName())));
        return new MadeKey(created.code(), owner.code(), created.generated());
    }

    /** THE remote create of a key, the panel's and a tool's: a blank key is generated, a blank name is "key". */
    private static @NonNull CreatedClientKey created(@NonNull SpamserviceClient client, @NonNull String clientId,
                                                     @Nullable String name, @Nullable Object key) {
        return SpamserviceRemoteStore.answered(() -> client.createKey(clientId,
            name == null || name.isBlank() ? "key" : name, raw(key)));
    }

    /** Turns a key made off off at once, as the service makes every key on. */
    private static void turnedOff(@NonNull SpamserviceClient client, @NonNull CreatedClientKey created,
                                  boolean enabled) {
        if (enabled) return;
        SpamserviceRemoteStore.answered(() -> {
            client.updateKey(created.id(), null, false);
            return created;
        });
    }

    /** @return the key a create was given, trimmed; null for none, so the service generates one */
    private static @Nullable String raw(@Nullable Object key) {
        return key instanceof Secret secret ? Texts.trimmedOrNull(secret.reveal()) : Texts.trimmedOrNull(key);
    }

    /** @return the keys a tool made for the viewer's account under the client, once, as the Keys tab's band */
    static @Nullable WidgetTree waiting(@NonNull ManagedClient client, @NonNull AccessContext viewer) {
        String keys = claimWaiting(client.id(), viewer);
        return keys == null ? null : new WidgetTree(List.of(TextBlockWidget.of(
            HohenheimMicrocopy.SPAMSERVICE_KEY.of("keys_waiting").withArg("keys", keys))));
    }

    /**
     * The keys a tool made for the viewer's account under the client, claimed once, as its Keys tab shows them.
     *
     * @return one line per key, its code, name and key; null for none
     */
    public static @Nullable String claimWaiting(@NonNull String clientId, @NonNull AccessContext viewer) {
        return TokenDisclosures.SPAM_KEYS.claim(viewer, clientId);
    }

    private static @NonNull String keyOf(@NonNull ManagedClientKey key) {
        return key.clientId() + "~" + key.id();
    }

    static @NonNull Map<String, Object> values(@NonNull ManagedClientKey row) {
        return Map.of("client_id", UUID.fromString(row.clientId()), "name", row.name(), "key", "",
            "enabled", row.active(), "last_used", Objects.requireNonNullElse(row.lastUsed(), ""),
            "created_at", Objects.requireNonNullElse(row.createdAt(), ""));
    }
}
