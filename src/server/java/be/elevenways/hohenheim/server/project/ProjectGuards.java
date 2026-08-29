package be.elevenways.hohenheim.server.project;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.zenit.auth.cms.RoleOwnership;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The project tier's write-funnel invariants, on EVERY writer (forms, services,
 * direct saves): a project's backing auth group exists from the first write and is
 * immutable afterwards; a project cannot be deleted while it owns records or
 * environments, and its auth footprint dies with it explicitly; an environment
 * cannot be re-homed while in use; and a record can only sit in an environment
 * whose project OWNS it -- grouping may never disagree with the grants.
 */
public final class ProjectGuards {

    /** Where the project remove hooks stash the doomed group ids. */
    private static final String DOOMED_GROUPS = "hohenheim.project.doomed-groups";

    private static boolean installed;

    private ProjectGuards() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        // AIDEV-NOTE: the group is created in beforeWrite because the schema has no
        // afterWrite hook; a failed insert can therefore strand one empty group. That
        // is inert debris (no grants reference it), preferred over a project row that
        // exists without an owner identity.
        ProjectModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Row stored = storedProject(row);
            if (stored == null) {
                if (row.get(ProjectModel.GROUP_ID) == null) {
                    String name = String.valueOf((Object) row.get(ProjectModel.NAME));
                    row.set(ProjectModel.GROUP_ID, Projects.createGroupFor(name));
                }
                return;
            }
            Integer storedGroup = stored.get(ProjectModel.GROUP_ID);
            if (row.has(ProjectModel.GROUP_ID.getName())
                    && storedGroup != null
                    && !storedGroup.equals(row.get(ProjectModel.GROUP_ID))) {
                // Membership grants reference the group's slug: swapping the group
                // would silently swap every member and every owned record.
                throw Violations.ofField(ProjectModel.GROUP_ID.getName(),
                    row.get(ProjectModel.GROUP_ID),
                    violation("project_group_immutable"));
            }
            if (row.has(ProjectModel.NAME.getName()) && storedGroup != null) {
                Projects.syncGroupTitle(storedGroup,
                    String.valueOf((Object) row.get(ProjectModel.NAME)));
            }
        });

        // Delete guard + explicit auth-footprint teardown (hard deletes; projects
        // have no soft delete).
        ProjectModel.SCHEMA.addBeforeRemoveHook(ProjectGuards::refuseNonEmptyAndCapture);
        ProjectModel.SCHEMA.addAfterRemoveHook(ProjectGuards::removeDoomedGroups);

        // The other direction of the same invariant: /admin/roles offers a plain Delete on
        // every group, including the ones this tier creates, and taking one out there left
        // the project pointing at a group id that no longer existed -- every member and
        // every owned record silently ungrouped. The roles resource is final and exposes no
        // per-record seam, so the refusal is declared HERE, on the model, where it also
        // covers a direct save. The project's own teardown deletes the project row first,
        // so this never fires on removeGroupFor.
        PermissionGroupModel.SCHEMA.addBeforeRemoveHook(ProjectGuards::refuseProjectOwnedGroup);

        // ...and the FRONT of that same invariant: declaring the ownership to zenit-auth
        // takes the Delete affordance off a project-owned role entirely, so the hook above
        // is the last line of defence (a direct save, a second surface) rather than the
        // only one. The hook alone left the operator pressing a button that always failed.
        RoleOwnership.INSTANCE.register(new ProjectRoleOwner());

        // An environment cannot move to another project while anything references it:
        // that would re-home the grouping across owners without any grant moving.
        EnvironmentModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(EnvironmentModel.PROJECT_ID.getName())) {
                return;
            }
            Object id = row.has(EnvironmentModel.ID.getName())
                ? row.get(EnvironmentModel.ID) : null;
            if (id == null) {
                return;
            }
            Row stored = Models.get(EnvironmentModel.class).findById(id);
            if (stored == null) {
                return;
            }
            Integer storedProject = stored.get(EnvironmentModel.PROJECT_ID);
            if (storedProject == null || storedProject.equals(row.get(EnvironmentModel.PROJECT_ID))) {
                return;
            }
            EnvironmentUsage usage = usageOf((Integer) id);
            if (!usage.isEmpty()) {
                throw Violations.ofField(EnvironmentModel.PROJECT_ID.getName(),
                    row.get(EnvironmentModel.PROJECT_ID), usage.refusal());
            }
        });

        // Deleting an environment that is still referenced would orphan the grouping
        // and silently drop its variables out of every future deploy. The refusal NAMES
        // the holders: "instances or variables" left the operator guessing which.
        EnvironmentModel.SCHEMA.addBeforeRemoveHook(context -> {
            for (Row doomed : doomedRows(context)) {
                Integer id = doomed.get(EnvironmentModel.ID);
                if (id == null) {
                    continue;
                }
                EnvironmentUsage usage = usageOf(id);
                if (!usage.isEmpty()) {
                    throw Violations.ofForm(usage.refusal());
                }
            }
        });

        // THE grouping-follows-ownership invariant: an instance may only sit in an
        // environment whose project owns it (owner subject set == {project group}).
        // beforeValidate, NOT beforeWrite: the quota reservation fires in beforeWrite
        // (InstanceQuota installs first), so a beforeWrite refusal here would spend a
        // slot the aborted create never hands back -- the InstanceImagePolicy lesson.
        InstanceModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(InstanceModel.ENVIRONMENT_ID.getName())) {
                return;
            }
            Integer environmentId = row.get(InstanceModel.ENVIRONMENT_ID);
            if (environmentId == null) {
                return;
            }
            Row environment = Models.get(EnvironmentModel.class).findById(environmentId);
            if (environment == null) {
                throw Violations.ofField(InstanceModel.ENVIRONMENT_ID.getName(), environmentId,
                    violation("environment_unknown"));
            }
            Row project = Models.get(ProjectModel.class)
                .findById(environment.get(EnvironmentModel.PROJECT_ID));
            if (project == null) {
                throw Violations.ofField(InstanceModel.ENVIRONMENT_ID.getName(), environmentId,
                    violation("environment_unknown"));
            }
            Set<String> owner = ownerOf(row);
            if (owner == null || !owner.equals(Projects.ownerSubjectsOf(project))) {
                // Fails CLOSED on unreadable grants: grouping without a proven owner
                // match is exactly the drift this hook exists to refuse.
                throw Violations.ofField(InstanceModel.ENVIRONMENT_ID.getName(), environmentId,
                    violation("environment_project_mismatch"));
            }
        });
    }

    /**
     * The owner the instance answers to for the grouping decision: the stored record's
     * grant-derived subjects, or -- at create, when no grants can exist yet -- the same
     * creation-owner derivation the quota charge and the planted grant use.
     */
    private static @Nullable Set<String> ownerOf(@NonNull Row row) {
        Object id = row.has(InstanceModel.ID.getName()) ? row.get(InstanceModel.ID) : null;
        if (id != null && Models.get(InstanceModel.class).findById(id) != null) {
            return HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, id);
        }
        return HohenheimAccess.creationOwnerSubjects(
            TenantWrites.isTenantOriginated() ? TenantWrites.acting() : null);
    }

    /**
     * What still references one environment, BY NAME: the live instances grouped in it
     * and the variables it holds. Fresh queries, because this feeds the write gate.
     */
    public static @NonNull EnvironmentUsage usageOf(int environmentId) {
        List<String> instances = new ArrayList<>();
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.ENVIRONMENT_ID.eq(environmentId))
                .where(InstanceModel.DELETED_AT.isNull())
                .all()) {
            instances.add(EnvironmentUsage.nameOf(instance.get(InstanceModel.NAME),
                instance.get(InstanceModel.ID)));
        }
        List<String> variables = new ArrayList<>();
        for (Row variable : Models.get(InstanceVariableModel.class).find()
                .where(InstanceVariableModel.ENVIRONMENT_ID.eq(environmentId))
                .all()) {
            variables.add(EnvironmentUsage.nameOf(variable.get(InstanceVariableModel.KEY),
                variable.get(InstanceVariableModel.ID)));
        }
        return new EnvironmentUsage(instances, variables);
    }

    /**
     * The holders of an environment and THE wording of the refusal they cause -- one
     * home for the write gate (fresh queries) and the dead delete affordance (request
     * snapshot), so the two can never name different things.
     *
     * @param instances live instance names grouped in the environment
     * @param variables the keys of the variables it holds
     */
    public record EnvironmentUsage(@NonNull List<String> instances, @NonNull List<String> variables) {

        public boolean isEmpty() {
            return this.instances.isEmpty() && this.variables.isEmpty();
        }

        /**
         * The refusal, naming every holder; the {@code holders} filter picks the sentence
         * shape (instances only, variables only, or both) so no locale has to render an
         * empty list.
         */
        public @NonNull Microcopy refusal() {
            String holders = this.instances.isEmpty() ? "variables"
                : this.variables.isEmpty() ? "instances" : "both";
            return violation("environment_in_use")
                .withFilter("holders", holders)
                .withArg("instances", String.join(", ", this.instances))
                .withArg("variables", String.join(", ", this.variables));
        }

        /** A record's name, or its id when it has none (a name a dialog can act on). */
        public static @NonNull String nameOf(@Nullable String name, @Nullable Integer id) {
            return name == null || name.isBlank() ? "#" + id : name;
        }
    }

    /** A permission group a live project still points at cannot be deleted on its own. */
    private static void refuseProjectOwnedGroup(@NonNull RemoveFromDatasource context) {
        for (Row group : doomedRows(context)) {
            Integer groupId = group.get(PermissionGroupModel.ID);
            if (groupId == null) {
                continue;
            }
            Row project = Projects.projectForGroup(groupId);
            if (project != null) {
                throw Violations.ofForm(violation("role_owned_by_project")
                    .withArg("name", String.valueOf((Object) project.get(ProjectModel.NAME))));
            }
        }
    }

    private static void refuseNonEmptyAndCapture(@NonNull RemoveFromDatasource context) {
        List<Integer> doomedGroups = new ArrayList<>();
        for (Row project : doomedRows(context)) {
            Integer id = project.get(ProjectModel.ID);
            Integer groupId = project.get(ProjectModel.GROUP_ID);
            if (groupId != null && Projects.ownedRecordCount(groupId) > 0) {
                throw Violations.ofForm(violation("project_not_empty"));
            }
            if (id != null && Projects.environmentCount(id) > 0) {
                throw Violations.ofForm(violation("project_not_empty"));
            }
            if (groupId != null) {
                doomedGroups.add(groupId);
            }
        }
        if (!doomedGroups.isEmpty()) {
            context.setAttribute(DOOMED_GROUPS, doomedGroups);
        }
    }

    private static void removeDoomedGroups(@NonNull RemoveFromDatasource context) {
        if (!(context.getAttribute(DOOMED_GROUPS) instanceof List<?> doomed)) {
            return;
        }
        for (Object groupId : doomed) {
            if (groupId instanceof Integer id) {
                Projects.removeGroupFor(id);
            }
        }
    }

    private static @Nullable Row storedProject(@NonNull Row row) {
        Object id = row.has(ProjectModel.ID.getName()) ? row.get(ProjectModel.ID) : null;
        return id == null ? null : Models.get(ProjectModel.class).findById(id);
    }

    /** The rows a criteria remove is about to delete (the PortLedger pairing shape). */
    private static @NonNull List<Row> doomedRows(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        if (model == null) {
            return List.of();
        }
        QueryContext queryContext = context.getQueryContext();
        Criteria criteria = queryContext != null ? queryContext.getCriteria() : null;
        QueryBuilder<Row> builder = model.find();
        if (criteria != null) {
            builder.where(criteria);
        }
        return builder.all();
    }

    private static Microcopy violation(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
