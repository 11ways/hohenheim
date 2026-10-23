package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.server.orm.command.CommandPayloads;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;
import java.util.TreeMap;

/**
 * The redacted copy of a release's spec a release operation keeps, so two releases still compare after their instance
 * rows were reclaimed.
 *
 * AIDEV-NOTE: an ALLOWLIST read off the release kind's own settings schema ({@link ReleaseKind#SETTINGS_SCHEMA}), the
 * one home of the keys a release spec declares: a key the schema does not declare is never stored (commit_sha has its
 * own column), a non-secret field is stored verbatim, and a secret field (the environment, which carries injected
 * database credentials) keeps only a keyed fingerprint of each value. The fingerprint is an HMAC under the install's
 * field-encryption keyring ({@link CommandPayloads#keyedDigests}), so a weak value cannot be confirmed from a database
 * or backup alone; it still answers changed or unchanged, until the keyring's active key rotates.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SpecSnapshots {

    /** What a fingerprinted secret value starts with. */
    static final String FINGERPRINT_PREFIX = "hmac:";

    /** The keyring purpose secret spec values are fingerprinted under. */
    static final String FINGERPRINT_PURPOSE = "hohenheim-release-spec";

    private SpecSnapshots() {
    }

    /** @return the spec's schema-declared keys as DRY text, keys sorted, secret values fingerprinted */
    public static @NonNull String snapshot(@NonNull Map<String, Object> desired) {
        Map<String, Object> redacted = new TreeMap<>();
        desired.forEach((key, value) -> {
            Field<?, ?> field = ReleaseKind.SETTINGS_SCHEMA.getField(key);
            if (value == null || field == null) {
                return;
            }
            if (!field.isSecret()) {
                redacted.put(key, value);
            } else if (field instanceof StringMapField) {
                // A map in a shape EnvVars cannot read is dropped, never stored as it came.
                Map<String, String> fingerprinted = new TreeMap<>();
                EnvVars.toMap(value).forEach((name, secret) -> fingerprinted.put(name, fingerprint(secret)));
                if (!fingerprinted.isEmpty()) {
                    redacted.put(key, fingerprinted);
                }
            } else {
                redacted.put(key, fingerprint(String.valueOf(value)));
            }
        });
        return Zenit.DRY.stringify(redacted);
    }

    /** @return the first twelve hex digits of the value's keyed digest under the active keyring key, prefixed */
    public static @NonNull String fingerprint(@NonNull String value) {
        return FINGERPRINT_PREFIX + CommandPayloads.keyedDigests(FINGERPRINT_PURPOSE, value).get(0).substring(0, 12);
    }
}
