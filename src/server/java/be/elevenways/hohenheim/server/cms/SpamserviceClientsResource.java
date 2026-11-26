package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.spamservice.client.ManagedClient;
import be.elevenways.spamservice.client.ManagedClientInput;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.PatchBase;
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
 * Canonical Hohenheim CRUD surface for remote Spamservice clients, a store entry over the management API whose Keys
 * tab lists the client's keys; its form's create and change are the operations a tool runs too.
 *
 * The list search is forwarded to the management API's own {@code q} search (its name match), never evaluated here.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SpamserviceClientsResource {

    static final Identifier ID = HohenheimIds.id("spamservice_client");

    /**
     * A client, keyed by its id in the panel; a tool names it by the code the spam service gave it (SPC12).
     *
     * AIDEV-NOTE: named by any code because the code is the service's to resolve (SpamserviceRemoteStore.found):
     * Hohenheim registers no code kind of its own for a record it does not store.
     */
    static final SubjectType<ManagedClient> CLIENT = SubjectType.of(ID, ManagedClient.class, ManagedClient::id)
        .namedByAnyCode();

    private static final StringField NAME = StringField.builder("name").required()
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("name"))
        .help(HohenheimMicrocopy.HELP.of("spam_client_name")).build();
    private static final BooleanField ENABLED = BooleanField.builder("enabled").defaultValue(true)
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("enabled"))
        .help(HohenheimMicrocopy.HELP.of("spam_client_enabled")).build();
    private static final BooleanField TRUSTED = BooleanField.builder("trusted").defaultValue(false)
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("trusted"))
        .help(HohenheimMicrocopy.HELP.of("spam_client_trusted")).build();
    private static final BooleanField PROVISIONER = BooleanField.builder("provisioner").defaultValue(false)
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("provisioner"))
        .help(HohenheimMicrocopy.HELP.of("spam_client_provisioner")).build();
    private static final BooleanField MANAGER = BooleanField.builder("manager").defaultValue(false)
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("manager"))
        .help(HohenheimMicrocopy.HELP.of("spam_client_manager")).build();
    private static final StringField EXTERNAL_ID = StringField.builder("external_id")
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("external_id")).build();
    private static final UuidField OWNER_ID = UuidField.builder("provisioned_by_client_id")
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("owner")).build();
    private static final StringField ALLOWED_LANGUAGES = StringField.builder("allowed_languages")
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("allowed_languages"))
        .help(HohenheimMicrocopy.HELP.of("spam_client_allowed_languages")).build();
    private static final IntegerField SPAM_THRESHOLD = IntegerField.builder("spam_threshold")
        .defaultValue(50).label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("spam_threshold"))
        .help(HohenheimMicrocopy.HELP.of("spam_client_spam_threshold")).build();
    private static final TextField NOTES = TextField.builder("notes")
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("notes"))
        .help(HohenheimMicrocopy.HELP.of("spam_client_notes")).build();

    /** The fields the management API answers for one client. */
    private static final List<Field<?, ?>> FIELDS = List.of(NAME, ENABLED, TRUSTED, PROVISIONER, MANAGER, EXTERNAL_ID,
        OWNER_ID, ALLOWED_LANGUAGES, SPAM_THRESHOLD, NOTES);

    /** What a create or a change writes, in the form's order; the external id and owner are the provisioner's. */
    private static final FormSpec WRITTEN = FormSpec.builder()
        .add(NAME).add(ENABLED).add(TRUSTED).add(PROVISIONER).add(MANAGER)
        .add(ALLOWED_LANGUAGES).add(SPAM_THRESHOLD).add(NOTES).build();

    /**
     * Creates a client at the spam service, as the clients list's form does, and answers its id.
     *
     * AIDEV-NOTE: the noun is the spam service's installation, the nearest model Hohenheim has: a client is a record of
     * the service alone, and an operation's noun names a local model.
     */
    public static final Operation<Void, OperationInput.Values, String> CREATE = Operation.declare(
            HohenheimIds.id("spamservice_client_create"))
        .happened(OperationSentences.of("spamservice_client_create"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("spamservice_client_create"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("spamservice_client_create_description"))
        .noSubject()
        .noun(SpamserviceInstallationModel.REF)
        .gate(HohenheimSources.OPERATOR)
        .input(OperationInput.derived(WRITTEN))
        .result(String.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    /**
     * Changes a client as its form does, answering its code and what was written; a patch, so a setting a call leaves
     * out stays as the service holds it.
     *
     * AIDEV-NOTE: the remote update is a revision-guarded full-DTO PUT, and the inline cell lane hands the update a
     * map holding EXACTLY ONE entry: the patch base is the stored client, never an empty map, which once DISABLED the
     * client, dropped its language whitelist and reset its threshold to 50 on any single edit -- a live filter
     * reconfigured by a rename.
     */
    public static final Operation<ManagedClient, OperationInput.Values, String> CHANGE = Operation.declare(
            HohenheimIds.id("spamservice_client_change"))
        .happened(OperationSentences.of("spamservice_client_change"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("spamservice_client_change"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("spamservice_client_change_description"))
        .one(CLIENT)
        .gate(HohenheimSources.OPERATOR)
        .input(OperationInput.derived(WRITTEN))
        .result(String.class)
        .patchable()
        .facts(OperationFact.IDEMPOTENT, OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    static {
        OperationHandlers.loader(CLIENT, key -> SpamserviceRemoteStore.found(SpamserviceRemoteStore.MANAGED,
            client -> client.client(key)));
        OperationHandlers.attach(CREATE).handle(call -> {
            ManagedClientInput input = input(Objects.requireNonNull(call.input()));
            SpamserviceClient client = SpamserviceRemoteStore.clientOf(call);
            return SpamserviceRemoteStore.answered(() -> client.createClient(input).id());
        });
        OperationHandlers.attach(CHANGE)
            .patchBase(PatchBase.ofValues(CHANGE, (stored, access) -> written(Objects.requireNonNull(stored))))
            .handle(call -> {
                ManagedClient stored = call.subject();
                OperationInput.Values merged = Objects.requireNonNull(call.input());
                SpamserviceClient client = SpamserviceRemoteStore.clientOf(call);
                SpamserviceRemoteStore.answered(() -> client.updateClient(stored.id(), input(merged),
                    stored.revision()));
                Set<String> sent = SpamserviceRemoteStore.sent(call, merged);
                return KindSettingsWrites.changedLine(stored.code(), String.join(", ", sent));
            });
    }

    private SpamserviceClientsResource() {
    }

    /** @return the entry over the managed runtime's client */
    public static @NonNull PanelResource<ManagedClient> create() {
        return create(SpamserviceRemoteStore.MANAGED);
    }

    /**
     * @param clients the client the entry reads and lists through; its create and change are {@link #CREATE} and
     *                {@link #CHANGE}, which write through the managed runtime's unless a call attaches another
     */
    static @NonNull PanelResource<ManagedClient> create(@NonNull Supplier<SpamserviceClient> clients) {
        SpamserviceRemoteStore.requireNonNull(clients);
        TableSpec<ManagedClient> table = TableSpec.<ManagedClient>builder()
            .column(ColumnSpec.fromField(NAME).build())
            .column(ColumnSpec.fromField(ENABLED).filterable().build())
            .column(ColumnSpec.fromField(TRUSTED).build())
            .column(ColumnSpec.fromField(PROVISIONER).build())
            .column(ColumnSpec.fromField(MANAGER).build())
            .column(ColumnSpec.fromField(EXTERNAL_ID).build())
            .column(ColumnSpec.fromField(SPAM_THRESHOLD).build())
            .filter(FilterSpec.leaf(ENABLED, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE).build())
            .build();
        FormSpec form = FormSpec.builder()
            .add(NAME).add(ENABLED).add(TRUSTED).add(PROVISIONER).add(MANAGER)
            .add(EXTERNAL_ID).add(OWNER_ID).add(ALLOWED_LANGUAGES).add(SPAM_THRESHOLD).add(NOTES).build();
        return PanelResource.builder(ID, HohenheimSlugs.SPAMSERVICE_CLIENTS, CLIENT)
            .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("plural"))
            .recordLabel(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("singular"))
            .navGroup(HohenheimPanel.SECURITY_GROUP)
            .navOrder(30)
            .showInNav(false)
            .standsUnder(HohenheimSlugs.SPAMSERVICE)
            .icon(Icon.of("users"))
            .reads(ResourceReads.<ManagedClient>typed(ManagedClient::id)
                .load((key, access) -> {
                    UUID id = SpamserviceRemoteStore.uuidOrNull(key);
                    return id == null ? null : SpamserviceRemoteStore.require(clients).client(id.toString());
                })
                .values(SpamserviceClientsResource::values)
                .build()
                .title(client -> Microcopy.literalOrNull(client.name())))
            .list(ResourceList.store(table, SpamserviceRemoteStore.pages(ID, clients, FIELDS, List.of("name"),
                    (client, applied, access) -> client.clients(applied.page(), applied.schema().pageSize(),
                        applied.searchTerm(), SpamserviceRemoteStore.booleanFilter(applied, "enabled"))),
                    HohenheimSources.SPAMSERVICE_SYSTEM_USER_SOURCE.liveFeed())
                .search("name")
                .chrome(ListChrome.MINIMAL)
                .notice(SpamserviceRemoteStore.notice(ID, clients))
                .build())
            .form(ResourceForm.<ManagedClient>of(form)
                .bindings(List.of(
                    ResourceFieldBinding.of("external_id", FieldAccess.alwaysReadonly()),
                    ResourceFieldBinding.of("provisioned_by_client_id", FieldAccess.alwaysReadonly())))
                .drawnOnly("external_id", "provisioned_by_client_id")
                .build())
            .writes(ResourceMutations.<ManagedClient>store()
                .create(CREATE)
                .update(CHANGE, SpamserviceClientsResource::version)
                .delete((existing, access) -> SpamserviceRemoteStore.require(clients).deleteClient(existing.id()))
                .build())
            .tabs(ResourceTabs.of(List.of(ChildList.<ManagedClient>of(SpamserviceClientKeysResource.create(clients))
                .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("keys"))
                // A key a tool made for this viewer's account is shown here once, as the panel's create shows its own.
                .widgets(SpamserviceClientKeysResource::waiting))))
            .actions(List.of(PanelAction.<ManagedClient>link(HohenheimIds.id("spamservice_client_keys"),
                    ActionPlacement.ROW)
                .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("keys"))
                .description(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("keys_hint"))
                .icon(Icon.of("key"))
                .route((row, request) -> keysTarget(row))
                .build()))
            .build();
    }

    /** @return the client's Keys tab, the target of its row link */
    static @NonNull RouteTarget keysTarget(@NonNull ManagedClient row) {
        return CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.SPAMSERVICE_CLIENTS, row.id(),
            HohenheimSlugs.Tab.KEYS);
    }

    /**
     * @return the code of the client the service holds under this id; null when it holds none or does not answer
     *     now, so a create that was written is never answered as refused
     */
    public static @Nullable String codeOf(@NonNull String id) {
        try {
            ManagedClient client = SpamserviceRemoteStore.found(SpamserviceRemoteStore.MANAGED,
                each -> each.client(id));
            return client == null ? null : client.code();
        } catch (DomainRefusal | Violations unanswered) {
            return null;
        }
    }

    /** @return the version the panel's form reviews: when the service last wrote the client, 0 for never */
    private static long version(@NonNull ManagedClient client) {
        return client.updatedAt() == null ? 0 : client.updatedAt().toEpochMilli();
    }

    private static @NonNull Map<String, Object> values(@NonNull ManagedClient row) {
        Map<String, Object> values = new LinkedHashMap<>(written(row));
        values.put("external_id", Objects.requireNonNullElse(row.externalId(), ""));
        values.put("provisioned_by_client_id", SpamserviceRemoteStore.uuidOrBlank(row.provisionedByClientId()));
        return values;
    }

    /** @return the client's settings a create or a change writes, as its form shows them */
    private static @NonNull Map<String, Object> written(@NonNull ManagedClient row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", Objects.requireNonNullElse(row.name(), ""));
        values.put("enabled", row.enabled());
        values.put("trusted", row.trusted());
        values.put("provisioner", row.provisioner());
        values.put("manager", row.manager());
        values.put("allowed_languages", Objects.requireNonNullElse(row.allowedLanguages(), ""));
        values.put("spam_threshold", row.spamThreshold());
        values.put("notes", Objects.requireNonNullElse(row.notes(), ""));
        return values;
    }

    /** The whole remote client, built from a create's values or a change's merged ones. */
    private static ManagedClientInput input(OperationInput.@NonNull Values values) {
        return new ManagedClientInput(
            SpamserviceRemoteStore.requiredText(values, NAME.getName()),
            bool(values, ENABLED), bool(values, TRUSTED), bool(values, PROVISIONER), bool(values, MANAGER),
            nullable(values, ALLOWED_LANGUAGES),
            RawValues.intOr(values.get(SPAM_THRESHOLD.getName()), 50),
            nullable(values, NOTES));
    }

    /** @return the flag as sent, its field's default when a create leaves it out */
    private static boolean bool(OperationInput.@NonNull Values values, @NonNull BooleanField field) {
        Object value = values.get(field.getName());
        return Boolean.TRUE.equals(value != null ? value : field.getDefaultValue());
    }

    private static @Nullable String nullable(OperationInput.@NonNull Values values, @NonNull Field<?, ?> field) {
        Object value = values.get(field.getName());
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
}
