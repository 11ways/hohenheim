package be.elevenways.hohenheim.test.history;

import be.elevenways.hohenheim.server.orm.HistorySecretPurge;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityPolicy;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.revision.RevisionModel;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retroactive half of secret redaction, end to end: history written while a field was
 * still PLAIN is found, reported without ever naming a value, rewritten out of both history
 * tables, and left alone on the second pass.
 *
 * AIDEV-NOTE: every plaintext row here is written by the REAL RevisionableBehaviour and the
 * REAL ActivityLog, with the fields still plain, and the declaration is flipped afterwards
 * (see ToggleSecretField). A hand-built history row would encode whatever this test imagined
 * the writers do, and would therefore prove nothing about the rows on a real install -- which
 * is the only reason the purge exists.
 */
class HistorySecretPurgeTest {

    private static final String PLANTED_TOP = "hh-history-top-credential-A1";
    private static final String PLANTED_NESTED = "hh-history-nested-credential-B2";
    private static final String KEPT_TOP = "an ordinary label";
    private static final String KEPT_NESTED = "an ordinary nested value";
    private static final String GHOST_MODEL = "uninstalled:ghost";

    @Test
    void preRedactionSecretsAreSurveyedThenRewrittenOutOfRevisionsAndActivity() throws Exception {

        SqliteDatasource datasource = emptyDatabase();
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        datasource.rawUpdate("CREATE TABLE " + HistoryFixtureModel.TABLE
            + " (id INTEGER PRIMARY KEY AUTOINCREMENT, label TEXT, token TEXT, kind TEXT, config TEXT)");

        HistoryFixtureModel fixture = new HistoryFixtureModel(datasource);
        Models.registerInstance(fixture);
        ActivityLog.setPolicy(HistoryFixtureModel.MODEL_ID, ActivityPolicy.ALL);
        ActivityLog.install();
        ToggleSecretField.declareSecret(false);

        try {
            // 1. PLANT. The fields are still plain, so the real writers store the real
            //    plaintext -- the exact situation every install that predates a .secret()
            //    declaration is in. Two saves, so history holds a create AND an update.
            Db.run(datasource, () -> {
                Model model = Models.get(HistoryFixtureModel.class);
                Row row = model.createEmptyRow();
                row.set(HistoryFixtureModel.LABEL, KEPT_TOP);
                row.set(HistoryFixtureModel.TOKEN, PLANTED_TOP);
                row.set(HistoryFixtureModel.KIND, "alpha");
                row.set(HistoryFixtureModel.CONFIG, config(KEPT_NESTED, PLANTED_NESTED));
                model.save(row);

                row.set(HistoryFixtureModel.LABEL, KEPT_TOP + " (edited)");
                model.save(row);
            });

            assertThat(snapshots(datasource))
                .as("step 1: the real behaviour wrote a revision per save")
                .hasSize(2);
            assertThat(String.join("\n", snapshots(datasource)))
                .as("step 1: history holds BOTH planted credentials in cleartext")
                .contains(PLANTED_TOP).contains(PLANTED_NESTED).contains(KEPT_NESTED);
            // The create delta carries every field; the update delta carries only what
            // CHANGED, which here is the ordinary label. That second row is the control:
            // it must come out of the purge byte-identical.
            List<String> plantedDeltas = deltas(datasource);
            assertThat(plantedDeltas)
                .as("step 1: the real activity log wrote one delta per save").hasSize(2);
            assertThat(plantedDeltas.get(0))
                .as("step 1: the create delta holds the same cleartext")
                .contains(PLANTED_TOP).contains(PLANTED_NESTED);
            String innocentDelta = plantedDeltas.get(1);
            assertThat(innocentDelta)
                .as("step 1: the update delta changed only the label, so it holds no credential")
                .contains(KEPT_TOP).doesNotContain(PLANTED_TOP).doesNotContain(PLANTED_NESTED);

            // 2. The declaration moves: both fields are secret from now on. Nothing rewrites
            //    the rows above -- that forward-only stance is the defect being fixed.
            ToggleSecretField.declareSecret(true);
            assertThat(HistoryFixtureModel.TOKEN.isSecret())
                .as("step 2: the top-level field is secret now").isTrue();
            assertThat(HistoryFixtureModel.ALPHA_TOKEN.isSecret())
                .as("step 2: the nested sub-field is secret now").isTrue();

            // 3. SURVEY: names the locations, writes NOTHING, and prints no value.
            HistorySecretPurge.Report survey = HistorySecretPurge.survey(datasource);
            assertThat(survey.applied()).as("step 3: a survey never claims to have written").isFalse();
            assertThat(survey.revisionRowsAffected())
                .as("step 3: both revisions would be rewritten").isEqualTo(2);
            assertThat(survey.activityRowsAffected())
                .as("step 3: ONLY the delta that carries a credential would be rewritten")
                .isEqualTo(1);
            assertThat(paths(survey, HistorySecretPurge.TABLE_REVISIONS))
                .as("step 3: the survey names the secret column AND the secret-bearing carrier")
                .contains(HistoryFixtureModel.TOKEN.getName(), HistoryFixtureModel.CONFIG.getName());
            assertThat(paths(survey, HistorySecretPurge.TABLE_ACTIVITY))
                .as("step 3: the delta side is named too")
                .contains(HistoryFixtureModel.TOKEN.getName(), HistoryFixtureModel.CONFIG.getName());
            assertNoValueLeaks(survey, "step 3");
            assertThat(String.join("\n", snapshots(datasource)))
                .as("step 3: a survey is READ-ONLY -- the history is untouched")
                .contains(PLANTED_TOP).contains(PLANTED_NESTED);

            // 4. PURGE. Values are rewritten IN PLACE: the row count does not move.
            long revisionsBefore = count(datasource, "zenit_revisions");
            long activityBefore = count(datasource, "zenit_activity");
            HistorySecretPurge.Report purge = HistorySecretPurge.purge(datasource);
            assertThat(purge.applied()).as("step 4: the purge reports itself as a write").isTrue();
            assertThat(purge.revisionRowsAffected()).as("step 4: both revisions rewritten").isEqualTo(2);
            assertThat(purge.activityRowsAffected())
                .as("step 4: only the credential-bearing delta rewritten").isEqualTo(1);
            assertNoValueLeaks(purge, "step 4");

            // 5. THE assertion: the credentials are gone from the stored bytes, and the
            //    non-secret values that shared those very rows are still there.
            String storedSnapshots = String.join("\n", snapshots(datasource));
            String storedDeltas = String.join("\n", deltas(datasource));
            assertThat(storedSnapshots)
                .as("step 5: no planted credential survives in zenit_revisions")
                .doesNotContain(PLANTED_TOP).doesNotContain(PLANTED_NESTED);
            assertThat(storedDeltas)
                .as("step 5: no planted credential survives in zenit_activity")
                .doesNotContain(PLANTED_TOP).doesNotContain(PLANTED_NESTED);
            assertThat(storedSnapshots)
                .as("step 5: the non-secret values in the same rows are untouched")
                .contains(KEPT_TOP).contains(KEPT_NESTED);
            assertThat(storedDeltas)
                .as("step 5: the delta still records the ordinary change it recorded before")
                .contains(KEPT_TOP);
            assertThat(deltas(datasource).get(1))
                .as("step 5: the delta that carried no credential is byte-identical afterwards")
                .isEqualTo(innocentDelta);
            assertThat(count(datasource, "zenit_revisions"))
                .as("step 5: REWRITTEN, not deleted -- every revision row is still there")
                .isEqualTo(revisionsBefore);
            assertThat(count(datasource, "zenit_activity"))
                .as("step 5: REWRITTEN, not deleted -- every activity row is still there")
                .isEqualTo(activityBefore);

            // 6. The rewritten history is still history: the revision keeps its position and
            //    its other fields, so a diff across it still reads.
            Db.run(datasource, () -> {
                Map<String, Object> restored = HistoryFixtureModel.REVISIONABLE
                    .snapshotOf(Models.get(HistoryFixtureModel.class), 1, 1);
                assertThat(restored)
                    .as("step 6: the ordinary column survives the rewrite")
                    .containsEntry(HistoryFixtureModel.LABEL.getName(), KEPT_TOP);
                assertThat(restored)
                    .as("step 6: the secret column is simply absent, as a snapshot written today would be")
                    .doesNotContainKey(HistoryFixtureModel.TOKEN.getName());
            });

            // 7. IDEMPOTENT: the rewrite converged on what the writers produce today, so a
            //    second run has nothing left to do.
            HistorySecretPurge.Report again = HistorySecretPurge.purge(datasource);
            assertThat(again.revisionRowsAffected())
                .as("step 7: the second purge rewrites no revision").isZero();
            assertThat(again.activityRowsAffected())
                .as("step 7: the second purge rewrites no activity row").isZero();
            assertThat(again.isClean())
                .as("step 7: and it reports nothing to do at all").isTrue();

            // 8. WHAT IT CANNOT REACH. A history row whose model left the build cannot be
            //    judged key by key, so the purge refuses to guess: it reports the row, leaves
            //    it byte-identical, and declares itself INCOMPLETE rather than successful.
            //    AIDEV-NOTE: this ONE row is written directly, because "a model that is no
            //    longer on the classpath" cannot, by definition, be produced by code that is.
            //    Its snapshot is a real one lifted off the fixture, so only the discriminator
            //    is synthetic.
            String ghostSnapshot = ghostRow(datasource);
            HistorySecretPurge.Report withGhost = HistorySecretPurge.purge(datasource);
            assertThat(withGhost.hasUnreachable())
                .as("step 8: an unresolvable model is reported, never silently skipped").isTrue();
            assertThat(withGhost.of(HistorySecretPurge.Reason.UNREACHABLE))
                .as("step 8: the report names the model it could not resolve")
                .anyMatch(finding -> finding.modelId().equals(GHOST_MODEL));
            assertThat(String.join("\n", withGhost.describe()))
                .as("step 8: and the operator is told the purge did not finish the job")
                .contains("INCOMPLETE");
            assertThat(snapshots(datasource))
                .as("step 8: the row it could not judge is left exactly as it was")
                .contains(ghostSnapshot);
            assertNoValueLeaks(withGhost, "step 8");

        } finally {
            ToggleSecretField.declareSecret(false);
            ActivityLog.uninstall();
            ActivityLog.setPolicy(HistoryFixtureModel.MODEL_ID, ActivityPolicy.ACTIVITY);
            Models.unregisterInstance(fixture);
            datasource.close();
        }
    }

