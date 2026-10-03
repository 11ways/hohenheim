package be.elevenways.hohenheim.server.api;

import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.result.ActionResult;
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
        if (!isApiKey(conduit)) {
            conduit.forbidden();
            return null;
        }
        return AccessContext.of(conduit);
    }

    /**
     * {@link #requireKey}, narrowed to a key whose owner holds the admin panel permission
     * (as the key's own scopes narrow it); anything else is 403. For the verbs only the
     * operator panel offers: site create and delete, zones, hosts, engines.
     *
     * AIDEV-NOTE: those endpoints ALSO declare requiresPermission(ADMIN_ACCESS), so the
     * middleware refuses first; this is the defense a relaxed declaration cannot remove.
     *
     * @return the access context, or null when the response has already been ended
     */
    public static @Nullable AccessContext requireAdminKey(@NonNull Conduit conduit) {
        AccessContext ctx = requireKey(conduit);
        if (ctx == null) {
            return null;
        }
        if (!HohenheimAccess.isAdmin(ctx)) {
            conduit.forbidden();
            return null;
        }
        return ctx;
    }

    /**
     * THE "is this caller an API key" fact: a header-carried key, never an ambient browser
     * session. Every csrfExempt automation endpoint rests on it.
     */
    public static boolean isApiKey(@NonNull Conduit conduit) {
        return conduit.getAttribute(ConduitAttributes.PRINCIPAL) instanceof ApiKeyPrincipal;
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
     * which one -- {@code unknown_field} in particular is useless without it.
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

    /**
     * THE wire adapter of {@code /api/v1} for a refusal of the operation pipeline: every reason it can receive answers
     * the status and body this API answered before the pipeline existed.
     *
     * AIDEV-NOTE: a frozen external wire keeps its shape (stage 2 contract 6.10, S3), so this maps reasons where every
     * new API lets core's edge render them. Hohenheim's instance-tier reasons answer the 422 envelope a form-level
     * {@code Violations} of the same key writes, byte-identical to the service gates' refusal; a core NOT_FOUND is the
     * route's own 404, a core FORBIDDEN or PERMISSION_DENIED the key gate's 403, and a core OPERATION_UNAVAILABLE the
     * form-level 422 of its shown reason (the words the row lane's unavailable reason wrote). Both switches are
     * exhaustive with no default, so a new member is a compile error here; any other reason, and any other module's, is
     * rethrown to core's edge, the answer an unexpected refusal escaping a handler always got.
     *
     * @return the answer, or null when the response has already been ended
     * @throws DomainRefusal a refusal this wire has no answer of its own for
     */
    public static @Nullable ActionResult<Object> refusal(@NonNull Conduit conduit, @NonNull DomainRefusal refusal) {
        DomainRefusal.Reason reason = refusal.reason();
        if (reason instanceof HohenheimRefusalReason hohenheim) {
            return switch (hohenheim) {
                case INSTANCE_NOT_PERMITTED, DATABASE_NOT_READY -> refusal(conduit, Violations.ofForm(refusal.shown()));
            };
        }
        if (reason instanceof ZenitRefusalReason zenit) {
            return switch (zenit) {
                case NOT_FOUND -> {
                    conduit.notFound();
                    yield null;
                }
                case FORBIDDEN, PERMISSION_DENIED -> {
                    conduit.forbidden();
                    yield null;
                }
                // An offered-but-dead verb (an operation's availability) is the form-level 422 the
                // row lane's unavailable reason wrote before the pipeline, same words.
                case OPERATION_UNAVAILABLE -> refusal(conduit, Violations.ofForm(refusal.shown()));
                case BAD_REQUEST, METHOD_NOT_ALLOWED, LOGIN_REQUIRED, INTERACTIVE_LOGIN_REQUIRED, RATE_LIMITED,
                     CSRF_ORIGIN, CSRF_TOKEN_MISSING, CSRF_TOKEN_INVALID, STALE, IN_PROGRESS, RETRY_MISMATCH, INVALID,
                     ARCHIVED, CYCLE, IN_USE, STORE_BUSY, OUTCOME_UNKNOWN, SECRET_ALREADY_DISCLOSED -> throw refusal;
            };
        }
        throw refusal;
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

    /**
     * The panel whose peers an API write's resource resolves its declared parent against ({@code ResourceWrites}), so a
     * record under an archived parent is refused on the API exactly as on the admin routes.
     *
     * @throws IllegalStateException when the admin panel was never registered
     */
    public static @NonNull Panel adminPanel() {
        return registeredPanel(HohenheimPanel.SLUG);
    }

    /** The {@link #adminPanel()} twin for the tenant resources, which resolve their parents in the operator panel. */
    public static @NonNull Panel managePanel() {
        return registeredPanel(ManagePanel.SLUG);
    }

    /**
     * The row entry an API write goes through, ending the response with the uniform 404 when the panel declares none
     * under that slug: on a node without the entry's role the panel's own route for it is gone
     * ({@code HohenheimPanel.addIf}), so the API answers what that route answers instead of failing on the lookup.
     *
     * AIDEV-NOTE: the role fact is read where it is declared, the panel's registration, never re-asked here; a second
     * slug-to-role list would drift from addIf the day an entry changes role.
     *
     * @return the entry, or null when the response has already been ended
     */
    public static @Nullable PanelResource<Row> rowEntry(@NonNull Conduit conduit, @NonNull Panel panel,
                                                        @NonNull String slug) {
        PanelResource<Row> entry = CmsSupport.declaredRowEntry(panel, slug);
        if (entry == null) {
            conduit.notFound();
        }
        return entry;
    }

    private static @NonNull Panel registeredPanel(@NonNull String slug) {
        Panel panel = PanelRegistry.getBySlug(slug);
        if (panel == null) {
            throw new IllegalStateException("panel '" + slug + "' is not registered");
        }
        return panel;
    }

    public static @NonNull ActionResult<Object> json(@NonNull Map<String, Object> body) {
        return HandlerSupport.json(body);
    }

    public static @NonNull Microcopy violationText(@NonNull String key) {
        return HohenheimViolations.text(key);
    }

    /** One submitted form value as a string, first-of-list folded, empty when absent. */
    public static @NonNull String formValue(@NonNull Conduit conduit, @NonNull String name) {
        return HandlerSupport.submittedString(
            FormSubmissionRawValues.fromConduit(conduit), name);
    }
}
