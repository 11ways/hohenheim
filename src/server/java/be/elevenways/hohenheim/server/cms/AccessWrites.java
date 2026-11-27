package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.server.auth.AccessRuleNodes;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.history.ChangeOp;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.PatchBase;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Access lists, their rules, sign-in providers and bans as operations an assistant's typed arguments reach: a list
 * through its panel resource ({@link PanelRecordWrites}), a rule through the tree writer the Rules tab's add and switch
 * use, a provider through its panel resource with its kind's settings laid into its config, a ban through the writer
 * the Bans page's form and Lift button use.
 *
 * AIDEV-NOTE: a rule's and a provider's kind-shaped settings (a rule's data, a provider's config) are flat arguments
 * here, each the field its kinds' schemas declare (AccessRuleModel.DATA, SiteAuthProviderModel.CONFIG), so a new
 * field of a kind is a new argument without an edit here; one a record's kind does not declare is refused by name
 * (KindSettingsWrites.appliesOnly). The panel's own add, switch and lift stay its buttons, kept from assistants.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AccessWrites {

    /** The argument choosing a rule's or a provider's kind by its word. */
    public static final String KIND = "kind";

    /** The argument naming the group rule a new rule goes inside. */
    public static final String GROUP = "group";

    private static final String REMOVE = SitePartWrites.REMOVE;

    /** A create has no subject to key its command by: the request id alone answers the first result. */
    private static final OperationCommand CREATES = OperationCommand.perVerb().onDatasource("default");

    /** An access list: its name, how its rules combine and, for an administrator, whether everyone may pick it. */
    public static final PanelRecordWrites ACCESS_LISTS = PanelRecordWrites.of("access_list", AccessListModel.MODEL_ID,
            SubjectType.record(AccessListModel.REF), AccessListModel.ID, HohenheimSlugs.ACCESS_LISTS)
        .setting(AccessListModel.NAME.getInputName(), AccessListModel.NAME,
            HohenheimMicrocopy.HELP.of("access_list_name"))
        .setting(AccessListModel.SATISFY.getInputName(), AccessListModel.SATISFY,
            HohenheimMicrocopy.HELP.of("access_list_satisfy"))
        .settings(List.of(AccessListModel.SHARED))
        .build();

    /**
     * A rule's kind-shaped data, by the argument giving each field: a provider named by its code. A kind only Hohenheim
     * writes (a signed link's secrets) gives none: no caller sets its fields.
     */
    private static final Map<String, Field<?, ?>> RULE_DATA = arguments(AccessRuleModel.DATA,
        AccessRuleModel.writableDataSchemas(), AccessRuleModel.PROVIDER_ID);

    /** Each rule entry's stored path to the argument naming it, for the refusals of a rule's save. */
    private static final Map<String, String> RULE_NAMED = named(AccessRuleModel.DATA, RULE_DATA,
        AccessRuleModel.TYPE.getName());

    /** Adds a rule at the top of a list or inside a group, filled in and switched as given. */
    public static final Operation<Void, OperationInput.Values, Integer> CREATE_RULE = Operation.declare(
            HohenheimIds.id("create_access_rule"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("create_access_rule"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("create_access_rule_description"))
        .happened(ChangeOp.CREATE.happened())
        .noSubject()
        // The list (or the group's list) decides who may, as it does for the Rules tab's add.
        .gate(OperationGate.open())
        .input(OperationInput.derived(ruleForm(true)))
        .result(Integer.class)
        .createdRecord(AccessRuleModel.MODEL_ID, key -> key)
        .facts(OperationFact.IDEMPOTENT)
        .command(CREATES)
        .register();

    /** Changes what a rule means or whether it counts; a new kind replaces its data. */
    public static final Operation<Row, OperationInput.Values, String> CHANGE_RULE = Operation.declare(
            HohenheimIds.id("change_access_rule"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("change_access_rule"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("change_access_rule_description"))
        .happened(ChangeOp.UPDATE.happened())
        .one(SubjectType.record(AccessRuleModel.REF))
        .gate(OperationGate.open())
        .input(OperationInput.derived(ruleForm(false)))
        .result(String.class)
        .patchable()
        .facts(OperationFact.IDEMPOTENT)
        .command(CmsCommands.TRANSACTIONAL)
        .register();

    /** A provider's kind-shaped config, by the argument giving each field. */
    private static final Map<String, Field<?, ?>> PROVIDER_CONFIG = arguments(SiteAuthProviderModel.CONFIG,
        SiteAuthProviderModel.CONFIG.possibleSchemas(), null);

    /** Each provider entry's stored path to the argument naming it, for the refusals of its save. */
    private static final Map<String, String> PROVIDER_NAMED = named(SiteAuthProviderModel.CONFIG, PROVIDER_CONFIG,
        SiteAuthProviderModel.PROVIDER_TYPE.getName());

    /** Creates a sign-in provider of a kind through the providers page's own create. */
    public static final Operation<Void, OperationInput.Values, Integer> CREATE_PROVIDER = Operation.declare(
            HohenheimIds.id("create_sign_in_provider"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("create_sign_in_provider"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("create_sign_in_provider_description"))
        .happened(ChangeOp.CREATE.happened())
        .noSubject()
        // The providers page decides who may: it is the operator's alone.
        .gate(OperationGate.open())
        .input(OperationInput.derived(providerForm()))
        .result(Integer.class)
        .createdRecord(SiteAuthProviderModel.MODEL_ID, key -> key)
        .facts(OperationFact.IDEMPOTENT)
        .command(CREATES)
        .register();

    /** Changes what a call sends of a sign-in provider through the providers page's own save. */
    public static final Operation<Row, OperationInput.Values, String> CHANGE_PROVIDER = Operation.declare(
            HohenheimIds.id("change_sign_in_provider"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("change_sign_in_provider"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("change_sign_in_provider_description"))
        .happened(ChangeOp.UPDATE.happened())
        .one(SubjectType.record(SiteAuthProviderModel.REF))
        .gate(OperationGate.open())
        .input(OperationInput.derived(providerForm()))
        .result(String.class)
        .patchable()
        .facts(OperationFact.IDEMPOTENT)
        .command(CmsCommands.TRANSACTIONAL)
        .register();

    /** Bans an address from web traffic for a while or for good, or lifts every ban of it in force. */
    public static final Operation<Void, OperationInput.Values, Microcopy> SET_BAN = Operation.declare(
            HohenheimIds.id("set_ban"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("set_ban"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("set_ban_description"))
        .happened(OperationSentences.of("set_ban"))
        .noSubject()
        .gate(HohenheimSources.OPERATOR)
        .input(OperationInput.derived(FormSpec.builder()
            .add(Plain.of(StringField.builder(BanModel.IP.getName()).required()
                .help(HohenheimMicrocopy.HELP.of("ban_set_ip")).build()))
            .add(Plain.of(StringField.builder(BanModel.REASON.getName())
                .help(HohenheimMicrocopy.HELP.of("ban_set_reason")).build()))
            .add(PanelRecordWrites.wordEntry(BanParts.DURATION.getName(), BanParts.DURATION,
                HohenheimMicrocopy.HELP.of("ban_set_duration")))
            .add(Plain.of(BooleanField.builder(REMOVE).defaultValue(false)
                .help(HohenheimMicrocopy.HELP.of("ban_set_remove")).build()))
            .build()))
        .result(Microcopy.class)
        // The firewall is programmed after the ban's own transaction commits (BanService.banAgain), and a lift as the
        // Lift button's own command runs.
        .command(CREATES.execution(CommandExecution.OUTSIDE_TRANSACTION))
        .register();

    static {
        OperationHandlers.attach(CREATE_RULE).handle(call -> ruleCreated(Objects.requireNonNull(call.input()),
            Objects.requireNonNull(call.access(), "a create has its caller")));
        OperationHandlers.attach(CHANGE_RULE)
            .authorize((rule, input, access) -> AccessRuleParts.writeRefusal(rule, access))
            .patchBase(PatchBase.ofValues(CHANGE_RULE, (rule, access) -> storedRule(Objects.requireNonNull(rule))))
            .handle(call -> ruleChanged(call.subject(), Objects.requireNonNull(call.input()), call.patched()));
        OperationHandlers.attach(CREATE_PROVIDER).handle(call -> providerCreated(Objects.requireNonNull(
            call.input()), Objects.requireNonNull(call.access(), "a create has its caller")));
        OperationHandlers.attach(CHANGE_PROVIDER)
            .patchBase(PatchBase.ofValues(CHANGE_PROVIDER, (provider, access) -> storedProvider(
                Objects.requireNonNull(provider))))
            .handle(call -> providerChanged(call.subject(), Objects.requireNonNull(call.input()), call.patched(),
                Objects.requireNonNull(call.access(), "a change has its caller")));
        OperationHandlers.attach(SET_BAN).handle(call -> banSet(Objects.requireNonNull(call.input()),
            Objects.requireNonNull(call.access(), "a ban has its caller")));
    }

    private AccessWrites() {
    }

    /**
     * @param schemas  the kinds' schemas whose fields become arguments
     * @param provider the field naming a sign-in provider, given by its code under {@code sign_in_provider}; null for
     *                 none
     * @return every field the schemas declare inside the map, by the argument giving it, each once
     * @throws IllegalStateException when the map's kinds are not registered yet
     */
    private static @NonNull Map<String, Field<?, ?>> arguments(@NonNull SchemaField container,
                                                             @NonNull List<Schema> schemas,
                                                             @Nullable Field<?, ?> provider) {
        Map<String, Field<?, ?>> arguments = new LinkedHashMap<>();
        for (Schema schema : schemas) {
            for (Field<?, ?> field : schema.getFields().values()) {
                String argument = field == provider ? SitePartWrites.SIGN_IN_PROVIDER : field.getInputName();
                arguments.putIfAbsent(argument, field);
            }
        }
        if (arguments.isEmpty()) {
            throw new IllegalStateException(container.getName() + " has no kinds registered to take arguments of");
        }
        return arguments;
    }

    /** @return each field's stored path inside the map, and the kind's own, to the argument giving it */
    private static @NonNull Map<String, String> named(@NonNull SchemaField container,
                                                      @NonNull Map<String, Field<?, ?>> arguments,
                                                      @NonNull String kind) {
        Map<String, String> named = new LinkedHashMap<>();
        arguments.forEach((argument, field) -> named.put(container.getName() + "." + field.getName(), argument));
        named.put(kind, KIND);
        return named;
    }

    /** @return the entry of each kind-shaped argument: a provider by its code, people as the credentials list */
    private static @NonNull List<FormEntry> entries(@NonNull Map<String, Field<?, ?>> arguments) {
        List<FormEntry> entries = new ArrayList<>();
        arguments.forEach((argument, field) -> {
            if (argument.equals(SitePartWrites.SIGN_IN_PROVIDER)) {
                entries.add(OperationForms.recordId(argument, HohenheimMicrocopy.HELP.of("rule_sign_in_provider"),
                    SiteAuthProviderModel.MODEL_ID, false));
            } else if (field instanceof StringMapField) {
                entries.add(SitePartWrites.CREDENTIAL_LIST);
            } else {
                entries.add(KindSettingsWrites.entryOf(field));
            }
        });
        return entries;
    }

    /** @return the rule form: where a new rule lands, its kind, whether it counts and its kind's fields */
    private static @NonNull FormSpec ruleForm(boolean creating) {
        FormSpec.Builder form = FormSpec.builder();
        if (creating) {
            form.add(OperationForms.recordId(SiteKindWrites.ACCESS_LIST,
                HohenheimMicrocopy.HELP.of("access_rule_list"), AccessListModel.MODEL_ID, false));
            form.add(OperationForms.recordId(GROUP, HohenheimMicrocopy.HELP.of("access_rule_group"),
                AccessRuleModel.MODEL_ID, false));
        }
        form.add(PanelRecordWrites.wordEntry(KIND, AccessRuleModel.TYPE, null));
        form.add(Plain.of(AccessRuleModel.ENABLED));
        for (FormEntry entry : entries(RULE_DATA)) form.add(entry);
        return form.build();
    }

    /** @return the provider form: its name, kind, kind's settings and required permission */
    private static @NonNull FormSpec providerForm() {
        FormSpec.Builder form = FormSpec.builder()
            .add(Plain.of(SiteAuthProviderModel.NAME))
            .add(PanelRecordWrites.wordEntry(KIND, SiteAuthProviderModel.PROVIDER_TYPE, null));
        for (FormEntry entry : entries(PROVIDER_CONFIG)) form.add(entry);
        return form.add(KindSettingsWrites.entryOf(SiteAuthProviderModel.REQUIRED_PERMISSION)).build();
    }

    /** @return the arguments of {@code arguments} the call gave */
    private static @NonNull Set<String> given(OperationInput.@NonNull Values input,
                                              @NonNull Set<String> arguments) {
        Set<String> given = new LinkedHashSet<>();
        for (String argument : arguments) {
            if (input.get(argument) != null) given.add(argument);
        }
        return given;
    }

    /**
     * @return the map {@code base} with each sent argument laid in by its field's name, a provider as its key, people
     *     as a username to password map
     * @throws Violations naming an argument the kind's schema does not declare
     */
    private static @NonNull Map<String, Object> laid(@NonNull SchemaField container, @NonNull String kind,
                                                     @NonNull Map<String, Field<?, ?>> arguments,
                                                     OperationInput.@NonNull Values input, @NonNull Set<String> sent,
                                                     @NonNull Map<String, Object> base) {
        Map<String, Object> laid = new LinkedHashMap<>(base);
        for (String argument : sent) {
            Field<?, ?> field = Objects.requireNonNull(arguments.get(argument));
            Object value = input.get(argument);
            if (!KindSettingsWrites.takes(container, kind, field.getName())) {
                // A secret, or people with their passwords, is never echoed, whatever shape it arrived in.
                boolean secret = field.isSecret() || field instanceof StringMapField;
                throw KindSettingsWrites.appliesOnly(argument, secret ? null : value, container, field.getName());
            }
            if (value == null) {
                laid.remove(field.getName());
            } else if (field instanceof StringMapField) {
                Map<String, Object> people = new LinkedHashMap<>();
                for (Map<String, Object> person : SitePartWrites.people(value)) {
                    people.put(String.valueOf(person.get(SitePartWrites.USERNAME)),
                        Objects.toString(person.get(SitePartWrites.PASSWORD), ""));
                }
                laid.put(field.getName(), people);
            } else if (argument.equals(SitePartWrites.SIGN_IN_PROVIDER)) {
                laid.put(field.getName(), Integer.valueOf(String.valueOf(value)));
            } else {
                laid.put(field.getName(), SitePartWrites.plain(value));
            }
        }
        return laid;
    }

    /**
     * Adds the rule at the top of the list, or inside the group, as the Rules tab's add does.
     *
     * @throws Violations for neither or both of a list and a group, or a field the kind does not take
     * @throws DomainRefusal not found for a list the caller does not manage, or a group that is none
     */
    private static @NonNull Integer ruleCreated(OperationInput.@NonNull Values input, @NonNull AccessContext caller) {
        Object list = input.get(SiteKindWrites.ACCESS_LIST);
        Object group = input.get(GROUP);
        if ((list == null) == (group == null)) {
            throw Violations.ofField(SiteKindWrites.ACCESS_LIST, null,
                HohenheimMicrocopy.VIOLATIONS.of("access_rule_where"));
        }
        Integer listId;
        Integer parentId = null;
        if (list != null) {
            listId = Integer.valueOf(String.valueOf(list));
            DomainRefusal refused = AccessRuleParts.listRefusal(listId, caller);
            if (refused != null) throw refused;
        } else {
            Row parent = Models.get(AccessRuleModel.class).findById(Integer.valueOf(String.valueOf(group)));
            if (parent == null || !AccessRuleModel.TYPE_GROUP.equals(parent.get(AccessRuleModel.TYPE))
                    || AccessRuleParts.writeRefusal(parent, caller) != null) {
                throw new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "group " + group + " is not reachable");
            }
            listId = parent.get(AccessRuleModel.ACCESS_LIST_ID);
            parentId = parent.get(AccessRuleModel.ID);
        }
        String type = PanelRecordWrites.keyOfWord(AccessRuleModel.TYPE, String.valueOf(input.get(KIND)));
        Map<String, Object> data = laid(AccessRuleModel.DATA, type, RULE_DATA, input,
            given(input, RULE_DATA.keySet()), Map.of());
        boolean enabled = !Boolean.FALSE.equals(input.get(AccessRuleModel.ENABLED.getInputName()));
        int into = Objects.requireNonNull(listId);
        Integer under = parentId;
        return KindSettingsWrites.speaking(() -> AccessRuleNodes.add(into, under, type, data, enabled)
            .get(AccessRuleModel.ID), RULE_NAMED);
    }

    /**
     * Writes what the call sent over the rule: a new kind replaces its data with what was sent, the same kind keeps
     * what was not sent (a password left out stays as stored); the save runs the model's completeness and
     * protected-path checks, as the rule form's save and the switch do.
     *
     * @return the rule's code and the names of what was written
     */
    private static @NonNull String ruleChanged(@NonNull Row rule, OperationInput.@NonNull Values input,
                                               @Nullable Set<String> patched) {
        Set<String> sent = patched != null ? patched : input.coerced().keySet();
        String stored = rule.get(AccessRuleModel.TYPE);
        String type = sent.contains(KIND)
            ? PanelRecordWrites.keyOfWord(AccessRuleModel.TYPE, String.valueOf(input.get(KIND))) : stored;
        Map<String, Object> base = Objects.equals(type, stored) ? AccessRuleModel.dataOf(rule) : Map.of();
        Set<String> dataSent = new LinkedHashSet<>(sent);
        dataSent.retainAll(RULE_DATA.keySet());
        Map<String, Object> data = laid(AccessRuleModel.DATA, Objects.requireNonNull(type), RULE_DATA, input,
            dataSent, base);
        // Asked of the rule as it stands, before this write touches it.
        String kind = Objects.requireNonNull(type);
        KindSettingsWrites.speaking(() -> {
            AccessRuleNodes.refuseMadeByHohenheim(rule, kind, data);
            return null;
        }, RULE_NAMED);
        rule.set(AccessRuleModel.TYPE, type);
        rule.set(AccessRuleModel.DATA, AccessRuleNodes.withHashedPassword(data));
        String enabled = AccessRuleModel.ENABLED.getInputName();
        if (sent.contains(enabled)) rule.set(AccessRuleModel.ENABLED, Boolean.TRUE.equals(input.get(enabled)));
        KindSettingsWrites.speaking(() -> {
            Models.get(AccessRuleModel.class).save(rule);
            return null;
        }, RULE_NAMED);
        return KindSettingsWrites.changedLine(RecordCodes.codeUnjudged(AccessRuleModel.MODEL_ID,
            String.valueOf((Object) rule.get(AccessRuleModel.ID))), String.join(", ", sent));
    }

    /** @return the rule as its change's input stands: its kind, switch and data, never a password or a provider */
    private static @NonNull Map<String, Object> storedRule(@NonNull Row rule) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(KIND, PanelRecordWrites.wordOf(String.valueOf((Object) rule.get(AccessRuleModel.TYPE))));
        values.put(AccessRuleModel.ENABLED.getInputName(), rule.get(AccessRuleModel.ENABLED));
        Map<String, Object> data = AccessRuleModel.dataOf(rule);
        RULE_DATA.forEach((argument, field) -> {
            if (!field.isSecret() && !argument.equals(SitePartWrites.SIGN_IN_PROVIDER)) {
                values.put(argument, data.get(field.getName()));
            }
        });
        return values;
    }

    /** @return the kind's config as the providers page's form posts it */
    private static @NonNull Map<String, Object> config(@NonNull String kind, @NonNull Map<String, Object> config) {
        Schema schema = SiteAuthProviderModel.CONFIG.resolveSchemaForSiblingValue(kind);
        return KindSettingsWrites.posted(schema == null ? List.of() : schema.getFields().values(), config);
    }

    /** Creates the provider through the providers page's own create, its config shaped by its kind. */
    private static @NonNull Integer providerCreated(OperationInput.@NonNull Values input,
                                                    @NonNull AccessContext caller) {
        String kind = PanelRecordWrites.keyOfWord(SiteAuthProviderModel.PROVIDER_TYPE, String.valueOf(input.get(KIND)));
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put(SiteAuthProviderModel.NAME.getName(), input.get(SiteAuthProviderModel.NAME.getInputName()));
        stored.put(SiteAuthProviderModel.PROVIDER_TYPE.getName(), kind);
        stored.put(SiteAuthProviderModel.CONFIG.getName(), config(kind, laid(SiteAuthProviderModel.CONFIG, kind,
            PROVIDER_CONFIG, input, given(input, PROVIDER_CONFIG.keySet()), Map.of())));
        Object permission = input.get(SiteAuthProviderModel.REQUIRED_PERMISSION.getInputName());
        if (permission != null) stored.put(SiteAuthProviderModel.REQUIRED_PERMISSION.getName(), permission);
        Object key = KindSettingsWrites.speaking(() -> PanelRecordWrites.createThrough(HohenheimSlugs.AUTH_PROVIDERS,
            stored, caller), PROVIDER_NAMED);
        return Integer.valueOf(String.valueOf(key));
    }

    /**
     * Writes what the call sent over the provider through the providers page's own save: its config is posted whole,
     * a setting the call left out as stored, a secret one blank, which the save keeps as stored; a new kind starts
     * its config afresh.
     *
     * @return the provider's code and the names of what was written
     */
    private static @NonNull String providerChanged(@NonNull Row provider, OperationInput.@NonNull Values input,
                                                   @Nullable Set<String> patched, @NonNull AccessContext caller) {
        Set<String> sent = patched != null ? patched : input.coerced().keySet();
        String stored = provider.get(SiteAuthProviderModel.PROVIDER_TYPE);
        String kind = sent.contains(KIND) ? PanelRecordWrites.keyOfWord(SiteAuthProviderModel.PROVIDER_TYPE,
            String.valueOf(input.get(KIND))) : Objects.requireNonNull(stored);
        Map<String, Object> written = new LinkedHashMap<>();
        for (Field<?, ?> own : List.of(SiteAuthProviderModel.NAME, SiteAuthProviderModel.REQUIRED_PERMISSION)) {
            if (sent.contains(own.getInputName())) written.put(own.getName(), input.get(own.getInputName()));
        }
        written.put(SiteAuthProviderModel.PROVIDER_TYPE.getName(), kind);
        Set<String> configSent = new LinkedHashSet<>(sent);
        configSent.retainAll(PROVIDER_CONFIG.keySet());
        boolean turned = !kind.equals(stored);
        if (turned || !configSent.isEmpty()) {
            Map<String, Object> base = turned ? Map.of() : keptConfig(kind, provider);
            written.put(SiteAuthProviderModel.CONFIG.getName(), config(kind, laid(SiteAuthProviderModel.CONFIG, kind,
                PROVIDER_CONFIG, input, configSent, base)));
        }
        Object key = Objects.requireNonNull(provider.get(SiteAuthProviderModel.ID));
        KindSettingsWrites.speaking(() -> {
            PanelRecordWrites.updateThrough(HohenheimSlugs.AUTH_PROVIDERS, key, provider, written, caller);
            return null;
        }, PROVIDER_NAMED);
        return KindSettingsWrites.changedLine(RecordCodes.codeUnjudged(SiteAuthProviderModel.MODEL_ID,
            String.valueOf(key)), String.join(", ", sent));
    }

    /**
     * @return the stored config, each secret blank (a person's password too) so the page's save keeps it as stored,
     *     never sending a stored value back as one typed
     */
    private static @NonNull Map<String, Object> keptConfig(@NonNull String kind, @NonNull Row provider) {
        Map<String, Object> config = new LinkedHashMap<>(RawValues.map(
            provider.get(SiteAuthProviderModel.CONFIG)));
        Schema schema = SiteAuthProviderModel.CONFIG.resolveSchemaForSiblingValue(kind);
        if (schema == null) return config;
        for (Field<?, ?> field : schema.getFields().values()) {
            Object value = config.get(field.getName());
            if (!field.isSecret() || value == null) continue;
            if (value instanceof Map<?, ?> people) {
                Map<String, Object> blank = new LinkedHashMap<>();
                for (Object username : people.keySet()) blank.put(String.valueOf(username), "");
                config.put(field.getName(), blank);
            } else {
                config.put(field.getName(), "");
            }
        }
        return config;
    }

    /** @return the provider as its change's input stands: its name, kind and permission, never a secret */
    private static @NonNull Map<String, Object> storedProvider(@NonNull Row provider) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SiteAuthProviderModel.NAME.getInputName(), provider.get(SiteAuthProviderModel.NAME));
        values.put(KIND, PanelRecordWrites.wordOf(String.valueOf((Object) provider.get(
            SiteAuthProviderModel.PROVIDER_TYPE))));
        values.put(SiteAuthProviderModel.REQUIRED_PERMISSION.getInputName(),
            provider.get(SiteAuthProviderModel.REQUIRED_PERMISSION));
        return values;
    }

    /**
     * Bans the address anew as the Bans page's form bans it, its web bans in force lifted in the same step so its end
     * is the one asked and it is never unbanned between the two (BanService.banAgain), or lifts every ban of it in
     * force, web and SSH alike, as the Lift button does.
     *
     * @return when the ban ends, or what was lifted, in the reader's words
     * @throws Violations for an address no ban may block, or a reason given with remove
     */
    private static @NonNull Microcopy banSet(OperationInput.@NonNull Values input, @NonNull AccessContext caller) {
        String ip = Objects.toString(Texts.trimmedOrNull(input.get(BanModel.IP.getName())), "");
        String who = caller.principal().displayName();
        if (Boolean.TRUE.equals(input.get(REMOVE))) {
            Object reason = input.get(BanModel.REASON.getName());
            if (reason != null) {
                throw Violations.ofField(BanModel.REASON.getName(), reason,
                    HohenheimMicrocopy.VIOLATIONS.of("ban_set_remove_reason"));
            }
            List<Row> blocking = BanService.INSTANCE.blockingNow(ip);
            for (Row ban : blocking) BanService.INSTANCE.lift(ban, who);
            return HohenheimMicrocopy.HOHENHEIM.of(blocking.isEmpty() ? "ban_set_nothing" : "ban_set_lifted")
                .withArg("ip", ip);
        }
        // Refused before anything is written, so a refused ban leaves the address as it was.
        BanParts.requireBannable(ip);
        Row ban = BanParts.banAgain(ip, input.get(BanModel.REASON.getName()), input.get(BanParts.DURATION.getName()),
            who);
        Instant ends = ban.get(BanModel.EXPIRES_AT);
        String banned = Objects.toString(ban.get(BanModel.IP), ip);
        return ends == null ? HohenheimMicrocopy.HOHENHEIM.of("ban_set_for_good").withArg("ip", banned)
            : HohenheimMicrocopy.HOHENHEIM.of("ban_set_until").withArg("ip", banned).withArg("until", ends);
    }
}
