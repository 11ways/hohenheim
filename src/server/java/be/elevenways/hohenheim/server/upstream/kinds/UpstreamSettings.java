package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.validation.validator.Range;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The settings that more than one forwarding upstream kind declares identically.
 *
 * AIDEV-NOTE: request_timeout used to be declared twice, and the two declarations
 * disagreed: the instance kind bound it to [1, 3600] while the address kind carried no
 * validator at all. Same stored key, same label, same help text, same runtime reader --
 * two different answers to "is 0 allowed" (the help text says it is) and to "is -5
 * allowed" (the runtime silently folds it to the 30s default). One home per setting is
 * what keeps that from happening again.
 */
final class UpstreamSettings {

    /**
     * The lower bound is the SENTINEL, not a floor: {@code RouteEntry.parseRequestTimeout}
     * maps 0 to Undertow's "no limit", which is what the help text promises for streaming,
     * gRPC and WebSocket backends. A negative value is refused rather than folded.
     */
    static final int REQUEST_TIMEOUT_MIN_SECONDS = 0;

    /** An hour: past this the value is a mistake, not a long-lived stream. */
    static final int REQUEST_TIMEOUT_MAX_SECONDS = 3600;

    private UpstreamSettings() {}

    /**
     * A fresh request-timeout field; each schema owns its own instance because
     * {@code Schema.addField} binds a field to its parent schema.
     *
     * The help text names the upper bound through a {@code max} ARGUMENT rather than
     * spelling the number, so the sentence and the validator cannot drift apart.
     */
    static @NonNull IntegerField requestTimeout() {
        return IntegerField.builder().name("request_timeout").suffix("s")
            .validator(Range.of(REQUEST_TIMEOUT_MIN_SECONDS, REQUEST_TIMEOUT_MAX_SECONDS))
            .label(HohenheimFormCopy.label("request_timeout"))
            .help(HohenheimFormCopy.help("request_timeout")
                .withArg("max", REQUEST_TIMEOUT_MAX_SECONDS))
            .build();
    }
}
