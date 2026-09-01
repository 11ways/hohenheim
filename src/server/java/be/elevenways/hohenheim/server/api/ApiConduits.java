package be.elevenways.hohenheim.server.api;

import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     *
     * The envelope is {@code {status, code, message, field, violations}}: the first three
     * describe the FIRST violation (as they always did), {@code field} is its path and
     * {@code violations} carries every refusal with its own path, key and sentence. Without
     * the path a caller submitting twenty form fields was told a value was refused and never
     * which one -- {@code zenit.coercion.unknown_field} in particular is useless without it.
     *
     * A form-level violation has no path, so {@code field} is absent rather than empty: an
     * API client must be able to tell "this field" from "this submission".
     */
    public static @NonNull ActionResult<Object> refusal(@NonNull Conduit conduit,
                                                        @NonNull Violations violations) {
        List<Violation> all = violations.all();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 422);
        if (all.isEmpty()) {
            body.put("code", "REFUSED");
            body.put("message", violations.getMessage());
        }
        else {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Violation violation : all) {
                rows.add(violationMap(conduit, violation));
            }
            body.putAll(rows.get(0));
            body.put("violations", rows);
        }
        conduit.setResponseStatus(422);
        return json(body);
    }

    /** One violation on the wire: its path (absent when form-level), machine key and sentence. */
    private static @NonNull Map<String, Object> violationMap(@NonNull Conduit conduit,
                                                             @NonNull Violation violation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", violation.message().key());
        row.put("message", violation.message()
            .resolve(conduit.getLocales(), conduit.getMessageResolver()));
        // The PATH, never the field name: it carries nesting, indices and locale prefixes
        // (settings.forward_port), which is what a caller needs to find its own input. A
        // form-level violation has none, and then there is no field key at all.
        if (!violation.path().isBlank()) {
            row.put("field", violation.path());
        }
        return row;
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