    // -- helpers --------------------------------------------------------------

    private static Map<String, Object> config(String keep, String token) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(HistoryFixtureModel.ALPHA_KEEP.getName(), keep);
        config.put(HistoryFixtureModel.ALPHA_TOKEN.getName(), token);
        return config;
    }

    /**
     * A revision filed under a model this build does not have, carrying a REAL snapshot.
     *
     * @return the stored snapshot text, so the caller can prove it was not touched
     */
    private static String ghostRow(SqliteDatasource datasource) {
        String[] stored = new String[1];
        Db.run(datasource, () -> {
            RevisionModel revisions = new RevisionModel(datasource);
            Row source = revisions.find().orderBy(RevisionModel.ID, SortOrder.ASC).first();
            Row ghost = revisions.createEmptyRow();
            ghost.set(RevisionModel.MODEL, GHOST_MODEL);
            ghost.set(RevisionModel.RECORD_ID, "1");
            ghost.set(RevisionModel.REVISION, 1);
            ghost.set(RevisionModel.SNAPSHOT, source.get(RevisionModel.SNAPSHOT));
            ghost.set(RevisionModel.CREATED_AT, Instant.now());
            revisions.save(ghost);
            stored[0] = ghost.get(RevisionModel.SNAPSHOT);
        });
        return stored[0];
    }

    /** Every field path the report named for one table. */
    private static List<String> paths(HistorySecretPurge.Report report, String table) {
        return report.findings().stream()
            .filter(finding -> finding.table().equals(table))
            .map(HistorySecretPurge.Finding::path)
            .toList();
    }

    /**
     * The contract that makes the survey safe to run on production and safe to paste into a
     * ticket: not one line of it, anywhere, may contain a stored value.
     */
    private static void assertNoValueLeaks(HistorySecretPurge.Report report, String step) {
        String printed = String.join("\n", report.describe());
        assertThat(printed).as(step + ": the report never prints a secret").doesNotContain(PLANTED_TOP);
        assertThat(printed).as(step + ": not the nested one either").doesNotContain(PLANTED_NESTED);
        assertThat(printed).as(step + ": and not the ordinary stored values either")
            .doesNotContain(KEPT_NESTED);
        for (HistorySecretPurge.Finding finding : report.findings()) {
            assertThat(finding.describe())
                .as(step + ": no finding carries a value")
                .doesNotContain(PLANTED_TOP).doesNotContain(PLANTED_NESTED)
                .doesNotContain(KEPT_NESTED);
        }
    }

    private static List<String> snapshots(SqliteDatasource datasource) {
        return column(datasource, "SELECT snapshot AS v FROM zenit_revisions ORDER BY id");
    }

    private static List<String> deltas(SqliteDatasource datasource) {
        return column(datasource,
            "SELECT delta AS v FROM zenit_activity WHERE delta IS NOT NULL ORDER BY id");
    }

    private static List<String> column(SqliteDatasource datasource, String sql) {
        List<String> values = new ArrayList<>();
        for (Row row : datasource.rawQuery(sql)) {
            values.add(String.valueOf(row.get("v")));
        }
        return values;
    }

    private static long count(SqliteDatasource datasource, String table) {
        return ((Number) datasource.rawQuery("SELECT COUNT(*) AS c FROM " + table)
            .get(0).get("c")).longValue();
    }

    private static SqliteDatasource emptyDatabase() throws Exception {
        File db = File.createTempFile("hohenheim-history-purge", ".db");
        db.delete();
        db.deleteOnExit();
        return new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
    }
}
