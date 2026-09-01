package be.elevenways.hohenheim.activity;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The subject of one activity row as a list cell: the record's own title, linked to its
 * admin detail page when a registered resource serves that model.
 *
 * AIDEV-NOTE: the label is deliberately the STORED title (what the record was called when
 * it was touched), never a fresh lookup -- an audit trail must not rewrite what a row said.
 * A null url is the ordinary case, not a failure: an activity row can name a model no panel
 * exposes, or a record that has since been deleted.
 *
 * @param label the record title as it was stored, falling back to the raw record id
 * @param url   admin detail URL of the record, or null when no registered resource serves it
 */
@HawkeyeClass
public record ActivityRecordCell(
    @NonNull String label,
    @Nullable String url
) {
}
