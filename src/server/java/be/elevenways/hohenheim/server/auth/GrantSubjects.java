package be.elevenways.hohenheim.server.auth;

import be.elevenways.zenit.auth.cms.SubjectTag;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.UUID;

/**
 * A grant subject as an owner token, the unit every owner set ({@link HohenheimAccess#manageSubjectsOf}, the
 * released-claim ledger, the quota bucket keys) is packed from: zenit-auth's {@link SubjectTag} spelling.
 *
 * AIDEV-NOTE: the token is STORED (packed owner sets in released claims, quota override rows and bucket keys), so its
 * shape is SubjectTag's ({@code <type key>:<uuid>}), never a spelling of this module's own; the rekey migration moved
 * every stored token from the pre-UUID {@code <type key>:<number>} to it.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class GrantSubjects {

    private GrantSubjects() {
    }

    /**
     * The token a stored grant row spells.
     *
     * AIDEV-NOTE: verbatim for a subject type this process does not know: an owner set holding an unknown member
     * must still compare UNEQUAL to every other set (sameOwner fails closed that way), which dropping or normalizing
     * the member would undo.
     */
    public static @NonNull String tokenOf(@NonNull Row grant) {
        String type = grant.get(RecordGrantModel.SUBJECT_TYPE);
        UUID id = grant.get(RecordGrantModel.SUBJECT_ID);
        GrantSubjectType known = GrantSubjectType.fromStored(type);
        return known != null && id != null ? new SubjectTag(known, id).token() : type + ":" + id;
    }

    /**
     * The WRITE boundary of a token, for a token this process derived itself.
     *
     * @throws IllegalArgumentException when the token does not parse
     */
    public static @NonNull SubjectTag require(@NonNull String token) {
        SubjectTag subject = SubjectTag.parse(token);
        if (subject == null) {
            throw new IllegalArgumentException("Not a grant subject token: " + token);
        }
        return subject;
    }
}
