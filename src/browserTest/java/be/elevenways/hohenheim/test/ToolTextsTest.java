package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tool-text check HohenheimMcpTest runs over every tool, proven on its own: a bare key, as file_write's folder
 * said on a real server, fails it, a sentence passes, and a nested entry's or a flag's text is among those checked.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class ToolTextsTest {

    @Test
    void aBareKeyFailsAndASentencePasses() {
        // 1. The bare keys a missing catalog entry leaves: alone, and scoped.
        assertThatThrownBy(() -> ToolTexts.assertWorded("mcp_folder", "step 1"))
            .as("step 1: the key file_write's folder said").isInstanceOf(AssertionError.class)
            .hasMessageContaining("mcp_folder");
        assertThatThrownBy(() -> ToolTexts.assertWorded("hohenheim.mcp_folder", "step 1"))
            .as("step 1: a scoped key").isInstanceOf(AssertionError.class);

        // 2. A sentence, and a one-word text that is no key, pass.
        ToolTexts.assertWorded("The folder to write the file in, under the app's files.", "step 2");
        ToolTexts.assertWorded("Folder", "step 2");

        // 3. A nested entry's and a flag's descriptions are found as well as the top level's.
        Map<String, Object> schema = Map.of("description", "Writes a file.", "properties", Map.of(
            "folder", Map.of("type", "string", "description", "mcp_folder"),
            "entries", Map.of("type", "array", "items", Map.of("properties", Map.of(
                "force", Map.of("type", "boolean", "description", "Overwrite it."))))));
        List<String> found = ToolTexts.descriptions(schema);
        assertThat(found).as("step 3: every description").containsExactlyInAnyOrder("Writes a file.", "mcp_folder",
            "Overwrite it.");
        assertThatThrownBy(() -> found.forEach(text -> ToolTexts.assertWorded(text, "step 3")))
            .as("step 3: the nested bare key fails the tool").isInstanceOf(AssertionError.class);
    }
}
