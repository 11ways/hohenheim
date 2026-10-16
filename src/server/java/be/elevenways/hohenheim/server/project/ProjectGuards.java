package be.elevenways.hohenheim.server.project;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.zenit.common.orm.model.DoomedRows;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.zenit.auth.cms.RoleOwnership;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The project tier's write-funnel invariants, on EVERY writer (forms, services,
 * direct saves): a project's backing auth group exists from the first write and is
 * immutable afterwards; a project cannot be deleted while it owns records or
 * environments, and its auth footprint dies with it explicitly; an environment
 * cannot be re-homed while in use; and a record can only sit in an environment
 * whose project OWNS it -- grouping may never disagree with the grants.
 */
public final class ProjectGuards {

    /**
     * The project tier's role owner.
     *
     * AIDEV-NOTE: one instance per JVM: zenit-auth's role-ownership registry refuses a second, different owner under
     * a taken id, while re-registering the same instance is its no-op.
     */
    private static final ProjectRoleOwner ROLE_OWNER = new ProjectRoleOwner();

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
            UUID storedGroup = stored.get(ProjectModel.GROUP_ID);
            if (row.has(ProjectModel.GROUP_ID.getName())
                    && storedGroup != null
                    && !storedGroup.equals(row.get(ProjectModel.GROUP_ID))) {
                // Membership grants reference the group's slug: swapping the group
                // would silently swap every member and every owned record.
                throw Violations.ofField(ProjectModel.GROUP_ID.getName(),
                    row.get(ProjectModel.GROUP_ID),
                    HohenheimMicrocopy.VIOLATIONS.of("project_group_immutable"));
            }
            if (row.has(ProjectModel.NAME.getName()) && storedGroup != null) {
                Projects.syncGroupTitle(storedGroup,
                    String.valueOf((Object) row.get(ProjectModel.NAME)));
            }
        });

        // Delete guard + explicit auth-footprint teardown (hard deletes; projects
        // have no soft delete).
        DoomedRows.handOverInDeletesDatasource(ProjectModel.SCHEMA, ProjectGuards::refuseNonEmptyAndCapture,
            ProjectGuards::removeDoomedGroups);

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
        RoleOwnership.INSTANCE.register(ROLE_OWNER);

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
            for (Object key : context.doomedPrimaryKeys()) {
                if (!(key instanceof Integer id)) {
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
        // (ChargedModel.INSTANCES installs first), so a beforeWrite refusal here would spend a
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
            // AIDEV-NOTE: only a write that PLACES the record is judged -- a create, or a move
            // into another environment. Ownership moves through grants without touching the
            // row, so a write carrying its UNCHANGED environment cannot cause the drift this
            // refuses; judging it anyway made a drifted record impossible to delete (the soft
            // delete used to save the whole row), to restore, or to edit at all.
            Row stored = StoredRows.of(Models.get(InstanceModel.class), row);
            if (stored != null && environmentId.equals(stored.get(InstanceModel.ENVIRONMENT_ID))) {
                return;
            }
            Row environment = Models.get(EnvironmentModel.class).findById(environmentId);
            Row project = environment == null ? null : projectOf(environment);
            if (project == null) {
                throw Violations.ofField(InstanceModel.ENVIRONMENT_ID.getName(), environmentId,
                    HohenheimMicrocopy.VIOLATIONS.of("environment_unknown"));
            }
            if (!owns(project, ownerOf(row))) {
                // Fails CLOSED on unreadable grants: grouping without a proven owner
                // match is exactly the drift this hook exists to refuse.
                throw Violations.ofField(InstanceModel.ENVIRONMENT_ID.getName(), environmentId,
                    HohenheimMicrocopy.VIOLATIONS.of("environment_project_mismatch"));
            }
        });
    }

    /**
     * THE project whose environments a record answering to {@code owner} may be grouped in: the one that owns it, by
     * the grouping rule the write hook judges a placement by, so a picker narrowed to it never offers what the save
     * refuses.
     *
     * @param owner the record's owner subjects ({@link #ownerOf(Object, AccessContext)}), null when unreadable
     * @return the project, null when the record answers to none (operator-owned, per-user or unreadable)
     */
    public static @Nullable Row groupingProject(@Nullable Set<String> owner) {
        Row project = Projects.projectForSubjects(owner);
        return project != null && owns(project, owner) ? project : null;
    }

    /**
     * Why a record answering to {@code owner} is offered no environment: its project has none yet, or it answers to
     * no project at all (unreadable grants included, which place nothing either).
     */
    public static @NonNull Microcopy noEnvironmentsReason(@Nullable Set<String> owner) {
        Row project = groupingProject(owner);
        if (project != null) {
            return HohenheimMicrocopy.INSTANCE.of("environment_empty_project")
                .withArg("project", String.valueOf((Object) project.get(ProjectModel.NAME)));
        }
        return HohenheimMicrocopy.INSTANCE.of("environment_empty_no_project");
    }

    /**
     * The owner an instance answers to for the grouping decision: a stored record's grant-derived subjects, or, for
     * a record not created yet, the creation owner of {@code creator}.
     *
     * @return the owner subjects, null when the grants are unreadable
     */
    public static @Nullable Set<String> ownerOf(@Nullable Object instanceId, @Nullable AccessContext creator) {
        // Trashed included: a stored record answers by its grants even while trashed.
        if (instanceId != null && StoredRows.byId(Models.get(InstanceModel.class), instanceId) != null) {
            return HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, instanceId);
        }
        return HohenheimAccess.creationOwnerSubjects(creator);
    }

    private static @Nullable Row projectOf(@NonNull Row environment) {
        Object projectId = environment.get(EnvironmentModel.PROJECT_ID);
        return projectId == null ? null : Models.get(ProjectModel.class).findById(projectId);
    }

    /** Whether the project owns a record answering to {@code owner}: its owner subject set is exactly the project's. */
    private static boolean owns(@NonNull Row project, @Nullable Set<String> owner) {
        return owner != null && owner.equals(Projects.ownerSubjectsOf(project));
    }

    /**
     * The owner a written instance row answers to: at create, when no grants can exist yet, the same creation-owner
     * derivation the quota charge and the planted grant use.
     */
    private static @Nullable Set<String> ownerOf(@NonNull Row row) {
        Object id = row.has(InstanceModel.ID.getName()) ? row.get(InstanceModel.ID) : null;
        return ownerOf(id, TenantWrites.isTenantOriginated() ? TenantWrites.acting() : null);
    }

    /**
     * What still references one environment, BY NAME: the live instances grouped in it
     * and the variables it holds. Fresh queries, because this feeds the write gate.
     */
    public static @NonNull EnvironmentUsage usageOf(int environmentId) {
        List<String> instances = new ArrayList<>();
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.ENVIRONMENT_ID.eq(environmentId))
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
            return HohenheimMicrocopy.VIOLATIONS.of("environment_in_use")
                .withFilter("holders", holders)
                .withArg("instances", String.join(", ", this.instances))
                .withArg("variables", String.join(", ", this.variables));
        }

        /** A record's name, or its id when it has none (a name a dialog can act on). */
        public static @NonNull String nameOf(@Nullable String name, @Nullable Integer id) {
            return name == null || name.isBlank() ? "#" + id : name;
        }
    }

    /**
     * A permission group a live project still points at cannot be deleted on its own.
     *
     * AIDEV-NOTE: fails CLOSED: a doomed key that names no group id (neither a UUID nor its canonical text) refuses
     * the whole delete. Skipping it, as the pre-UUID integer check did for every key once group ids became UUIDs,
     * lets a project's own group be deleted out from under it.
     *
     * @throws IllegalStateException when a doomed key is no group id
     */
    private static void refuseProjectOwnedGroup(@NonNull RemoveFromDatasource context) {
        for (Object key : context.doomedPrimaryKeys()) {
            UUID groupId = key instanceof UUID uuid ? uuid : key instanceof String text ? Uuids.canonical(text) : null;
            if (groupId == null) {
                throw new IllegalStateException("Refusing to delete permission group " + key
                    + ": its key is no group id, so whether a project owns it cannot be decided");
            }
            Row project = Projects.projectForGroup(groupId);
            if (project != null) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("role_owned_by_project")
                    .withArg("name", String.valueOf((Object) project.get(ProjectModel.NAME))));
            }
        }
    }

    /**
     * @return the auth groups of the doomed projects, torn down once they are gone
     * @throws Violations {@code project_not_empty}
     */
    private static @Nullable List<UUID> refuseNonEmptyAndCapture(@NonNull RemoveFromDatasource context) {
        List<UUID> doomedGroups = new ArrayList<>();
        context.eachDoomedRow(project -> {
            Integer id = project.get(ProjectModel.ID);
            UUID groupId = project.get(ProjectModel.GROUP_ID);
            if (groupId != null && Projects.ownedRecordCount(groupId) > 0) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("project_not_empty"));
            }
            if (id != null && Projects.environmentCount(id) > 0) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("project_not_empty"));
            }
            if (groupId != null) {
                doomedGroups.add(groupId);
            }
        });
        return doomedGroups.isEmpty() ? null : doomedGroups;
    }

    private static void removeDoomedGroups(@NonNull RemoveFromDatasource context,
                                           @NonNull List<UUID> doomedGroups) {
        for (UUID groupId : doomedGroups) {
            Projects.removeGroupFor(groupId);
        }
    }

    private static @Nullable Row storedProject(@NonNull Row row) {
        Object id = row.has(ProjectModel.ID.getName()) ? row.get(ProjectModel.ID) : null;
        return Models.get(ProjectModel.class).findById(id);
    }

}
