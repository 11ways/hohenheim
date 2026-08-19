package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.net.IpRanges;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * One node of an access list's rule TREE: either a nested any/all group or a leaf that
 * answers a single question about the request.
 *
 * AIDEV-NOTE: which fields a node carries FOLLOWS FROM ITS TYPE, declared once on the
 * {@link #TYPE} enum values, and lives in the {@link #DATA} column ({@code schemaFrom} --
 * the {@link DnsRecordModel} shape). There are deliberately NO flat username/network
 * columns: a type-specific field goes into that type's sub-schema, never into a column
 * every other type must carry. The access list itself is the implicit ROOT group, and its
 * {@code satisfy} column is that root's mode -- there is no root row.
 *
 * A leaf is created DISABLED and cannot be enabled until its data validates (see the
 * hook below): the tree is enforced per REQUEST, so a half-typed rule that counted as a
 * FAIL would lock live traffic out of the site between two clicks.
 */
public class AccessRuleModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "access_rule");
    public static final Schema SCHEMA = new Schema();

    /** A nested group of rules, combined by its own satisfy mode. */
    public static final String TYPE_GROUP = "group";

    /** HTTP basic credentials; passes when the request presents exactly this pair. */
    public static final String TYPE_BASIC_AUTH = "basic_auth";

    /** Passes when the client address is inside the network. */
    public static final String TYPE_IP_ALLOW = "ip_allow";

    /** Passes when the client address is OUTSIDE the network. */
    public static final String TYPE_IP_DENY = "ip_deny";

    /** Passes when the request is authenticated by the named site auth provider. */
    public static final String TYPE_AUTH_PROVIDER = "auth_provider";

    /** PermissionSuggestionSources key: the realm of the provider THIS rule points at. */
    public static final String RULE_PROVIDER_SUGGESTION_SOURCE = "hohenheim:access_rule_realm";

    // --- Per-type sub-schemas (the ONLY home for type-specific fields) ---

    public static final Schema GROUP_DATA_SCHEMA = new Schema();
    public static final EnumField GROUP_SATISFY = GROUP_DATA_SCHEMA.addField(EnumField.builder("satisfy")
        .value(AccessListModel.SATISFY_ANY, v -> v.displayName("Any")
            .label(Microcopy.of("any").withFilter("scope", "access_satisfy"))
            .icon("check").color("blue"))
        .value(AccessListModel.SATISFY_ALL, v -> v.displayName("All")
            .label(Microcopy.of("all").withFilter("scope", "access_satisfy"))
            .icon("list-check").color("orange"))
        .defaultValue(AccessListModel.SATISFY_ANY)
        .label(HohenheimFormCopy.label("satisfy"))
        .help(HohenheimFormCopy.help("rule_satisfy"))
        .build());

    public static final Schema BASIC_AUTH_DATA_SCHEMA = new Schema();
    public static final StringField BASIC_AUTH_USERNAME = BASIC_AUTH_DATA_SCHEMA.addField(
        StringField.builder().name("username")
            .label(HohenheimFormCopy.label("basic_auth_user"))
            .help(HohenheimFormCopy.help("basic_auth_user")).build());
    /** Typed in plaintext, STORED as an argon2 hash (the resource hashes on save). */
    public static final StringField BASIC_AUTH_PASSWORD = BASIC_AUTH_DATA_SCHEMA.addField(
        StringField.builder().name("password").secret()
            .label(HohenheimFormCopy.label("basic_auth_password"))
            .help(HohenheimFormCopy.help("basic_auth_password")).build());

    /**
     * Shared by both address leaves: allow and deny ask the same question of the same
     * value and differ only in which answer passes, so they share one sub-schema.
     */
    public static final Schema NETWORK_DATA_SCHEMA = new Schema();
    public static final StringField NETWORK = NETWORK_DATA_SCHEMA.addField(
        StringField.builder().name("network")
            .label(HohenheimFormCopy.label("rule_network"))
            .help(HohenheimFormCopy.help("rule_network")).build());

    public static final Schema AUTH_PROVIDER_DATA_SCHEMA = new Schema();
    public static final IntegerField PROVIDER_ID = AUTH_PROVIDER_DATA_SCHEMA.addField(
        IntegerField.builder().name("provider_id")
            .label(HohenheimFormCopy.label("rule_provider"))
            .help(HohenheimFormCopy.help("rule_provider")).build());
    /**
     * Narrows the identity this leaf accepts; blank = any identity the provider
     * authenticates. Suggestions come from the CHOSEN provider's realm.
     */
    public static final StringField PROVIDER_REQUIRED_PERMISSION = AUTH_PROVIDER_DATA_SCHEMA.addField(
        PermissionField.builder("required_permission")
            .suggestionSource(RULE_PROVIDER_SUGGESTION_SOURCE)
            .label(HohenheimFormCopy.label("required_permission"))
            .help(HohenheimFormCopy.help("required_permission")).build());
    public static final BelongsTo<SiteAuthProviderModel> PROVIDER = AUTH_PROVIDER_DATA_SCHEMA.addRelation(
        BelongsTo.to(SiteAuthProviderModel.class)
            .name("provider")
            .localKey(PROVIDER_ID)
            .remoteKey(SiteAuthProviderModel.ID)
            .build());

    // --- Columns ---

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField ACCESS_LIST_ID = SCHEMA.addField(
        IntegerField.builder().name("access_list_id")
            .label(HohenheimFormCopy.label("rule_access_list")).build());
    /** The enclosing group row, or null for a direct child of the list's implicit root. */
    public static final IntegerField PARENT_ID = SCHEMA.addField(
        IntegerField.builder().name("parent_id").filterable(false).build());
    public static final IntegerField SORT = SCHEMA.addField(
        IntegerField.builder().name("sort").defaultValue(0).build());
    public static final EnumField TYPE = SCHEMA.addField(EnumField.builder("type")
        .label(HohenheimFormCopy.label("rule_type")).help(HohenheimFormCopy.help("rule_type"))
        .value(TYPE_GROUP, v -> v.displayName("Group")
            .label(Microcopy.of("group").withFilter("scope", "access_rule_type"))
            .icon("layer-group").color("gray").schema(GROUP_DATA_SCHEMA))
        .value(TYPE_BASIC_AUTH, v -> v.displayName("Basic auth")
            .label(Microcopy.of("basic_auth").withFilter("scope", "access_rule_type"))
            .icon("lock").color("amber").schema(BASIC_AUTH_DATA_SCHEMA))
        .value(TYPE_IP_ALLOW, v -> v.displayName("Allowed network")
            .label(Microcopy.of("ip_allow").withFilter("scope", "access_rule_type"))
            .icon("check").color("green").schema(NETWORK_DATA_SCHEMA))
        .value(TYPE_IP_DENY, v -> v.displayName("Denied network")
            .label(Microcopy.of("ip_deny").withFilter("scope", "access_rule_type"))
            .icon("ban").color("red").schema(NETWORK_DATA_SCHEMA))
        .value(TYPE_AUTH_PROVIDER, v -> v.displayName("Auth provider")
            .label(Microcopy.of("auth_provider").withFilter("scope", "access_rule_type"))
            .icon("shield-halved").color("indigo").schema(AUTH_PROVIDER_DATA_SCHEMA))
        .build());

    /** Type-specific configuration, shaped by the sub-schema the rule's TYPE declares. */
    public static final SchemaField DATA = SCHEMA.addField(SchemaField.builder("data")
        .schemaFrom("type")
        .label(HohenheimFormCopy.label("rule_data")).build());

    /**
     * A DERIVED search index: the rule's own data values as one plain string.
     *
     * AIDEV-NOTE: "which access list holds 10.0.0.5" used to be answerable because the
     * addresses were a column on the list. They are per-type JSON now, which no backend
     * searches portably, so the hook below re-derives this on EVERY save -- one writer, no
     * drift, and never a label (the rendered summary is localized microcopy, this is data).
     */
    public static final StringField SEARCH_TEXT = SCHEMA.addField(
        StringField.builder().name("search_text")
            .label(HohenheimFormCopy.label("rule_search_text")).build());

    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled")
        .defaultValue(true)
        .label(HohenheimFormCopy.label("rule_enabled"))
        .help(HohenheimFormCopy.help("rule_enabled")).build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    public static final BelongsTo<AccessListModel> ACCESS_LIST = SCHEMA.addRelation(
        BelongsTo.to(AccessListModel.class)
            .name("access_list")
            .localKey(ACCESS_LIST_ID)
            .remoteKey(AccessListModel.ID)
            .build());

    /**
     * THE rule-type vocabulary, DERIVED from {@link #TYPE}'s declared values rather than
     * re-listed beside them: adding a type is ONE {@code .value(...)} edit.
     */
    public static final List<String> ALL_TYPES = List.copyOf(TYPE.getValues().keySet());

    static {
        // AIDEV-NOTE: enforcement lives on the model pipeline, never on a form spec, so
        // the row cannot hold a type outside the vocabulary however it was written (an
        // EnumField does NOT enforce membership on save). Data completeness is checked
        // only for an ENABLED rule: an unconfigured leaf is a DRAFT, and the request-time
        // gate skips disabled rules entirely.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            if (row.has(TYPE.getName())) {
                String type = row.get(TYPE);
                if (type == null || !TYPE.isValidValue(type)) {
                    throw Violations.ofField(TYPE.getName(), type,
                        Microcopy.of("access_rule_type_invalid").withFilter("scope", "violations"));
                }
            }
            if (Boolean.TRUE.equals(row.get(ENABLED))) {
                requireUsableData(row.get(TYPE), row.get(DATA));
            }
            row.set(SEARCH_TEXT, searchTextFor(row.get(TYPE), row.get(DATA)));
        });
    }

    /**
     * Refuse to ENABLE a rule whose data cannot answer a request.
     *
     * @throws Violations when the rule is enabled and its type-specific data is unusable
     */
    private static void requireUsableData(@Nullable String type, @Nullable Object data) {
        Map<?, ?> map = data instanceof Map<?, ?> values ? values : Map.of();
        switch (type == null ? "" : type) {
            case TYPE_IP_ALLOW, TYPE_IP_DENY -> {
                if (parseNetwork(text(map.get(NETWORK.getName()))) == null) {
                    throw Violations.ofField("data." + NETWORK.getName(),
                        map.get(NETWORK.getName()),
                        Microcopy.of("access_rule_network_invalid").withFilter("scope", "violations"));
                }
            }
            case TYPE_BASIC_AUTH -> {
                if (text(map.get(BASIC_AUTH_USERNAME.getName())) == null
                        || text(map.get(BASIC_AUTH_PASSWORD.getName())) == null) {
                    throw Violations.ofField("data." + BASIC_AUTH_USERNAME.getName(),
                        map.get(BASIC_AUTH_USERNAME.getName()),
                        Microcopy.of("access_rule_credential_incomplete").withFilter("scope", "violations"));
                }
            }
            case TYPE_AUTH_PROVIDER -> {
                if (map.get(PROVIDER_ID.getName()) == null) {
                    throw Violations.ofField("data." + PROVIDER_ID.getName(), null,
                        Microcopy.of("access_rule_provider_missing").withFilter("scope", "violations"));
                }
            }
            default -> {
                // A group carries nothing that can be incomplete: an empty group is inert.
            }
        }
    }

    /** The searchable data values of a rule: its network, its username, its provider id. */
    private static @NonNull String searchTextFor(@Nullable String type, @Nullable Object data) {
        Map<?, ?> map = data instanceof Map<?, ?> values ? values : Map.of();
        StringBuilder text = new StringBuilder(type == null ? "" : type);
        for (String key : List.of(NETWORK.getName(), BASIC_AUTH_USERNAME.getName(),
                PROVIDER_ID.getName(), PROVIDER_REQUIRED_PERMISSION.getName())) {
            String value = text(map.get(key));
            if (value != null) {
                text.append(' ').append(value);
            }
        }
        return text.toString();
    }

    /**
     * Parse an address rule into the range it names, reusing zenit's CIDR home
     * ({@link IpRanges}) rather than a second copy of the mask arithmetic.
     *
     * AIDEV-NOTE: literal addresses ONLY (IpRanges is DNS-free and stricter than
     * InetAddress). A hostname or a 3-part shorthand therefore parses to null, which
     * the model refuses at save time and the gate treats as a rule that matches
     * nothing -- never a name lookup on the request path.
     *
     * @return the range, or null when the text is not an address or CIDR block
     */
    public static IpRanges.@Nullable Range parseNetwork(@Nullable String rule) {
        if (rule == null || rule.isBlank()) {
            return null;
        }
        String value = rule.trim();
        int slash = value.indexOf('/');
        try {
            if (slash < 0) {
                byte[] address = IpRanges.parseLiteral(value);
                return address == null ? null : new IpRanges.Range(address, address.length * 8);
            }
            return IpRanges.Range.of(value.substring(0, slash),
                Integer.parseInt(value.substring(slash + 1).trim()));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** @return the trimmed text, or null when the value is absent or blank */
    public static @Nullable String text(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }

    /** The rule's type-specific data as a map (never null). */
    public static @NonNull Map<String, Object> dataOf(@NonNull Row row) {
        Object data = row.get(DATA);
        if (data instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }

    /** Every rule of one access list, in tree-agnostic sort order. */
    public List<Row> findForAccessList(int accessListId) {
        return find().where(ACCESS_LIST_ID.eq(accessListId))
            .orderBy(SORT, SortOrder.ASC)
            .orderBy(ID, SortOrder.ASC)
            .all();
    }

    /** The children of one node (null parent = the list's root children). */
    public List<Row> findChildren(int accessListId, @Nullable Integer parentId) {
        var query = find().where(ACCESS_LIST_ID.eq(accessListId));
        query = parentId == null ? query.and(PARENT_ID.isNull()) : query.and(PARENT_ID.eq(parentId));
        return query.orderBy(SORT, SortOrder.ASC).orderBy(ID, SortOrder.ASC).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "AccessRule"; }

    @Override
    public String getTableName() { return "access_rules"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
