package be.elevenways.hohenheim.server.application;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * What changes between two release specs, from their redacted snapshots ({@link SpecSnapshots#diff}).
 *
 * AIDEV-NOTE: a secret value never appears here, not even as its fingerprint: a secret setting says only that it
 * changed, and a secret map (the environment) only which names were added, removed or changed.
 *
 * @param settings the single-value settings that differ, in key order
 * @param maps     the map settings whose entries differ, in key order
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public record SpecDiff(@NonNull List<Setting> settings, @NonNull List<Entries> maps) {

    public SpecDiff {
        settings = List.copyOf(settings);
        maps = List.copyOf(maps);
    }

    /** @return whether both specs declare the same settings with the same values */
    public boolean isEmpty() {
        return this.settings.isEmpty() && this.maps.isEmpty();
    }

    /**
     * One single-value setting that differs.
     *
     * @param key    the release schema's field name
     * @param secret whether the field is secret, in which case both values are null
     * @param from   the stored value before, null when unset or secret
     * @param to     the stored value after, null when unset or secret
     */
    public record Setting(@NonNull String key, boolean secret, @Nullable Object from, @Nullable Object to) {
    }

    /**
     * One map setting whose entries differ, by entry name only.
     *
     * @param key     the release schema's field name
     * @param added   names only the target spec holds
     * @param removed names only the current spec holds
     * @param changed names both hold with different values
     */
    public record Entries(@NonNull String key, @NonNull List<String> added, @NonNull List<String> removed,
                          @NonNull List<String> changed) {

        public Entries {
            added = List.copyOf(added);
            removed = List.copyOf(removed);
            changed = List.copyOf(changed);
        }
    }
}
