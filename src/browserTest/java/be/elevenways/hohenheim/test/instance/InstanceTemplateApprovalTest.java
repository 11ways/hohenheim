package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.action.CmsConfirmation;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Approving and withdrawing a template are placed operations on the operator's catalog: each stamps or clears the
 * approval through the one invoke route, and each applies only in its own state.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class InstanceTemplateApprovalTest extends HohenheimTestBase {

    @Test
    void anOperatorApprovesAndWithdrawsATemplateThroughItsPlacedOperations() throws Exception {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row template = templates.createEmptyRow();
        template.set(InstanceTemplateModel.NAME, "approval-journey");
        template.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        template.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        templates.save(template);
        int templateId = template.get(InstanceTemplateModel.ID);

        // 1. Approve: the stamp names when and who.
        HttpResponse<String> approved = invoke("approve_template", templateId);
        assertThat(approved.statusCode()).as("step 1: the approval is accepted").isIn(200, 302, 303);
        Row stamped = templates.findById(templateId);
        assertThat((Object) stamped.get(InstanceTemplateModel.APPROVED_AT)).as("step 1: stamped when").isNotNull();
        assertThat((Object) stamped.get(InstanceTemplateModel.APPROVED_BY_USER_ID)).as("step 1: and by whom")
            .isNotNull();

        // 2. An approved template is not approved again: the operation no longer applies to it.
        assertThat(invoke("approve_template", templateId).statusCode())
            .as("step 2: approving an approved template is refused").isGreaterThanOrEqualTo(400);

        // 3. Withdraw: the stamp is cleared, and withdrawing again no longer applies.
        HttpResponse<String> withdrawn = invoke("unapprove_template", templateId);
        assertThat(withdrawn.statusCode()).as("step 3: the withdrawal is accepted").isIn(200, 302, 303);
        Row cleared = templates.findById(templateId);
        assertThat((Object) cleared.get(InstanceTemplateModel.APPROVED_AT)).as("step 3: the stamp is cleared")
            .isNull();
        assertThat(invoke("unapprove_template", templateId).statusCode())
            .as("step 3: withdrawing an unapproved template is refused").isGreaterThanOrEqualTo(400);
    }

    private HttpResponse<String> invoke(String operation, int templateId) throws Exception {
        String target = CmsRoutes.invoke(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_TEMPLATES,
            HohenheimIds.id(operation)).with(CmsEndpoints.SUBJECT_PARAM, String.valueOf(templateId)).toUrl();
        return httpPostForm(target, ApiSupport.form(CmsEndpoints.INVOCATION_PARAM.getName(),
            UUID.randomUUID().toString(), CmsConfirmation.FIELD, CmsConfirmation.PLAIN_PROOF), sessionToken, csrfToken);
    }
}
