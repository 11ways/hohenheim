package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.protoblast.common.dry.MapEntryFields;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.PatchBase;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A family of records that keep their kind's own settings in a settings map (app kinds, site kinds): each kind's
 * settings declaration becomes one create and one change operation, the change refusing a record of another kind
 * unless the call says {@link #CHANGE_KIND}, and landing what it sends on the record's stored settings.
 *
 * AIDEV-NOTE: the one home of the kind-tool mechanism (later database, stack and host kinds declare a family too).
 * A family says only what differs: its common fields, its extra entries, its gate and command, and its own create and
 * change writers, which run through the panel resource so its gates and hooks decide as they do for the form.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class KindSettingsWrites {

    /** The argument turning a record of another kind into this kind. */
    public static final String CHANGE_KIND = "change_kind";

    private KindSettingsWrites() {
    }

    /** A family's create: the record made of one kind from the call's input, answering its key. */
    @FunctionalInterface
    public interface Creates {
        @NonNull Integer create(@NonNull Kind kind, OperationInput.@NonNull Values input,
                                @NonNull AccessContext caller);
    }

    /** A family's change of a record the call may change, answering the names of what it wrote. */
    @FunctionalInterface
    public interface Changes {

        /**
         * @param sent   the entries the call sent, {@link #CHANGE_KIND} left out
         * @param turned whether the record is of another kind and the call turns it into this one
         */
        @NonNull String change(@NonNull Kind kind, @NonNull Row record, OperationInput.@NonNull Values input,
                               @NonNull Set<String> sent, boolean turned, @NonNull AccessContext caller);
    }

    /**
     * One kind's create and change.
     *
     * @param id       the kind's id, as a record stores it
     * @param settings the kind's settings an assistant gives by name
     * @param tool     the tool that creates and changes a record of the kind
     * @param thing    what a create makes, as a refusal names it
     * @param needs    the arguments a create cannot do without
     */
    public record Kind(@NonNull Family family, @NonNull Identifier id, @NonNull Schema schema,
                       @NonNull List<Field<?, ?>> settings, @NonNull String tool, @NonNull Microcopy thing,
                       @NonNull List<String> needs, @NonNull Operation<Void, OperationInput.Values, Integer> create,
                       @NonNull Operation<Row, OperationInput.Values, String> change) {

        /** @return whether the record is of this kind */
        public boolean holds(@NonNull Row record) {
            return this.id.toString().equals(String.valueOf(record.get(this.family.kindField.getName())));
        }
    }

    /** @return a family of the model's records, their kind and settings stored in the given fields */
    public static @NonNull Family family(@NonNull String noun, @NonNull Identifier model,
                                         @NonNull SubjectType<Row> subject, @NonNull Field<?, ?> idField,
                                         @NonNull Field<?, ?> kindField, @NonNull Field<?, ?> settingsField) {
        return new Family(noun, model, subject, idField, kindField, settingsField);
    }

    /**
     * What a family's kinds share. Configure it fully before its first {@link #kind}: each kind is registered with
     * the family as it stands.
     */
    public static final class Family {

        private final String noun;
        private final Identifier model;
        private final SubjectType<Row> subject;
        private final Field<?, ?> idField;
        private final Field<?, ?> kindField;
        private final Field<?, ?> settingsField;
        private final List<Kind> kinds = new ArrayList<>();
        private List<Field<?, ?>> common = List.of();
        private Function<Schema, List<Field<?, ?>>> settings = schema -> List.copyOf(schema.getFields().values());
        private Function<Identifier, List<FormEntry>> createEntries = kind -> List.of();
        private Function<Identifier, List<FormEntry>> changeEntries = kind -> List.of();
        private OperationGate changeGate = OperationGate.open();
        private OperationCommand creates;
        private OperationCommand changes;
        private Creates creator;
        private Changes changer;

        private Family(@NonNull String noun, @NonNull Identifier model, @NonNull SubjectType<Row> subject,
                       @NonNull Field<?, ?> idField, @NonNull Field<?, ?> kindField,
                       @NonNull Field<?, ?> settingsField) {
            this.noun = noun;
            this.model = model;
            this.subject = subject;
            this.idField = idField;
            this.kindField = kindField;
            this.settingsField = settingsField;
        }

        /** Declares the record's own settings beside its kind's, entries of both the create and the change. */
        public @NonNull Family common(@NonNull List<Field<?, ?>> fields) {
            this.common = List.copyOf(fields);
            return this;
        }

        /** Declares which of a kind's declared settings an assistant gives by name: all of them unless said. */
        public @NonNull Family settings(@NonNull Function<Schema, List<Field<?, ?>>> settings) {
            this.settings = settings;
            return this;
        }

        /** Declares the entries a create of a kind takes beside the common ones and the kind's settings. */
        public @NonNull Family createEntries(@NonNull Function<Identifier, List<FormEntry>> entries) {
            this.createEntries = entries;
            return this;
        }

        /** Declares the entries a change of a kind takes beside the common ones and the kind's settings. */
        public @NonNull Family changeEntries(@NonNull Function<Identifier, List<FormEntry>> entries) {
            this.changeEntries = entries;
            return this;
        }

        /** Declares who reaches a change: open unless said, the writer then judging the caller. */
        public @NonNull Family changeGate(@NonNull OperationGate gate) {
            this.changeGate = gate;
            return this;
        }

        /** Declares the commands creates and changes run as, so the same request id answers the first result. */
        public @NonNull Family commands(@NonNull OperationCommand creates, @NonNull OperationCommand changes) {
            this.creates = creates;
            this.changes = changes;
            return this;
        }

        /** Declares the family's writers. */
        public @NonNull Family writers(@NonNull Creates creator, @NonNull Changes changer) {
            this.creator = creator;
            this.changer = changer;
            return this;
        }

        /** @return every kind declared, in the order their tools are offered */
        public @NonNull List<Kind> kinds() {
            return List.copyOf(this.kinds);
        }

        /** @return the common fields */
        public @NonNull List<Field<?, ?>> commonFields() {
            return this.common;
        }

        /**
         * Declares one kind: its create and change operations, ids {@code create_<word>} and {@code change_<word>},
         * labelled and described by the Hohenheim microcopy of those ids.
         *
         * @param needs the arguments a create cannot do without besides the name
         */
        public @NonNull Kind kind(@NonNull Identifier id, @NonNull Schema schema, @NonNull String word,
                                  @NonNull String tool, @NonNull Microcopy thing, @NonNull String @NonNull ... needs) {
            Objects.requireNonNull(this.creator, "a family declares its writers before its kinds");
            Objects.requireNonNull(this.creates, "a family declares its commands before its kinds");
            List<Field<?, ?>> declared = List.copyOf(this.settings.apply(schema));
            FormSpec.Builder created = FormSpec.builder();
            FormSpec.Builder changed = FormSpec.builder();
            for (Field<?, ?> field : this.common) {
                created.add(field);
                changed.add(field);
            }
            for (FormEntry entry : this.createEntries.apply(id)) created.add(entry);
            for (FormEntry entry : this.changeEntries.apply(id)) changed.add(entry);
            changed.add(Plain.of(BooleanField.builder(CHANGE_KIND).defaultValue(false)
                .help(HohenheimMicrocopy.HELP.of(this.noun + "_change_kind")).build()));
            for (Field<?, ?> field : declared) {
                created.add(entryOf(field));
                changed.add(entryOf(field));
            }
            Operation<Void, OperationInput.Values, Integer> create = Operation.declare(HohenheimIds.id("create_" + word))
                .label(HohenheimMicrocopy.HOHENHEIM.of("create_" + word))
                .description(HohenheimMicrocopy.HOHENHEIM.of("create_" + word + "_description"))
                .noSubject()
                // Any account reaches the create: its writer judges the caller as the panel's create does.
                .gate(OperationGate.open())
                .input(OperationInput.derived(created.build()))
                .result(Integer.class)
                .createdRecord(this.model, key -> key)
                .facts(OperationFact.IDEMPOTENT)
                .command(this.creates)
                .register();
            Operation<Row, OperationInput.Values, String> change = Operation.declare(HohenheimIds.id("change_" + word))
                .label(HohenheimMicrocopy.HOHENHEIM.of("change_" + word))
                .description(HohenheimMicrocopy.HOHENHEIM.of("change_" + word + "_description"))
                .one(this.subject)
                .gate(this.changeGate)
                .input(OperationInput.derived(changed.build()))
                .result(String.class)
                .patchable()
                .facts(OperationFact.IDEMPOTENT)
                .command(this.changes)
                .register();
            List<String> needed = new ArrayList<>(List.of("name"));
            needed.addAll(List.of(needs));
            Kind kind = new Kind(this, id, schema, declared, tool, thing, List.copyOf(needed), create, change);
            OperationHandlers.attach(create).handle(call -> this.creator.create(kind,
                Objects.requireNonNull(call.input()), Objects.requireNonNull(call.access(), "a create has its caller")));
            // A record of another kind is a subject too: its change refuses it in words naming its kind's tool, unless
            // the call turns it into this kind.
            OperationHandlers.attach(change)
                .patchBase(PatchBase.ofValues(change, (record, access) -> this.stored(kind,
                    Objects.requireNonNull(record))))
                .handle(call -> this.changed(kind, call.subject(), Objects.requireNonNull(call.input()),
                    call.patched(), Objects.requireNonNull(call.access(), "a change has its caller")));
            this.kinds.add(kind);
            return kind;
        }

        /**
         * @return what a create of the kind writes from the call: the kind, the common fields and the kind's settings
         *     the call gives, the settings as one map under the family's settings field
         */
        public @NonNull Map<String, Object> created(@NonNull Kind kind, OperationInput.@NonNull Values input) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put(this.kindField.getName(), kind.id().toString());
            for (Field<?, ?> field : this.common) {
                if (input.get(field.getName()) != null) raw.put(field.getName(), input.get(field.getName()));
            }
            Map<String, Object> settings = new LinkedHashMap<>();
            for (Field<?, ?> field : kind.settings()) {
                Object given = input.get(field.getInputName());
                if (given != null) settings.put(field.getName(), given);
            }
            raw.put(this.settingsField.getName(), settings);
            return raw;
        }

        private @NonNull String changed(@NonNull Kind kind, @NonNull Row record, OperationInput.@NonNull Values input,
                                        @Nullable Set<String> patched, @NonNull AccessContext caller) {
            Set<String> sent = new LinkedHashSet<>(patched != null ? patched : input.coerced().keySet());
            sent.remove(CHANGE_KIND);
            boolean turned = !kind.holds(record);
            if (turned && !Boolean.TRUE.equals(input.get(CHANGE_KIND))) throw this.otherKind(record);
            String written = this.changer.change(kind, record, input, sent, turned, caller);
            return changedLine(RecordCodes.codeUnjudged(this.model, String.valueOf(record.get(this.idField
                .getName()))), written);
        }

        /** @return the refusal of a record of another kind, naming the tool of its own kind where one exists */
        private @NonNull Violations otherKind(@NonNull Row record) {
            String code = RecordCodes.codeUnjudged(this.model, String.valueOf(record.get(this.idField.getName())));
            String stored = String.valueOf(record.get(this.kindField.getName()));
            Kind own = this.kinds.stream().filter(each -> each.id().toString().equals(stored)).findFirst()
                .orElse(null);
            Identifier parsed = Identifier.tryParse(stored);
            Microcopy words = HohenheimMicrocopy.VIOLATIONS.of(this.noun + (own == null ? "_kind_other_untooled"
                : "_kind_other")).withArg("code", String.valueOf(code))
                .withArg("kind", parsed == null ? stored : parsed.getPath());
            return Violations.ofForm(own == null ? words : words.withArg("tool", own.tool()));
        }

        /**
         * @return the record's stored values in its kind's change input: its common fields and the settings this
         *     kind declares, which a record of another kind keeps only where this kind declares them too
         */
        private @NonNull Map<String, Object> stored(@NonNull Kind kind, @NonNull Row record) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Field<?, ?> field : this.common) values.put(field.getName(), record.get(field.getName()));
            Map<String, Object> settings = RawValues.map(record.get(this.settingsField.getName()));
            for (Field<?, ?> field : kind.settings()) {
                values.put(field.getInputName(), settings.get(field.getName()));
            }
            return values;
        }
    }

    /**
     * @return the setting's entry as the panel derives it, or the field itself where the panel draws an editor of its
     *     own over the field's text (a repository picked from its provider): the tool takes that text; either named
     *     by the field's input name
     */
    static @NonNull FormEntry entryOf(@NonNull Field<?, ?> field) {
        FormEntry derived = FieldFormEntryRegistry.INSTANCE.deriveEntry(field);
        FormEntry entry = derived instanceof FormEntry.Embedded embedded && embedded.jsonSchema() == null
            ? Plain.of(field) : derived;
        return entry.named(field.getInputName());
    }

    /** @return the stored name of the field going by this input name among the fields, else the name itself */
    static @NonNull String storedName(@NonNull Collection<? extends Field<?, ?>> fields, @NonNull String inputName) {
        for (Field<?, ?> field : fields) {
            if (field.getInputName().equals(inputName)) return field.getName();
        }
        return inputName;
    }

    /** @return what a change answers: the record's code and the names of the settings it wrote */
    static @NonNull String changedLine(@Nullable String code, @NonNull String written) {
        return code + " changed: " + (written.isBlank() ? "nothing" : written);
    }

    /** Runs a write of stored values that answers nothing, its refusals naming each field by its input name. */
    static void speaking(@NonNull Collection<? extends Field<?, ?>> fields, @NonNull String prefix,
                         @NonNull Runnable write) {
        speaking(fields, prefix, Map.of(), write);
    }

    /**
     * Runs a write of stored values that answers nothing, its refusals naming each field by its input name and each
     * linked record's column by the argument that set it.
     */
    static void speaking(@NonNull Collection<? extends Field<?, ?>> fields, @NonNull String prefix,
                         @NonNull Map<String, ? extends Field<?, ?>> columns, @NonNull Runnable write) {
        speaking(fields, prefix, columns, () -> {
            write.run();
            return null;
        });
    }

    /**
     * Runs a write of stored values, its refusals naming each field by its input name, as the caller named it.
     *
     * @param prefix the path the fields' values sit under in the write, empty for a record's own columns
     */
    static <T> T speaking(@NonNull Collection<? extends Field<?, ?>> fields, @NonNull String prefix,
                          @NonNull Supplier<T> write) {
        return speaking(fields, prefix, Map.of(), write);
    }

    /**
     * Runs a write of stored values, its refusals naming each field by its input name and each linked record's column
     * by the argument that set it, as the caller named them.
     *
     * @param prefix  the path the fields' values sit under in the write, empty for a record's own columns
     * @param columns argument name to the record's own column the argument writes (a host's code into server_id)
     */
    static <T> T speaking(@NonNull Collection<? extends Field<?, ?>> fields, @NonNull String prefix,
                          @NonNull Map<String, ? extends Field<?, ?>> columns, @NonNull Supplier<T> write) {
        try {
            return write.get();
        } catch (Violations refused) {
            Violations said = new Violations();
            for (Violation violation : refused.all()) {
                String path = violation.path();
                String fieldName = violation.fieldName();
                Map<String, String> named = new LinkedHashMap<>();
                for (Field<?, ?> field : fields) named.put(prefix + field.getName(), field.getInputName());
                columns.forEach((argument, column) -> named.put(column.getName(), argument));
                for (Map.Entry<String, String> name : named.entrySet()) {
                    String stored = name.getKey();
                    if (path.equals(stored) || path.startsWith(stored + ".")) {
                        path = name.getValue() + path.substring(stored.length());
                        fieldName = name.getValue();
                        break;
                    }
                }
                said.add(new Violation(fieldName, path, violation.value(), violation.message(),
                    violation.subject()));
            }
            throw said;
        }
    }

    /**
     * @return the values as the panel's form posts them: a map field as its key/value editor's numbered rows of
     *     {key, value}, the shape that editor's coercion reads (zenit-forms KeyValueField), a kept secret as itself
     *
     * AIDEV-NOTE: zenit-forms declares no writer of its editor's rows, only the reader; until it does, this spells
     * them with the one shared key names (MapEntryFields), as the editor's own render state does.
     */
    static @NonNull Map<String, Object> posted(@NonNull Collection<? extends Field<?, ?>> fields,
                                               @NonNull Map<String, Object> settings) {
        Map<String, Object> posted = new LinkedHashMap<>(settings);
        for (Field<?, ?> field : fields) {
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
}
