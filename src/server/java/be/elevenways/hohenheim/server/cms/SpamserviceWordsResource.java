package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
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
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Remote Spamservice spam-word dictionary CRUD, a store entry over the management API.
 *
 * The list search is forwarded to the management API's own {@code q} search (its word match), never evaluated here.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SpamserviceWordsResource {

    public static final String SLUG = "spamservice-words";
    static final Identifier ID = HohenheimIds.id("spamservice_word");
    static final SubjectType<SpamWordEntry> WORD_ENTRY = SubjectType.of(ID, SpamWordEntry.class, SpamWordEntry::id);

    private static final StringField WORD = StringField.builder("word").required()
        .label(Microcopy.of("word").withFilter("scope", "spamservice_word")).build();
    private static final IntegerField SCORE = IntegerField.builder("score").required()
        .label(Microcopy.of("score").withFilter("scope", "spamservice_word")).build();
    private static final StringField LANGUAGE = StringField.builder("language")
        .label(Microcopy.of("language").withFilter("scope", "spamservice_word")).build();
    private static final BooleanField LEET = BooleanField.builder("leet").defaultValue(false)
        .label(Microcopy.of("leet").withFilter("scope", "spamservice_word")).build();

    /** The fields the management API answers for one spam word. */
    private static final List<Field<?, ?>> FIELDS = List.of(WORD, SCORE, LANGUAGE, LEET);

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
        return PanelResource.builder(ID, SLUG, WORD_ENTRY)
            .label(Microcopy.of("plural").withFilter("scope", "spamservice_word"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "spamservice_word"))
            .navGroup(HohenheimPanel.SECURITY_GROUP)
            .navOrder(60)
            .showInNav(false)
            .icon(Icon.of("book"))
            .reads(ResourceReads.<SpamWordEntry>typed(SpamWordEntry::id)
                .load((key, access) -> {
                    UUID id = SpamserviceRemoteStore.uuidOrNull(key);
                    return id == null ? null : SpamserviceRemoteStore.require(clients).spamWord(id.toString());
                })
                .values(SpamserviceWordsResource::values)
                .cells(SpamserviceWordsResource::cell)
                .build()
                .title(SpamWordEntry::word))
            .list(ResourceList.store(table, SpamserviceRemoteStore.pages(ID, clients, FIELDS, List.of("word"),
                    (client, applied, access) -> client.spamWords(applied.page(), applied.schema().pageSize(),
                        applied.searchTerm())))
                .search("word")
                .chrome(ListChrome.MINIMAL)
                .notice(SpamserviceRemoteStore.notice(ID, clients))
                .build())
            .form(ResourceForm.<SpamWordEntry>of(FormSpec.builder().add(WORD).add(SCORE).add(LANGUAGE).add(LEET)
                .build()).build())
            .writes(ResourceMutations.<SpamWordEntry>store()
                .create((values, access) -> SpamserviceRemoteStore.require(clients).createSpamWord(input(values, null)))
                .update((word, values, access) -> SpamserviceRemoteStore.require(clients)
                    .updateSpamWord(word.id(), input(values, word)))
                .delete((word, access) -> SpamserviceRemoteStore.require(clients).deleteSpamWord(word.id()))
                .build())
            .build();
    }

    private static @NonNull Map<String, Object> values(@NonNull SpamWordEntry row) {
        return Map.of("word", row.word(), "score", row.score(), "language",
            SpamserviceRemoteStore.orBlank(row.language()), "leet", row.leet());
    }

    private static @Nullable Object cell(@NonNull SpamWordEntry row, @NonNull ColumnSpec column) {
        return switch (column.name()) {
            case "word" -> row.word();
            case "score" -> row.score();
            case "language" -> row.language();
            case "leet" -> row.leet();
            default -> null;
        };
    }

    /**
     * The whole remote word, filled from the STORED entry wherever this write carries no value for a field.
     *
     * AIDEV-NOTE: the remote update is a full-DTO PUT, and the inline cell lane hands the update a map holding EXACTLY
     * ONE entry. Building the DTO off that map alone pushed a blank word, a zero score and leet=false to the LIVE
     * filter on any single edit. The stored record is the fallback here rather than a remote merge semantic, which the
     * service's own API does not promise.
     *
     * @param stored the entry being edited, or null on a create
     */
    private static SpamWordInput input(Map<String, Object> values, @Nullable SpamWordEntry stored) {
        String word = SpamserviceRemoteStore.requiredText(values, "word", stored == null ? "" : stored.word());
        Object score = values.getOrDefault("score", stored == null ? null : stored.score());
        Object rawLanguage = values.getOrDefault("language", stored == null ? null : stored.language());
        Object leet = values.getOrDefault("leet", stored == null ? null : stored.leet());
        String language = rawLanguage != null ? String.valueOf(rawLanguage).trim() : "";
        return new SpamWordInput(word,
            score instanceof Number number ? number.intValue() : 0,
            language.isEmpty() ? null : language, Boolean.TRUE.equals(leet));
    }
}
