package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The ports table of an instance: its rows, and whether any of them names what it is for.
 *
 * @param rows     every published port, joined to its host address
 * @param purposes whether a row names its purpose, so the table draws its For column; a column of blanks says nothing
 * @author Jelle De Loecker
 * @since 0.10.0
 */
@HawkeyeClass
public record InstanceEndpointTable(@NonNull List<InstanceEndpointView> rows, boolean purposes) {

    /** @return the table of these rows, its For column drawn only when one of them names a purpose */
    public static @NonNull InstanceEndpointTable of(@NonNull List<InstanceEndpointView> rows) {
        return new InstanceEndpointTable(List.copyOf(rows), rows.stream().anyMatch(row -> row.purpose() != null));
    }
}
