package be.elevenways.hohenheim.server.dns;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A record row could not be turned into a valid resource record.
 * Carries the offending form field plus a violations-scope microcopy key.
 */
public class DnsValueException extends Exception {

    private final String field;
    private final String microcopyKey;

    public DnsValueException(@NonNull String field, @NonNull String microcopyKey) {
        super(field + ": " + microcopyKey);
        this.field = field;
        this.microcopyKey = microcopyKey;
    }

    public @NonNull String getField() {
        return this.field;
    }

    public @NonNull String getMicrocopyKey() {
        return this.microcopyKey;
    }
}
