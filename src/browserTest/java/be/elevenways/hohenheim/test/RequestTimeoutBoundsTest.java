package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.upstream.kinds.AddressUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.submit.FormValidator;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request-timeout setting is written by two upstream kinds under one key, read by one
 * runtime and explained by one help text; all four must agree. The instance kind used to
 * refuse 0 -- the value its own help text documents as "no limit" and the value
 * {@code RouteEntry.parseRequestTimeout} maps to Undertow's unlimited -- while the address
 * kind validated nothing at all, so a negative there was accepted and then silently folded
 * to the 30s default.
 */
class RequestTimeoutBoundsTest {

    /** The submit path in miniature: coerce is transport, this is the validation it feeds. */
    private static Violations validate(IntegerField field, Integer seconds) {
        FormSpec spec = FormSpec.builder().add(field).build();
        Map<String, Object> coerced = new HashMap<>();
        coerced.put(field.getName(), seconds);
        return FormValidator.validateCoercedForm(spec, coerced, Collections.emptyMap());
    }

    @Test
    void bothKindsAcceptTheDocumentedSentinelAndRefuseWhatTheRuntimeCannotHonour() {
        IntegerField instance = InstanceUpstreamKind.REQUEST_TIMEOUT;
        IntegerField address = AddressUpstreamKind.REQUEST_TIMEOUT;

        // 1. Zero is the documented "no limit" sentinel for streaming, gRPC and WebSocket
        //    backends -- the whole reason the field is not simply a positive number.
        assertThat(validate(instance, 0).isEmpty())
            .as("step 1: the instance kind accepts the sentinel its help text documents")
            .isTrue();
        assertThat(validate(address, 0).isEmpty())
            .as("step 1: and so does the address kind").isTrue();

        // 2. An ordinary positive value, and the upper bound itself (inclusive).
        assertThat(validate(instance, 30).isEmpty()).as("step 2: an ordinary timeout").isTrue();
        assertThat(validate(instance, 3600).isEmpty())
            .as("step 2: the upper bound is inclusive").isTrue();
        assertThat(validate(address, 3600).isEmpty())
            .as("step 2: on both kinds").isTrue();

        // 3. One second past it is refused, on both kinds.
        assertThat(validate(instance, 3601).isEmpty())
            .as("step 3: past the bound the instance kind refuses").isFalse();
        assertThat(validate(address, 3601).isEmpty())
            .as("step 3: and so does the address kind").isFalse();

        // 4. A negative is refused LOUDLY rather than folded: the runtime turns it into
        //    the 30s default, so an operator who typed -1 would otherwise be told nothing
        //    and get a timeout they never asked for.
        assertThat(validate(instance, -1).isEmpty())
            .as("step 4: the instance kind refuses a negative").isFalse();
        assertThat(validate(address, -1).isEmpty())
            .as("step 4: and the address kind, which used to accept it").isFalse();

        // 5. Absence stays absence: no value means the runtime's own default, not a
        //    violation -- the field is optional on both kinds.
        assertThat(validate(instance, null).isEmpty())
            .as("step 5: an unset timeout is not a violation").isTrue();
        assertThat(validate(address, null).isEmpty())
            .as("step 5: on either kind").isTrue();
    }
}
