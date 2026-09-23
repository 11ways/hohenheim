package be.elevenways.hohenheim.server.auth;

import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE spelling of one grant subject as an owner token ({@code type:id}), the unit every
 * owner set ({@link HohenheimAccess#manageSubjectsOf}, the released-claim ledger, the quota
 * bucket keys) is packed from.
 *
 * AIDEV-NOTE: the TYPE half is zenit-auth's {@link GrantSubjectType#key()}, never a
 * hand-spelled "user"/"group" literal: that enum is the declaring home of the subject
 * vocabulary, and a third subject type must reach every reader here without an edit. The
 * token format itself is STORED (packed owner sets in released_claims and quota bucket keys
 * of a running installation), so it never changes shape: {@code key() + ":" + id}, exactly
 * what the rows written before this class existed carry.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class GrantSubjects {

    /** Separates the type key from the subject id inside one token. */
    private static final char SEPARATOR = ':';

    private GrantSubjects() {
    }

    /**
     * One parsed subject token.
     *
     * @param type the subject type the token names
     * @param id   the subject's id
     */
    public record Subject(@NonNull GrantSubjectType type, int id) {

        /** @return the token this subject is spelled as */
        public @NonNull String token() {
            return GrantSubjects.token(this.type, this.id);
        }
    }

    /** @return the owner token of one subject */
    public static @NonNull String token(@NonNull GrantSubjectType type, long id) {
        return type.key() + SEPARATOR + id;
    }

    /** @return the owner token of one user */
    public static @NonNull String userToken(long userId) {
        return token(GrantSubjectType.USER, userId);
    }

    /**
     * The token a stored grant row spells.
     *
     * AIDEV-NOTE: verbatim, even for a subject type this process does not know: an owner set
     * holding an unknown member must still compare UNEQUAL to every other set (sameOwner
     * fails closed that way), which dropping or normalizing the member would undo.
     */
    public static @NonNull String tokenOf(@NonNull Row grant) {
        return grant.get(RecordGrantModel.SUBJECT_TYPE) + String.valueOf(SEPARATOR)
            + grant.get(RecordGrantModel.SUBJECT_ID);
    }

    /**
     * The READ boundary of a token.
     *
     * @return the parsed subject, or null when the token is malformed or names a subject
     *         type zenit-auth does not declare
     */
    public static @Nullable Subject parse(@Nullable String token) {
        if (token == null) {
            return null;
        }
        int separator = token.indexOf(SEPARATOR);
        if (separator <= 0) {
            return null;
        }
        GrantSubjectType type = GrantSubjectType.fromStored(token.substring(0, separator));
        if (type == null) {
            return null;
        }
        try {
            return new Subject(type, Integer.parseInt(token.substring(separator + 1)));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /**
     * The WRITE boundary of a token, for a token this process derived itself.
     *
     * @throws IllegalArgumentException when the token does not parse
     */
    public static @NonNull Subject require(@NonNull String token) {
        Subject subject = parse(token);
        if (subject == null) {
            throw new IllegalArgumentException("Not a grant subject token: " + token);
        }
        return subject;
    }
}
