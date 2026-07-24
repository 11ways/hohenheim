package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SpamWordEntry;
import be.elevenways.spamservice.client.SpamWordInput;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Remote Spamservice spam-word dictionary CRUD. */
public final class SpamserviceWordsResource extends SpamserviceRemoteResource<SpamWordEntry> {

    public static final String SLUG = "spamservice-words";
    private static final Schema SCHEMA = new Schema();
    private static final StringField WORD = SCHEMA.addField(StringField.builder("word").required()
        .label(Microcopy.of("word").withFilter("scope", "spamservice_word")).build());
    private static final IntegerField SCORE = SCHEMA.addField(IntegerField.builder("score").required()
        .label(Microcopy.of("score").withFilter("scope", "spamservice_word")).build());
    private static final StringField LANGUAGE = SCHEMA.addField(StringField.builder("language")
        .label(Microcopy.of("language").withFilter("scope", "spamservice_word")).build());
    private static final BooleanField LEET = SCHEMA.addField(BooleanField.builder("leet").defaultValue(false)
        .label(Microcopy.of("leet").withFilter("scope", "spamservice_word")).build());
    private final FormSpec formSpec = FormSpec.builder().add(WORD).add(SCORE).add(LANGUAGE).add(LEET).build();

    public SpamserviceWordsResource() {}

    SpamserviceWordsResource(Supplier<SpamserviceClient> clientSupplier) { super(clientSupplier); }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "spamservice_word"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "spamservice_word"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Schema schema() { return SCHEMA; }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 60; }
    @Override public @NonNull Icon icon() { return Icon.of("book"); }

    @Override
    public @NonNull TableSpec<SpamWordEntry> tableSpec() {
        return TableSpec.<SpamWordEntry>builder()
            .column(ColumnSpec.fromField(WORD).build()).column(ColumnSpec.fromField(SCORE).build())
            .column(ColumnSpec.fromField(LANGUAGE).build()).column(ColumnSpec.fromField(LEET).build())
            .filter(FilterSpec.global("q", Microcopy.of("search").withFilter("scope", "spamservice"),
                FilterSpec.Kind.TEXT).build()).build();
    }

    @Override
    protected @NonNull PageResult<SpamWordEntry> fetchPage(@NonNull SpamserviceClient client,
                                                            TableView.@NonNull Applied<SpamWordEntry> applied) {
        return client.spamWords(applied.page(), applied.schema().pageSize(), textFilter(applied, "q"));
    }

    @Override public @NonNull String rowKey(@NonNull SpamWordEntry row) { return row.id(); }
    @Override public @Nullable Object parsePrimaryKey(@NonNull String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
    @Override public @Nullable SpamWordEntry loadRow(@NonNull Object key, @NonNull AccessContext context) {
        return this.requireClient().spamWord(String.valueOf(key));
    }
    @Override public @NonNull Map<String, Object> valuesFromRow(@NonNull SpamWordEntry row) {
        return Map.of("word", row.word(), "score", row.score(), "language", value(row.language()), "leet", row.leet());
    }
    @Override public @NonNull Object persistRow(@NonNull Map<String, Object> values, @NonNull AccessContext context) {
        return this.requireClient().createSpamWord(input(values));
    }
    @Override public void updateRow(@NonNull SpamWordEntry row, @NonNull Map<String, Object> values,
                                    @NonNull AccessContext context) {
        this.requireClient().updateSpamWord(row.id(), input(values));
    }
    @Override public void deleteRow(@NonNull SpamWordEntry row, @NonNull AccessContext context) {
        this.requireClient().deleteSpamWord(row.id());
    }

    @Override
    public @Nullable Object cellValue(@NonNull SpamWordEntry row, @NonNull ColumnSpec column) {
        return switch (column.name()) {
            case "word" -> row.word();
            case "score" -> row.score();
            case "language" -> row.language();
            case "leet" -> row.leet();
            default -> null;
        };
    }

    @Override public @Nullable String recordTitle(@NonNull SpamWordEntry row) { return row.word(); }

    private static SpamWordInput input(Map<String, Object> values) {
        String language = String.valueOf(values.getOrDefault("language", "")).trim();
        return new SpamWordInput(String.valueOf(values.getOrDefault("word", "")),
            values.get("score") instanceof Number number ? number.intValue() : 0,
            language.isEmpty() ? null : language, Boolean.TRUE.equals(values.get("leet")));
    }
    private static String value(@Nullable String value) { return value != null ? value : ""; }
}
