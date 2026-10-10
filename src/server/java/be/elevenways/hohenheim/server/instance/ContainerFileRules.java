package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * THE rule for a file staged into a container: its path is absolute and never climbs, its mode is octal. One home for
 * every model that stores such a file (instance files, template files), checked on the model's own save so a form, an
 * inline cell, an API write and a direct save all answer to it.
 *
 * AIDEV-NOTE: before VALIDATE, and only for the keys the write carries: a partial write (the inline cell lane submits
 * ONE entry) is judged on what it changes, the stored value having passed this rule when it was written. The path is
 * stored trimmed.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class ContainerFileRules {

    private static final Set<Schema> INSTALLED = Collections.synchronizedSet(
        Collections.newSetFromMap(new IdentityHashMap<>()));

    private ContainerFileRules() {
    }

    /**
     * Installs the rule on a file model; idempotent per schema.
     *
     * @param path the container path column
     * @param mode the octal mode column
     */
    public static void install(@NonNull Schema schema, @NonNull StringField path, @NonNull StringField mode) {
        if (!INSTALLED.add(schema)) {
            return;
        }
        schema.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null) {
                check(row, path, mode);
            }
        });
    }

    /** @throws be.elevenways.zenit.common.validation.Violations anchored on the offending column */
    static void check(@NonNull Row row, @NonNull StringField path, @NonNull StringField mode) {
        if (row.has(path.getName()) && row.get(path) != null) {
            row.set(path, checkedPath(path.getName(), row.get(path)));
        }
        checkMode(mode.getName(), row.has(mode.getName()) ? row.get(mode) : null);
    }

    /**
     * The path rule over a value not yet in a row, such as an imported document's file entry.
     *
     * @return the path, trimmed
     * @throws be.elevenways.zenit.common.validation.Violations {@code file_path_absolute}, anchored on {@code field}
     */
    static @NonNull String checkedPath(@NonNull String field, @Nullable Object path) {
        String trimmed = trimmed(path);
        if (!trimmed.startsWith("/") || trimmed.contains("..")) {
            throw Violations.ofField(field, trimmed, HohenheimMicrocopy.VIOLATIONS.of("file_path_absolute"));
        }
        return trimmed;
    }

    /**
     * The mode rule over a value not yet in a row; a blank mode leaves the default to the writer.
     *
     * @throws be.elevenways.zenit.common.validation.Violations {@code file_mode_format}, anchored on {@code field}
     */
    static void checkMode(@NonNull String field, @Nullable Object mode) {
        if (mode != null && !String.valueOf(mode).isBlank()) {
            try {
                Integer.parseInt(String.valueOf(mode).trim(), 8);
            } catch (NumberFormatException notOctal) {
                throw Violations.ofField(field, mode, HohenheimMicrocopy.VIOLATIONS.of("file_mode_format"));
            }
        }
    }
}
