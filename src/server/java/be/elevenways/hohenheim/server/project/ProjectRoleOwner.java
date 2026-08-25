package be.elevenways.hohenheim.server.project;

import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.cms.RoleOwner;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Declares the project tier's ownership of the auth groups it synthesizes, so the roles
 * surface hides and refuses the Delete instead of offering one that the
 * {@code ProjectGuards} remove hook then fails.
 *
 * AIDEV-NOTE: ownership is answered by the LIVE pointer (a project whose group_id is
 * this group), never by the {@code project-} slug prefix -- an operator can spell that
 * prefix by hand, and a hand-made role must stay deletable.
 */
final class ProjectRoleOwner implements RoleOwner {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "project-roles");
    }

    @Override
    public @Nullable Microcopy managedBy(@NonNull Row group) {
        Integer groupId = group.get(PermissionGroupModel.ID);
        if (groupId == null) {
            return null;
        }
        Row project = Projects.projectForGroup(groupId);
        return project == null ? null
            : Projects.managedByCopy(String.valueOf((Object) project.get(ProjectModel.NAME)));
    }
}
