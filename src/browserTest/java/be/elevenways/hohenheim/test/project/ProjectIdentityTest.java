package be.elevenways.hohenheim.test.project;

import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project's identity: the slug its backing group is minted with, and the owner token
 * its records carry.
 *
 * AIDEV-NOTE: both are STORED on a running installation (the group slug names the membership
 * permission, the owner token is packed into owner sets), so this pins what must never move:
 * the token keeps its {@code group:<id>} shape and a rename never re-slugs a group. Only a NEW
 * group's slug comes from zenit's Slugs, which folds letters the old slugifier dropped.
 */
class ProjectIdentityTest extends HohenheimTestBase {

    @Test
    void aProjectsSlugAndOwnerTokenAreStableIdentities() {
        ProjectModel projects = Models.get(ProjectModel.class);
        Row row = projects.createEmptyRow();
        row.set(ProjectModel.NAME, "Grüße Straße");
        projects.save(row);
        int projectId = row.get(ProjectModel.ID);
        try {
            Row project = projects.findById(projectId);
            int groupId = project.get(ProjectModel.GROUP_ID);

            // 1. A NEW group's slug is the shared fold: the sharp s becomes "ss" instead of a
            //    separator, which is what the old NFD-stripping slugifier produced.
            assertThat(slugOf(groupId))
                .as("step 1: the group slug folds every letter")
                .isEqualTo(Projects.GROUP_SLUG_PREFIX + "grusse-strasse");

            // 2. The owner token keeps the stored group:<id> shape and round-trips.
            String token = Projects.subjectOf(project);
            assertThat(token).as("step 2: the owner token is group:<id>")
                .isEqualTo("group:" + groupId);
            assertThat((Integer) Projects.projectForSubjects(Set.of(token)).get(ProjectModel.ID))
                .as("step 2: and resolves back to its project").isEqualTo(projectId);

            // 3. Any other subject shape is not a project owner: a user, a malformed id, an
            //    unknown type, a set of two.
            assertThat(Projects.projectForSubjects(Set.of("user:" + groupId)))
                .as("step 3: a user token names no project").isNull();
            assertThat(Projects.projectForSubjects(Set.of("group:not-a-number")))
                .as("step 3: a malformed group token names no project").isNull();
            assertThat(Projects.projectForSubjects(Set.of("robot:" + groupId)))
                .as("step 3: an unknown subject type names no project").isNull();
            assertThat(Projects.projectForSubjects(Set.of(token, "user:1")))
                .as("step 3: a mixed owner set is not a project owner").isNull();

            // 4. A rename moves the group's title, never its slug: existing slugs are identities.
            Row renamed = projects.findById(projectId);
            renamed.set(ProjectModel.NAME, "Something else entirely");
            projects.save(renamed);
            assertThat(slugOf(groupId))
                .as("step 4: the slug did not move with the rename")
                .isEqualTo(Projects.GROUP_SLUG_PREFIX + "grusse-strasse");
        } finally {
            projects.delete(projectId);
        }
    }

    private static String slugOf(int groupId) {
        return AuthModels.permissionGroups().findById(groupId).get(PermissionGroupModel.SLUG);
    }
}
