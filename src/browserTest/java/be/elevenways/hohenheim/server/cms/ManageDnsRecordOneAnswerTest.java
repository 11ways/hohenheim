package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The tenant DNS record form answers ONE refusal for a name no hosted zone contains and a
 * name inside a hosted zone the tenant does not answer for, so the form is no oracle for
 * which zones this installation hosts.
 */
class ManageDnsRecordOneAnswerTest extends HohenheimTestBase {

    private static final String ORIGIN = "one-answer-zone.test";

    private static UserPrincipal tenant;
    private static UserPrincipal operator;

    @BeforeAll
    static void seed() {
        Model zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, ORIGIN);
        zone.set(DnsZoneModel.ENABLED, true);
        zone.set(DnsZoneModel.DEFAULT_TTL, 3600);
        zone.set(DnsZoneModel.NEGATIVE_TTL, 300);
        zone.set(DnsZoneModel.SOA_REFRESH, 7200);
        zone.set(DnsZoneModel.SOA_RETRY, 3600);
        zone.set(DnsZoneModel.SOA_EXPIRE, 1209600);
        zones.save(zone);
        DnsZoneStore.INSTANCE.reload();

        int userId = ApiSupport.user("one-answer-tenant@hohenheim.local", "One Answer Tenant");
        tenant = new UserPrincipal(userId, "One Answer Tenant");

        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        operator = new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }

    @Test
    void aTenantCannotTellAnUnhostedNameFromSomeoneElsesName() {
        // 1. A name inside a HOSTED zone the tenant answers for nothing in: refused by the
        //    write pipeline's own authority check.
        Violation hosted = refusal(tenant, "www." + ORIGIN);

        // 2. A name NO hosted zone contains.
        Violation unhosted = refusal(tenant, "www.one-answer-nowhere.invalid");

        // 3. The two answers are indistinguishable: same field, same sentence.
        assertThat(unhosted.fieldName())
            .as("step 3: both refusals land on the name field")
            .isEqualTo(hosted.fieldName())
            .isEqualTo(DnsRecordModel.NAME.getName());
        assertThat(unhosted.message())
            .as("step 3: an unhosted name reads exactly like a name someone else owns")
            .isEqualTo(hosted.message())
            .isEqualTo(CmsSupport.violationText("tenant_record_not_authorized"));

        // 4. Counterfactual: the operator, who can list every zone anyway, keeps the precise
        //    sentence -- so the tenant's answer is a decision, not a lost message.
        assertThat(refusal(operator, "www.one-answer-nowhere.invalid").message())
            .as("step 4: the operator is told no hosted zone contains the name")
            .isEqualTo(CmsSupport.violationText("tenant_record_no_zone"));
    }

    /** Submit a create as {@code principal} and return the first refusal it raised. */
    private static Violation refusal(UserPrincipal principal, String name) {
        Map<String, Object> values = new HashMap<>();
        values.put(DnsRecordModel.NAME.getName(), name);
        values.put(DnsRecordModel.TYPE.getName(), DnsRecordModel.TYPE_A);
        values.put(DnsRecordModel.VALUE.getName(), "192.0.2.10");
        values.put(DnsRecordModel.TTL.getName(), 300);
        values.put(DnsRecordModel.ENABLED.getName(), true);
        Throwable[] thrown = new Throwable[1];
        TenantConduits.as(principal, () -> thrown[0] = catchThrowable(() ->
            new ManageDnsRecordResource().persistRow(values,
                AccessContext.of(TenantConduits.stubFor(principal)))));
        assertThat(thrown[0])
            .as("the create of %s as %s is refused", name, principal.displayName())
            .isInstanceOf(Violations.class);
        return ((Violations) thrown[0]).all().get(0);
    }
}
