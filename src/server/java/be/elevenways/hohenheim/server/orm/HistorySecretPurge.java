package be.elevenways.hohenheim.server.orm;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.FieldRedaction;
import be.elevenways.zenit.common.orm.field.ListField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.revision.RevisionModel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Re-applies TODAY's redaction rules to history written BEFORE them: the retroactive
 * half of a secret declaration, which the framework's writers deliberately do not do.
 *
 * Redaction in this stack is forward-only by design -- {@code ActivityLog} states plainly
 * that "existing activity rows are not rewritten", and {@code RevisionableBehaviour} omits
 * secret fields from the snapshots it writes FROM NOW ON. Everything written while a field
 * was still plain is therefore sitting in {@code zenit_revisions} and {@code zenit_activity}
 * in cleartext, and nothing has ever gone back for it. This is that walk.
 *
 * SHAPE: values are REWRITTEN IN PLACE, never deleted, and the target form per table is
 * whatever that table's own writer would produce today -- the snapshot DROPS the value
 * (RevisionableBehaviour.snapshot omits redacted fields, so a restore keeps the record's
 * live value) and the delta MASKS both sides with the marker (ActivityLog.computeDelta
 * writes a redacted pair, so the entry still records THAT the value changed). Deleting the
 * rows was the alternative and it is strictly worse: a deleted revision cannot be diffed
 * across and takes every non-secret field of that save with it, and a deleted activity row
 * destroys the who/when/what that is the entire point of an audit log -- all to remove a
 * value that the rewrite provably removes anyway.
 *
 * AIDEV-NOTE: the transform is ALWAYS {@link FieldRedaction}, never a local re-derivation of
 * "what counts as secret". That class is THE rule every derived surface shares; a second
 * opinion living here would drift from it the first time the rule changes, and a purge that
 * disagrees with the writer is a purge that leaves credentials behind. The only policy
 * decision taken here is what to do with a value the rule CANNOT judge (see below).
 *
 * AIDEV-NOTE: this mechanism knows nothing about hohenheim -- it walks two CORE tables with
 * CORE redaction rules, and every app with a revisionable model or an ALL-policy activity
 * log has this exact problem. Its home is zenit core beside EncryptionRekey
 * ({@code server/orm/crypto}), which is the same survey-then-rewrite shape over stored
 * values. It lives here because the framework chain was frozen when it was written; promoting
 * it is a file move plus a package rename, and nothing in it needs to change.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class HistorySecretPurge {

    public static final String TABLE_REVISIONS = "zenit_revisions";
    public static final String TABLE_ACTIVITY = "zenit_activity";

    /** Snapshot envelope keys, mirroring RevisionableBehaviour. */
    private static final String FIELDS_KEY = "fields";
    private static final String TRANSLATIONS_KEY = "translations";

    /** Delta pair keys, mirroring ActivityLog.changePair. */
    private static final String BEFORE_KEY = "before";
    private static final String AFTER_KEY = "after";

    /** Rows read per query; the walk never holds a whole history table in memory. */
    private static final int BATCH = 500;

    /** Locations listed per bucket before the report collapses the rest into a count. */
    private static final int LISTED_PER_BUCKET = 50;

    private HistorySecretPurge() {
    }

    /**
     * Why one history location appears in the report.
     */
    public enum Reason {

        /** The current schema calls this value secret or encrypted; the rewrite removes it. */
        REDACTED,

        /**
         * The current schema cannot judge this value, so it is OVER-redacted: a field the
         * model no longer declares, or a JSON carrier with no resolvable sub-schema. Today's
         * writer would not have written it at all, and it may be a renamed or removed secret.
         */
        UNCLASSIFIABLE,

        /** Left completely untouched: nothing here can be judged, so nothing may be rewritten. */
        UNREACHABLE
    }

    /**
     * One history location. Carries the ROW and the FIELD PATH and never a value, not even a
     * redacted one -- a survey an operator runs on production must be safe to paste anywhere.
     */
    public record Finding(@NonNull String table, @NonNull String modelId, @NonNull String rowId,
                          @NonNull String path, @NonNull Reason reason, int nestedValues,
                          @NonNull String note) {

        /** @return the operator-facing line; field names and counts only */
        public @NonNull String describe() {
            StringBuilder line = new StringBuilder()
                .append(this.table).append(" #").append(this.rowId)
                .append("  ").append(this.modelId)
                .append("  ").append(this.path);
            if (this.nestedValues > 0) {
                line.append(" (").append(this.nestedValues).append(" nested value(s))");
            }
            if (!this.note.isBlank()) {
                line.append("  [").append(this.note).append("]");
            }
            return line.toString();
        }
    }

    /**
     * What one survey or purge saw. A purge that leaves any {@link Reason#UNREACHABLE}
     * finding behind is INCOMPLETE and says so; success is never inferred from the absence
     * of an exception.
     */
    public record Report(boolean applied,
                         int revisionRowsScanned, int activityRowsScanned,
                         int revisionRowsAffected, int activityRowsAffected,
                         @NonNull List<Finding> findings) {

        /** @return true when nothing in either history table needs anything done to it */
        public boolean isClean() {
            return this.findings.isEmpty();
        }

        /** @return true when rows were left untouched because they could not be judged */
        public boolean hasUnreachable() {
            return this.findings.stream().anyMatch(finding -> finding.reason() == Reason.UNREACHABLE);
        }

        public @NonNull List<Finding> of(@NonNull Reason reason) {
            return this.findings.stream().filter(finding -> finding.reason() == reason).toList();
        }

        /** @return the operator-facing report, one line per element; never contains a value */
        public @NonNull List<String> describe() {
            List<String> lines = new ArrayList<>();
            lines.add(this.applied
                ? "History secret PURGE -- rewrite applied."
                : "History secret SURVEY -- read-only, nothing was written.");
            lines.add("  Shape: values are REWRITTEN IN PLACE, never deleted. " + TABLE_REVISIONS
                + " drops the value from the snapshot (what RevisionableBehaviour writes today,"
                + " so a restore keeps the record's live value); " + TABLE_ACTIVITY
                + " masks both sides of the delta entry with " + FieldRedaction.REDACTED
                + " (what ActivityLog writes today, so the entry still records THAT it changed).");
            lines.add("  " + TABLE_REVISIONS + ": " + this.revisionRowsScanned + " row(s) scanned, "
                + this.revisionRowsAffected + (this.applied ? " rewritten" : " would be rewritten"));
            lines.add("  " + TABLE_ACTIVITY + ": " + this.activityRowsScanned
                + " row(s) with a delta scanned, " + this.activityRowsAffected
                + (this.applied ? " rewritten" : " would be rewritten"));

            appendBucket(lines, Reason.REDACTED,
                "Secret under the CURRENT schema (no values are ever printed):");
            appendBucket(lines, Reason.UNCLASSIFIABLE,
                "CANNOT CLASSIFY -- over-redacted, because today's writer would not write them at all:");
            appendBucket(lines, Reason.UNREACHABLE,
                "NOT REACHED -- left untouched; this purge is INCOMPLETE while these exist:");

            if (this.isClean()) {
                lines.add("  Nothing to do: no history value is secret under the current schema.");
            } else if (this.applied && this.hasUnreachable()) {
                lines.add("  INCOMPLETE: the NOT REACHED rows above still hold whatever they held."
                    + " Re-run with the owning module on the classpath, or remove those rows by hand.");
            }
            return lines;
        }

        private void appendBucket(@NonNull List<String> lines, @NonNull Reason reason,
                                  @NonNull String heading) {
            List<Finding> bucket = this.of(reason);
            if (bucket.isEmpty()) {
                return;
            }
            lines.add("  " + heading + " " + bucket.size() + " location(s)");
            for (int i = 0; i < bucket.size() && i < LISTED_PER_BUCKET; i++) {
                lines.add("    " + bucket.get(i).describe());
            }
            if (bucket.size() > LISTED_PER_BUCKET) {
                lines.add("    ... and " + (bucket.size() - LISTED_PER_BUCKET) + " more");
            }
        }
    }

    /**
     * Report what a purge WOULD do, writing nothing; safe to run against a live install.
     */
    public static @NonNull Report survey(@NonNull Datasource datasource) {
        return walk(datasource, false);
    }

    /**
     * Rewrite every history value the current schema calls secret, and every value it cannot
     * judge. Idempotent: a second run finds nothing, because the rewrite converges on exactly
     * what the writers produce today.
     */
    public static @NonNull Report purge(@NonNull Datasource datasource) {
        return walk(datasource, true);
    }

    private static @NonNull Report walk(@NonNull Datasource datasource, boolean apply) {
        Collector collector = new Collector(apply);
        if (apply) {
            // One transaction: a crash mid-walk leaves the history tables in a state that is
            // both consistent and still re-runnable, rather than half-converged.
            datasource.withTransaction(tx -> {
                walkRevisions(datasource, collector);
                walkActivity(datasource, collector);
            });
        } else {
            walkRevisions(datasource, collector);
            walkActivity(datasource, collector);
        }
        return collector.toReport();
    }

    // ── zenit_revisions ──────────────────────────────────────────────────────

    private static void walkRevisions(@NonNull Datasource datasource, @NonNull Collector collector) {
        RevisionModel revisions = new RevisionModel(datasource);
        int offset = 0;
        while (true) {
            List<Row> batch = revisions.find()
                .where(RevisionModel.SNAPSHOT.isNotNull())
                .orderBy(RevisionModel.ID, SortOrder.ASC)
                .limit(BATCH)
                .offset(offset)
                .all();
            if (batch.isEmpty()) {
                return;
            }
            for (Row row : batch) {
                collector.scannedRevision();
                rewriteRevision(revisions, row, collector);
            }
            if (batch.size() < BATCH) {
                return;
            }
            offset += BATCH;
        }
    }

    private static void rewriteRevision(@NonNull RevisionModel revisions, @NonNull Row row,
                                        @NonNull Collector collector) {
        String modelId = String.valueOf(row.get(RevisionModel.MODEL));
        String rowId = String.valueOf(row.get(RevisionModel.ID));

        Schema schema = collector.schemaOf(modelId);
        if (schema == null) {
            collector.unreachable(TABLE_REVISIONS, modelId, rowId, "*",
                "model not registered on this classpath, so no key can be judged");
            return;
        }

        Object parsed = parseOrNull(row.get(RevisionModel.SNAPSHOT));
        if (parsed == null) {
            return;
        }
        if (!(parsed instanceof Map<?, ?> snapshot)) {
            collector.unreachable(TABLE_REVISIONS, modelId, rowId, "*",
                "snapshot does not revive as a map");
            return;
        }

        Map<String, Object> rewritten = stringKeyed(snapshot);
        boolean changed = false;

        if (rewritten.get(FIELDS_KEY) instanceof Map<?, ?> fields) {
            Map<String, Object> original = stringKeyed(fields);
            Map<String, Object> cleaned = cleanSnapshotFields(schema, original, collector, modelId, rowId);
            if (!cleaned.equals(original)) {
                rewritten.put(FIELDS_KEY, cleaned);
                changed = true;
            }
        }

        if (rewritten.get(TRANSLATIONS_KEY) instanceof Map<?, ?> translations) {
            Map<String, Object> original = stringKeyed(translations);
            Map<String, Object> cleaned = cleanSnapshotTranslations(schema, original, collector, modelId, rowId);
            if (!cleaned.equals(original)) {
                rewritten.put(TRANSLATIONS_KEY, cleaned);
                changed = true;
            }
        }

        if (!changed) {
            return;
        }

        collector.affectedRevision();
        if (collector.apply) {
            row.set(RevisionModel.SNAPSHOT, Zenit.DRY.stringify(rewritten));
            revisions.save(row);
        }
    }

    /**
     * The snapshot's plain fields, reduced to what RevisionableBehaviour would store today:
     * a redacted or unjudgeable key is REMOVED (omit mode), never marked, so restore's
     * secret graft-back keeps working exactly as it does for a snapshot written today.
     */
    private static @NonNull Map<String, Object> cleanSnapshotFields(
            @NonNull Schema schema, @NonNull Map<String, Object> fields,
            @NonNull Collector collector, @NonNull String modelId, @NonNull String rowId) {

        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            Field<?, ?> field = schema.getField(name);

            if (field == null) {
                collector.unclassifiable(TABLE_REVISIONS, modelId, rowId, name, 0,
                    "the model declares no such field today");
                continue;
            }
            if (FieldRedaction.redactsWholeValue(field)) {
                collector.redacted(TABLE_REVISIONS, modelId, rowId, name, 0,
                    "secret or encrypted field");
                continue;
            }

            Object stripped = FieldRedaction.withoutSecretSubValues(field, value, null);
            if (Objects.equals(stripped, value)) {
                cleaned.put(name, value);
                continue;
            }
            collector.nested(TABLE_REVISIONS, modelId, rowId, name, field,
                countChangedValues(value, stripped));
            cleaned.put(name, stripped);
        }
        return cleaned;
    }

    /** Same rule per localized field, applied to each stored locale's value. */
    private static @NonNull Map<String, Object> cleanSnapshotTranslations(
            @NonNull Schema schema, @NonNull Map<String, Object> translations,
            @NonNull Collector collector, @NonNull String modelId, @NonNull String rowId) {

        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : translations.entrySet()) {
            String name = entry.getKey();
            Field<?, ?> field = schema.getField(name);

            if (field == null) {
                collector.unclassifiable(TABLE_REVISIONS, modelId, rowId, name, 0,
                    "the model declares no such localized field today");
                continue;
            }
            if (FieldRedaction.redactsWholeValue(field)) {
                collector.redacted(TABLE_REVISIONS, modelId, rowId, name, 0,
                    "secret or encrypted localized field");
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> byTag)) {
                cleaned.put(name, entry.getValue());
                continue;
            }

            Map<String, Object> originalTags = stringKeyed(byTag);
            Map<String, Object> cleanedTags = new LinkedHashMap<>();
            boolean tagChanged = false;
            for (Map.Entry<String, Object> tagged : originalTags.entrySet()) {
                Object stripped = FieldRedaction.withoutSecretSubValues(field, tagged.getValue(), null);
                if (!Objects.equals(stripped, tagged.getValue())) {
                    collector.nested(TABLE_REVISIONS, modelId, rowId, name + "." + tagged.getKey(),
                        field, countChangedValues(tagged.getValue(), stripped));
                    tagChanged = true;
                }
                cleanedTags.put(tagged.getKey(), stripped);
            }
            // The ORIGINAL object when nothing moved, so the caller's equality check compares
            // identity rather than two differently-normalized copies of the same content.
            cleaned.put(name, tagChanged ? cleanedTags : entry.getValue());
        }
        return cleaned;
    }

    // ── zenit_activity ───────────────────────────────────────────────────────

    private static void walkActivity(@NonNull Datasource datasource, @NonNull Collector collector) {
        ActivityModel activity = new ActivityModel(datasource);
        int offset = 0;
        while (true) {
            List<Row> batch = activity.find()
                .where(ActivityModel.DELTA.isNotNull())
                .orderBy(ActivityModel.ID, SortOrder.ASC)
                .limit(BATCH)
                .offset(offset)
                .all();
            if (batch.isEmpty()) {
                return;
            }
            for (Row row : batch) {
                collector.scannedActivity();
                rewriteActivity(activity, row, collector);
            }
            if (batch.size() < BATCH) {
                return;
            }
            offset += BATCH;
        }
    }

    private static void rewriteActivity(@NonNull ActivityModel activity, @NonNull Row row,
                                        @NonNull Collector collector) {
        String modelId = String.valueOf(row.get(ActivityModel.MODEL));
        String rowId = String.valueOf(row.get(ActivityModel.ID));

        Schema schema = collector.schemaOf(modelId);
        if (schema == null) {
            collector.unreachable(TABLE_ACTIVITY, modelId, rowId, "*",
                "model not registered on this classpath, so no delta entry can be judged");
            return;
        }

        Object parsed = parseOrNull(row.get(ActivityModel.DELTA));
        if (parsed == null) {
            return;
        }
        if (!(parsed instanceof Map<?, ?> delta)) {
            collector.unreachable(TABLE_ACTIVITY, modelId, rowId, "*",
                "delta does not revive as a map");
            return;
        }

        Map<String, Object> original = stringKeyed(delta);
        Map<String, Object> rewritten = new LinkedHashMap<>();
        boolean changed = false;

        for (Map.Entry<String, Object> entry : original.entrySet()) {
            String path = entry.getKey();
            if (!(entry.getValue() instanceof Map<?, ?> pair)) {
                collector.unreachable(TABLE_ACTIVITY, modelId, rowId, path,
                    "delta entry is not a before/after pair");
                rewritten.put(path, entry.getValue());
                continue;
            }
            Map<String, Object> source = stringKeyed(pair);
            Map<String, Object> cleaned =
                cleanDeltaEntry(schema, source, collector, modelId, rowId, path);
            changed |= !cleaned.equals(source);
            rewritten.put(path, cleaned);
        }

        if (!changed) {
            return;
        }

        collector.affectedActivity();
        if (collector.apply) {
            row.set(ActivityModel.DELTA, Zenit.DRY.stringify(rewritten));
            activity.save(row);
        }
    }

    /**
     * One delta entry, reduced to what ActivityLog would record today: a redacted or
     * unjudgeable field collapses to a marker pair (the entry keeps saying THAT the value
     * changed), and everything else is redacted per sub-value in marker mode.
     */
    private static @NonNull Map<String, Object> cleanDeltaEntry(
            @NonNull Schema schema, @NonNull Map<String, Object> pair, @NonNull Collector collector,
            @NonNull String modelId, @NonNull String rowId, @NonNull String path) {

        Field<?, ?> field = deltaField(schema, path);

        if (field == null || FieldRedaction.redactsWholeValue(field)) {
            Map<String, Object> masked = new LinkedHashMap<>(pair);
            masked.put(BEFORE_KEY, FieldRedaction.REDACTED);
            masked.put(AFTER_KEY, FieldRedaction.REDACTED);
            if (!masked.equals(pair)) {
                if (field == null) {
                    collector.unclassifiable(TABLE_ACTIVITY, modelId, rowId, path, 0,
                        "the model declares no such field today");
                } else {
                    collector.redacted(TABLE_ACTIVITY, modelId, rowId, path, 0,
                        "secret or encrypted field");
                }
            }
            return masked;
        }

        Object before = pair.get(BEFORE_KEY);
        Object after = pair.get(AFTER_KEY);
        Object cleanBefore = FieldRedaction.redactSubValues(field, before, null);
        Object cleanAfter = FieldRedaction.redactSubValues(field, after, null);

        if (Objects.equals(cleanBefore, before) && Objects.equals(cleanAfter, after)) {
            return pair;
        }

        collector.nested(TABLE_ACTIVITY, modelId, rowId, path, field,
            countChangedValues(before, cleanBefore) + countChangedValues(after, cleanAfter));

        Map<String, Object> cleaned = new LinkedHashMap<>(pair);
        cleaned.put(BEFORE_KEY, cleanBefore);
        cleaned.put(AFTER_KEY, cleanAfter);
        return cleaned;
    }

    /**
     * @return the schema field a delta path names, or null when the model no longer declares
     *         it; a localized entry is keyed {@code field.localeTag} (ActivityLog.computeDelta)
     */
    private static @Nullable Field<?, ?> deltaField(@NonNull Schema schema, @NonNull String path) {
        Field<?, ?> direct = schema.getField(path);
        if (direct != null) {
            return direct;
        }
        int dot = path.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        Field<?, ?> localized = schema.getField(path.substring(0, dot));
        return localized != null && localized.isLocalized() ? localized : null;
    }

    // ── Classification helpers ───────────────────────────────────────────────

    /**
     * Whether a JSON carrier has NO schema to judge its keys against: a schema-less
     * SchemaField, or a dynamic one whose discriminator names no enum at all.
     *
     * AIDEV-NOTE: this classifies the REPORT, never the transform -- FieldRedaction stays the
     * only thing that decides what is removed (it treats an empty candidate list as "every key
     * is undeclared" and drops the whole value). Without this the operator would see a large
     * nested-value count with no hint that an entire column was wiped for want of a schema,
     * which is the one outcome of this tool that most deserves to be read before it is run.
     */
    private static boolean hasNoCandidateSchema(@NonNull Field<?, ?> field) {
        SchemaField carrier = carrierSchemaField(field);
        if (carrier == null || carrier.getSubSchema() != null) {
            return false;
        }
        return !carrier.isDynamic() || carrier.resolveSiblingEnumField() == null;
    }

    private static @Nullable SchemaField carrierSchemaField(@NonNull Field<?, ?> field) {
        if (field instanceof SchemaField schemaField) {
            return schemaField;
        }
        if (field instanceof ListField<?> list && list.getItemField() instanceof SchemaField itemSchema) {
            return itemSchema;
        }
        return null;
    }

    /**
     * How many values the redaction changed, counted at the SHALLOWEST differing position.
     *
     * AIDEV-NOTE: a COUNT, deliberately, and never the differing key paths. Nested keys inside
     * a JSON column are arbitrary stored text (a StringMapField's keys are the environment
     * variable names of a customer's site), and a survey whose whole contract is "never prints
     * a value" must not print stored strings it did not declare. Stopping at the shallowest
     * difference is also what keeps the walk out of a wholly-redacted value's interior.
     */
    private static int countChangedValues(@Nullable Object before, @Nullable Object after) {
        if (before instanceof Map<?, ?> beforeMap && after instanceof Map<?, ?> afterMap) {
            Map<String, Object> from = stringKeyed(beforeMap);
            Map<String, Object> to = stringKeyed(afterMap);
            int changed = 0;
            for (Map.Entry<String, Object> entry : from.entrySet()) {
                if (!to.containsKey(entry.getKey())) {
                    changed++;
                    continue;
                }
                Object other = to.get(entry.getKey());
                if (Objects.equals(entry.getValue(), other)) {
                    continue;
                }
                changed += Math.max(1, countChangedValues(entry.getValue(), other));
            }
            for (String key : to.keySet()) {
                if (!from.containsKey(key)) {
                    changed++;
                }
            }
            return changed;
        }
        if (before instanceof List<?> beforeList && after instanceof List<?> afterList) {
            int changed = Math.abs(beforeList.size() - afterList.size());
            for (int i = 0; i < beforeList.size() && i < afterList.size(); i++) {
                if (Objects.equals(beforeList.get(i), afterList.get(i))) {
                    continue;
                }
                changed += Math.max(1, countChangedValues(beforeList.get(i), afterList.get(i)));
            }
            return changed;
        }
        return Objects.equals(before, after) ? 0 : 1;
    }

    private static @Nullable Object parseOrNull(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Zenit.DRY.parse(text);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static @NonNull Map<String, Object> stringKeyed(@NonNull Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    // ── Accumulation ─────────────────────────────────────────────────────────

    /** Findings plus counters; also memoizes model lookups across the whole walk. */
    private static final class Collector {

        private final boolean apply;
        private final List<Finding> findings = new ArrayList<>();
        private final Map<String, Schema> schemas = new LinkedHashMap<>();
        private final List<String> unresolvable = new ArrayList<>();

        private int revisionRowsScanned;
        private int activityRowsScanned;
        private int revisionRowsAffected;
        private int activityRowsAffected;

        private Collector(boolean apply) {
            this.apply = apply;
        }

        /** @return the model's current schema, or null when it is not on this classpath */
        private @Nullable Schema schemaOf(@NonNull String modelId) {
            if (this.unresolvable.contains(modelId)) {
                return null;
            }
            Schema known = this.schemas.get(modelId);
            if (known != null) {
                return known;
            }
            Identifier id = Identifier.tryParse(modelId);
            Model model = id != null ? Models.get(id) : null;
            Schema schema = model != null ? model.getSchema() : null;
            if (schema == null) {
                this.unresolvable.add(modelId);
                return null;
            }
            this.schemas.put(modelId, schema);
            return schema;
        }

        private void scannedRevision() {
            this.revisionRowsScanned++;
        }

        private void scannedActivity() {
            this.activityRowsScanned++;
        }

        private void affectedRevision() {
            this.revisionRowsAffected++;
        }

        private void affectedActivity() {
            this.activityRowsAffected++;
        }

        private void redacted(String table, String modelId, String rowId, String path,
                              int nested, String note) {
            this.findings.add(new Finding(table, modelId, rowId, path, Reason.REDACTED, nested, note));
        }

        private void unclassifiable(String table, String modelId, String rowId, String path,
                                    int nested, String note) {
            this.findings.add(
                new Finding(table, modelId, rowId, path, Reason.UNCLASSIFIABLE, nested, note));
        }

        private void unreachable(String table, String modelId, String rowId, String path, String note) {
            this.findings.add(new Finding(table, modelId, rowId, path, Reason.UNREACHABLE, 0, note));
        }

        /**
         * A change INSIDE a JSON value. It lands in the unclassifiable bucket when the carrier
         * has no schema at all, because then the redaction dropped every key on the mere
         * absence of a sub-schema rather than on any judgement about the keys themselves.
         */
        private void nested(String table, String modelId, String rowId, String path,
                            Field<?, ?> field, int nested) {
            if (hasNoCandidateSchema(field)) {
                this.unclassifiable(table, modelId, rowId, path, nested,
                    "no sub-schema resolves for this carrier, so every key in it is undeclared");
                return;
            }
            this.redacted(table, modelId, rowId, path, nested, "secret sub-value(s)");
        }

        private @NonNull Report toReport() {
            return new Report(this.apply, this.revisionRowsScanned, this.activityRowsScanned,
                this.revisionRowsAffected, this.activityRowsAffected, List.copyOf(this.findings));
        }
    }
}
