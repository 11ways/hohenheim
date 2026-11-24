package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.StaticSiteKind;
import be.elevenways.protoblast.common.dry.MapEntryFields;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.PatchBase;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An app of one kind created or changed by its settings as an assistant names them: each kind's create and change
 * are operations whose input is the kind's own settings declaration, run through the instance panel resource's own
 * create and update, so its placement, quota, image policy and resize hooks decide as they do for the panel's form.
 *
 * AIDEV-NOTE: the settings entries are the kind's SETTINGS_SCHEMA fields, never a copy: a setting a kind declares is
 * an argument of its tool, or the build fails nowhere and the tool simply has it. The variables map is the one field
 * left out, as the app's variables are written by set_instance_variables (its rows win over the map at a deploy).
 * A change sends only what it changes (a patch): the stored settings map takes the sent settings over it, as the
 * form's save would otherwise rewrite the whole map.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AppKindWrites {

    /** The argument naming the host of a new app. */
    public static final String HOST = "host";

    /** The argument naming the project of a new app. */
    public static final String PROJECT = "project";

    /** The argument naming the backup target an app's backups go to. */
    public static final String BACKUP_TARGET = "backup_target";

    /** The argument turning an app of another kind into this kind. */
    public static final String CHANGE_KIND = "change_kind";

    /** The settings an app of any kind takes besides its kind's own: its name and its crash policy. */
    private static final List<Field<?, ?>> COMMON = List.of(InstanceModel.NAME, InstanceModel.CRASH_POLICY);

    /**
     * Creates and changes run as one command of their caller, so the same request id answers the first result.
     *
     * AIDEV-NOTE: outside a transaction, as every instance command: the panel write inside opens its own and joins
     * this command (PartsWrites.asOneCommand), and a placement on a host is never rolled back by a later refusal. A
     * create has no subject to resolve its command's datasource from, so it names the instances' own.
     */
    private static final OperationCommand CREATES = OperationCommand.perVerb(InstanceOperations.KEYS)
        .onDatasource(() -> Models.get(InstanceModel.class).getResolvedDatasource())
        .execution(CommandExecution.OUTSIDE_TRANSACTION);
    private static final OperationCommand CHANGES = OperationCommand.perSubject(InstanceOperations.KEYS)
        .execution(CommandExecution.OUTSIDE_TRANSACTION);

    /**
     * One kind's create and change.
     *
     * @param kind   the kind's id, as an app stores it
     * @param schema the kind's settings declaration
     * @param thing  what a create makes, as a refusal names it
     */
    public record KindWrites(@NonNull Identifier kind, @NonNull Schema schema, @NonNull Microcopy thing,
                             @NonNull Operation<Void, OperationInput.Values, Integer> create,
                             @NonNull Operation<Row, OperationInput.Values, String> change) {

        /** @return whether the app is of this kind */
        public boolean holds(@NonNull Row app) {
            return this.kind.toString().equals(String.valueOf(app.get(InstanceModel.KIND)));
        }
    }

    public static final KindWrites DOCKER_CONTAINER = declare(DockerContainerKind.ID,
        DockerContainerKind.SETTINGS_SCHEMA);
    public static final KindWrites APPLICATION = declare(ApplicationKind.ID, ApplicationKind.SETTINGS_SCHEMA);
    public static final KindWrites STATIC_SITE = declare(StaticSiteKind.ID, StaticSiteKind.SETTINGS_SCHEMA);

    private AppKindWrites() {
    }

    /** @return the kind's settings an assistant gives by name: all but the variables map */
    private static @NonNull List<Field<?, ?>> settings(@NonNull Schema schema) {
        List<Field<?, ?>> settings = new ArrayList<>();
        for (Field<?, ?> field : schema.getFields().values()) {
            if (!InstanceKindFields.ENVIRONMENT_VARIABLES.equals(field.getName())) settings.add(field);
        }
        return settings;
    }

    private static @NonNull KindWrites declare(@NonNull Identifier kind, @NonNull Schema schema) {
        String word = kind.getPath();
        FormSpec.Builder created = FormSpec.builder();
        FormSpec.Builder changed = FormSpec.builder();
        for (Field<?, ?> field : COMMON) {
            created.add(field);
            changed.add(field);
        }
        created.add(OperationForms.recordId(HOST, HohenheimMicrocopy.HELP.of("app_create_host"), HostModel.MODEL_ID,
            false));
        created.add(OperationForms.recordId(PROJECT, HohenheimMicrocopy.HELP.of("app_create_project"),
            ProjectModel.MODEL_ID, false));
        created.add(backupTarget());
        changed.add(backupTarget());
        changed.add(Plain.of(BooleanField.builder(CHANGE_KIND).defaultValue(false)
            .help(HohenheimMicrocopy.HELP.of("app_change_kind")).build()));
        for (Field<?, ?> field : settings(schema)) {
            created.add(entryOf(field));
            changed.add(entryOf(field));
        }
        Operation<Void, OperationInput.Values, Integer> create = Operation.declare(
                HohenheimIds.id("create_" + word + "_app"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("create_" + word + "_app"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("create_" + word + "_app_description"))
            .noSubject()
            // Any account reaches the create: the handler refuses one that is no administrator in its own words.
            .gate(OperationGate.open())
            .input(OperationInput.derived(created.build()))
            .result(Integer.class)
            .createdRecord(InstanceModel.MODEL_ID, id -> id)
            .facts(OperationFact.IDEMPOTENT)
            .command(CREATES)
            .register();
        Operation<Row, OperationInput.Values, String> change = Operation.declare(
                HohenheimIds.id("change_" + word + "_app"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("change_" + word + "_app"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("change_" + word + "_app_description"))
            .one(InstanceOperations.INSTANCE)
            .gate(InstanceOperations.gate(HohenheimCapabilities.CONFIG))
            .input(OperationInput.derived(changed.build()))
            .result(String.class)
            .patchable()
            .facts(OperationFact.IDEMPOTENT)
            .command(CHANGES)
            .register();
        KindWrites writes = new KindWrites(kind, schema, HohenheimMicrocopy.HOHENHEIM.of("app_thing_" + word),
            create, change);
        OperationHandlers.attach(create).handle(call -> created(writes, Objects.requireNonNull(call.input()),
            call.access()));
        // An app of another kind is a subject too: its change refuses it in words naming its kind's tool, unless the
        // call turns it into this kind (change_kind).
        OperationHandlers.attach(change)
            .patchBase(PatchBase.ofValues(change, (app, access) -> stored(writes, Objects.requireNonNull(app))))
            .handle(call -> changed(writes, call.subject(), Objects.requireNonNull(call.input()), call.patched(),
                call.access()));
        return writes;
    }

    /** @return the entry naming the backup target an app's backups go to, by its code */
    private static @NonNull FormEntry backupTarget() {
        return OperationForms.recordId(BACKUP_TARGET, HohenheimMicrocopy.HELP.of("app_backup_target"),
            BackupTargetModel.MODEL_ID, false);
    }

    /** @return the tool that creates and changes an app of the kind, as an assistant calls it */
    public static @NonNull String toolName(@NonNull Identifier kind) {
        return "app_" + kind.getPath() + "_set";
    }

    /**
     * @return the setting's entry as the panel derives it, or the field itself where the panel draws an editor of its
     *     own over the field's text (a repository picked from its provider): the tool takes that text
     */
    private static @NonNull FormEntry entryOf(@NonNull Field<?, ?> field) {
        FormEntry derived = FieldFormEntryRegistry.INSTANCE.deriveEntry(field);
        return derived instanceof FormEntry.Embedded embedded && embedded.jsonSchema() == null ? Plain.of(field)
            : derived;
    }

    /** @return every kind's writes, in the order their tools are offered */
    public static @NonNull List<KindWrites> all() {
        return List.of(APPLICATION, DOCKER_CONTAINER, STATIC_SITE);
    }

    /**
     * Creates the app through the operator's instance resource; nothing is deployed.
     *
     * @throws DomainRefusal FORBIDDEN, in its own words, for a caller who is no administrator
     */
    private static @NonNull Integer created(@NonNull KindWrites writes, OperationInput.@NonNull Values input,
                                            @Nullable AccessContext access) {
        AccessContext caller = Objects.requireNonNull(access, "a create has its caller");
        if (!HohenheimAccess.isAdmin(caller)) {
            Microcopy words = HohenheimMicrocopy.VIOLATIONS.of("app_create_needs_admin");
            throw new DomainRefusal(ZenitRefusalReason.FORBIDDEN, words.describe(), words);
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put(InstanceModel.KIND.getName(), writes.kind().toString());
        for (Field<?, ?> field : COMMON) {
            if (input.get(field.getName()) != null) raw.put(field.getName(), input.get(field.getName()));
        }
        Object host = input.get(HOST);
        if (host != null) raw.put(InstanceModel.HOST_ID.getName(), Integer.valueOf(String.valueOf(host)));
        Object project = input.get(PROJECT);
        if (project != null) raw.put(InstanceParts.PROJECT.getName(), Integer.valueOf(String.valueOf(project)));
        Object target = input.get(BACKUP_TARGET);
        if (target != null) raw.put(InstanceModel.BACKUP_TARGET_ID.getName(), Integer.valueOf(String.valueOf(target)));
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Field<?, ?> field : settings(writes.schema())) {
            if (input.get(field.getName()) != null) settings.put(field.getName(), input.get(field.getName()));
        }
        raw.put(InstanceModel.SETTINGS.getName(), posted(writes.schema(), settings));
        Panel panel = ApiConduits.adminPanel();
        Object key = ResourceWrites.create(panel, instances(panel), raw, caller);
        return Integer.valueOf(String.valueOf(key));
    }

    /**
     * Writes what the call sent over the app as it stands, through the caller's instance resource: an administrator's
     * the whole form, anyone else's only the name and crash policy. An app of another kind is turned into this kind
     * only when the call says {@code change_kind}, keeping the settings this kind declares, the panel's kind change
     * judging whether its host and state allow it.
     *
     * @return the names of the settings written
     * @throws Violations for an app of another kind without change_kind, or a setting only an administrator changes
     */
    private static @NonNull String changed(@NonNull KindWrites writes, @NonNull Row app,
                                           OperationInput.@NonNull Values input, @Nullable Set<String> patched,
                                           @Nullable AccessContext access) {
        AccessContext caller = Objects.requireNonNull(access, "a change has its caller");
        Set<String> sent = patched != null ? patched : input.coerced().keySet();
        String stored = String.valueOf(app.get(InstanceModel.KIND));
        boolean turned = !writes.holds(app);
        if (turned && !Boolean.TRUE.equals(input.get(CHANGE_KIND))) throw otherKind(app, stored);
        Map<String, Object> raw = new LinkedHashMap<>();
        List<String> written = new ArrayList<>();
        for (Field<?, ?> field : COMMON) {
            if (sent.contains(field.getName())) {
                raw.put(field.getName(), input.get(field.getName()));
                written.add(field.getName());
            }
        }
        boolean admin = HohenheimAccess.isAdmin(caller);
        if (sent.contains(BACKUP_TARGET)) {
            if (!admin) throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("tenant_field_frozen"));
            Object target = input.get(BACKUP_TARGET);
            raw.put(InstanceModel.BACKUP_TARGET_ID.getName(), target == null ? null
                : Integer.valueOf(String.valueOf(target)));
            written.add(BACKUP_TARGET);
        }
        // A kind change keeps only what the new kind declares: the settings schema is closed-world.
        Map<String, Object> kept = RawValues.map(app.get(InstanceModel.SETTINGS));
        Map<String, Object> settings = new LinkedHashMap<>();
        if (turned) {
            for (String name : writes.schema().getFields().keySet()) {
                if (kept.containsKey(name)) settings.put(name, kept.get(name));
            }
        } else {
            settings.putAll(kept);
        }
        boolean settingsSent = turned;
        for (Field<?, ?> field : settings(writes.schema())) {
            if (!sent.contains(field.getName())) continue;
            settings.put(field.getName(), input.get(field.getName()));
            written.add(field.getName());
            settingsSent = true;
        }
        if (turned) {
            raw.put(InstanceModel.KIND.getName(), writes.kind().toString());
            written.add(0, InstanceModel.KIND.getName());
        }
        if (settingsSent) {
            if (!admin) throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("tenant_field_frozen"));
            raw.put(InstanceModel.SETTINGS.getName(), posted(writes.schema(), settings));
        }
        if (raw.isEmpty()) return "";
        Panel panel = admin ? ApiConduits.adminPanel() : ApiConduits.managePanel();
        ResourceWrites.update(panel, instances(panel), Objects.requireNonNull(app.get(InstanceModel.ID)), app, raw,
            caller);
        return String.join(", ", written);
    }

    /** @return the refusal of an app of another kind, naming the tool of its own kind where one exists */
    private static @NonNull Violations otherKind(@NonNull Row app, @NonNull String stored) {
        String code = RecordCodes.codeUnjudged(InstanceModel.MODEL_ID, String.valueOf(app.get(InstanceModel.ID)));
        KindWrites own = all().stream().filter(each -> each.kind().toString().equals(stored)).findFirst()
            .orElse(null);
        Identifier parsed = Identifier.tryParse(stored);
        String kind = parsed == null ? stored : parsed.getPath();
        return Violations.ofForm(own == null
            ? HohenheimMicrocopy.VIOLATIONS.of("app_kind_other_untooled").withArg("code", String.valueOf(code))
                .withArg("kind", kind)
            : HohenheimMicrocopy.VIOLATIONS.of("app_kind_other").withArg("code", String.valueOf(code))
                .withArg("kind", kind).withArg("tool", toolName(own.kind())));
    }

    /** @return the app's stored values in its kind's change input: its name, crash policy and settings */
    private static @NonNull Map<String, Object> stored(@NonNull KindWrites writes, @NonNull Row app) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field<?, ?> field : COMMON) values.put(field.getName(), app.get(field.getName()));
        Map<String, Object> settings = RawValues.map(app.get(InstanceModel.SETTINGS));
        for (Field<?, ?> field : settings(writes.schema())) values.put(field.getName(), settings.get(field.getName()));
        return values;
    }

    /**
     * @return the settings as the panel's form posts them: a map setting as its key/value editor's numbered rows of
     *     {key, value}, the shape that editor's coercion reads (zenit-forms KeyValueField), a kept secret as itself
     *
     * AIDEV-NOTE: zenit-forms declares no writer of its editor's rows, only the reader; until it does, this spells
     * them with the one shared key names (MapEntryFields), as the editor's own render state does.
     */
    private static @NonNull Map<String, Object> posted(@NonNull Schema schema, @NonNull Map<String, Object> settings) {
        Map<String, Object> posted = new LinkedHashMap<>(settings);
        for (Field<?, ?> field : schema.getFields().values()) {
            if (!(field instanceof StringMapField) || !(settings.get(field.getName()) instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> rows = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = entry.getValue() instanceof Secret sealed ? sealed.reveal() : entry.getValue();
                rows.put(String.valueOf(rows.size()), Map.of(MapEntryFields.KEY, String.valueOf(entry.getKey()),
                    MapEntryFields.VALUE, value == null ? "" : String.valueOf(value)));
            }
            posted.put(field.getName(), rows);
        }
        return posted;
    }

    /** @return the panel's instance resource, the one whose form the panel posts */
    private static @NonNull PanelResource<Row> instances(@NonNull Panel panel) {
        return Objects.requireNonNull(CmsSupport.declaredRowEntry(panel, HohenheimSlugs.INSTANCES),
            "the panel declares its instances");
    }
}
