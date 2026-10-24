package be.elevenways.hohenheim;

import be.elevenways.hohenheim.source.GitSourceSchema;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The stored secrets a page may reveal on request ({@link HohenheimEndpoints#SECRETS_REVEAL}): a page never renders
 * one of these values, it renders the reveal control naming the member, and each press fetches the value afresh.
 *
 * AIDEV-NOTE: the server's gate, record and value per member are an exhaustive switch in {@code SecretReveals}, so a
 * new member does not compile until it says who may read it. The token travels in the reveal URL; an unknown token
 * answers 404 like a refused one.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum StoredSecret {

    /** A managed database's password ({@code databases.db_password}). */
    DATABASE_PASSWORD("database_password", "db_password"),

    /** The push webhook secret of an instance carrying a git source, a key of its settings map. */
    WEBHOOK_SECRET("webhook_secret", "settings." + GitSourceSchema.WEBHOOK_SECRET);

    private final String token;
    private final String field;

    StoredSecret(@NonNull String token, @NonNull String field) {
        this.token = token;
        this.field = field;
    }

    /** @return the token naming this secret in the reveal URL and in a page's reveal control */
    public @NonNull String token() {
        return this.token;
    }

    /** @return the dotted field path on the record's model, the {@code field} of the activity entry a reveal records */
    public @NonNull String field() {
        return this.field;
    }

    /** @return the member the token names, null for an unknown one */
    public static @Nullable StoredSecret forToken(@Nullable String token) {
        for (StoredSecret secret : values()) {
            if (secret.token.equals(token)) {
                return secret;
            }
        }
        return null;
    }
}
