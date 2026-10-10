package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creating an instance from a template through the PAGE wizard: the template is chosen first,
 * the document asks the details and the template's own variables as one form submitted once through the hosting
 * panel's invoke route, a blank secret falls back to its declared default without ever being rendered, and the
 * result opens the new instance in the same panel.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class InstanceFromTemplateWizardTest extends HohenheimTestBase {

    private static final String PREFIX = "wizard-";
    private static final String SECRET_DEFAULT = "never-rendered-default";

    private static int templateId;

    @BeforeAll
    static void seed() {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row template = templates.createEmptyRow();
        template.set(InstanceTemplateModel.NAME, PREFIX + "template");
        template.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        template.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        template.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
        template.set(InstanceTemplateModel.APPROVED_BY_USER_ID, 1L);
        templates.save(template);
        templateId = template.get(InstanceTemplateModel.ID);
        variable("SITE_TITLE", "hohenheim:string", "");
        variable("ADMIN_PASSWORD", "hohenheim:secret", SECRET_DEFAULT);
    }

    @Test
    void theWizardChoosesATemplateFirstThenCreatesFromOneDocument() throws Exception {
        String page = "/" + HohenheimSlugs.ADMIN + "/" + HohenheimSlugs.INSTANCES_FROM_TEMPLATE;

        // 1. Without a selection the page is the chooser, offering the template by its own link.
        HttpResponse<String> chooser = httpGet(page, sessionToken);
        assertThat(chooser.statusCode()).as("step 1: the chooser renders").isEqualTo(200);
        assertThat(chooser.body()).as("step 1: the chooser offers the template")
            .contains("data-hh-template-choices").contains(PREFIX + "template");

        // 2. With the template selected, one document holds both steps; the secret's default never reaches it.
        HttpResponse<String> document = httpGet(page + "?template=" + templateId, sessionToken);
        assertThat(document.statusCode()).as("step 2: the document renders").isEqualTo(200);
        assertThat(document.body()).as("step 2: the details step and the variables step, in one document")
            .contains("data-zf-step=\"details\"").contains("data-zf-step=\"variables\"");
        assertThat(document.body()).as("step 2: the variables step leads with what a blank secret keeps")
            .contains("A secret left blank keeps the value set on the template.");
        assertThat(document.body()).as("step 2: the secret default is never rendered")
            .doesNotContain(SECRET_DEFAULT);

        // 3. An unknown template is concealed as missing.
        assertThat(httpGet(page + "?template=999999999", sessionToken).statusCode())
            .as("step 3: an unknown template answers as missing").isEqualTo(404);

        // 4. A refused final submit (a required variable left blank) creates nothing.
        String target = ApiSupport.fromTemplateTarget(HohenheimSlugs.ADMIN, templateId);
        String transport = "&" + ApiSupport.fromTemplateTransport();
        HttpResponse<String> refused = httpPostForm(target, "name=" + PREFIX + "refused"
            + "&serverId=" + ServerModel.localServerId() + "&variables.SITE_TITLE=" + transport,
            sessionToken, csrfToken);
        assertThat(refused.statusCode()).as("step 4: a blank required variable is refused").isEqualTo(422);
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "refused")).count())
            .as("step 4: nothing landed").isZero();

        // 5. The corrected submit lands, answers with the new instance in this panel, stores the typed variable and
        //    the blank secret's declared default.
        HttpResponse<String> created = httpPostForm(target, "name=" + PREFIX + "created"
            + "&serverId=" + ServerModel.localServerId() + "&variables.SITE_TITLE=Hello" + transport,
            sessionToken, csrfToken);
        assertThat(created.statusCode()).as("step 5: the create lands").isIn(302, 303);
        Row instance = Models.get(InstanceModel.class).find()
            .where(InstanceModel.NAME.eq(PREFIX + "created")).first();
        assertThat(instance).as("step 5: the instance exists").isNotNull();
        assertThat(created.headers().firstValue("Location").orElse(""))
            .as("step 5: the result opens the instance in the hosting panel")
            .contains("/" + HohenheimSlugs.ADMIN + "/" + HohenheimSlugs.INSTANCES + "/"
                + instance.get(InstanceModel.ID));
        Map<String, String> values = new InstanceVariables().valuesFor(instance.get(InstanceModel.ID));
        assertThat(values).as("step 5: the typed variable is stored").containsEntry("SITE_TITLE", "Hello");
        assertThat(values).as("step 5: the blank secret received its declared default")
            .containsEntry("ADMIN_PASSWORD", SECRET_DEFAULT);
    }

    private static void variable(String key, String type, String defaultValue) {
        Model variables = Models.get(InstanceTemplateVariableModel.class);
        Row row = variables.createEmptyRow();
        row.set(InstanceTemplateVariableModel.TEMPLATE_ID, templateId);
        row.set(InstanceTemplateVariableModel.KEY, key);
        row.set(InstanceTemplateVariableModel.TYPE, type);
        row.set(InstanceTemplateVariableModel.REQUIRED, true);
        row.set(InstanceTemplateVariableModel.DEFAULT_VALUE, defaultValue);
        row.set(InstanceTemplateVariableModel.SETTINGS, Map.of());
        variables.save(row);
    }
}
