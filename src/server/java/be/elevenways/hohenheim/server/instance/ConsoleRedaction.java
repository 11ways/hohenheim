package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tenant-safe redaction of an instance's console/log stream: the values the control plane
 * KNOWS are secret for THIS instance are replaced with {@link #PLACEHOLDER} before the text
 * reaches any ring, any viewer or any stored row.
 *
 * AIDEV-NOTE: redaction is BY KNOWN VALUE, never by pattern. A shape matcher ("looks like a
 * token", "looks like a connection string") produces false negatives -- the one that
 * matters, because the secret that does not match its guessed shape is published verbatim --
 * and false positives that silently corrupt legitimate output an operator is trying to read.
 * The control plane already holds every value it declared secret ({@code instance_variables}
 * rows of kind {@code secret}, plus the same rows on the instance's environment), so the
 * honest question is "did this exact value appear", and it has an exact answer.
 *
 * AIDEV-NOTE: cloud-init user-data is {@code .secret()} as a whole DOCUMENT and is
 * deliberately NOT a redaction subject. It is content, not a value: matching a multi-KB YAML
 * blob against a console stream would never hit, while the secrets INSIDE it arrive through
 * {@code {{KEY}}} substitution from the very variables this class collects, so they are
 * covered by their own value.
 *
 * AIDEV-NOTE: MINIMUM LENGTH {@value #MIN_SECRET_LENGTH}. A short value collides with
 * ordinary output by coincidence, and replacing every occurrence of a 3-character password
 * would shred unrelated text (log levels, hex bytes, words) while teaching a viewer nothing.
 * The accepted consequence is explicit: a secret shorter than the minimum is NOT redacted.
 * That is a statement about the value -- something that short is not a secret -- and not a
 * silent best effort.
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

    /** What a redacted value is replaced with, in every stream and every stored row. */
    public static final String PLACEHOLDER = "[redacted]";

    /** Values shorter than this are left alone; see the class note. */
    public static final int MIN_SECRET_LENGTH = 8;

    private ConsoleRedaction() {
    }

    /**
     * Every secret value that could legitimately appear in one instance's output: its own
     * secret variables plus the ones its environment contributes as the deploy baseline.
     * Values below {@link #MIN_SECRET_LENGTH} are dropped here, once, so no consumer has to
     * remember the rule.
     */
    public static @NonNull Set<String> secretsOf(int instanceId) {
        Set<String> secrets = new LinkedHashSet<>();
        try {
            Row instance = Models.get(InstanceModel.class).findById(instanceId);
            Integer environmentId = instance == null ? null
                : instance.get(InstanceModel.ENVIRONMENT_ID);
            if (environmentId != null) {
                collect(secrets, Models.get(InstanceVariableModel.class)
                    .findByEnvironmentId(environmentId));
            }
            collect(secrets, Models.get(InstanceVariableModel.class).findByInstanceId(instanceId));
        } catch (RuntimeException unreadable) {
            // A console must still open when the variable table cannot be read, but it must
            // say so: an empty secret set means NOTHING is redacted.
            Blast.log("CONSOLE: could not read the secret values of instance", instanceId,
                "- its output is NOT redacted:", unreadable.getMessage());
        }
        return secrets;
    }

    private static void collect(@NonNull Set<String> into, @NonNull Iterable<Row> rows) {
        for (Row row : rows) {
            if (!InstanceVariableModel.KIND_SECRET.equals(row.get(InstanceVariableModel.KIND))) {
                continue;
            }
            String value = row.get(InstanceVariableModel.SECRET_VALUE);
            if (value != null && value.length() >= MIN_SECRET_LENGTH) {
                into.add(value);
            }
        }
    }

    /** The redactor for one instance's live session, seeded from {@link #secretsOf}. */
    public static @NonNull Redactor redactorFor(int instanceId) {
        return new Redactor(secretsOf(instanceId));
    }

    /** A redactor over an explicit value set: the no-database lane (tests, fakes). */
    public static @NonNull Redactor redactorOf(@NonNull Set<String> secrets) {
        return new Redactor(secrets);
    }

    /**
     * One-shot redaction of a COMPLETE text (the {@code logs} tail read, a stored row):
     * nothing is held back because there is no later chunk to fuse with.
     */
    public static @NonNull String redactWhole(@NonNull String text, int instanceId) {
        return redactorFor(instanceId).redactComplete(text);
    }

    /** One-shot redaction against an EXISTING session redactor, leaving its stream buffer alone. */
    public static @NonNull String redactWhole(@NonNull String text, @NonNull Redactor redactor) {
        return redactor.redactComplete(text);
    }

    /**
     * The streaming redactor of ONE console session.
     *
     * AIDEV-NOTE: CHUNK BOUNDARIES are handled, not assumed away. A secret split across two
     * websocket frames defeats per-chunk matching, so this holds back exactly the longest
     * suffix of its buffer that is a proper PREFIX of some secret and emits everything
     * before it. Ordinary output therefore holds back nothing at all (no lag, no
     * interactive prompt stuck behind a buffer), while a chunk that ends mid-secret waits
     * for the rest. {@link #flush()} releases the held tail when the stream ends, so a
     * near-miss cannot be swallowed either.
     */
    public static final class Redactor {

        private final StringBuilder pending = new StringBuilder();
        private final List<String> secrets = new ArrayList<>();
        private int longest;

        Redactor(@NonNull Set<String> secrets) {
            for (String secret : secrets) {
                this.addSecret(secret);
            }
        }

        /**
         * Teach this live redactor a value declared secret AFTER the session opened -- a
         * variable written while an operator watches must not stream in the clear until
         * the next attach.
         */
        public synchronized void addSecret(@Nullable String secret) {
            if (secret == null || secret.length() < MIN_SECRET_LENGTH
                    || this.secrets.contains(secret)) {
                return;
            }
            this.secrets.add(secret);
            // Longest first: a secret that contains another must be replaced whole.
            this.secrets.sort(Comparator.comparingInt(String::length).reversed());
            this.longest = Math.max(this.longest, secret.length());
        }

        /** Whether anything is being redacted at all (the fast path, and a test seam). */
        public synchronized boolean isActive() {
            return !this.secrets.isEmpty();
        }

        /**
         * Feed one raw chunk.
         *
         * @return the text that is safe to emit now (possibly empty)
         */
        public synchronized @NonNull String feed(@NonNull String chunk) {
            if (this.secrets.isEmpty()) {
                return chunk;
            }
            this.pending.append(chunk);
            this.replaceKnown();
            int hold = this.holdBack();
            int cut = this.pending.length() - hold;
            String emit = this.pending.substring(0, cut);
            this.pending.delete(0, cut);
            return emit;
        }

        /** Redact a text that is complete in itself; the streaming buffer is untouched. */
        public synchronized @NonNull String redactComplete(@NonNull String text) {
            String redacted = text;
            for (String secret : this.secrets) {
                redacted = redacted.replace(secret, PLACEHOLDER);
            }
            return redacted;
        }

        /** Release whatever is still held back (stream end, or a one-shot read). */
        public synchronized @NonNull String flush() {
            if (this.pending.length() == 0) {
                return "";
            }
            this.replaceKnown();
            String emit = this.pending.toString();
            this.pending.setLength(0);
            return emit;
        }

        private void replaceKnown() {
            String text = this.pending.toString();
            String replaced = text;
            for (String secret : this.secrets) {
                replaced = replaced.replace(secret, PLACEHOLDER);
            }
            if (!replaced.equals(text)) {
                this.pending.setLength(0);
                this.pending.append(replaced);
            }
        }

        /** The longest suffix of the buffer that could still grow into a secret. */
        private int holdBack() {
            int max = Math.min(this.longest - 1, this.pending.length());
            for (int k = max; k > 0; k--) {
                String tail = this.pending.substring(this.pending.length() - k);
                for (String secret : this.secrets) {
                    if (secret.length() > k && secret.startsWith(tail)) {
                        return k;
                    }
                }
            }
            return 0;
        }
    }
}
