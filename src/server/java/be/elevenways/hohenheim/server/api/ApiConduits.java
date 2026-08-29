package be.elevenways.hohenheim.server.api;

import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.ErrorResponse;
import be.elevenways.zenit.common.result.ErrorResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The shared plumbing of every {@code /api/v1} handler: key-only principals, the
 * violation-to-422 mapping and the JSON result shape -- ONE definition, so the
 * instance lane and the PaaS lane can never drift apart on refusal semantics.
 */
public final class ApiConduits {

    private ApiConduits() {
    }

    /**
     * Refuse anything that is not an API key. A browser session reaching an automation
     * endpoint would be a CSRF-exempt, cookie-authenticated mutation, which is what
     * csrfExempt would otherwise cost; it is also how the two surfaces stay honestly
     * separate ("HTML routes are not the automation API").
     *
     * @return the access context, or null when the response has already been ended
     */
    public static @Nullable AccessContext requireKey(@NonNull Conduit conduit) {
        if (!(conduit.getAttribute(ConduitAttributes.PRINCIPAL) instanceof ApiKeyPrincipal)) {
            conduit.forbidden();
            return null;
        }
        return AccessContext.of(conduit);
    }

    /**
     * Map a typed refusal onto 422 carrying the violation's MACHINE KEY as the error
     * code, so an API caller and an HTML caller are told the same named thing (the
     * HTML surface renders the same Microcopy).
     */
    @SuppressWarnings("unchecked")
    public static @NonNull ActionResult<Object> refusal(@NonNull Conduit conduit,
                                                        @NonNull Violations violations) {
        List<Violation> all = violations.all();
        String code = "REFUSED";
        String message = violations.getMessage();
        if (!all.isEmpty()) {
            code = all.get(0).message().key();
            message = all.get(0).message()
                .resolve(conduit.getLocales(), conduit.getMessageResolver());
        }
        return (ActionResult<Object>) (ActionResult<?>) new ErrorResult(
            ErrorResponse.of(422, code, message));
    }

    @SuppressWarnings("unchecked")
    public static @NonNull ActionResult<Object> json(@NonNull Map<String, Object> body) {
        return (ActionResult<Object>) (ActionResult<?>) new JsonResult<>(body);
    }

    public static @NonNull Microcopy violationText(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }

    /** One submitted form value as a string, first-of-list folded, empty when absent. */
    public static @NonNull String formValue(@NonNull Conduit conduit, @NonNull String name) {
        return InstanceTemplates.submittedString(
            FormSubmissionRawValues.fromConduit(conduit), name);
    }
}
