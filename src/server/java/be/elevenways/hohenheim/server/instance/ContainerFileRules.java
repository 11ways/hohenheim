package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

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
            String trimmed = String.valueOf(row.get(path)).trim();
            if (!trimmed.startsWith("/") || trimmed.contains("..")) {
                throw HohenheimViolations.ofField(path.getName(), trimmed, "file_path_absolute");
            }
            row.set(path, trimmed);
        }
        Object value = row.has(mode.getName()) ? row.get(mode) : null;
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                Integer.parseInt(String.valueOf(value).trim(), 8);
            } catch (NumberFormatException notOctal) {
                throw HohenheimViolations.ofField(mode.getName(), value, "file_mode_format");
            }
        }
    }
}
