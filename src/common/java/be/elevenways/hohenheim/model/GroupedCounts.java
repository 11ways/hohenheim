package be.elevenways.hohenheim.model;

import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.aggregate.Aggregate;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How many rows a query matches per value of one integer key, in ONE grouped aggregate.
 *
 * AIDEV-NOTE: the app's single grouped count, so a list asks one query per page instead of a COUNT per row. zenit's
 * QueryBuilder has no count-per-key reader; this class is what such a framework reader later replaces.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class GroupedCounts {

    private static final String COUNT_ALIAS = "grouped_count";

    private GroupedCounts() {}

    /** @return key value to its row count; a key no row matches, and the null key, are absent */
    public static @NonNull Map<Integer, Long> of(@NonNull QueryBuilder<?> query, @NonNull IntegerField key) {
        Map<Integer, Long> counts = new HashMap<>();
        List<Row> groups = query.groupBy(key).aggregateAll(Aggregate.count().as(COUNT_ALIAS));
        for (Row group : groups) {
            if (group.get(key.getName()) instanceof Number id && group.get(COUNT_ALIAS) instanceof Number count) {
                counts.put(id.intValue(), count.longValue());
            }
        }
        return counts;
    }
}
