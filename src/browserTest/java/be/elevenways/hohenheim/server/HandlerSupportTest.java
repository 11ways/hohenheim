package be.elevenways.hohenheim.server;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared handler plumbing other packages now reach: the first-violation message never
 * throws on an empty refusal, and a submitted id is coerced through the framework's one
 * parse, with absence and refusal both reading as "no id".
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class HandlerSupportTest {

    @Test
    void refusalTextAndSubmittedValuesNeverThrow() {
        // 1. An EMPTY refusal (the shape DnsZoneHandlers used to index with get(0)) answers
        //    the generic refusal instead of throwing IndexOutOfBounds out of the handler.
        assertThat(HandlerSupport.violationMessage(new Violations()).key())
            .as("step 1: an empty refusal falls back to the generic sentence")
            .isEqualTo("refused");

        // 2. A real refusal keeps the domain's own message.
        Violations named = Violations.ofForm(Microcopy.of("import_empty").withFilter("scope", "dns_zone"));
        assertThat(HandlerSupport.violationMessage(named).key())
            .as("step 2: the first violation's own key survives")
            .isEqualTo("import_empty");

        // 3. Submitted values: first of a list, trimmed, "" when absent.
        Map<String, Object> form = Map.of("list", List.of(" 42 ", "7"), "blank", "  ", "text", "x1");
        assertThat(HandlerSupport.submittedString(form, "list"))
            .as("step 3: a list answers its first value, trimmed").isEqualTo("42");
        assertThat(HandlerSupport.submittedString(form, "absent"))
            .as("step 3: an absent value is empty").isEmpty();

        // 4. Integers ride PrimitiveCoercion: parsed when well-formed, null when absent,
        //    blank or malformed -- never an exception.
        assertThat(HandlerSupport.submittedInteger(form, "list"))
            .as("step 4: a well-formed id parses").isEqualTo(42);
        assertThat(HandlerSupport.submittedInteger(form, "blank"))
            .as("step 4: blank is absence").isNull();
        assertThat(HandlerSupport.submittedInteger(form, "text"))
            .as("step 4: malformed is refused, not thrown").isNull();

        // 5. An id is positive or 0: the old contract of submittedId, kept.
        assertThat(HandlerSupport.submittedId(form, "list")).as("step 5: positive id").isEqualTo(42);
        assertThat(HandlerSupport.submittedId(Map.of("id", "-3"), "id"))
            .as("step 5: a non-positive id reads as none").isZero();
        assertThat(HandlerSupport.submittedId(form, "text"))
            .as("step 5: a malformed id reads as none").isZero();
    }
}
