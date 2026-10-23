package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.instance.VariableKind;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tenant-safe redaction of an instance's console/log stream: the values the control plane KNOWS are secret for THIS
 * instance are handed to zenit's {@link KnownValueRedactor}, which replaces them with its placeholder before the text
 * reaches any ring, any viewer or any stored row.
 *
 * AIDEV-NOTE: the mechanism (by known value, never by pattern; the minimum length; the held-back tail across chunk
 * boundaries) is {@link KnownValueRedactor}'s. This class only answers which values are secret: the
 * {@code instance_variables} rows of kind {@code secret}, plus the same rows on the instance's environment. Its
 * minimum length is the ONE floor of every redaction in Hohenheim (consoles, build logs, release steps).
 *
 * AIDEV-NOTE: cloud-init user-data is {@code .secret()} as a whole DOCUMENT and is
 * deliberately NOT a redaction subject. It is content, not a value: matching a multi-KB YAML
 * blob against a console stream would never hit, while the secrets INSIDE it arrive through
 * {@code {{KEY}}} substitution from the very variables this class collects, so they are
 * covered by their own value.
 *
 * AIDEV-NOTE: an ADMIN sees the redacted stream too, and that is a decision, not an
 * oversight. Redaction happens at INGEST, on the single session ring that every viewer and
 * the stored log share; a viewer-dependent stream would mean keeping the raw text somewhere,
 * which is exactly the leak being closed (a stored secret is a leak even if every reader
 * redacts). The values themselves stay reachable at their own surface -- the variable rows,
 * whose {@code .secret()} declaration governs who may unmask them -- so nothing is lost
 * except the accident of a secret echoed into a log.
 */
public final class ConsoleRedaction {

    private ConsoleRedaction() {
    }

    /**
     * Every secret value that could legitimately appear in one instance's output: its own
     * secret variables plus the ones its environment contributes as the deploy baseline.
     * A value {@link KnownValueRedactor#accepts} refuses is dropped here, once, and the variables
     * carrying one are named in a warning (never their values): they are NOT redacted.
     */
    public static @NonNull Set<String> secretsOf(int instanceId) {
        Set<String> secrets = new LinkedHashSet<>();
        Set<String> refused = new TreeSet<>();
        try {
            // Trashed included: a destroyed record's logs still carry its environment secrets.
            Row instance = StoredRows.byId(Models.get(InstanceModel.class), instanceId);
            Integer environmentId = instance == null ? null
                : instance.get(InstanceModel.ENVIRONMENT_ID);
            if (environmentId != null) {
                collect(secrets, refused, Models.get(InstanceVariableModel.class)
                    .findByEnvironmentId(environmentId));
            }
            collect(secrets, refused, Models.get(InstanceVariableModel.class).findByInstanceId(instanceId));
        } catch (RuntimeException unreadable) {
            // A console must still open when the variable table cannot be read, but it must
            // say so: an empty secret set means NOTHING is redacted.
            Blast.log("CONSOLE: could not read the secret values of instance", instanceId,
                "- its output is NOT redacted:", unreadable.getMessage());
        }
        if (!refused.isEmpty()) {
            Blast.log("CONSOLE: secret variables", refused, "of instance", instanceId, "are shorter than",
                KnownValueRedactor.MIN_LENGTH, "characters and are NOT redacted from its output");
        }
        return secrets;
    }

    private static void collect(@NonNull Set<String> into, @NonNull Set<String> refused,
                                @NonNull Iterable<Row> rows) {
        for (Row row : rows) {
            // Fail closed: a kind nobody recognizes is redacted like a secret, from
            // whichever column carries its value.
            if (!VariableKind.of(row.get(InstanceVariableModel.KIND)).isSecret()) {
                continue;
            }
            for (String value : new String[] {row.get(InstanceVariableModel.SECRET_VALUE),
                    row.get(InstanceVariableModel.PLAIN_VALUE)}) {
                if (KnownValueRedactor.accepts(value)) {
                    into.add(value);
                } else if (value != null && !value.isEmpty()) {
                    refused.add(String.valueOf((Object) row.get(InstanceVariableModel.KEY)));
                }
            }
        }
    }

    /** A redactor over one instance's secret values ({@link #secretsOf}), for ONE stream of its output. */
    public static @NonNull KnownValueRedactor redactorFor(int instanceId) {
        return KnownValueRedactor.of(secretsOf(instanceId));
    }

    /**
     * Redaction of one WHOLE text (the {@code logs} tail read, a console backlog) as the one stream it is: a fresh
     * redactor is fed all of it, then flushed, so a multi-line secret is matched across its lines and a secret the
     * text was cut off in is masked. Never call it per line of a text: a line alone never shows a multi-line value.
     */
    public static @NonNull String redactWhole(@NonNull String text, int instanceId) {
        KnownValueRedactor redactor = redactorFor(instanceId);
        return redactor.feed(text) + redactor.flush();
    }
}
