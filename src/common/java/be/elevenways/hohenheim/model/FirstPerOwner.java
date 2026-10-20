package be.elevenways.hohenheim.model;

import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.query.GroupedRows;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * THE one-read answer to "the first row each of these owners has, in this query's order", for a page of owners.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class FirstPerOwner {

    private FirstPerOwner() {
    }

    /**
     * Reads the first row of each owner in one grouped query.
     *
     * @param ordered the owners' rows, filtered and ordered so each owner's wanted row comes first
     * @param owner   the field naming a row's owner
     * @return owner key to its first row; an owner with none is absent
     */
    public static <K> @NonNull Map<K, Row> of(@NonNull QueryBuilder<Row> ordered, @NonNull Field<K, ?> owner,
                                              @NonNull Collection<K> owners) {
        Map<K, Row> first = new HashMap<>();
        if (owners.isEmpty()) {
            return first;
        }
        for (GroupedRows.Group<K, Row> group : ordered.firstPerGroup(owner, 1, owners).groups()) {
            if (group.key() != null && !group.rows().isEmpty()) {
                first.put(group.key(), group.rows().get(0));
            }
        }
        return first;
    }
}
