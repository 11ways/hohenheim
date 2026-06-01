package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.handlers.HandlerSupport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the {@code prefix[i].name}/{@code prefix[i].value} parser used by the
 * key-value editor -- the domain handler previously read {@code header_name_i} and silently
 * dropped every custom header the editor submitted.
 */
class HandlerSupportTest {

    @Test
    void parsesEditorIndexedPairsAndSkipsBlankNames() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("header[0].name", "X-Foo");
        form.put("header[0].value", "bar");
        form.put("header[1].name", "X-Empty-Ok");
        form.put("header[1].value", "");            // blank value is kept
        form.put("header[2].name", "   ");          // blank name -> skipped, iteration continues
        form.put("header[2].value", "ignored");
        // index 3 absent -> iteration stops

        List<Map<String, String>> pairs = HandlerSupport.extractIndexedPairs(form, "header");

        assertThat(pairs).hasSize(2);
        assertThat(pairs.get(0)).containsEntry("name", "X-Foo").containsEntry("value", "bar");
        assertThat(pairs.get(1)).containsEntry("name", "X-Empty-Ok").containsEntry("value", "");
    }
}
