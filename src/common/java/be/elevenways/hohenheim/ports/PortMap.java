package be.elevenways.hohenheim.ports;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.zenit.common.orm.field.MapEntries;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A settings map of ports, each key to its stored spelling, read as a typed list, written back and offered to JSON
 * callers as typed entries; each port type declares its own key, spelling and parse over it.
 *
 * AIDEV-NOTE: the parse is the one judge of a spelling: a typed entry is joined into a spelling and parsed like a
 * typed one, so no shape is accepted that the stored map would refuse.
 *
 * @param <P> the port type
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class PortMap<P> {

    private final String field;
    private final BiFunction<String, String, P> parse;
    private final Function<P, String> key;
    private final Function<P, String> spec;

    /**
     * @param field the settings key holding the map
     * @param parse the port a key and its spelling describe, throwing Violations for one it refuses
     * @param key   the key a port is stored under
     * @param spec  the spelling a port is stored as
     */
    public PortMap(@NonNull String field, @NonNull BiFunction<String, String, P> parse,
                   @NonNull Function<P, String> key, @NonNull Function<P, String> spec) {
        this.field = Objects.requireNonNull(field);
        this.parse = Objects.requireNonNull(parse);
        this.key = Objects.requireNonNull(key);
        this.spec = Objects.requireNonNull(spec);
    }

    /** @return the path a refusal of the map is shown on, inside the record's settings */
    public @NonNull String path() {
        return "settings." + this.field;
    }

    /**
     * @return every port the settings declare, in declaration order, empty when they hold no map
     * @throws Violations from the parse, for the first entry it refuses
     */
    public @NonNull List<P> of(@NonNull Map<String, Object> settings) {
        List<P> ports = new ArrayList<>();
        if (settings.get(this.field) instanceof Map<?, ?> map) {
            map.forEach((key, spec) -> ports.add(this.parse.apply(String.valueOf(key),
                spec == null ? "" : spec.toString())));
        }
        return ports;
    }

    /** @return the settings map value for these ports, in their order */
    public @NonNull Map<String, String> toSettings(@NonNull List<P> ports) {
        Map<String, String> map = new LinkedHashMap<>();
        for (P port : ports) {
            map.put(this.key.apply(port), this.spec.apply(port));
        }
        return map;
    }

    /**
     * @param members the entry's key and typed members
     * @param spelled the stored spelling an entry's given members make, before the parse judges it
     * @return the typed entries a JSON caller writes the map in, each stored as the parse reads its spelling
     */
    public @NonNull MapEntries entries(MapEntries.@NonNull Builder members,
                                       @NonNull Function<Map<String, Object>, String> spelled) {
        return members.joinedBy((key, values) -> this.spec.apply(this.parse.apply(key, spelled.apply(values))));
    }

    /** @return a typed entry member's whole number as its text by RawValues' whole-number rule, null for none */
    public static @Nullable String wholeNumber(@Nullable Object typed) {
        Long number = RawValues.parsedLong(typed);
        return number == null ? null : String.valueOf(number);
    }
}
