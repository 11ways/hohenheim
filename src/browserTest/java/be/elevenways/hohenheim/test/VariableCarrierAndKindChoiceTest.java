package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.panel.PartsForms;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.forms.common.render.FormEntryState;
import be.elevenways.zenit.forms.common.render.FormState;
import be.elevenways.zenit.forms.common.render.ConditionalEntryState;
import be.elevenways.zenit.common.edit.FormCondition;
import be.elevenways.zenit.forms.server.render.FormStateTranslator;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        Panel admin = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        PanelResource<?> resource = (PanelResource<?>) admin.entryBySlug(HohenheimSlugs.ENVIRONMENT_VARIABLES);
        FormState state = new FormStateTranslator().translate(
            PartsForms.formSpec(resource), PartsForms.fieldAccessByPath(resource), view,
            TestAccessContexts.contextFor(null), record == null ? PartsForms.formSpec(resource).defaultValues()
                : PartsReads.valuesFromRow(resource, record), List.<Violation>of(),
            null, false, record);

        List<String> carriers = new ArrayList<>();
        for (FormEntryState entry : state.entries()) {
            if (entry instanceof ConditionalEntryState conditional
                    && !FormCondition.matches(conditional.conditions(), Map.of())) continue;
            if (entry.path().equals(InstanceVariableModel.PLAIN_VALUE.getName())
                || entry.path().equals(InstanceVariableModel.SECRET_VALUE.getName())) {
                carriers.add(entry.path());
            }
        }
        return carriers;
    }

    /** Creates one variable through the panel entry's CREATE with the value field its submitted kind declares. */
    private static void create(Integer environmentId, String key, String kind, String value) {
        PanelResourceCalls.create(HohenheimSlugs.ADMIN, HohenheimSlugs.ENVIRONMENT_VARIABLES, Map.of(
            "environment_id", String.valueOf(environmentId), "key", key, "kind", kind,
            "secret".equals(kind) ? "secret_value" : "plain_value", value),
            TenantConduits.operator());
    }

    /** Saves one variable through the panel entry's UPDATE, as its edit form posts the given entries. */
    private static void patch(Integer variableId, Map<String, Object> values) {
        Map<String, Object> raw = new LinkedHashMap<>();
        values.forEach((name, value) -> raw.put(name, String.valueOf(value)));
        PanelResourceCalls.patch(HohenheimSlugs.ADMIN, HohenheimSlugs.ENVIRONMENT_VARIABLES, variableId, raw,
            TenantConduits.operator());
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
        create(environmentId, "CARRIER_PROBE", "plain", "visible-config");

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

        // 5. The submitted kind and secret are accepted together; an inactive plain value is ignored.
        patch(variableId, Map.of("environment_id", environmentId, "key", "CARRIER_PROBE", "kind", "secret",
            "plain_value", "smuggled-config", "secret_value", "hunter2-carrier"));

        stored = Models.get(InstanceVariableModel.class).find()
            .where(InstanceVariableModel.ID.eq(variableId)).first();
        assertThat(stored.get(InstanceVariableModel.KIND)).isEqualTo(InstanceVariableModel.KIND_SECRET);
        assertThat(stored.get(InstanceVariableModel.SECRET_VALUE)).as("step 5: secret switches in one request")
            .isEqualTo("hunter2-carrier");
        assertThat(stored.get(InstanceVariableModel.PLAIN_VALUE))
            .as("the retired carrier is cleared, not left behind")
            .isNull();

        // 6. The same form now offers the secret carrier alone, and a value typed there
        //    lands in the encrypted column.
        assertThat(valueEntriesOf(stored, EditView.EDIT))
            .as("a secret row edits its secret carrier and nothing else")
            .containsExactly(InstanceVariableModel.SECRET_VALUE.getName());

        patch(variableId, Map.of("environment_id", environmentId, "key", "CARRIER_PROBE", "kind", "secret",
            "secret_value", "hunter2-carrier"));

        stored = Models.get(InstanceVariableModel.class).find()
            .where(InstanceVariableModel.ID.eq(variableId)).first();
        assertThat(stored.get(InstanceVariableModel.SECRET_VALUE)).isEqualTo("hunter2-carrier");
        assertThat(stored.get(InstanceVariableModel.PLAIN_VALUE)).isNull();

        // 7. The hidden carrier is not merely unrendered: a hand-crafted submission
        //    naming it is stripped, so the stored column can never disagree with the kind.
        patch(variableId, Map.of("environment_id", environmentId, "key", "CARRIER_PROBE", "kind", "secret",
            "plain_value", "smuggled"));
        stored = Models.get(InstanceVariableModel.class).find()
            .where(InstanceVariableModel.ID.eq(variableId)).first();
        assertThat(stored.get(InstanceVariableModel.PLAIN_VALUE))
            .as("the withheld carrier stays unwritable")
            .isNull();

        // 8. The newly selected carrier can be written in the same POST that changes kind.
        HttpResponse<String> liveSwitched = httpPostForm("/admin/environment-variables/" + variableId,
            "environment_id=" + environmentId + "&key=CARRIER_PROBE&kind=plain&plain_value=new-config",
            sessionToken, csrfToken);
        assertThat(liveSwitched.statusCode()).as("step 8: selected carrier writes with its kind").isEqualTo(302);
        stored = Models.get(InstanceVariableModel.class).find().where(InstanceVariableModel.ID.eq(variableId)).first();
        assertThat(stored.get(InstanceVariableModel.PLAIN_VALUE)).as("step 8: new carrier is stored").isEqualTo("new-config");
        assertThat(stored.get(InstanceVariableModel.SECRET_VALUE)).as("step 8: old secret is retired").isNull();

        // 9. Unknown kinds refuse before either physical carrier is touched.
        HttpResponse<String> unknown = httpPostForm("/admin/environment-variables/" + variableId,
            "environment_id=" + environmentId + "&key=CARRIER_PROBE&kind=unknown&secret_value=smuggled",
            sessionToken, csrfToken);
        assertThat(unknown.statusCode()).as("step 9: unknown kind is a form refusal").isEqualTo(200);
        stored = Models.get(InstanceVariableModel.class).find().where(InstanceVariableModel.ID.eq(variableId)).first();
        assertThat(stored.get(InstanceVariableModel.PLAIN_VALUE)).as("step 9: refused write keeps old value").isEqualTo("new-config");
        assertThat(stored.get(InstanceVariableModel.SECRET_VALUE)).as("step 9: no hidden carrier write").isNull();

        HttpResponse<String> blankKind = httpPostForm("/admin/environment-variables/" + variableId,
            "environment_id=" + environmentId + "&key=CARRIER_PROBE&kind=&secret_value=smuggled",
            sessionToken, csrfToken);
        assertThat(blankKind.statusCode()).as("step 9: a blank kind refuses instead of retiring plain data").isEqualTo(200);
        for (String kind : new String[] {null, "", "unknown"}) {
            Row invalid = Models.get(InstanceVariableModel.class).createEmptyRow();
            invalid.set(InstanceVariableModel.ID, variableId);
            invalid.set(InstanceVariableModel.KIND, kind);
            assertThatThrownBy(() -> Models.get(InstanceVariableModel.class).save(invalid))
                .as("step 9: every writer refuses a missing or unknown kind").isInstanceOf(Violations.class);
        }
        stored = Models.get(InstanceVariableModel.class).find().where(InstanceVariableModel.ID.eq(variableId)).first();
        assertThat(stored.get(InstanceVariableModel.PLAIN_VALUE))
            .as("step 9: missing-kind refusals preserve the plain carrier").isEqualTo("new-config");
        assertThat(stored.get(InstanceVariableModel.SECRET_VALUE))
            .as("step 9: missing-kind refusals write no secret carrier").isNull();

        // 10. Hydrated switching retains the plain draft and excludes the hidden native controls from submission.
        navigateToApp("/admin/environment-variables/" + variableId);
        var plainInput = page.locator("pl-textarea[name='plain_value'] textarea");
        plainInput.fill("retained-draft");
        page.locator("zf-select-field pl-select[name='kind'] .pl-select-field").click();
        page.locator("he-bottom .pl-select-popup[data-open] [role='option'][data-value='secret']").click();
        page.waitForFunction("() => document.querySelector('[data-conditional-entry=secret_value]').hidden === false");
        assertThat(page.locator("[data-conditional-entry='plain_value']").isVisible())
            .as("step 10: old carrier is hidden").isFalse();
        assertThat(page.evaluate("() => new FormData(document.querySelector('textarea[name=plain_value]').form).has('plain_value')"))
            .as("step 10: hidden carrier is not a successful control").isEqualTo(false);
        page.locator("zf-select-field pl-select[name='kind'] .pl-select-field").click();
        page.locator("he-bottom .pl-select-popup[data-open] [role='option'][data-value='plain']").click();
        page.waitForFunction("() => document.querySelector('[data-conditional-entry=plain_value]').hidden === false");
        assertThat(plainInput.inputValue()).as("step 10: switching back retains the native draft").isEqualTo("retained-draft");
    }

    /**
     * Creating a variable as a SECRET stores the value it was created with, in the
     * encrypted carrier -- the create form has only one value field to type it into.
     */
    @Test
    void aVariableCreatedAsASecretKeepsTheValueItWasCreatedWith() throws Exception {

        // 1. An environment to own it.
        Row project = Models.get(ProjectModel.class).createEmptyRow();
        project.set(ProjectModel.NAME, "Secret Create Probe");
        Models.get(ProjectModel.class).save(project);
        Row environment = Models.get(EnvironmentModel.class).createEmptyRow();
        environment.set(EnvironmentModel.PROJECT_ID, project.get(ProjectModel.ID));
        environment.set(EnvironmentModel.NAME, "secret-create-probe");
        Models.get(EnvironmentModel.class).save(environment);
        Integer environmentId = environment.get(EnvironmentModel.ID);

        // 2. The operator picks Kind = Secret on the create form and types the value into
        //    the one value field it offers. This used to be refused, in column language,
        //    with no field on the page that could have satisfied it.
        create(environmentId, "SECRET_AT_BIRTH", "secret", "hunter2-at-birth");

        // 3. And it landed in the column its kind declares, not the one it was typed in.
        Row stored = Models.get(InstanceVariableModel.class).find()
            .where(InstanceVariableModel.KEY.eq("SECRET_AT_BIRTH")).first();
        assertThat(stored).as("the row exists").isNotNull();
        assertThat(stored.get(InstanceVariableModel.KIND))
            .as("stored as the kind that was chosen")
            .isEqualTo(InstanceVariableModel.KIND_SECRET);
        assertThat(stored.get(InstanceVariableModel.SECRET_VALUE))
            .as("the typed value is in the encrypted carrier")
            .isEqualTo("hunter2-at-birth");
        assertThat(stored.get(InstanceVariableModel.PLAIN_VALUE))
            .as("and nowhere else -- a secret in the plain column is the leak")
            .isNull();

        // 4. A PLAIN create is untouched by that move.
        create(environmentId, "PLAIN_AT_BIRTH", "plain", "visible-at-birth");
        Row plainRow = Models.get(InstanceVariableModel.class).find()
            .where(InstanceVariableModel.KEY.eq("PLAIN_AT_BIRTH")).first();
        assertThat(plainRow.get(InstanceVariableModel.PLAIN_VALUE))
            .as("a plain variable still stores its plain carrier")
            .isEqualTo("visible-at-birth");
        assertThat(plainRow.get(InstanceVariableModel.SECRET_VALUE)).isNull();

        // 5. Quick-add uses the full form's retained conditions, not a permanently visible second carrier.
        //    Quick add is a header button that opens its CREATE entries in a sheet.
        navigateToApp("/admin/environment-variables?environment_id=" + environmentId);
        page.locator("pl-button[data-cms-quick-add-open]").click();
        String quick = "[data-cms-quick-add] ";
        assertThat(page.locator(quick + "[data-zf-create-field='plain_value']").isVisible())
            .as("step 5: quick-add initially offers the declared plain default").isTrue();
        assertThat(page.locator(quick + "[data-zf-create-field='secret_value']").isVisible())
            .as("step 5: quick-add retains but hides the secret carrier").isFalse();
        page.locator(quick + "[data-zf-create-field='plain_value'] textarea").fill("inactive-plain-draft");
        page.locator(quick + "[data-zf-create-field='kind'] .pl-select-field").click();
        page.locator("he-bottom .pl-select-popup[data-open] [role='option'][data-value='secret']").click();
        page.waitForFunction("() => document.querySelector('[data-cms-quick-add] [data-conditional-entry=secret_value]').hidden === false");
        assertThat(page.locator(quick + "[data-zf-create-field='plain_value']").isVisible())
            .as("step 5: choosing secret hides the retained plain carrier").isFalse();
        assertThat(page.locator(quick + "[data-zf-create-field='secret_value']").isVisible())
            .as("step 5: choosing secret shows its carrier").isTrue();

        // 6. The real quick-add submit stores the typed secret and never its inactive plain draft.
        page.locator(quick + "[data-zf-create-field='key'] input").fill("SECRET_QUICK_ADD");
        page.locator(quick + "[data-zf-create-field='secret_value'] textarea").fill("hunter2-quick-add");
        page.locator(quick + "[data-cms-quick-add-submit]").click();
        page.waitForCondition(() -> Models.get(InstanceVariableModel.class).find()
            .where(InstanceVariableModel.KEY.eq("SECRET_QUICK_ADD")).first() != null);
        Row quickSecret = Models.get(InstanceVariableModel.class).find()
            .where(InstanceVariableModel.KEY.eq("SECRET_QUICK_ADD")).first();
        assertThat(quickSecret.get(InstanceVariableModel.KIND)).as("step 6: the chosen kind is stored")
            .isEqualTo(InstanceVariableModel.KIND_SECRET);
        assertThat(quickSecret.get(InstanceVariableModel.SECRET_VALUE)).as("step 6: quick-add stores the secret value")
            .isEqualTo("hunter2-quick-add");
        assertThat(quickSecret.get(InstanceVariableModel.PLAIN_VALUE)).as("step 6: the inactive draft is not stored")
            .isNull();

        // 7. A secret create without a value refuses on every writer instead of saving an empty secret.
        long beforeRefusal = Models.get(InstanceVariableModel.class).find().count();
        HttpResponse<String> emptySecret = httpPostForm("/admin/environment-variables/new",
            "environment_id=" + environmentId + "&key=EMPTY_SECRET&kind=secret", sessionToken, csrfToken);
        assertThat(emptySecret.statusCode()).as("step 7: missing secret rerenders the form").isEqualTo(200);
        assertThat(emptySecret.body()).as("step 7: refusal names the secret carrier")
            .containsPattern("<pl-field data-path=\"secret_value\"[^>]*\\sinvalid[\\s>=]");
        Row missingSecret = Models.get(InstanceVariableModel.class).createEmptyRow();
        missingSecret.set(InstanceVariableModel.ENVIRONMENT_ID, environmentId);
        missingSecret.set(InstanceVariableModel.KEY, "EMPTY_SECRET_DIRECT");
        missingSecret.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_SECRET);
        assertThatThrownBy(() -> Models.get(InstanceVariableModel.class).save(missingSecret))
            .as("step 7: a direct save enforces the same carrier requirement").isInstanceOf(Violations.class);
        assertThat(Models.get(InstanceVariableModel.class).find().count())
            .as("step 7: neither refused create stores a row").isEqualTo(beforeRefusal);
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
        //    The field's own tag is matched, not a fixed attribute run: zenit-forms marks a
        //    required entry `required` between data-path and invalid (zenit-forms abcc211).
        assertThat(empty.body())
            .as("the kind field is marked invalid")
            .containsPattern("<pl-field data-path=\"kind\"[^>]*\\sinvalid[\\s>=]");
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
            .as("the name field is marked invalid")
            .containsPattern("<pl-field data-path=\"name\"[^>]*\\sinvalid[\\s>=]");
        // zenit's required copy leads with the field's own label ("{field} is required").
        assertThat(namelessButTyped.body())
            .as("the missing name is reported")
            .contains("Name is required");

        // 5. Nothing was stored by either refusal.
        assertThat(Models.get(GitProviderModel.class).find().count())
            .as("the refused creates wrote no row")
            .isEqualTo(providersBefore);
    }
}
