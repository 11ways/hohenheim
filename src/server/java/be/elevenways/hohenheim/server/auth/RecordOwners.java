package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantAdministration;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static be.elevenways.hohenheim.server.auth.HohenheimAccess.MANAGE;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.SUBJECT_SEPARATOR;

/**
 * Record ownership as the set of {@link HohenheimAccess#MANAGE} grant subjects: comparison,
 * packing, labelling and the creation-owner derivation; reached through {@link HohenheimAccess}.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class RecordOwners {

    /**
     * The explicit creation-owner override (a PROJECT create): set by the one funnel
     * that validated the actor's membership, read by every consumer of
     * {@link #creationOwnerSubjects} -- so the quota charge, the placement decision
     * and the planted grant follow the override as ONE derivation, never three.
     */
    private static final ThreadLocal<@Nullable Set<String>> CREATION_OWNER =
        new ThreadLocal<>();

    private RecordOwners() {
    }

    /**
     * Whether two records of one model answer to the SAME owner, which is what separates
     * a deliberate configuration from a cross-tenant seizure.
     *
     * AIDEV-NOTE: ownership is the record's set of {@link HohenheimAccess#MANAGE} grant
     * SUBJECTS, never an owner column -- InstanceModel deliberately has NO owner_principal_id,
     * and this method is THE one derivation every tier (routes, released claims, instances)
     * answers from; a second spelling is how two authorities drift. Two records an operator
     * alone controls hold no manage grants at all, so they compare equal and an admin may
     * deliberately point a wildcard at one site and carve one host out to another (a
     * shipped, dispatch-tested capability -- exact beats wildcard, and two upstreams need
     * two sites). The moment either side is TENANT-held, the subject sets differ and the
     * same shape becomes a takeover. Equality, not overlap: {A} versus {A, B} would let B
     * seize what A was serving. Mirrors WorkloadIdentity.isTenantManaged, which is the
     * same tenancy predicate one seam over.
     *
     * @return true when both records carry the same manage-grant subjects (both empty
     *         included), failing CLOSED to "different owners" when grants cannot be read
     */
    static boolean sameOwner(@NonNull Identifier model, @NonNull Object firstId,
                             @NonNull Object secondId) {
        // Grants key records by their stringified id, so identity folds the same way.
        if (String.valueOf(firstId).equals(String.valueOf(secondId))) {
            return true;
        }
        Set<String> first = manageSubjectsOf(model, firstId);
        Set<String> second = manageSubjectsOf(model, secondId);
        return first != null && second != null && first.equals(second);
    }

    /** Site convenience over {@link #sameOwner(Identifier, Object, Object)}. */
    static boolean sameOwner(int firstSiteId, int secondSiteId) {
        return sameOwner(SiteModel.MODEL_ID, firstSiteId, secondSiteId);
    }

    /**
     * THE owner identity of a record: the subjects holding {@link HohenheimAccess#MANAGE} on
     * it, spelled {@code subjectType:subjectId}. An EMPTY set means operator-owned (nobody was
     * granted anything), which is why it is a legitimate value and never an error.
     *
     * AIDEV-NOTE: public (on the HohenheimAccess facade) because the released-claim ledger
     * (ReleasedClaims) must STORE this exact set at release time and compare a later
     * claimant against it. It is the same
     * authority {@link #sameOwner} answers from -- a second spelling of "who owns this
     * record" is how the quarantine and the overlap refusal would end up disagreeing.
     *
     * AIDEV-NOTE: only LIVE grant rows count, read through zenit-auth's own
     * {@link GrantAdministration#liveRecordGrantRows} -- an expired grant decides nothing
     * in the check path, so it must not keep a tenant the OWNER here either. Until
     * 2026-09-23 this counted every stored row with value=true, so an expired manage grant
     * still made its holder "owner" for sameOwner, the quota bucket and the released-claim
     * ledger while the walk had already stopped honouring it.
     *
     * AIDEV-NOTE: "zenit-auth is not installed" is asked through its presence fact
     * ({@link AuthModels#datasourceOrNull}), never inferred from an exception. The previous
     * {@code catch (IllegalStateException)} read ANY IllegalStateException from the grant
     * read as "no auth, operator-owned" and answered the empty set -- so an unrelated
     * failure made two tenants' records compare as the same owner (fail OPEN).
     *
     * @return the manage-grant subjects, or null when grants are unreadable (callers fail closed)
     */
    static @Nullable Set<String> manageSubjectsOf(@NonNull Identifier model,
                                                  @NonNull Object recordId) {
        Set<String> subjects = new HashSet<>();
        if (AuthModels.datasourceOrNull() == null) {
            // ZenitAuth.init never ran (tools, minimal tests): no grant can exist, so every
            // record is operator-owned and the sets are legitimately equal.
            return subjects;
        }
        try {
            for (Row grant : GrantAdministration.liveRecordGrantRows(model, recordId)) {
                if (MANAGE.equals(grant.get(RecordGrantModel.CAPABILITY))
                        && Boolean.TRUE.equals(grant.get(RecordGrantModel.VALUE))) {
                    subjects.add(GrantSubjects.tokenOf(grant));
                }
            }
        } catch (RuntimeException unreadable) {
            return null;
        }
        return subjects;
    }

    /**
     * THE canonical packing of a subject set (released-claim ledger, quota bucket keys):
     * sorted and newline-joined, so two spellings of one owner set can never compare
     * unequal. EMPTY packs to "" -- the operator. A second packing beside this one is how
     * the quarantine and the quota would end up disagreeing about who an owner is.
     */
    static @NonNull String packSubjects(@NonNull Set<String> subjects) {
        return String.join(SUBJECT_SEPARATOR, new TreeSet<>(subjects));
    }

    /** The inverse of {@link #packSubjects}; null/"" parses to the empty (operator) set. */
    static @NonNull Set<String> parseSubjects(@Nullable Object packed) {
        if (packed == null) {
            return Set.of();
        }
        String raw = String.valueOf(packed);
        if (raw.isEmpty()) {
            return Set.of();
        }
        Set<String> subjects = new LinkedHashSet<>();
        for (String part : raw.split(SUBJECT_SEPARATOR)) {
            if (!part.isEmpty()) {
                subjects.add(part);
            }
        }
        return subjects;
    }

    /**
     * THE human label of ONE packed subject: the user's display name (else its email) or
     * the group's title (else its slug).
     *
     * AIDEV-NOTE: it lives beside {@link #packSubjects}/{@link #parseSubjects} because it
     * reads the SAME {@code subjectType:subjectId} vocabulary those write -- a labeller
     * that cuts the token itself somewhere else is how a third subject type would end up
     * rendered as a raw id by half the surfaces. An unresolvable subject renders as its
     * RAW token on purpose: a deleted user is a fact worth showing, and a released claim's
     * former owner is usually exactly that.
     */
    static @NonNull String subjectLabel(@NonNull String subject) {
        GrantSubjects.Subject parsed = GrantSubjects.parse(subject);
        if (parsed == null) {
            return subject;
        }
        String label = switch (parsed.type()) {
            case USER -> {
                Row user = AuthModels.users().findById(parsed.id());
                yield user == null ? null : firstNonBlank(user.get(UserModel.DISPLAY_NAME),
                    user.get(UserModel.EMAIL));
            }
            case GROUP -> {
                Row group = AuthModels.permissionGroups().findById(parsed.id());
                yield group == null ? null : firstNonBlank(group.get(PermissionGroupModel.TITLE),
                    group.get(PermissionGroupModel.SLUG));
            }
        };
        return label != null ? label : subject;
    }

    /**
     * A whole packed subject set rendered for a reader, in the canonical order.
     *
     * @return the joined labels, empty for the operator-owned (empty) set
     */
    static @NonNull String labelSubjects(@Nullable Object packed) {
        StringBuilder labels = new StringBuilder();
        for (String subject : parseSubjects(packed)) {
            if (labels.length() > 0) {
                labels.append(", ");
            }
            labels.append(subjectLabel(subject));
        }
        return labels.toString();
    }

    private static @Nullable String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    /**
     * Run {@code body} with the creation-owner derivation pinned to {@code subjects}
     * (a validated project subject). Nesting is refused: two pending owners on one
     * thread means two funnels interleaved, which is a bug, not a use case.
     *
     * @throws IllegalStateException when a creation owner is already pinned
     */
    static void withCreationOwner(@NonNull Set<String> subjects, @NonNull Runnable body) {
        if (CREATION_OWNER.get() != null) {
            throw new IllegalStateException("A creation owner is already pinned on this thread");
        }
        CREATION_OWNER.set(Set.copyOf(subjects));
        try {
            body.run();
        } finally {
            CREATION_OWNER.remove();
        }
    }

    /**
     * THE owner identity a NEW record created by this context will answer to: an
     * explicitly pinned owner (a validated project create) when one is active, else the
     * acting user's own subject, or the empty (operator) set for admins and system work.
     * One derivation, because the quota bucket charged at create, the placement decision
     * and the manage grant planted right after MUST name the same owner -- two spellings
     * is how a tenant's instance ends up charged to the operator's bucket.
     */
    static @NonNull Set<String> creationOwnerSubjects(@Nullable AccessContext ctx) {
        Set<String> pinned = CREATION_OWNER.get();
        if (pinned != null) {
            return pinned;
        }
        if (ctx == null || HohenheimAccess.isAdmin(ctx) || ctx.isAnonymous()) {
            return Set.of();
        }
        Long principalId = ctx.principalId();
        return principalId == null ? Set.of() : Set.of(GrantSubjects.userToken(principalId));
    }

    /**
     * Hand the creation owner ({@link #creationOwnerSubjects}) {@link HohenheimAccess#MANAGE}
     * on a record it just created, then drop the request memo the grant just made stale.
     * Operator and system creates plant nothing: an empty subject set IS operator ownership,
     * and a grant there would make one admin's record look tenant-held to sameOwner.
     *
     * AIDEV-NOTE: THE one planting loop. Four hand-rolled copies (instances, databases, git
     * providers, access lists) each cut the token with indexOf(':') and only two of them
     * remembered to drop the memo -- without that, zenit-cms verifying the created row
     * against the caller's own scope predicate makes a legitimate create refuse ITSELF
     * with out_of_scope. It MUST name the same subjects the quota charged at create.
     */
    static void grantCreatorManage(@NonNull Identifier model, @NonNull Object recordId,
                                   @Nullable AccessContext ctx) {
        for (String token : creationOwnerSubjects(ctx)) {
            GrantSubjects.Subject subject = GrantSubjects.require(token);
            RecordGrants.grant(subject.type(), subject.id(), model, recordId, MANAGE, true);
        }
        if (ctx != null) {
            CapabilityScopes.forgetCapabilityScopes(ctx);
        }
    }

    /**
     * Undo {@link #grantCreatorManage} for a create that is being compensated.
     *
     * AIDEV-NOTE: callers delete the record FIRST and revoke after: the delete rides the
     * tenant-write hooks, which ask for a capability the creator's own manage implies, so
     * revoking first makes the compensation refuse itself.
     */
    static void revokeCreatorManage(@NonNull Identifier model, @NonNull Object recordId,
                                    @Nullable AccessContext ctx) {
        for (String token : creationOwnerSubjects(ctx)) {
            GrantSubjects.Subject subject = GrantSubjects.require(token);
            RecordGrants.revoke(subject.type(), subject.id(), model, recordId, MANAGE);
        }
        if (ctx != null) {
            CapabilityScopes.forgetCapabilityScopes(ctx);
        }
    }
}
