package be.elevenways.hohenheim.server.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the {@code {name, value}} environment-variable list stored in site settings (as emitted by
 * the key-value editor) into an ordered name-&gt;value map.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class EnvVars {

    private EnvVars() {
    }

    /**
     * Ordered name-&gt;value map from the stored value: the canonical map shape, or the
     * legacy pre-migration {@code List<Map{name,value}>} shape. Blank names are skipped.
     */
    public static Map<String, String> toMap(Object rawList) {
        Map<String, String> env = new LinkedHashMap<>();
        if (rawList instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object name = entry.getKey();
                if (name != null && !name.toString().isBlank()) {
                    Object value = entry.getValue();
                    env.put(name.toString(), value == null ? "" : value.toString());
                }
            }
            return env;
        }
        if (rawList instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry) {
                    Object name = entry.get("name");
                    if (name != null && !name.toString().isBlank()) {
                        Object value = entry.get("value");
                        env.put(name.toString(), value == null ? "" : value.toString());
                    }
                }
            }
        }
        return env;
    }
}
