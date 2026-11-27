package be.elevenways.hohenheim.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check that a text an assistant reads from a tool is words: a description, a nested entry's or a flag's, never
 * the bare key a microcopy key no catalog holds resolves to.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class ToolTexts {

    private ToolTexts() {
    }

    /** Fails when the text is a bare microcopy key ("mcp_folder"), which is what a key no catalog holds says. */
    static void assertWorded(String text, String where) {
        assertThat(text).as(where + " says words, not a key: " + text).doesNotMatch("[a-z][a-z0-9_.:-]*");
    }

    /** @return every description in an input schema, nested entries' and enum members' too */
    static List<String> descriptions(Object schema) {
        List<String> found = new ArrayList<>();
        if (schema instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if ("description".equals(key) && value instanceof String text) found.add(text);
                else found.addAll(descriptions(value));
            });
        } else if (schema instanceof List<?> list) {
            for (Object item : list) found.addAll(descriptions(item));
        }
        return found;
    }
}
