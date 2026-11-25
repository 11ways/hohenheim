package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SpamWordEntry;
import be.elevenways.spamservice.client.SpamWordInput;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.PatchBase;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * Remote Spamservice spam-word dictionary CRUD, a store entry over the management API.
 *
 * The list search is forwarded to the management API's own {@code q} search (its word match), never evaluated here.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SpamserviceWordsResource {

    static final Identifier ID = HohenheimIds.id("spamservice_word");
    static final SubjectType<SpamWordEntry> WORD_ENTRY = SubjectType.of(ID, SpamWordEntry.class, SpamWordEntry::id);

    private static final StringField WORD = StringField.builder("word").required()
        .label(HohenheimMicrocopy.SPAMSERVICE_WORD.of("word"))
        .help(HohenheimMicrocopy.HELP.of("spam_word_word")).build();
    private static final IntegerField SCORE = IntegerField.builder("score").required()
        .label(HohenheimMicrocopy.SPAMSERVICE_WORD.of("score")).build();
    private static final StringField LANGUAGE = StringField.builder("language")
        .label(HohenheimMicrocopy.SPAMSERVICE_WORD.of("language")).build();
    private static final BooleanField LEET = BooleanField.builder("leet").defaultValue(false)
        .label(HohenheimMicrocopy.SPAMSERVICE_WORD.of("leet"))
        .help(HohenheimMicrocopy.HELP.of("spam_word_leet")).build();

    /** The fields the management API answers for one spam word. */
    private static final List<Field<?, ?>> FIELDS = List.of(WORD, SCORE, LANGUAGE, LEET);

    /** A word's language as a set names it: part of its key, so one word may score differently per language. */
    private static final StringField WORD_LANGUAGE = StringField.builder("word_language")
        .label(HohenheimMicrocopy.SPAMSERVICE_WORD.of("language"))
        .help(HohenheimMicrocopy.HELP.of("spam_word_language")).build();

    /** A word's score as a set takes it: needed to add a word, left out to keep a stored word's. */
    private static final IntegerField SET_SCORE = IntegerField.builder("score")
        .label(HohenheimMicrocopy.SPAMSERVICE_WORD.of("score"))
        .help(HohenheimMicrocopy.HELP.of("spam_word_score")).build();

    private static final BooleanField REMOVE = BooleanField.builder("remove").defaultValue(false)
        .help(HohenheimMicrocopy.HELP.of("spam_word_remove")).build();

    /** The most words one page of the management API's word search answers. */
    private static final int SEARCH_PAGE = 100;

    /**
     * Adds, changes or removes the word its text and language name, through the management API the dictionary page
     * writes through; a patch, so a change leaves what it does not send as stored.
     *
     * AIDEV-NOTE: the service keeps no word and language unique, so a key naming several entries is refused rather
     * than changing one of them at random.
     */
    public static final Operation<Void, OperationInput.Values, String> SET =
            Operation.declare(HohenheimIds.id("set_spamservice_word"))
        .happened(OperationSentences.of("set_spamservice_word"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("set_spamservice_word"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("set_spamservice_word_description"))
        .noSubject()
        .gate(HohenheimSources.OPERATOR)
        .input(OperationInput.derived(FormSpec.builder().add(Plain.of(WORD)).add(Plain.of(WORD_LANGUAGE))
            .add(Plain.of(SET_SCORE)).add(Plain.of(LEET)).add(Plain.of(REMOVE)).build()))
        .result(String.class)
        .patchable()
        .facts(OperationFact.IDEMPOTENT, OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    static {
        // No stored entry is the base (the word and its language name it), so a change writes what it sent.
        OperationHandlers.attach(SET)
            .availability((none, access) -> SpamserviceOperations.connectedReason(SpamserviceManager.get()))
            .patchBase(PatchBase.ofValues(SET, (none, access) -> Map.of()))
            .handle(call -> SpamserviceRemoteStore.answered(() -> set(
                SpamserviceRemoteStore.require(SpamserviceRemoteStore.MANAGED), Objects.requireNonNull(call.input()),
                call.patched())));
    }

    private SpamserviceWordsResource() {
    }

    /** @return the entry over the managed runtime's client */
    public static @NonNull PanelResource<SpamWordEntry> create() {
        return create(SpamserviceRemoteStore.MANAGED);
    }

    static @NonNull PanelResource<SpamWordEntry> create(@NonNull Supplier<SpamserviceClient> clients) {
        SpamserviceRemoteStore.requireNonNull(clients);
        TableSpec<SpamWordEntry> table = TableSpec.<SpamWordEntry>builder()
            .column(ColumnSpec.fromField(WORD).build()).column(ColumnSpec.fromField(SCORE).build())
            .column(ColumnSpec.fromField(LANGUAGE).build()).column(ColumnSpec.fromField(LEET).build()).build();
        return PanelResource.builder(ID, HohenheimSlugs.SPAMSERVICE_WORDS, WORD_ENTRY)
            .label(HohenheimMicrocopy.SPAMSERVICE_WORD.of("plural"))
            .recordLabel(HohenheimMicrocopy.SPAMSERVICE_WORD.of("singular"))
            .navGroup(HohenheimPanel.SECURITY_GROUP)
            .navOrder(60)
            .showInNav(false)
            .standsUnder(HohenheimSlugs.SPAMSERVICE)
            .icon(Icon.of("book"))
            .reads(ResourceReads.<SpamWordEntry>typed(SpamWordEntry::id)
                .load((key, access) -> {
                    UUID id = SpamserviceRemoteStore.uuidOrNull(key);
                    return id == null ? null : SpamserviceRemoteStore.require(clients).spamWord(id.toString());
                })
                .values(SpamserviceWordsResource::values)
                .build()
                .title(entry -> Microcopy.literalOrNull(entry.word())))
            .list(ResourceList.store(table, SpamserviceRemoteStore.pages(ID, clients, FIELDS, List.of("word"),
                    (client, applied, access) -> client.spamWords(applied.page(), applied.schema().pageSize(),
                        applied.searchTerm())), HohenheimSources.SPAMSERVICE_SYSTEM_USER_SOURCE.liveFeed())
                .search("word")
                .chrome(ListChrome.MINIMAL)
                .notice(SpamserviceRemoteStore.notice(ID, clients))
                .build())
            .form(ResourceForm.<SpamWordEntry>of(FormSpec.builder().add(WORD).add(SCORE).add(LANGUAGE).add(LEET)
                .build()).build())
            .writes(ResourceMutations.<SpamWordEntry>store()
                .create((values, access) -> SpamserviceRemoteStore.require(clients)
                    .createSpamWord(input(new OperationInput.Values(values))))
                .update((word, values, access) -> SpamserviceRemoteStore.require(clients)
                    .updateSpamWord(word.id(), input(values.merged())))
                .delete((word, access) -> SpamserviceRemoteStore.require(clients).deleteSpamWord(word.id()))
                .build())
            .build();
    }

    private static @NonNull Map<String, Object> values(@NonNull SpamWordEntry row) {
        return Map.of("word", Objects.requireNonNullElse(row.word(), ""), "score", row.score(), "language",
            Objects.requireNonNullElse(row.language(), ""), "leet", row.leet());
    }

    /**
     * The whole remote word, built from the record's merged values.
     *
     * AIDEV-NOTE: the remote update is a full-DTO PUT, and the inline cell lane hands the update a map holding EXACTLY
     * ONE entry: an update reads {@code merged()} (the stored entry with the submission over it), never the submitted
     * map alone, which once pushed a blank word, a zero score and leet=false to the LIVE filter on any single edit.
     */
    private static SpamWordInput input(OperationInput.@NonNull Values values) {
        String word = SpamserviceRemoteStore.requiredText(values, WORD.getName());
        String language = trimmed(values.get(LANGUAGE.getName()));
        return new SpamWordInput(word,
            RawValues.intOr(values.get(SCORE.getName()), 0),
            language.isEmpty() ? null : language, Boolean.TRUE.equals(values.get(LEET.getName())));
    }

    /** @return what became of the word: added, changed, removed, or absent */
    private static @NonNull String set(@NonNull SpamserviceClient client, OperationInput.@NonNull Values input,
                                       @Nullable Set<String> patched) {
        Set<String> sent = patched != null ? patched : input.coerced().keySet();
        String word = trimmed(input.get(WORD.getName()));
        String language = trimmed(input.get(WORD_LANGUAGE.getName()));
        List<SpamWordEntry> named = named(client, word, language.isEmpty() ? null : language);
        if (named.size() > 1) {
            throw Violations.ofField(WORD.getName(), word, HohenheimMicrocopy.VIOLATIONS.of("spam_word_duplicates")
                .withArg("word", word).withArg("count", named.size()));
        }
        SpamWordEntry found = named.isEmpty() ? null : named.getFirst();
        if (Boolean.TRUE.equals(input.get(REMOVE.getName()))) {
            if (found == null) return word + " absent: nothing removed";
            client.deleteSpamWord(found.id());
            return word + " removed";
        }
        Object score = input.get(SET_SCORE.getName());
        if (found == null) {
            if (score == null) {
                throw Violations.ofField(SET_SCORE.getName(), null,
                    HohenheimMicrocopy.VIOLATIONS.of("spam_word_score_needed"));
            }
            client.createSpamWord(new SpamWordInput(word, RawValues.intOr(score, 0), language.isEmpty() ? null
                : language, Boolean.TRUE.equals(input.get(LEET.getName()))));
            return word + " added";
        }
        // The remote update is a whole-entry write: what the call leaves out is the stored entry's.
        int kept = score != null ? RawValues.intOr(score, found.score()) : found.score();
        boolean leet = sent.contains(LEET.getName()) ? Boolean.TRUE.equals(input.get(LEET.getName())) : found.leet();
        client.updateSpamWord(found.id(), new SpamWordInput(word, kept, found.language(), leet));
        return word + " changed";
    }

    /** @return every dictionary entry of exactly this word in this language, null for every language */
    private static @NonNull List<SpamWordEntry> named(@NonNull SpamserviceClient client, @NonNull String word,
                                                      @Nullable String language) {
        List<SpamWordEntry> named = new ArrayList<>();
        for (int page = 1; ; page++) {
            PageResult<SpamWordEntry> found = client.spamWords(page, SEARCH_PAGE, word);
            for (SpamWordEntry entry : found.items()) {
                String stored = entry.language() == null || entry.language().isBlank() ? null
                    : entry.language().trim();
                if (word.equals(entry.word().trim()) && Objects.equals(language, stored)) named.add(entry);
            }
            if (found.items().size() < SEARCH_PAGE || (long) page * SEARCH_PAGE >= found.total()) return named;
        }
    }
}
