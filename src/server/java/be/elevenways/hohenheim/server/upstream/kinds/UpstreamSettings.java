package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.validation.validator.Range;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The settings that more than one upstream kind declares identically, and the one spelling of
 * their stored keys the dispatcher reads them by.
 *
 * AIDEV-NOTE: request_timeout used to be declared twice, and the two declarations
 * disagreed: the instance kind bound it to [1, 3600] while the address kind carried no
 * validator at all. Same stored key, same label, same help text, same runtime reader --
 * two different answers to "is 0 allowed" (the help text says it is) and to "is -5
 * allowed" (the runtime silently folds it to the 30s default). One home per setting is
 * what keeps that from happening again. The delay setting had the same shape three times
 * over (static, redirect, address) with no validator anywhere; it lives here too.
 */
public final class UpstreamSettings {

    /** The stored key of the per-route request timeout, in seconds. */
    public static final String REQUEST_TIMEOUT = "request_timeout";

    /** The stored key of the per-route artificial delay, in milliseconds. */
    public static final String DELAY = "delay";

    /**
     * The lower bound is the SENTINEL, not a floor: {@code RouteEntry.parseRequestTimeout}
     * maps 0 to Undertow's "no limit", which is what the help text promises for streaming,
     * gRPC and WebSocket backends. A negative value is refused rather than folded.
     */
    static final int REQUEST_TIMEOUT_MIN_SECONDS = 0;

    /** An hour: past this the value is a mistake, not a long-lived stream. */
    static final int REQUEST_TIMEOUT_MAX_SECONDS = 3600;

    /**
     * A minute: the delay holds a request before it is dispatched, so past this it outlasts
     * the default request timeout it precedes. Validators run on save only, so a stored
     * larger value still loads and is honoured until the site is next edited.
     */
    static final int DELAY_MAX_MILLIS = 60_000;

    private UpstreamSettings() {}

    /**
     * A fresh request-timeout field; each schema owns its own instance because
     * {@code Schema.addField} binds a field to its parent schema.
     *
     * The help text names the upper bound through a {@code max} ARGUMENT rather than
     * spelling the number, so the sentence and the validator cannot drift apart.
     */
    static @NonNull IntegerField requestTimeout() {
        return IntegerField.builder().name(REQUEST_TIMEOUT).suffix("s")
            .validator(Range.of(REQUEST_TIMEOUT_MIN_SECONDS, REQUEST_TIMEOUT_MAX_SECONDS))
            .label(HohenheimFormCopy.label(REQUEST_TIMEOUT))
            .help(HohenheimFormCopy.help(REQUEST_TIMEOUT)
                .withArg("max", REQUEST_TIMEOUT_MAX_SECONDS))
            .build();
    }

    /** A fresh delay field, honoured generically by the dispatcher's per-route delay scheduler. */
    static @NonNull IntegerField delay() {
        return IntegerField.builder().name(DELAY).suffix("ms")
            .validator(Range.of(0, DELAY_MAX_MILLIS))
            .label(HohenheimFormCopy.label(DELAY))
            .help(HohenheimFormCopy.help(DELAY).withArg("max", DELAY_MAX_MILLIS))
            .build();
    }
}
