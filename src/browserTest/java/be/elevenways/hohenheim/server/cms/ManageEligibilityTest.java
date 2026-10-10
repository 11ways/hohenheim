package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /manage admits a principal managing any record of a model the panel projects: an access list too, whose entry
 * (and its rules) the panel serves, so a tenant handed only a list is never locked out of the twin built for it.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class ManageEligibilityTest extends HohenheimTestBase {

    private static final String PREFIX = "manage-eligibility-";

    @Test
    void aTenantManagingOnlyAnAccessListIsEligible() {
        int tenantId = ApiSupport.user(PREFIX + "tenant@hohenheim.local", "Manage Eligibility Tenant");
        Model lists = Models.get(AccessListModel.class);
        Row list = lists.createEmptyRow();
        list.set(AccessListModel.NAME, PREFIX + "list");
        lists.save(list);
        int listId = list.get(AccessListModel.ID);
        try {
            // 1. Holding nothing, the tenant is not eligible (the counterfactual).
            assertThat(ManagePanel.eligible(tenant(tenantId)))
                .as("step 1: a tenant holding no grant is refused /manage").isFalse();

            // 2. A manage grant on one access list, and nothing else, admits the tenant.
            RecordGrants.grant(GrantSubjectType.USER, tenantId, AccessListModel.MODEL_ID, listId,
                HohenheimCapabilities.MANAGE, true);
            assertThat(ManagePanel.eligible(tenant(tenantId)))
                .as("step 2: a tenant managing only an access list is eligible").isTrue();

            // 3. Revoked, the tenant is refused again.
            RecordGrants.revoke(GrantSubjectType.USER, tenantId, AccessListModel.MODEL_ID, listId,
                HohenheimCapabilities.MANAGE);
            assertThat(ManagePanel.eligible(tenant(tenantId)))
                .as("step 3: the revoked grant admits nothing").isFalse();
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, tenantId, AccessListModel.MODEL_ID, listId,
                HohenheimCapabilities.MANAGE);
            HardDeletes.byId(lists, listId);
        }
    }

    /** A fresh caller each step: the walk's scope memo belongs to one request. */
    private static AccessContext tenant(int tenantId) {
        return AccessContext.of(TenantConduits.stubFor(new UserPrincipal(tenantId, "Manage Eligibility Tenant")));
    }
}
