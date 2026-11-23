package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.upstream.kinds.AddressUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.UpstreamSettings;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.submit.FormValidator;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The request-timeout setting is written by two upstream kinds under one key, read by one
 * runtime and explained by one help text; all four must agree. The instance kind used to
 * refuse 0 -- the value its own help text documents as "no limit" and the value
 * {@code RouteEntry.parseRequestTimeout} maps to Undertow's unlimited -- while the address
 * kind validated nothing at all, so a negative there was accepted and then silently folded
 * to the 30s default. The idle timeout shares that home and its bounds, and like every site
 * setting it is the operator's alone: a tenant managing the site writes neither.
 */
class RequestTimeoutBoundsTest extends HohenheimHttpTestBase {

    /** The submit path in miniature: coerce is transport, this is the validation it feeds. */
    private static Violations validate(IntegerField field, Integer seconds) {
        FormSpec spec = FormSpec.builder().add(field).build();
        Map<String, Object> coerced = new HashMap<>();
        coerced.put(field.getName(), seconds);
        return FormValidator.validateCoercedForm(spec, coerced, Collections.emptyMap());
    }

    @Test
    void bothKindsAcceptTheDocumentedSentinelAndRefuseWhatTheRuntimeCannotHonour() {
        IntegerField instance = InstanceUpstreamKind.REQUEST_TIMEOUT;
        IntegerField address = AddressUpstreamKind.REQUEST_TIMEOUT;

        // 1. Zero is the documented "no limit" sentinel for streaming, gRPC and WebSocket
        //    backends -- the whole reason the field is not simply a positive number.
        assertThat(validate(instance, 0).isEmpty())
            .as("step 1: the instance kind accepts the sentinel its help text documents")
            .isTrue();
        assertThat(validate(address, 0).isEmpty())
            .as("step 1: and so does the address kind").isTrue();

        // 2. An ordinary positive value, and the upper bound itself (inclusive).
        assertThat(validate(instance, 30).isEmpty()).as("step 2: an ordinary timeout").isTrue();
        assertThat(validate(instance, 3600).isEmpty())
            .as("step 2: the upper bound is inclusive").isTrue();
        assertThat(validate(address, 3600).isEmpty())
            .as("step 2: on both kinds").isTrue();

        // 3. One second past it is refused, on both kinds.
        assertThat(validate(instance, 3601).isEmpty())
            .as("step 3: past the bound the instance kind refuses").isFalse();
        assertThat(validate(address, 3601).isEmpty())
            .as("step 3: and so does the address kind").isFalse();

        // 4. A negative is refused LOUDLY rather than folded: the runtime turns it into
        //    the 30s default, so an operator who typed -1 would otherwise be told nothing
        //    and get a timeout they never asked for.
        assertThat(validate(instance, -1).isEmpty())
            .as("step 4: the instance kind refuses a negative").isFalse();
        assertThat(validate(address, -1).isEmpty())
            .as("step 4: and the address kind, which used to accept it").isFalse();

        // 5. Absence stays absence: no value means the runtime's own default, not a
        //    violation -- the field is optional on both kinds.
        assertThat(validate(instance, null).isEmpty())
            .as("step 5: an unset timeout is not a violation").isTrue();
        assertThat(validate(address, null).isEmpty())
            .as("step 5: on either kind").isTrue();
    }

    @Test
    void bothKindsBoundTheIdleTimeoutAsTheRequestTimeout() {
        IntegerField instance = InstanceUpstreamKind.IDLE_TIMEOUT;
        IntegerField address = AddressUpstreamKind.IDLE_TIMEOUT;

        // 1. Unset and 0 both mean "no idle limit", and the upper bound is inclusive.
        for (IntegerField field : List.of(instance, address)) {
            assertThat(validate(field, null).isEmpty()).as("step 1: unset is no limit").isTrue();
            assertThat(validate(field, 0).isEmpty()).as("step 1: 0 is no limit").isTrue();
            assertThat(validate(field, 900).isEmpty()).as("step 1: an ordinary idle time").isTrue();
            assertThat(validate(field, 3600).isEmpty()).as("step 1: the upper bound is inclusive").isTrue();
        }

        // 2. Past the bound and below zero are refused in the request timeout's own words.
        for (IntegerField field : List.of(instance, address)) {
            assertThat(validate(field, 3601).isEmpty()).as("step 2: past the bound is refused").isFalse();
            assertThat(validate(field, -1).isEmpty()).as("step 2: a negative is refused").isFalse();
            assertThat(validate(field, -1).all().get(0).message().key())
                .as("step 2: with the key the request timeout's refusal carries")
                .isEqualTo(validate(InstanceUpstreamKind.REQUEST_TIMEOUT, -1).all().get(0).message().key());
        }
    }

    @Test
    void aTenantManagingTheSiteWritesNeitherTimeoutWhileTheOperatorDoes() {
        Model model = Models.get(SiteModel.class);
        Row admin = Models.get(UserModel.class).find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        UserPrincipal operator = new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
        UUID tenantId = ApiSupport.user("idle-timeout@hohenheim.local", "Idle Timeout Tenant");
        UserPrincipal tenant = new UserPrincipal(tenantId, "Idle Timeout Tenant");
        Row site = model.createEmptyRow();
        site.set(SiteModel.NAME, "idle-timeout-site");
        site.set(SiteModel.SLUG, "idle-timeout-site");
        site.set(SiteModel.UPSTREAM_KIND, AddressUpstreamKind.ID.toString());
        // A public address passes the upstream judgement, so the refusal below is the settings freeze itself.
        site.set(SiteModel.SETTINGS, new HashMap<>(Map.of("forward_scheme", "http",
            "forward_host", "93.184.216.34", "forward_port", 80)));
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, true);
        TenantConduits.as(operator, () -> model.save(site));
        int siteId = site.get(SiteModel.ID);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId,
            HohenheimCapabilities.MANAGE, true);

        // 1. A tenant write of either timeout is refused as a frozen column, and nothing is stored.
        for (String key : List.of(UpstreamSettings.IDLE_TIMEOUT, UpstreamSettings.REQUEST_TIMEOUT)) {
            Violations refused = catchThrowableOfType(() -> TenantConduits.as(tenant,
                () -> model.save(withSetting(model, siteId, key, 900))), Violations.class);
            assertThat((Throwable) refused).as("step 1: a tenant may not set %s", key).isNotNull();
            assertThat(refused.all().get(0).message().key()).as("step 1: %s is operator authority", key)
                .isEqualTo("tenant_field_frozen");
            assertThat(settingOf(model, siteId, key)).as("step 1: %s stayed unset", key).isNull();
        }

        // 2. The operator's write of the idle timeout lands.
        assertThatCode(() -> TenantConduits.as(operator,
            () -> model.save(withSetting(model, siteId, UpstreamSettings.IDLE_TIMEOUT, 900))))
            .as("step 2: the operator sets the idle timeout").doesNotThrowAnyException();
        assertThat(settingOf(model, siteId, UpstreamSettings.IDLE_TIMEOUT)).as("step 2: stored")
            .asString().isEqualTo("900");
    }

    /** @return the stored site with one setting changed, ready to save */
    private static Row withSetting(Model model, int siteId, String key, Object value) {
        Row row = model.findById(siteId);
        Map<String, Object> next = new HashMap<>(RawValues.map(row.get(SiteModel.SETTINGS)));
        next.put(key, value);
        row.set(SiteModel.SETTINGS, next);
        return row;
    }

    private static Object settingOf(Model model, int siteId, String key) {
        return RawValues.map(model.findById(siteId).get(SiteModel.SETTINGS)).get(key);
    }
}
