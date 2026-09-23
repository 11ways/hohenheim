package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.model.InstanceVariableModel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE vocabulary of an instance variable row's {@code kind}: where its value is stored and
 * whether it is ever shown.
 *
 * AIDEV-NOTE: the stored tokens stay the {@link InstanceVariableModel} constants (the
 * column's declared enum values), and {@code VariableKindVocabularyDriftTest} binds the
 * two sets. A READER fails closed: {@link #of} maps a null or unknown token to
 * {@link #SECRET}, so a value whose kind nobody recognizes is redacted and never
 * projected, instead of being read as plain text the way {@code KIND_SECRET.equals(k)
 * ? secret : plain} did.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum VariableKind {

    /** Stored in {@code plain_value}; shown and exported as is. */
    PLAIN(InstanceVariableModel.KIND_PLAIN, false),

    /** Stored ONLY in the encrypted {@code secret_value}; redacted and never echoed. */
    SECRET(InstanceVariableModel.KIND_SECRET, true);

    private final String token;
    private final boolean secret;

    VariableKind(@NonNull String token, boolean secret) {
        this.token = token;
        this.secret = secret;
    }

    /** @return the stored column value */
    public @NonNull String token() {
        return this.token;
    }

    /** @return whether values of this kind are secrets */
    public boolean isSecret() {
        return this.secret;
    }

    /** @return the member stored as {@code token}; null and unknown tokens read as {@link #SECRET} */
    public static @NonNull VariableKind of(@Nullable Object token) {
        VariableKind known = parse(token);
        return known != null ? known : SECRET;
    }

    /** @return the member stored as {@code token}, or null when it is no member (a writer refuses it) */
    public static @Nullable VariableKind parse(@Nullable Object token) {
        if (token == null) {
            return null;
        }
        String text = token.toString();
        for (VariableKind kind : values()) {
            if (kind.token.equals(text)) {
                return kind;
            }
        }
        return null;
    }
}
