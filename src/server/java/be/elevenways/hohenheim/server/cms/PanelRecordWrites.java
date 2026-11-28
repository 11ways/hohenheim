package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.cms.server.panel.ResourceVerbs;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.orm.history.ChangeOp;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.PatchBase;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A panel resource's create and change as two operations, writing through the resource's own form lane
 * ({@link ResourceWrites}) so its gates, hooks and refusals decide as they do for the panel's form; its settings go by
 * their input names and its linked records by code. A record named by its natural key under its parent (a config file
 * by its path) is set by one operation instead, which creates, changes or removes it.
 *
 * AIDEV-NOTE: the one home of "a panel resource's row create and change as operations" (AddressWrites, the database
 * tier, attachments, backup targets, DNS zones, volumes, config files and schedules declare one each). A declaration
 * says only what differs: its settings, which of them a create alone takes, its linked records, the fixed parent a
 * create is made under and entries a writer of their own handles: on a change (a database's password), and on a create
 * too when it declares one for creates (a zone's secondaries, a schedule's steps). An administrator writes through the
 * operator's panel, anyone else through its /manage twin, whose smaller form refuses what it does not take, and a
 * resource the twin does not declare is refused there. A setting left at its field's default is not posted on a
 * create, so a twin's smaller form takes a create that names only what it offers. A parent the child's form names
 * (one not fixed) is posted as that entry, as the form opened under it posts it.
 *
 * AIDEV-NOTE: a change that turns the field a map's shape is chosen by (a template variable's type) starts that map
 * empty, as the panel's form draws the new kind's settings empty: the old kind's settings would be refused as unknown
 * keys of the new one. A keyed record's new key ({@code renamedBy}) is a change's alone, refused by name on a create.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class PanelRecordWrites {

    /** A create has no subject to key its command by: the request id alone answers the first result. */
    private static final OperationCommand CREATES = OperationCommand.perVerb().onDatasource("default");

    /**
     * A change's entries a writer of their own handles: it judges them before the record's own settings are written,
     * and its write runs after they were.
     */
    @FunctionalInterface
    public interface Also {

        /**
         * @param sent the entries of the change this writer handles that the call sent
         * @return the write to run once the record's own settings are written
         */
        @NonNull Runnable judge(@NonNull Row record, OperationInput.@NonNull Values input, @NonNull Set<String> sent,
                                @NonNull AccessContext caller);
    }

    /**
     * A create's entries a writer of their own handles: it judges them before the record is created, and its write
     * runs once it was.
     */
    @FunctionalInterface
    public interface AlsoCreated {

        /**
         * @param sent the entries of the create this writer handles that the call sent
         * @return the write to run with the created record's key
         */
        @NonNull Consumer<Integer> judge(OperationInput.@NonNull Values input, @NonNull Set<String> sent,
                                         @NonNull AccessContext caller);
    }

    /** When a call takes an entry: on every write, only when it creates the record, or only when it changes one. */
    private enum Takes { ALWAYS, CREATE, CHANGE }

    /**
     * One entry of the record: a setting written as itself, or a linked record named by code into its column.
     *
     * @param argument  the name a caller gives it by
     * @param field     the setting, or the column the link writes
     * @param linked    the model a link names a record of, null for a setting
     * @param stored    what a link writes for the record's full id
     * @param container the map field it is written inside (a kind's settings), null for the record's own column
     * @param takes     which writes take it
     * @param byWord    whether a caller names the enum's member by its word, the path of its stored key
     * @param parent    whether the link names the fixed parent the record is created under, never a posted value
     * @param given     the entry a caller gives it by where it is no entry of its field (a key's new value, which
     *                  the record's own form judges), null to derive it
     */
    private record Part(@NonNull String argument, @NonNull Field<?, ?> field, @Nullable Identifier linked,
                        @Nullable Microcopy help, @NonNull Function<String, Object> stored,
                        @Nullable Field<?, ?> container, @NonNull Takes takes, boolean byWord, boolean parent,
                        @Nullable FormEntry given) {

        /** @return whether only a create takes it */
        boolean createOnly() {
            return this.takes == Takes.CREATE;
        }

        /** @return whether only a change of a record that exists takes it */
        boolean changeOnly() {
            return this.takes == Takes.CHANGE;
        }

        @NonNull FormEntry entry() {
            if (this.given != null) return this.given;
            if (this.linked != null) {
                return OperationForms.recordId(this.argument, Objects.requireNonNull(this.help), this.linked, false);
            }
            if (this.byWord) return wordEntry(this.argument, (EnumField) this.field, this.help);
            // A text setting said in words of its own where the field has none, worded as the field is otherwise.
            if (this.help != null && this.field instanceof StringField text) {
                StringField.Builder said = StringField.builder(this.argument);
                Microcopy label = text.getAttribute(FieldAttributes.LABEL_MICROCOPY);
                if (label != null) said.label(label);
                said.help(this.help);
                return Plain.of(said.build());
            }
            FormEntry derived = KindSettingsWrites.entryOf(this.field);
            if (derived.name().equals(this.argument)) return derived;
            // A setting a caller gives under another name than its field's (generated for generate).
            if (!(derived instanceof Plain)) {
                throw new IllegalStateException(this.field.getName() + " is no plain field, so it cannot be"
                    + " given as " + this.argument);
            }
            return Plain.of(this.field).named(this.argument);
        }

        /**
         * @return what the write carries for the value the call gave: a secret as its text, as the panel's form posts
         *     it (the resource's form lane coerces text and seals it again), a word as its member's stored key
         */
        @Nullable Object written(@Nullable Object value) {
            if (value instanceof Secret sealed) return sealed.reveal();
            if (value == null) return null;
            if (this.byWord) return keyOfWord((EnumField) this.field, String.valueOf(value));
            if (this.linked == null) return value;
            return this.stored.apply(String.valueOf(value));
        }

        /** @return the stored value as a caller gives it: a member's key as its word */
        @Nullable Object shown(@Nullable Object stored) {
            return this.byWord && stored != null ? wordOf(String.valueOf(stored)) : stored;
        }

        /** @return the path a refusal of this entry names */
        @NonNull String path() {
            return this.container == null ? this.field.getInputName()
                : this.container.getInputName() + "." + this.field.getInputName();
        }
    }

    /** @return a member's word: the path of its stored key ({@code hohenheim:ssh} is {@code ssh}) */
    static @NonNull String wordOf(@NonNull String key) {
        Identifier parsed = Identifier.tryParse(key);
        return parsed == null ? key : parsed.getPath();
    }

    /**
     * @return each member of the enum as an option under its word
     * @throws IllegalStateException for two members spelled by one word
     */
    private static @NonNull List<FieldOption<String>> wordOptions(@NonNull EnumField field) {
        List<FieldOption<String>> options = new ArrayList<>();
        Set<String> words = new HashSet<>();
        for (EnumField.EnumValue member : field.getValues().values()) {
            String word = wordOf(member.getKey());
            if (!words.add(word)) {
                throw new IllegalStateException(field.getName() + " has two members spelled " + word);
            }
            options.add(FieldOption.of(word, member.getLabel()).withDescription(member.getDescription()));
        }
        return options;
    }

    /**
     * @return the stored key of the member this word spells; an unknown word is passed on as given, so the resource's
     *     own form refuses it as none of its options (the call's form already refused it as none of the words)
     */
    static @NonNull String keyOfWord(@NonNull EnumField field, @NonNull String word) {
        for (EnumField.EnumValue member : field.getValues().values()) {
            if (wordOf(member.getKey()).equals(word)) return member.getKey();
        }
        return word;
    }

    /**
     * @return the entry taking one of the enum's members under {@code argument} by its word, labelled as the field is
     *     and helped in {@code help}, or as the field is for null
     */
    static @NonNull FormEntry wordEntry(@NonNull String argument, @NonNull EnumField members,
                                        @Nullable Microcopy help) {
        StringField.Builder said = StringField.builder(argument);
        Microcopy label = members.getAttribute(FieldAttributes.LABEL_MICROCOPY);
        Microcopy words = help != null ? help : members.getAttribute(FieldAttributes.HELP);
        if (label != null) said.label(label);
        if (words != null) said.help(words);
        return Select.of(said.build()).options(OptionSource.supplied(() -> wordOptions(members))).build();
    }

    /** The record's own columns a create fills from entries of its own, before it is written. */
    @FunctionalInterface
    public interface Fill {

        /**
         * @param sent      the entries of the create this writer handles that the call sent
         * @param parentKey the key of the fixed parent the record is created under, null for none
         * @return the record's columns, by stored name, the create writes beside the call's own settings
         */
        @NonNull Map<String, Object> fill(OperationInput.@NonNull Values input, @NonNull Set<String> sent,
                                          @Nullable Object parentKey, @NonNull AccessContext caller);
    }

    private final Identifier model;
    private final String word;
    private final Field<?, ?> idField;
    private final String slug;
    private final List<Part> parts;
    private final List<FormEntry> alsoEntries;
    private final @Nullable Also also;
    private final @Nullable AlsoCreated alsoCreated;
    private final List<FormEntry> fillEntries;
    private final @Nullable Fill fill;
    private final @Nullable Operation<Void, OperationInput.Values, Integer> create;
    private final @Nullable Operation<Row, OperationInput.Values, String> change;
    private final @Nullable Operation<Row, OperationInput.Values, String> set;
    private final @Nullable SubjectType<Row> keyedUnder;
    private final @Nullable Field<?, ?> keyedColumn;
    private final List<Field<?, ?>> keys;
    private final @Nullable BooleanField remove;

    private PanelRecordWrites(@NonNull Builder builder) {
        this.model = builder.model;
        this.word = builder.word;
        this.idField = builder.idField;
        this.slug = builder.slug;
        this.parts = List.copyOf(builder.parts);
        this.alsoEntries = List.copyOf(builder.alsoEntries);
        this.also = builder.also;
        this.alsoCreated = builder.alsoCreated;
        this.fillEntries = List.copyOf(builder.fillEntries);
        this.fill = builder.fill;
        this.keyedUnder = builder.keyedUnder;
        this.keyedColumn = builder.keyedColumn;
        this.keys = List.copyOf(builder.keys);
        this.remove = builder.remove;
        if (this.keyedUnder != null) {
            this.create = null;
            this.change = null;
            this.set = Operation.declare(HohenheimIds.id("set_" + builder.word))
                .label(HohenheimMicrocopy.HOHENHEIM.of("set_" + builder.word))
                .description(HohenheimMicrocopy.HOHENHEIM.of("set_" + builder.word + "_description"))
                .happened(OperationSentences.of("set_" + builder.word))
                .one(this.keyedUnder)
                // The resource's create, change and delete decide who may, as they do for the panel's form.
                .gate(OperationGate.open())
                .input(OperationInput.derived(this.keyedForm()))
                .result(String.class)
                .patchable()
                .facts(OperationFact.IDEMPOTENT)
                .command(builder.changes)
                .register();
            // No stored record is a keyed call's base (its keys name the record), so a change writes what it sent.
            OperationHandlers.attach(this.set)
                .patchBase(PatchBase.ofValues(this.set, (parent, access) -> Map.of()))
                .handle(call -> this.setUnder(call.subject(), Objects.requireNonNull(call.input()), call.patched(),
                    Objects.requireNonNull(call.access(), "a set has its caller")));
            return;
        }
        this.set = null;
        Operation<Void, OperationInput.Values, Integer> create = Operation.declare(
                HohenheimIds.id("create_" + builder.word))
            .label(HohenheimMicrocopy.HOHENHEIM.of("create_" + builder.word))
            .description(HohenheimMicrocopy.HOHENHEIM.of("create_" + builder.word + "_description"))
            .happened(ChangeOp.CREATE.happened())
            .noSubject()
            // The resource's create decides who may, as it does for the panel's form.
            .gate(OperationGate.open())
            .input(OperationInput.derived(this.form(true)))
            .result(Integer.class)
            .createdRecord(this.model, id -> id)
            .facts(OperationFact.IDEMPOTENT)
            .command(builder.creates)
            .register();
        Operation<Row, OperationInput.Values, String> change = Operation.declare(
                HohenheimIds.id("change_" + builder.word))
            .label(HohenheimMicrocopy.HOHENHEIM.of("change_" + builder.word))
            .description(HohenheimMicrocopy.HOHENHEIM.of("change_" + builder.word + "_description"))
            .happened(ChangeOp.UPDATE.happened())
            .one(builder.subject)
            .gate(OperationGate.open())
            .input(OperationInput.derived(this.form(false)))
            .result(String.class)
            .patchable()
            .facts(OperationFact.IDEMPOTENT)
            .command(builder.changes)
            .register();
        this.create = create;
        this.change = change;
        OperationHandlers.attach(create).handle(call -> this.created(Objects.requireNonNull(call.input()),
            Objects.requireNonNull(call.access(), "a create has its caller"), null));
        OperationHandlers.attach(change)
            .patchBase(PatchBase.ofValues(change, (record, access) -> this.stored(Objects.requireNonNull(record))))
            .handle(call -> this.changed(call.subject(), Objects.requireNonNull(call.input()), call.patched(),
                Objects.requireNonNull(call.access(), "a change has its caller")));
    }

    /**
     * Starts the declaration of a resource's create and change, operation ids {@code create_<word>} and
     * {@code change_<word>}, labelled and described by the Hohenheim microcopy of those ids.
     *
     * @param slug the resource's slug in both panels
     */
    public static @NonNull Builder of(@NonNull String word, @NonNull Identifier model,
                                      @NonNull SubjectType<Row> subject, @NonNull Field<?, ?> idField,
                                      @NonNull String slug) {
        return new Builder(word, model, subject, idField, slug);
    }

    /**
     * @return the create operation, answering the new record's key
     * @throws IllegalStateException for a record set by its keys
     */
    public @NonNull Operation<Void, OperationInput.Values, Integer> create() {
        if (this.create == null) throw new IllegalStateException(this.word + " is set by its keys");
        return this.create;
    }

    /**
     * @return the change operation, answering the record's code and what it wrote
     * @throws IllegalStateException for a record set by its keys
     */
    public @NonNull Operation<Row, OperationInput.Values, String> change() {
        if (this.change == null) throw new IllegalStateException(this.word + " is set by its keys");
        return this.change;
    }

    /**
     * A panel resource's delete as an operation, id {@code delete_<word>} labelled and described by the Hohenheim
     * microcopy of that id: the record goes through the caller's panel resource's own delete
     * ({@link ResourceWrites#delete}), whose authority, offered-dead reason and hooks decide as they do for the
     * panel's Delete button.
     *
     * @param slug the resource's slug in both panels
     */
    public static @NonNull Operation<Row, Void, Integer> deletion(@NonNull String word,
                                                                  @NonNull SubjectType<Row> subject,
                                                                  @NonNull String slug) {
        Operation<Row, Void, Integer> delete = Operation.declare(HohenheimIds.id("delete_" + word))
            .label(HohenheimMicrocopy.HOHENHEIM.of("delete_" + word))
            .description(HohenheimMicrocopy.HOHENHEIM.of("delete_" + word + "_description"))
            .happened(ChangeOp.DELETE.happened())
            .one(subject)
            // The resource's delete decides who may, as it does for the panel's button.
            .gate(OperationGate.open())
            .result(Integer.class)
            .facts(OperationFact.DESTRUCTIVE)
            .command(CmsCommands.TRANSACTIONAL)
            .register();
        // Who may delete the record is the resource's question, asked before the call and by describe's offer alike.
        OperationHandlers.attach(delete)
            .authorize((record, input, caller) -> {
                Panel panel = panelOf(caller);
                PanelResource<Row> resource = CmsSupport.declaredRowEntry(panel, slug);
                return resource != null && ResourceVerbs.removableBy(panel, resource, record, caller) ? null
                    : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "the caller's " + slug + " may not delete it");
            })
            .handle(call -> {
                AccessContext caller = Objects.requireNonNull(call.access(), "a delete has its caller");
                Panel panel = panelOf(caller);
                ResourceWrites.delete(panel, resourceOf(panel, slug), call.subject(), caller);
                return 1;
            });
        return delete;
    }

    /**
     * @return the operation setting the record its keys name under the parent it acts on, answering what it did
     * @throws IllegalStateException for a record made by a create
     */
    public @NonNull Operation<Row, OperationInput.Values, String> set() {
        if (this.set == null) throw new IllegalStateException(this.word + " is made by a create");
        return this.set;
    }

    /** @return the form of the keyed set: every entry, and the removal where the record can be removed */
    private @NonNull FormSpec keyedForm() {
        FormSpec.Builder form = FormSpec.builder();
        for (Part part : this.parts) form.add(part.entry());
        if (this.remove != null) form.add(Plain.of(this.remove));
        return form.build();
    }

    /**
     * Creates, changes or removes the record the call's keys name under the parent, each through the caller's panel
     * resource.
     *
     * @return what was done, by the record's code where it has one
     */
    private @NonNull String setUnder(@NonNull Row parent, OperationInput.@NonNull Values input,
                                     @Nullable Set<String> patched, @NonNull AccessContext caller) {
        Model parentModel = Objects.requireNonNull(Models.get(Objects.requireNonNull(
            Objects.requireNonNull(this.keyedUnder).modelId())), "a parent's model is registered");
        Object parentKey = Objects.requireNonNull(parent.get(parentModel.getPrimaryKeyField().getName()));
        Row found = this.keyed(parentKey, input);
        if (this.remove != null && Boolean.TRUE.equals(input.get(this.remove.getName()))) {
            if (found == null) return this.word + " absent: nothing removed";
            Panel panel = panelOf(caller);
            ResourceWrites.delete(panel, offering(resourceOf(panel, this.slug), ResourceVerb.DELETE), found, caller);
            return this.label(found.get(this.idField.getName())) + " removed";
        }
        if (found == null) {
            // A new key of a record that does not exist yet has nothing to rename.
            for (Part part : this.parts) {
                Object given = input.get(part.argument());
                if (part.changeOnly() && given != null && !String.valueOf(given).isBlank()) {
                    throw Violations.ofField(part.argument(), given, HohenheimMicrocopy.VIOLATIONS.of(
                        "keyed_change_only").withArg("name", part.argument()));
                }
            }
            return this.label(this.created(input, caller, parentKey)) + " created";
        }
        Set<String> sent = new LinkedHashSet<>(patched != null ? patched : input.coerced().keySet());
        if (this.remove != null) sent.remove(this.remove.getName());
        // The keys named the record; only a part renaming one writes it.
        for (Field<?, ?> key : this.keys) sent.remove(key.getInputName());
        return this.changed(found, input, sent, caller);
    }

    /** @return the record under the parent the call's keys name, null for none */
    private @Nullable Row keyed(@NonNull Object parentKey, OperationInput.@NonNull Values input) {
        Model own = Objects.requireNonNull(Models.get(this.model), "a keyed record's model is registered");
        @SuppressWarnings("unchecked")
        Field<Object, ?> column = (Field<Object, ?>) Objects.requireNonNull(this.keyedColumn);
        var query = own.find().where(column.eq(parentKey));
        for (Field<?, ?> key : this.keys) {
            @SuppressWarnings("unchecked")
            Field<Object, ?> each = (Field<Object, ?>) key;
            query = query.where(each.eq(input.get(key.getInputName())));
        }
        return query.first();
    }

    /** @return the record's code, or the word of its kind for one without codes */
    private @NonNull String label(@Nullable Object key) {
        String code = key == null ? null : RecordCodes.codeUnjudged(this.model, String.valueOf(key));
        return code != null ? code : this.word;
    }

    private @NonNull FormSpec form(boolean creating) {
        FormSpec.Builder form = FormSpec.builder();
        for (Part part : this.parts) {
            if (creating ? !part.changeOnly() : !part.createOnly()) form.add(part.entry());
        }
        if (!creating || this.alsoCreated != null) {
            for (FormEntry entry : this.alsoEntries) form.add(entry);
        }
        if (creating) {
            for (FormEntry entry : this.fillEntries) form.add(entry);
        }
        return form.build();
    }

    private @NonNull List<Field<?, ?>> settings() {
        List<Field<?, ?>> settings = new ArrayList<>();
        for (Part part : this.parts) {
            if (part.linked() == null && part.container() == null) settings.add(part.field());
        }
        return settings;
    }

    /**
     * @param sent the arguments the call gave, whose part names a stored path that two parts write (a key and its
     *     new value)
     * @return each entry's stored path to the argument naming it, those inside a map first
     */
    private @NonNull Map<String, String> named(@NonNull Set<String> sent) {
        Map<String, String> named = new LinkedHashMap<>();
        for (Part part : this.parts) {
            if (part.container() != null) name(named, part, sent);
        }
        for (Part part : this.parts) {
            if (part.container() == null) name(named, part, sent);
        }
        return named;
    }

    /** Names the part's path by its argument, unless an earlier part names it and the call did not give this one. */
    private static void name(@NonNull Map<String, String> named, @NonNull Part part, @NonNull Set<String> sent) {
        if (!named.containsKey(part.path()) || sent.contains(part.argument())) named.put(part.path(), part.argument());
    }

    /** @return the arguments a call gave a value for */
    private static @NonNull Set<String> given(OperationInput.@NonNull Values input) {
        Set<String> given = new LinkedHashSet<>();
        input.coerced().forEach((argument, value) -> {
            if (value != null) given.add(argument);
        });
        return given;
    }

    /**
     * Lays the value into the write: the record's own column, or the map it sits inside, as that map stands. A change
     * inside a map whose shape its record's kind decides is laid over that map as the resource's form holds it (its
     * read of the record, so a map stored in a former shape is changed as every reader shows it) and posts that
     * stored kind with it, as the panel's form does, so the map is judged by the kind it belongs to.
     *
     * @param record the record a change writes over, null for a create or a map a change of its kind empties
     * @param shown  the record's values as the resource's form holds them, null for a create
     */
    private static void put(@NonNull Map<String, Object> raw, @NonNull Part part, @Nullable Object value,
                            @Nullable Row record, @Nullable Map<String, Object> shown) {
        if (part.container() == null) {
            if (record != null && part.field() instanceof SchemaField schema && schema.isDynamic()
                    && value instanceof Map<?, ?> sent) {
                // A whole map whose shape its record's kind decides: a change sends what changes inside it, laid
                // over the shown map while the kind stays, and posts that stored kind so the map is judged by it.
                String selector = Objects.requireNonNull(schema.getSchemaFromField());
                if (!raw.containsKey(selector)) {
                    Map<String, Object> merged = new LinkedHashMap<>(RawValues.map(shown != null
                        && shown.containsKey(schema.getName()) ? shown.get(schema.getName())
                        : record.get(schema.getName())));
                    sent.forEach((key, entry) -> merged.put(String.valueOf(key), entry));
                    raw.put(schema.getName(), merged);
                    raw.put(selector, record.get(selector));
                    return;
                }
            }
            raw.put(part.field().getName(), value);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> inside = (Map<String, Object>) raw.computeIfAbsent(part.container().getName(),
            name -> new LinkedHashMap<>(record == null ? Map.of() : RawValues.map(record.get(name))));
        inside.put(part.field().getName(), value);
        if (record != null && part.container() instanceof SchemaField schema && schema.isDynamic()) {
            String selector = Objects.requireNonNull(schema.getSchemaFromField());
            raw.putIfAbsent(selector, record.get(selector));
        }
    }

    /**
     * @return the names of the maps whose shape a selector decides that the call turns to another value (a variable's
     *     type): the panel's form draws the new kind's settings empty, so the old kind's settings go for good
     */
    private @NonNull Set<String> turned(@NonNull Row record, OperationInput.@NonNull Values input,
                                        @NonNull Set<String> sent) {
        Set<String> turned = new LinkedHashSet<>();
        for (Part part : this.parts) {
            if (!(part.container() instanceof SchemaField schema) || !schema.isDynamic()) continue;
            String selector = Objects.requireNonNull(schema.getSchemaFromField());
            for (Part chooser : this.parts) {
                if (chooser.container() != null || !chooser.field().getName().equals(selector)
                        || !sent.contains(chooser.argument())) {
                    continue;
                }
                Object chosen = chooser.written(input.get(chooser.argument()));
                if (!String.valueOf(chosen).equals(String.valueOf((Object) record.get(selector)))) {
                    turned.add(schema.getName());
                }
            }
        }
        return turned;
    }

    /** @return the part a caller gives by this name, null for an entry a writer of its own handles */
    private @Nullable Part part(@NonNull String argument) {
        for (Part part : this.parts) {
            if (part.argument().equals(argument)) return part;
        }
        return null;
    }

    /**
     * Creates the record through the caller's panel resource, under the parent a fixed-parent link names (or
     * {@code under}, the parent a keyed set acts on), then hands the entries a writer of their own handles the created
     * record.
     */
    private @NonNull Integer created(OperationInput.@NonNull Values input, @NonNull AccessContext caller,
                                     @Nullable Object under) {
        Map<String, Object> raw = new LinkedHashMap<>();
        Object parentKey = under;
        Field<?, ?> parentColumn = this.keyedColumn;
        for (Part part : this.parts) {
            Object value = input.get(part.argument());
            if (value == null || String.valueOf(value).isBlank() || part.changeOnly()) continue;
            if (part.linked() == null && value.equals(part.field().getDefaultValue())) continue;
            if (part.parent()) {
                parentKey = part.written(value);
                parentColumn = part.field();
            } else {
                put(raw, part, part.written(value), null, null);
            }
        }
        Set<String> handled = new LinkedHashSet<>();
        for (FormEntry entry : this.alsoEntries) {
            if (input.get(entry.name()) != null) handled.add(entry.name());
        }
        // Judged before the record is written: a create is no transaction of its own.
        @Nullable Consumer<Integer> after = handled.isEmpty() || this.alsoCreated == null ? null
            : this.alsoCreated.judge(input, handled, caller);
        Panel panel = panelOf(caller);
        PanelResource<Row> resource = offering(this.resource(panel), ResourceVerb.CREATE);
        // Filled once the caller's panel offers the create, inside the create's command, so a refused create
        // rolls back what the fill wrote.
        if (this.fill != null) {
            Set<String> filling = new LinkedHashSet<>();
            for (FormEntry entry : this.fillEntries) {
                if (input.get(entry.name()) != null) filling.add(entry.name());
            }
            raw.putAll(this.fill.fill(input, filling, parentKey, caller));
        }
        Object parent = parentKey == null ? null
            : postParent(resource, raw, parentKey, Objects.requireNonNull(parentColumn, "a parent has its column"));
        Map<String, Object> posted = this.posted(resource, raw);
        Object key = KindSettingsWrites.speaking(() -> parent == null
            ? ResourceWrites.create(panel, resource, posted, caller)
            : ResourceWrites.createUnder(panel, resource, parent, posted, caller),
            this.named(given(input)));
        Integer created = Integer.valueOf(String.valueOf(key));
        if (after != null) after.accept(created);
        return created;
    }

    /**
     * Posts the parent as the child's form opened from it posts it: a field parent is the create's parent (a fixed one
     * read from the create, another posted under its field too); a path parent's nearest record (a stack file's
     * service) is posted under the child's own column, as the tab linking to the form preselects it.
     *
     * @param column the child's column naming the parent
     * @return the key the record is created under, null for a plain create
     */
    private static @Nullable Object postParent(@NonNull PanelResource<Row> resource, @NonNull Map<String, Object> raw,
                                               @NonNull Object parentKey, @NonNull Field<?, ?> column) {
        ResourceParent<?> parent = resource.parent();
        if (parent != null && parent.field() != null) {
            if (!parent.isFixed()) raw.put(parent.field().getName(), parentKey);
            return parentKey;
        }
        raw.put(column.getName(), parentKey);
        return null;
    }

    /**
     * Writes only what the call sent over the record, through the caller's panel resource, then hands the entries a
     * writer of their own handles to it.
     *
     * @return the record's code and the names of the settings written
     */
    private @NonNull String changed(@NonNull Row record, OperationInput.@NonNull Values input,
                                    @Nullable Set<String> patched, @NonNull AccessContext caller) {
        Set<String> sent = patched != null ? patched : input.coerced().keySet();
        Map<String, Object> raw = new LinkedHashMap<>();
        Set<String> handled = new LinkedHashSet<>();
        Set<String> turned = this.turned(record, input, sent);
        for (String map : turned) raw.put(map, new LinkedHashMap<>());
        // A caller whose panel declares no such resource changes nothing, an entry of its own writer neither.
        Panel panel = panelOf(caller);
        PanelResource<Row> resource = this.resource(panel);
        Map<String, Object> shown = PartsReads.valuesFromRow(resource, record);
        for (String name : sent) {
            Part part = this.part(name);
            Object value = input.get(name);
            // A change takes a create-only entry only as one its own writer handles (a database's password).
            if (part == null || part.createOnly()) {
                handled.add(name);
            } else {
                put(raw, part, part.written(value), part.container() != null
                    && turned.contains(part.container().getName()) ? null : record, shown);
            }
        }
        String code = this.label(record.get(this.idField.getName()));
        @Nullable Runnable after = handled.isEmpty() || this.also == null ? null
            : this.also.judge(record, input, handled, caller);
        if (!raw.isEmpty()) {
            offering(resource, ResourceVerb.UPDATE);
            Map<String, Object> posted = edited(resource, record, this.posted(resource, raw));
            KindSettingsWrites.speaking(() -> {
                ResourceWrites.update(panel, resource, Objects.requireNonNull(record.get(this.idField.getName())),
                    record, posted, caller);
                return null;
            }, this.named(sent));
        }
        if (after != null) after.run();
        return KindSettingsWrites.changedLine(code, String.join(", ", sent));
    }

    /**
     * @return what the resource's edit form posts for a change of only {@code sent}: those values, over the form's
     *     stored ones where an operation writes the change, as it takes its whole input (ResourceWrites.update)
     */
    private static @NonNull Map<String, Object> edited(@NonNull PanelResource<Row> resource, @NonNull Row record,
                                                       @NonNull Map<String, Object> sent) {
        Operation<?, ?, ?> writer = resource.operationOf(ResourceVerb.UPDATE);
        if (writer == null) return sent;
        Map<String, Object> stored = PartsReads.valuesFromRow(resource, record);
        Map<String, Object> whole = new LinkedHashMap<>();
        for (FormEntry entry : writer.input().form().entries()) {
            if (stored.containsKey(entry.name())) whole.put(entry.name(), stored.get(entry.name()));
        }
        whole.putAll(sent);
        return whole;
    }

    /**
     * @return the values, gathered by their stored names, as the resource's form posts them: by its entries' names
     *     (ApiConduits.formValues), a map setting as its editor's rows
     */
    private @NonNull Map<String, Object> posted(@NonNull PanelResource<Row> resource,
                                                @NonNull Map<String, Object> raw) {
        return KindSettingsWrites.posted(this.settings(), ApiConduits.formValues(resource, raw));
    }

    /** @return the record's stored settings, the patch a change lands on */
    private @NonNull Map<String, Object> stored(@NonNull Row record) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Part part : this.parts) {
            if (part.linked() != null || part.createOnly()) continue;
            Object stored = part.container() == null ? record.get(part.field().getName())
                : RawValues.map(record.get(part.container().getName())).get(part.field().getName());
            values.put(part.argument(), part.shown(stored));
        }
        return values;
    }

    /** @return the operator's panel for an administrator, the /manage panel for anyone else */
    private static @NonNull Panel panelOf(@NonNull AccessContext caller) {
        return HohenheimAccess.isAdmin(caller) ? ApiConduits.adminPanel() : ApiConduits.managePanel();
    }

    /** @return the panel's resource, the one whose form the panel posts */
    private @NonNull PanelResource<Row> resource(@NonNull Panel panel) {
        return resourceOf(panel, this.slug);
    }

    /**
     * @return the panel's resource of this slug
     * @throws DomainRefusal forbidden for a caller whose panel declares none (an operator-only resource on /manage)
     */
    private static @NonNull PanelResource<Row> resourceOf(@NonNull Panel panel, @NonNull String slug) {
        PanelResource<Row> resource = CmsSupport.declaredRowEntry(panel, slug);
        if (resource == null) {
            throw new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "the caller's panel declares no " + slug);
        }
        return resource;
    }

    /**
     * Creates a record of the resource under its parent through the caller's panel, as its form opened under that
     * parent would: the child rows a writer of a parent's own entry writes (a schedule's steps).
     *
     * @param column the child's column naming the parent
     * @param stored the record's values by their stored names
     * @return the created record's key
     */
    public static @NonNull Object createChild(@NonNull String slug, @NonNull Field<?, ?> column,
                                              @NonNull Object parentKey, @NonNull Map<String, Object> stored,
                                              @NonNull AccessContext caller) {
        Panel panel = panelOf(caller);
        PanelResource<Row> resource = resourceOf(panel, slug);
        Map<String, Object> raw = new LinkedHashMap<>(stored);
        Object under = postParent(resource, raw, parentKey, column);
        Map<String, Object> posted = KindSettingsWrites.posted(List.of(), ApiConduits.formValues(resource, raw));
        return under == null ? ResourceWrites.create(panel, resource, posted, caller)
            : ResourceWrites.createUnder(panel, resource, under, posted, caller);
    }

    /** Deletes a record of the resource through the caller's panel, as its delete button does. */
    public static void deleteChild(@NonNull String slug, @NonNull Row record, @NonNull AccessContext caller) {
        Panel panel = panelOf(caller);
        ResourceWrites.delete(panel, offering(resourceOf(panel, slug), ResourceVerb.DELETE), record, caller);
    }

    /**
     * Creates a record of the resource through the caller's panel, as its form's create does, for a writer whose
     * arguments become the record's values by a shape of their own (a sign-in provider's settings by its kind).
     *
     * @param stored the record's values by their stored names
     * @return the created record's key
     */
    public static @NonNull Object createThrough(@NonNull String slug, @NonNull Map<String, Object> stored,
                                                @NonNull AccessContext caller) {
        Panel panel = panelOf(caller);
        PanelResource<Row> resource = offering(resourceOf(panel, slug), ResourceVerb.CREATE);
        Map<String, Object> posted = KindSettingsWrites.posted(List.of(), ApiConduits.formValues(resource, stored));
        return ResourceWrites.create(panel, resource, posted, caller);
    }

    /**
     * Changes a record of the resource through the caller's panel, as its form's save does, writing only what
     * {@code stored} holds.
     *
     * @param key    the record's key
     * @param stored the values written, by their stored names
     */
    public static void updateThrough(@NonNull String slug, @NonNull Object key, @NonNull Row record,
                                     @NonNull Map<String, Object> stored, @NonNull AccessContext caller) {
        Panel panel = panelOf(caller);
        PanelResource<Row> resource = offering(resourceOf(panel, slug), ResourceVerb.UPDATE);
        Map<String, Object> posted = edited(resource, record,
            KindSettingsWrites.posted(List.of(), ApiConduits.formValues(resource, stored)));
        ResourceWrites.update(panel, resource, key, record, posted, caller);
    }

    /**
     * @return the resource, once it offers the caller's panel the verb
     * @throws DomainRefusal forbidden where it does not (a read-only /manage twin), as its missing button says
     */
    private static @NonNull PanelResource<Row> offering(@NonNull PanelResource<Row> resource,
                                                        @NonNull ResourceVerb verb) {
        if (!resource.offers(verb)) {
            throw new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "resource " + resource.id() + " offers this caller"
                + " no " + verb);
        }
        return resource;
    }

    /** Declares a resource's settings, links and change entries in the order a form lists them. */
    public static final class Builder {

        private final String word;
        private final Identifier model;
        private final SubjectType<Row> subject;
        private final Field<?, ?> idField;
        private final String slug;
        private final List<Part> parts = new ArrayList<>();
        private final List<FormEntry> alsoEntries = new ArrayList<>();
        private @Nullable Also also;
        private @Nullable AlsoCreated alsoCreated;
        private final List<FormEntry> fillEntries = new ArrayList<>();
        private @Nullable Fill fill;
        private @Nullable Field<?, ?> container;
        private OperationCommand creates = CREATES;
        private OperationCommand changes = CmsCommands.TRANSACTIONAL;
        private @Nullable SubjectType<Row> keyedUnder;
        private @Nullable Field<?, ?> keyedColumn;
        private final List<Field<?, ?>> keys = new ArrayList<>();
        private @Nullable BooleanField remove;

        private Builder(@NonNull String word, @NonNull Identifier model, @NonNull SubjectType<Row> subject,
                        @NonNull Field<?, ?> idField, @NonNull String slug) {
            this.word = word;
            this.model = model;
            this.subject = subject;
            this.idField = idField;
            this.slug = slug;
        }

        /** Writes the entries declared after this inside the map field, or the record's own columns for null. */
        public @NonNull Builder within(@Nullable Field<?, ?> container) {
            this.container = container;
            return this;
        }

        /** Declares settings a create and a change both take, each by its input name. */
        public @NonNull Builder settings(@NonNull List<? extends Field<?, ?>> fields) {
            for (Field<?, ?> field : fields) this.add(field.getInputName(), field, Takes.ALWAYS, false);
            return this;
        }

        /** Declares settings only a change of a record that exists takes: a create naming one is refused by name. */
        public @NonNull Builder changeOnly(@NonNull List<? extends Field<?, ?>> fields) {
            for (Field<?, ?> field : fields) this.add(field.getInputName(), field, Takes.CHANGE, false);
            return this;
        }

        /**
         * Declares the argument giving a keyed record a new value of one of its keys ({@code new_key}), judged by the
         * record's own form as the key is; a call naming no record that exists is refused it by name.
         */
        public @NonNull Builder renamedBy(@NonNull String argument, @NonNull Field<?, ?> key, @NonNull Microcopy help) {
            this.parts.add(new Part(argument, key, null, null, id -> id, this.container, Takes.CHANGE, false, false,
                Plain.of(StringField.builder(argument).help(help).build())));
            return this;
        }

        /**
         * Declares an enum setting a create and a change both take, a caller naming its member by a plain word: the
         * path of the member's stored key ({@code ssh} for {@code hohenheim:ssh}). A word naming no member is
         * refused as none of the options.
         */
        public @NonNull Builder byWord(@NonNull EnumField field) {
            return this.byWord(field.getInputName(), field);
        }

        /** {@link #byWord(EnumField)} given by {@code argument}, where the field's input name says less (a kind). */
        public @NonNull Builder byWord(@NonNull String argument, @NonNull EnumField field) {
            this.add(argument, field, Takes.ALWAYS, true);
            return this;
        }

        /**
         * Declares a setting a create and a change both take under {@code argument} where the field's own input name
         * says less than the spec's word (a redirect's {@code target} is its {@code target_url}, {@code generated} for
         * a secret's {@code generate}); an enum's member is given by its word, as {@link #byWord(EnumField)} takes one.
         *
         * @throws IllegalStateException when its form is built, for a renamed field whose entry is no plain one
         */
        public @NonNull Builder setting(@NonNull String argument, @NonNull Field<?, ?> field) {
            return this.setting(argument, field, null);
        }

        /**
         * {@link #setting(String, Field)} said in {@code help} where the field's own words say nothing a caller needs
         * (a field the panel labels without help); only a text or an enum setting takes words of its own.
         *
         * @throws IllegalStateException for words given to a setting of another type
         */
        public @NonNull Builder setting(@NonNull String argument, @NonNull Field<?, ?> field,
                                        @Nullable Microcopy help) {
            if (help != null && !(field instanceof StringField) && !(field instanceof EnumField)) {
                throw new IllegalStateException(field.getName() + " takes no words of its own: it is no text or enum");
            }
            boolean byWord = field instanceof EnumField;
            this.parts.add(new Part(argument, field, null, help, id -> id, this.container, Takes.ALWAYS, byWord, false,
                null));
            return this;
        }

        /**
         * Declares a setting a create and a change both take through the entry the panel's form declares for it (a
         * list offering a vocabulary its field cannot name), so the form and the write read one declaration.
         */
        public @NonNull Builder setting(@NonNull FormEntry entry, @NonNull Field<?, ?> field) {
            this.parts.add(new Part(entry.name(), field, null, null, id -> id, this.container, Takes.ALWAYS, false,
                false, entry));
            return this;
        }

        /**
         * Declares entries only a create takes, from which the writer fills the record's own columns before it is
         * written (a protected path's list made from the people given); it runs whether or not one was sent.
         */
        public @NonNull Builder fillsOnCreate(@NonNull List<? extends FormEntry> entries, @NonNull Fill writer) {
            this.fillEntries.addAll(entries);
            this.fill = writer;
            return this;
        }

        /**
         * Declares an enum setting only a create takes, given by {@code argument} and its member by a plain word as
         * {@link #byWord(EnumField)} takes one, where the field's own input name says less than the argument (a
         * device's {@code type} is its {@code kind}).
         */
        public @NonNull Builder createOnlyByWord(@NonNull String argument, @NonNull EnumField field) {
            this.add(argument, field, Takes.CREATE, true);
            return this;
        }

        /** Declares settings only a create takes: a change naming one is refused by name. */
        public @NonNull Builder createOnly(@NonNull List<? extends Field<?, ?>> fields) {
            for (Field<?, ?> field : fields) this.add(field.getInputName(), field, Takes.CREATE, false);
            return this;
        }

        /**
         * Declares a linked record, named by its code under {@code argument} and written into the column as its id.
         *
         * @param createOnly whether only a create takes it
         */
        public @NonNull Builder link(@NonNull String argument, @NonNull Microcopy help, @NonNull Field<?, ?> column,
                                     @NonNull Identifier linked, boolean createOnly) {
            return this.link(argument, help, column, linked, createOnly, id -> id);
        }

        /**
         * Declares a linked record written into the column as {@code stored} spells its id (a registry key).
         *
         * @param createOnly whether only a create takes it
         */
        public @NonNull Builder link(@NonNull String argument, @NonNull Microcopy help, @NonNull Field<?, ?> column,
                                     @NonNull Identifier linked, boolean createOnly,
                                     @NonNull Function<String, Object> stored) {
            this.parts.add(new Part(argument, column, linked, help, stored, this.container,
                createOnly ? Takes.CREATE : Takes.ALWAYS, false, false, null));
            return this;
        }

        /**
         * Declares the record's fixed parent, named by its code under {@code argument}: a create is made under it as
         * a create form opened under it is, and a change never takes it.
         *
         * @param column the child's parent field, the path its refusals name
         */
        public @NonNull Builder parent(@NonNull String argument, @NonNull Microcopy help, @NonNull Field<?, ?> column,
                                       @NonNull Identifier linked) {
            this.parts.add(new Part(argument, column, linked, help, id -> id, this.container, Takes.CREATE, false,
                true, null));
            return this;
        }

        private void add(@NonNull String argument, @NonNull Field<?, ?> field, @NonNull Takes takes, boolean byWord) {
            this.parts.add(new Part(argument, field, null, null, id -> id, this.container, takes, byWord, false,
                null));
        }

        /** Declares entries a change takes that the writer handles, after the record's own settings were written. */
        public @NonNull Builder also(@NonNull List<? extends FormEntry> entries, @NonNull Also writer) {
            this.alsoEntries.addAll(entries);
            this.also = writer;
            return this;
        }

        /** Declares that a create takes the {@link #also} entries too, the writer handling them once it is created. */
        public @NonNull Builder alsoOnCreate(@NonNull AlsoCreated writer) {
            this.alsoCreated = writer;
            return this;
        }

        /** Declares the command the change runs as, outside the receipt's transaction for a writer reaching a host. */
        public @NonNull Builder changesAs(@NonNull OperationCommand changes) {
            this.changes = changes;
            return this;
        }
        /**
         * Runs the create outside the receipt's transaction, for a resource whose create owns its write envelope and
         * reaches a host after it (a host's enrolment spends its trust token on the daemon).
         */
        public @NonNull Builder createsOutsideTransaction() {
            this.creates = CREATES.execution(CommandExecution.OUTSIDE_TRANSACTION);
            return this;
        }

        /**
         * Declares the record set by its natural key under its parent instead of made by a create and a change: one
         * operation {@code set_<word>} on the parent creates the record its keys name, or changes it. The keys are
         * settings a call always gives, declared here; the other settings follow.
         *
         * @param column the record's field naming its parent
         */
        public @NonNull Builder keyedUnder(@NonNull SubjectType<Row> parent, @NonNull Field<?, ?> column,
                                           @NonNull List<? extends Field<?, ?>> keys) {
            this.keyedUnder = parent;
            this.keyedColumn = column;
            this.keys.addAll(keys);
            return this.settings(keys);
        }

        /**
         * Declares that a keyed set takes {@code remove}, deleting the record its keys name as its delete button does.
         *
         * @param help what removing it does
         */
        public @NonNull Builder removable(@NonNull Microcopy help) {
            this.remove = BooleanField.builder("remove").defaultValue(false).help(help).build();
            return this;
        }

        /** @return the declared operations, their handlers attached */
        public @NonNull PanelRecordWrites build() {
            return new PanelRecordWrites(this);
        }
    }
}
