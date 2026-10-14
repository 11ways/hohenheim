package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.StaticSiteKind;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.orm.model.Schema;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A git-sourced kind's form offers only the source fields its own lane reads: a field nothing reads is a setting an
 * operator fills in for nothing.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class GitSourceFieldsOfferedTest {

    @Test
    void eachKindOffersTheSourceFieldsItsLaneReads() {
        // 1. An application builds its Dockerfile or a buildpack: there is no build command to run, so no folder to
        //    run it in and no environment that exists only while it runs. Its previews and timeout stay.
        assertThat(offered(ApplicationKind.SETTINGS_SCHEMA))
            .as("step 1: the application offers no build command, folder or build-only environment")
            .doesNotContain(GitSourceSchema.BUILD_COMMAND, GitSourceSchema.BUILD_DIRECTORY,
                GitSourceSchema.BUILD_ENVIRONMENT_VARIABLES)
            .contains(GitSourceSchema.BUILD_TIMEOUT, GitSourceSchema.PREVIEWS_ENABLED);

        // 2. A static site runs its build command in its build folder; its build stage takes no environment.
        assertThat(offered(StaticSiteKind.SETTINGS_SCHEMA))
            .as("step 2: the static site offers its command and folder, no environment, no previews")
            .contains(GitSourceSchema.BUILD_COMMAND, GitSourceSchema.BUILD_DIRECTORY, GitSourceSchema.BUILD_TIMEOUT)
            .doesNotContain(GitSourceSchema.BUILD_ENVIRONMENT_VARIABLES, GitSourceSchema.PREVIEWS_ENABLED);

        // 3. A workspace runs its command in its folder with its build-only environment.
        assertThat(offered(WorkspaceKind.SETTINGS_SCHEMA))
            .as("step 3: the workspace offers command, folder and environment, no previews")
            .contains(GitSourceSchema.BUILD_COMMAND, GitSourceSchema.BUILD_DIRECTORY,
                GitSourceSchema.BUILD_ENVIRONMENT_VARIABLES)
            .doesNotContain(GitSourceSchema.PREVIEWS_ENABLED);
    }

    /** @return the names of the fields an edit view shows (no declared views means every view) */
    private static Set<String> offered(Schema schema) {
        Set<String> names = new TreeSet<>();
        for (Field<?, ?> field : schema.getFields().values()) {
            Set<EditView> views = field.getAttribute(FieldAttributes.VISIBLE_IN);
            if (views == null || views.contains(EditView.EDIT)) {
                names.add(field.getName());
            }
        }
        return names;
    }
}
