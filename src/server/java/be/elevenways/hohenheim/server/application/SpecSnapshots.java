package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.server.orm.command.CommandPayloads;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

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

    /**
     * What changes when the spec of {@code from} is replaced by the spec of {@code to}.
     *
     * AIDEV-NOTE: read through the release schema again, so a key it no longer declares is never named; a secret
     * field compares by fingerprint and reports no value, which only holds while both snapshots were taken under the
     * same keyring key (a rotation between them reads as every secret changed).
     *
     * @param from the current release's snapshot, null or blank for none
     * @param to   the target release's snapshot, null or blank for none
     */
    public static @NonNull SpecDiff diff(@Nullable String from, @Nullable String to) {
        Map<String, Object> before = parsed(from);
        Map<String, Object> after = parsed(to);
        List<SpecDiff.Setting> settings = new ArrayList<>();
        List<SpecDiff.Entries> maps = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            Field<?, ?> field = ReleaseKind.SETTINGS_SCHEMA.getField(key);
            Object old = before.get(key);
            Object now = after.get(key);
            if (field == null || ApplicationReleases.settingsEqual(old, now)) {
                continue;
            }
            if (field instanceof StringMapField) {
                SpecDiff.Entries entries = entries(key, old, now);
                if (!entries.added().isEmpty() || !entries.removed().isEmpty() || !entries.changed().isEmpty()) {
                    maps.add(entries);
                }
            } else if (field.isSecret()) {
                settings.add(new SpecDiff.Setting(key, true, null, null));
            } else {
                settings.add(new SpecDiff.Setting(key, false, old, now));
            }
        }
        return new SpecDiff(settings, maps);
    }

    private static SpecDiff.@NonNull Entries entries(@NonNull String key, @Nullable Object old, @Nullable Object now) {
        Map<String, String> before = EnvVars.toMap(old);
        Map<String, String> after = EnvVars.toMap(now);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            if (!before.containsKey(name)) {
                added.add(name);
            } else if (!after.containsKey(name)) {
                removed.add(name);
            } else if (!Objects.equals(before.get(name), after.get(name))) {
                changed.add(name);
            }
        }
        return new SpecDiff.Entries(key, added, removed, changed);
    }

    /** @return a snapshot's map, empty for none or for text that is no map */
    private static @NonNull Map<String, Object> parsed(@Nullable String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return Map.of();
        }
        Map<String, Object> map = new TreeMap<>();
        if (Zenit.DRY.parse(snapshot) instanceof Map<?, ?> stored) {
            stored.forEach((key, value) -> map.put(String.valueOf(key), value));
        }
        return map;
    }
}
