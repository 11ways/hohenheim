package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.cms.EnvironmentVariableResource;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.forms.common.render.FormEntryState;
import be.elevenways.zenit.forms.common.render.FormState;
import be.elevenways.zenit.forms.server.render.FormStateTranslator;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two admin forms that used to leave the operator guessing: the environment-variable
 * form offered BOTH value carriers at once (two inputs, one label, no way to tell which
 * column a save would write), and the git-provider form accepted an empty Kind in
 * silence while its per-kind section claimed the unchosen type had no settings.
 */
class VariableCarrierAndKindChoiceTest extends HohenheimTestBase {

    /**
     * The value entries the form actually offers for one record, in the shape the page
     * renderer consumes: {@code fieldAccessByPath} decided against THAT record, so this
     * is the same walk that hides the carrier and the same one that strips it on submit.
     */
    private static List<String> valueEntriesOf(@Nullable Row record, EditView view) {
        EnvironmentVariableResource resource = new EnvironmentVariableResource();
        FormState state = new FormStateTranslator().translate(
            resource.formSpec(), resource.fieldAccessByPath(), view,
            TestAccessContexts.contextFor(null), Map.of(), List.<Violation>of(),
            null, false, record);

        List<String> carriers = new ArrayList<>();
        for (FormEntryState entry : state.entries()) {
            if (entry.path().equals(InstanceVariableModel.PLAIN_VALUE.getName())
                || entry.path().equals(InstanceVariableModel.SECRET_VALUE.getName())) {
                carriers.add(entry.path());
            }
        }
        return carriers;
    }

    @Test
    void everyVariableFormOffersExactlyOneValueCarrierForItsKind() throws Exception {

        // 1. A project + environment to own the variables.
        Row project = Models.get(ProjectModel.class).createEmptyRow();
        project.set(ProjectModel.NAME, "Carrier Probe");
        Models.get(ProjectModel.class).save(project);

        Row environment = Models.get(EnvironmentModel.class).createEmptyRow();
        environment.set(EnvironmentModel.PROJECT_ID, project.get(ProjectModel.ID));
        environment.set(EnvironmentModel.NAME, "carrier-probe");
        Models.get(EnvironmentModel.class).save(environment);
        Integer environmentId = environment.get(EnvironmentModel.ID);

        // 2. The CREATE form has no stored kind, so it offers the default (plain)
        //    carrier and ONLY that one -- never both value fields at once.
        assertThat(valueEntriesOf(null, EditView.CREATE))
            .as("the create form offers exactly one value field, the plain carrier")
            .containsExactly(InstanceVariableModel.PLAIN_VALUE.getName());

        // 3. A plain variable created through the real form stores plain_value.
        HttpResponse<String> created = httpPostForm("/admin/environment-variables/new",
            "environment_id=" + environmentId + "&key=CARRIER_PROBE&kind=plain"
                + "&plain_value=visible-config", sessionToken, csrfToken);
        assertThat(created.statusCode()).as("the create succeeds").isEqualTo(302);

        Row stored = Models.get(InstanceVariableModel.class).find()
            .where(InstanceVariableModel.KEY.eq("CARRIER_PROBE")).first();
        assertThat(stored).isNotNull();
        assertThat(stored.get(InstanceVariableModel.PLAIN_VALUE)).isEqualTo("visible-config");
        assertThat(stored.get(InstanceVariableModel.SECRET_VALUE)).isNull();
        Integer variableId = stored.get(InstanceVariableModel.ID);

        // 4. Its EDIT form offers the plain carrier alone.
        assertThat(valueEntriesOf(stored, EditView.EDIT))
            .as("a plain row edits its plain carrier and nothing else")
            .containsExactly(InstanceVariableModel.PLAIN_VALUE.getName());

        // 5. Switching the kind retires the previous carrier instead of being refused
        //    by the model's one-carrier-per-kind hook.
        HttpResponse<String> switched = httpPostForm("/admin/environment-variables/" + variableId,
            "environment_id=" + environmentId + "&key=CARRIER_PROBE&kind=secret"
                + "&plain_value=visible-config", sessionToken, csrfToken);
        assertThat(switched.statusCode()).as("the kind switch saves").isEqualTo(302);

        stored = Models.get(InstanceVariableModel.class).find()
            .where(InstanceVariableModel.ID.eq(variableId)).first();
        assertThat(stored.get(InstanceVariableModel.KIND)).isEqualTo(InstanceVariableModel.KIND_SECRET);
        assertThat(stored.get(InstanceVariableModel.PLAIN_VALUE))
            .as("the retired carrier is cleared, not left behind")
            .isNull();

        // 6. The same form now offers the secret carrier alone, and a value typed there
        //    lands in the encrypted column.
        assertThat(valueEntriesOf(stored, EditView.EDIT))
            .as("a secret row edits its secret carrier and nothing else")
            .containsExactly(InstanceVariableModel.SECRET_VALUE.getName());

        HttpResponse<String> secretSaved = httpPostForm(
            "/admin/environment-variables/" + variableId,
            "environment_id=" + environmentId + "&key=CARRIER_PROBE&kind=secret"
                + "&secret_value=hunter2-carrier", sessionToken, csrfToken);
        assertThat(secretSaved.statusCode()).isEqualTo(302);

        stored = Models.get(InstanceVariableModel.class).find()
            .where(InstanceVariableModel.ID.eq(variableId)).first();
        assertThat(stored.get(InstanceVariableModel.SECRET_VALUE)).isEqualTo("hunter2-carrier");
        assertThat(stored.get(InstanceVariableModel.PLAIN_VALUE)).isNull();

        // 7. The hidden carrier is not merely unrendered: a hand-crafted submission
        //    naming it is stripped, so the stored column can never disagree with the kind.
        HttpResponse<String> smuggled = httpPostForm(
            "/admin/environment-variables/" + variableId,
            "environment_id=" + environmentId + "&key=CARRIER_PROBE&kind=secret"
                + "&plain_value=smuggled", sessionToken, csrfToken);
        assertThat(smuggled.statusCode()).isEqualTo(302);
        stored = Models.get(InstanceVariableModel.class).find()
            .where(InstanceVariableModel.ID.eq(variableId)).first();
        assertThat(stored.get(InstanceVariableModel.PLAIN_VALUE))
            .as("the withheld carrier stays unwritable")
            .isNull();
    }

