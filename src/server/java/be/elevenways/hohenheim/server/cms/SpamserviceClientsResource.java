package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
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
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Canonical Hohenheim CRUD surface for remote Spamservice clients, a store entry over the management API whose Keys
 * tab lists the client's keys.
 *
 * The list search is forwarded to the management API's own {@code q} search (its name match), never evaluated here.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SpamserviceClientsResource {

    static final Identifier ID = HohenheimIds.id("spamservice_client");
    static final SubjectType<ManagedClient> CLIENT = SubjectType.of(ID, ManagedClient.class, ManagedClient::id);

    private static final StringField NAME = StringField.builder("name").required()
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("name")).build();
    private static final BooleanField ENABLED = BooleanField.builder("enabled").defaultValue(true)
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("enabled")).build();
    private static final BooleanField TRUSTED = BooleanField.builder("trusted").defaultValue(false)
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("trusted")).build();
    private static final BooleanField PROVISIONER = BooleanField.builder("provisioner").defaultValue(false)
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("provisioner")).build();
    private static final BooleanField MANAGER = BooleanField.builder("manager").defaultValue(false)
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("manager")).build();
    private static final StringField EXTERNAL_ID = StringField.builder("external_id")
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("external_id")).build();
    private static final UuidField OWNER_ID = UuidField.builder("provisioned_by_client_id")
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("owner")).build();
    private static final StringField ALLOWED_LANGUAGES = StringField.builder("allowed_languages")
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("allowed_languages")).build();
    private static final IntegerField SPAM_THRESHOLD = IntegerField.builder("spam_threshold")
        .defaultValue(50).label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("spam_threshold")).build();
    private static final TextField NOTES = TextField.builder("notes")
        .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("notes")).build();

    /** The fields the management API answers for one client. */
    private static final List<Field<?, ?>> FIELDS = List.of(NAME, ENABLED, TRUSTED, PROVISIONER, MANAGER, EXTERNAL_ID,
        OWNER_ID, ALLOWED_LANGUAGES, SPAM_THRESHOLD, NOTES);

    private SpamserviceClientsResource() {
    }

    /** @return the entry over the managed runtime's client */
    public static @NonNull PanelResource<ManagedClient> create() {
        return create(SpamserviceRemoteStore.MANAGED);
    }

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
                .title(ManagedClient::name))
            .list(ResourceList.store(table, SpamserviceRemoteStore.pages(ID, clients, FIELDS, List.of("name"),
                    (client, applied, access) -> client.clients(applied.page(), applied.schema().pageSize(),
                        applied.searchTerm(), SpamserviceRemoteStore.booleanFilter(applied, "enabled"))))
                .search("name")
                .chrome(ListChrome.MINIMAL)
                .notice(SpamserviceRemoteStore.notice(ID, clients))
                .build())
            .form(ResourceForm.<ManagedClient>of(form)
                .bindings(List.of(
                    ResourceFieldBinding.of("external_id", FieldAccess.alwaysReadonly()),
                    ResourceFieldBinding.of("provisioned_by_client_id", FieldAccess.alwaysReadonly())))
                .build())
            .writes(ResourceMutations.<ManagedClient>store()
                .create((values, access) -> SpamserviceRemoteStore.require(clients).createClient(input(values, null))
                    .id())
                .update((existing, values, access) -> SpamserviceRemoteStore.require(clients)
                    .updateClient(existing.id(), input(values, existing), existing.revision()))
                .delete((existing, access) -> SpamserviceRemoteStore.require(clients).deleteClient(existing.id()))
                .build())
            .tabs(ResourceTabs.of(List.of(ChildList.<ManagedClient>of(HohenheimSlugs.SPAMSERVICE_KEYS)
                .label(HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("keys")))))
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

    private static @NonNull Map<String, Object> values(@NonNull ManagedClient row) {
        return Map.ofEntries(
            Map.entry("name", row.name()), Map.entry("enabled", row.enabled()),
            Map.entry("trusted", row.trusted()), Map.entry("provisioner", row.provisioner()),
            Map.entry("manager", row.manager()), Map.entry("external_id",
                Objects.requireNonNullElse(row.externalId(), "")),
            Map.entry("provisioned_by_client_id", SpamserviceRemoteStore.uuidOrBlank(row.provisionedByClientId())),
            Map.entry("allowed_languages", Objects.requireNonNullElse(row.allowedLanguages(), "")),
            Map.entry("spam_threshold", row.spamThreshold()),
            Map.entry("notes", Objects.requireNonNullElse(row.notes(), "")));
    }

    /**
     * The whole remote client, filled from the STORED record wherever this write carries no value for a field.
     *
     * AIDEV-NOTE: the remote update is a revision-guarded full-DTO PUT, and the inline cell lane hands the update a
     * map holding EXACTLY ONE entry. Building the DTO off that map alone would have DISABLED the client, dropped its
     * language whitelist and reset its threshold to 50 on any single edit -- a live filter reconfigured by a rename.
     * The stored record is the fallback here rather than a remote merge semantic, which the service's own API does
     * not promise.
     *
     * @param stored the client being edited, or null on a create
     */
    private static ManagedClientInput input(Map<String, Object> values, @Nullable ManagedClient stored) {
        return new ManagedClientInput(
            SpamserviceRemoteStore.requiredText(values, "name", stored == null ? "" : stored.name()),
            bool(values, "enabled", stored == null ? null : stored.enabled()),
            bool(values, "trusted", stored == null ? null : stored.trusted()),
            bool(values, "provisioner", stored == null ? null : stored.provisioner()),
            bool(values, "manager", stored == null ? null : stored.manager()),
            nullable(values, "allowed_languages", stored == null ? null : stored.allowedLanguages()),
            RawValues.intOr(values.getOrDefault("spam_threshold", stored == null ? null : stored.spamThreshold()), 50),
            nullable(values, "notes", stored == null ? null : stored.notes()));
    }

    private static boolean bool(Map<String, Object> values, String name, @Nullable Object stored) {
        return Boolean.TRUE.equals(values.getOrDefault(name, stored));
    }

    private static @Nullable String nullable(Map<String, Object> values, String name, @Nullable Object stored) {
        Object value = values.getOrDefault(name, stored);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
}
