package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.activity.LegacyFacts;
import be.elevenways.zenit.common.orm.activity.M015_ConvertActivity;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;
import java.util.Set;

/**
 * Finishes core M015's conversion of Hohenheim's history: the verbs Hohenheim once stored for a plain create, update
 * or delete become the converted change alone, no fact.
 *
 * AIDEV-NOTE: until history v3 Hohenheim registered its own spellings "created", "updated" and "deleted" as legacy
 * keys of core's CREATE, UPDATE and DELETE verbs. Those keys are gone, so core M015 keeps such a row as a
 * {@code legacy_kind} fact, and the change's op already says what happened. Only a row of a Hohenheim model is
 * Hohenheim's: another module stored the same bare spellings for rows of its own ({@code zenit:settings}'s
 * "updated", its changed paths the detail), which that module's conversion reads. Every write goes through
 * {@link LegacyFacts}, so the provenance columns stay in step; a rerun finds no legacy fact left to convert.
 * Irreversible (W4): its rollback is the backup taken before the upgrade.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class M018_ConvertHohenheimHistory extends HohenheimMigration {

    /** The verbs Hohenheim stored for a plain row write, as they are stored. */
    static final Set<String> PLAIN_ROW_VERBS = Set.of("created", "updated", "deleted");

    private static final String EVENT = "hohenheim.migration.convert_hohenheim_history";

    private static final String IRREVERSIBLE = "the conversion folds rows into actions; its rollback is the backup"
        + " taken before the upgrade";

    public M018_ConvertHohenheimHistory() {
        super("018", "Convert Hohenheim's history");
        dependsOn(M015_ConvertActivity.class);
        irreversible(IRREVERSIBLE);
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.data("turn Hohenheim's plain row verbs into their changes", "1",
            M018_ConvertHohenheimHistory::plainRowVerbs);
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        // Irreversible (declared in the constructor): the runner refuses a rollback before this is reached.
    }

    /** @return the legacy facts converted */
    public static int plainRowVerbs(@NonNull Datasource datasource) {
        int converted = LegacyFacts.convert(datasource, fact -> PLAIN_ROW_VERBS.contains(fact.kind())
            && hohenheims(fact.model()) ? LegacyFacts.Outcome.copy() : null);
        Blast.slog(EVENT, Map.of("converted", converted));
        return converted;
    }

    /** @return whether the stored model token names one of Hohenheim's own models */
    private static boolean hohenheims(@NonNull String model) {
        Identifier id = Identifier.tryParse(model);
        return id != null && HohenheimIds.PRODUCT.namespace().equals(id.getNamespace());
    }
}
