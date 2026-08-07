package be.elevenways.hohenheim.server.project;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.PermissionResolver;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The project tier's one policy home. A project's OWNER identity is its zenit-auth
 * permission group: a record is project-owned when its {@code manage} grant is held
 * by the subject {@code group:<group_id>} and by nothing else, membership is a
 * positive {@code group.<slug>} grant (the framework's one membership walk), and
 * every existing derivation -- sameOwner, quota buckets, placement, the released-claim
 * quarantine -- keeps answering from HohenheimAccess.manageSubjectsOf unchanged.
 *
 * AIDEV-NOTE: "which project owns record X" is DERIVED (subject-set equality), never
 * a column. A record whose subject set carries the project group PLUS extra subjects
 * is deliberately NOT project-owned: partial overlap is a different owner, exactly as
 * sameOwner treats it.
 */
public final class Projects {

    /** Membership permissions are {@code group.<slug>}; the resolver's group walk. */
    private static final String GROUP_PERMISSION_PREFIX = "group.";

    /** Every project group slug starts with this; the rest derives from the name. */
    public static final String GROUP_SLUG_PREFIX = "project-";

    private Projects() {
    }

    // -- identity -------------------------------------------------------------

    /** The owner subject token of a project ({@code group:<id>}). */
    public static @NonNull String subjectOf(@NonNull Row project) {
        Integer groupId = project.get(ProjectModel.GROUP_ID);
        if (groupId == null) {
            throw new IllegalStateException("Project " + project.get(ProjectModel.ID)
                + " carries no owner group; the ProjectGuards write hook did not run");
        }
        return "group:" + groupId;
    }

    /** The owner subject SET a record owned by this project answers with. */
    public static @NonNull Set<String> ownerSubjectsOf(@NonNull Row project) {
        return Set.of(subjectOf(project));
    }

    /**
     * The project whose owner subject set this is: exactly one {@code group:<id>}
     * token naming a project's group. Any other shape -- empty, per-user, mixed --
     * is not a project owner.
     */
    public static @Nullable Row projectForSubjects(@Nullable Set<String> subjects) {
        if (subjects == null || subjects.size() != 1) {
            return null;
        }
        String token = subjects.iterator().next();
        if (!token.startsWith("group:")) {
            return null;
        }
        int groupId;
        try {
            groupId = Integer.parseInt(token.substring("group:".length()));
        } catch (NumberFormatException malformed) {
            return null;
        }
        return Models.get(ProjectModel.class).find()
            .where(ProjectModel.GROUP_ID.eq(groupId)).first();
    }

    /** The project owning a record, derived from its manage-grant subject set. */
    public static @Nullable Row projectOf(@NonNull Identifier model, @NonNull Object recordId) {
        return projectForSubjects(HohenheimAccess.manageSubjectsOf(model, recordId));
    }

    // -- membership -----------------------------------------------------------

    /** The membership permission of a project's group ({@code group.<slug>}). */
    public static @NonNull String membershipPermissionOf(@NonNull Row project) {
        Integer groupId = project.get(ProjectModel.GROUP_ID);
        Row group = groupId == null ? null : AuthModels.permissionGroups().findById(groupId);
        if (group == null) {
            throw new IllegalStateException("Project " + project.get(ProjectModel.ID)
                + " references a missing permission group " + groupId);
        }
        return GROUP_PERMISSION_PREFIX + group.get(PermissionGroupModel.SLUG);
    }

    /**
     * Whether the context may create records INTO this project: an admin always, a
     * member (the group is among the context's expanded subjects) otherwise.
     */
    public static boolean mayCreateInto(@Nullable AccessContext ctx, @NonNull Row project) {
        if (ctx == null) {
            return false;
        }
        if (HohenheimAccess.isAdmin(ctx)) {
            return true;
        }
        Long principalId = ctx.principalId();
        if (principalId == null || ctx.isAnonymous()) {
            return false;
        }
        String subject = subjectOf(project);
        for (PermissionResolver.Subject expanded
                : PermissionResolver.expandSubjects("user", principalId.intValue())) {
            if (subject.equals(expanded.type() + ":" + expanded.id())) {
                return true;
            }
        }
        return false;
    }

    /** Add a user as a project member (a positive membership grant on its group). */
    public static void addMember(@NonNull Row project, int userId) {
        GrantService.createDirectGrant("user", userId, membershipPermissionOf(project), true);
    }

    /** Add a whole permission group as a member (groups nest through the same walk). */
    public static void addMemberGroup(@NonNull Row project, int groupId) {
        GrantService.createDirectGrant("group", groupId, membershipPermissionOf(project), true);
    }

    /**
     * THE project-visibility policy every listing surface routes through: an admin sees
     * every project, everyone else the projects they are a MEMBER of -- but a
     * scope-narrowed API key sees none unless its scopes cover the vocabulary
     * project-owned records answer to.
     *
     * AIDEV-NOTE: membership is grant-derived, NOT a record capability, so the capability
     * walk -- the one place zenit-auth applies a key's scope narrowing -- never runs for a
     * membership listing. Without coversOwnedVocabulary below, a key narrowed to an
     * unrelated scope still enumerates its owner's projects: authority it was never
     * granted. This lived as three separate derivations (the PaaS API, the from-template
     * pick, and every future surface); two of them were unguarded. It is ONE derivation
     * now precisely so the next surface cannot forget.
     */
    public static @NonNull List<Row> visibleTo(@NonNull AccessContext ctx) {
        if (HohenheimAccess.isAdmin(ctx)) {
            return Models.get(ProjectModel.class).find()
                .orderBy(ProjectModel.ID, SortOrder.ASC).all();
        }
        Long principalId = ctx.principalId();
        if (principalId == null || ctx.isAnonymous() || !coversOwnedVocabulary(ctx)) {
            return List.of();
        }
        return projectsOf(principalId.intValue());
    }

    /**
     * The criteria face of {@link #visibleTo}, for query-scoped surfaces.
     *
     * @return null for admins (no extra constraint), else a criteria over exactly the
     *         ids {@link #visibleTo} enumerates -- never a second derivation
     */
    public static @Nullable Criteria visibleScope(@NonNull AccessContext ctx) {
        if (HohenheimAccess.isAdmin(ctx)) {
            return null;
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (Row project : visibleTo(ctx)) {
            Integer id = project.get(ProjectModel.ID);
            if (id != null) {
                ids.add(id);
            }
        }
        // NEVER ID.in(empty), which some backends reject or widen.
        return ids.isEmpty() ? Models.get(ProjectModel.class).matchNone() : ProjectModel.ID.in(ids);
    }

    /**
     * Whether the principal's credential covers the vocabulary every project-owned record
     * answers to. Only API keys carry scopes; a session principal is unnarrowed.
     */
    private static boolean coversOwnedVocabulary(@NonNull AccessContext ctx) {
        if (!(ctx.principal() instanceof ApiKeyPrincipal key)) {
            return true;
        }
        return key.coversCapability(SiteModel.MODEL_ID, HohenheimAccess.MANAGE)
            || key.coversCapability(InstanceModel.MODEL_ID, HohenheimAccess.MANAGE);
    }

    /**
     * Every project the user is a member of (direct or through nested groups).
     *
     * AIDEV-NOTE: package-private ON PURPOSE. This is the raw membership walk, with no
     * credential narrowing on it; the only supported entry point for a listing surface
     * is {@link #visibleTo}. Widening this back to public re-opens the hole by making
     * the unguarded derivation reachable again.
     */
    static @NonNull List<Row> projectsOf(int userId) {
        Set<Integer> groupIds = new LinkedHashSet<>();
        for (PermissionResolver.Subject expanded
                : PermissionResolver.expandSubjects("user", userId)) {
            if ("group".equals(expanded.type())) {
                groupIds.add(expanded.id());
            }
        }
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return Models.get(ProjectModel.class).find()
            .where(ProjectModel.GROUP_ID.in(groupIds)).all();
    }

    /** One direct membership of a project: the subject the positive grant names. */
    public record Member(@NonNull String subjectType, int subjectId) {
    }

    /**
     * The DIRECT members of a project: every subject holding a positive membership grant,
     * users and nested groups alike (a group's own members are not expanded -- they reach
     * the project through the resolver's walk, not through a row here).
     */
    public static @NonNull List<Member> directMembersOf(@NonNull Row project) {
        List<Member> members = new ArrayList<>();
        for (Row grant : AuthModels.grants().find()
                .where(GrantModel.PERMISSION.eq(membershipPermissionOf(project)))
                .all()) {
            String type = grant.get(GrantModel.SUBJECT_TYPE);
            Integer id = grant.get(GrantModel.SUBJECT_ID);
            if (Boolean.TRUE.equals(grant.get(GrantModel.VALUE)) && type != null && id != null) {
                members.add(new Member(type, id));
            }
        }
        return members;
    }

    // -- ownership moves ------------------------------------------------------

    /**
     * Move a record INTO a project: every existing {@code manage} grant on it is
     * revoked and the project's group becomes the one manage holder, so sameOwner,
     * the quota derivation and placement all flip in the same write. Callers gate
     * this (admin action / validated adoption) -- the move itself is mechanism.
     */
    public static void adoptRecord(@NonNull Row project, @NonNull Identifier model,
                                   @NonNull Object recordId) {
        String subject = subjectOf(project);
        for (Row grant : RecordGrants.listForRecord(model, recordId)) {
            if (!HohenheimAccess.MANAGE.equals(grant.get(RecordGrantModel.CAPABILITY))) {
                continue;
            }
            String type = grant.get(RecordGrantModel.SUBJECT_TYPE);
            Integer id = grant.get(RecordGrantModel.SUBJECT_ID);
            if (subject.equals(type + ":" + id)) {
                continue;
            }
            RecordGrants.revoke(type, id, model, recordId, HohenheimAccess.MANAGE);
        }
        Integer groupId = project.get(ProjectModel.GROUP_ID);
        RecordGrants.grant("group", groupId, model, recordId, HohenheimAccess.MANAGE, true);
    }

    // -- group lifecycle (called by ProjectGuards hooks) ----------------------

    /** Create the project's backing permission group; slug from the name, unique. */
    static int createGroupFor(@NonNull String projectName) {
        Model groups = AuthModels.permissionGroups();
        String base = GROUP_SLUG_PREFIX + safeSlug(projectName);
        String slug = base;
        int attempt = 2;
        while (groups.find().where(PermissionGroupModel.SLUG.eq(slug)).first() != null) {
            slug = base + "-" + attempt++;
        }
        Row group = groups.createEmptyRow();
        group.set(PermissionGroupModel.SLUG, slug);
        group.set(PermissionGroupModel.TITLE, projectName);
        groups.save(group);
        return group.get(PermissionGroupModel.ID);
    }

    /** Keep the group's TITLE mirroring a renamed project (the slug never moves). */
    static void syncGroupTitle(int groupId, @NonNull String projectName) {
        Row group = AuthModels.permissionGroups().findById(groupId);
        if (group != null && !projectName.equals(group.get(PermissionGroupModel.TITLE))) {
            group.set(PermissionGroupModel.TITLE, projectName);
            AuthModels.permissionGroups().save(group);
        }
    }

    /**
     * Tear down a deleted project's auth footprint: membership grants, record grants
     * held by the group, and the group row itself. Explicit, because nothing else
     * would -- the InstanceService.destroy lesson: cleanup that must happen needs a
     * call, never an assumed hook.
     */
    static void removeGroupFor(int groupId) {
        Row group = AuthModels.permissionGroups().findById(groupId);
        if (group != null) {
            String permission = GROUP_PERMISSION_PREFIX + group.get(PermissionGroupModel.SLUG);
            List<Integer> doomed = new ArrayList<>();
            for (Row grant : AuthModels.grants().find()
                    .where(GrantModel.PERMISSION.eq(permission)).all()) {
                doomed.add(grant.get(GrantModel.ID));
            }
            for (Integer grantId : doomed) {
                AuthModels.grants().delete(grantId);
            }
        }
        RecordGrants.revokeAllForSubject("group", groupId);
        if (group != null) {
            AuthModels.permissionGroups().delete(groupId);
        }
    }

    /**
     * The records a project still owns, per model: used by the delete guard.
     */
    static int ownedRecordCount(int groupId) {
        int count = 0;
        for (Row grant : RecordGrants.listForSubject("group", groupId)) {
            if (HohenheimAccess.MANAGE.equals(grant.get(RecordGrantModel.CAPABILITY))) {
                count++;
            }
        }
        return count;
    }

    /** The environments of a project (delete guard + pickers). */
    static long environmentCount(int projectId) {
        return Models.get(EnvironmentModel.class).find()
            .where(EnvironmentModel.PROJECT_ID.eq(projectId)).count();
    }

    private static @NonNull String safeSlug(@NonNull String name) {
        String slug = BlastString.slug(name);
        return slug.isEmpty() ? "unnamed" : slug;
    }

    static Violations refusal(String key) {
        return Violations.ofForm(Microcopy.of(key).withFilter("scope", "violations"));
    }
}