    @Test
    void anEmptyGitProviderCreateNamesEveryMissingRequirement() throws Exception {

        // The server is shared across test classes, so the anchor is the count BEFORE
        // this submit, never an absolute zero.
        long providersBefore = Models.get(GitProviderModel.class).find().count();

        // 1. Submitting the create form empty is refused and rerendered.
        HttpResponse<String> empty = httpPostForm("/admin/git-providers/new",
            "name=&kind=&base_url=", sessionToken, csrfToken);
        assertThat(empty.statusCode()).as("the refusal rerenders the form").isEqualTo(200);

        // 2. The missing kind is REPORTED -- it used to pass in silence, leaving the
        //    select unmarked while the form complained about the name only.
        //
        //    AIDEV-NOTE: the kind refusal lands in the COERCION stage (a required enum
        //    derives a non-clearable select, whose blank has no valid option), and
        //    coercion failing means the field VALIDATORS never run -- so this one submit
        //    names the kind and not the name. That staging is the framework's
        //    coerce-then-validate pipeline, not this form's declaration; step 4 proves
        //    the name requirement is enforced too.
        assertThat(empty.body())
            .as("the kind field is marked invalid")
            .contains("data-path=\"kind\" invalid");
        assertThat(empty.body())
            .as("the missing kind is reported in words")
            .contains("Choose one of the offered options");

        // 3. The per-kind section says a kind must be chosen instead of claiming the
        //    (unchosen) type has no settings.
        assertThat(empty.body())
            .as("an unchosen kind asks for a kind")
            .contains("choose a type above");
        assertThat(empty.body())
            .as("an unchosen kind is never described as a settings-less type")
            .doesNotContain("This type has no extra settings");

        // 4. Choosing a kind but no name is refused just as loudly, so BOTH
        //    requirements are enforced server-side and neither passes in silence.
        HttpResponse<String> namelessButTyped = httpPostForm("/admin/git-providers/new",
            "name=&kind=hohenheim%3Agithub&base_url=", sessionToken, csrfToken);
        assertThat(namelessButTyped.statusCode()).isEqualTo(200);
        assertThat(namelessButTyped.body())
            .as("the missing name is reported")
            .contains("name is required");

        // 5. Nothing was stored by either refusal.
        assertThat(Models.get(GitProviderModel.class).find().count())
            .as("the refused creates wrote no row")
            .isEqualTo(providersBefore);
    }
}
