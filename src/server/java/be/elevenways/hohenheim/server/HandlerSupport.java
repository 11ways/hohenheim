package be.elevenways.hohenheim.server;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.HttpConduit;
import be.elevenways.zenit.server.http.RedirectResult;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The plumbing every host-declared handler class shares: redirects, downloads, form
 * reading, untyped JSON and the Fetch-Metadata cross-site decision.
 */
final class HandlerSupport {

    /**
     * Every redirect below lands on the OPERATOR panel: these are the installation
     * administration lanes (certificates, DNS zones, databases). The slug is the literal
     * these handlers already produced, so no URL moves.
     */
    static final String ADMIN = "admin";

    private HandlerSupport() {
    }

    static Map<String, String> formMap(Conduit conduit) {
        if (conduit instanceof HttpConduit http) {
            return http.getFormData().toStringMap();
        }
        return Map.of();
    }

    static String submittedString(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value instanceof List<?> list) {
            value = list.isEmpty() ? null : list.get(0);
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * Whether this request is a cross-site browser drive (an {@code <img src>}, a
     * cross-origin fetch, a form POST from another site) rather than a same-origin click or
     * a top-level navigation. Reads the browser-set {@code Sec-Fetch-Site} metadata header.
     */
    static boolean isCrossSiteBrowserRequest(Conduit conduit) {
        return isCrossSiteFetch(conduit.getRequestHeader("Sec-Fetch-Site"));
    }

    /**
     * The Fetch-Metadata decision, isolated as a pure function: {@code cross-site} is the ONLY
     * value refused. {@code same-origin}/{@code same-site} (a real click) and {@code none} (a
     * top-level navigation) pass, and so does a header-less client (an old browser, curl or
     * ddclient) -- a request with no ambient cookie is not the CSRF victim this guards.
     */
    static boolean isCrossSiteFetch(@Nullable String secFetchSite) {
        return secFetchSite != null && secFetchSite.equalsIgnoreCase("cross-site");
    }

    /** Stream a binary body as a downloadable attachment with a sanitized filename. */
    static void download(Conduit conduit, String contentType, String filename, byte[] body) {
        if (conduit instanceof HttpConduit http) {
            String safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            http.setResponseHeader("Content-Type", contentType);
            http.setResponseHeader("Content-Disposition", "attachment; filename=\"" + safeName + "\"");
        }
        conduit.endWithBytes(contentType, body);
    }

    @SuppressWarnings("unchecked")
    static ActionResult<Object> jsonUntyped(Map<String, Object> data) {
        return (ActionResult<Object>) (ActionResult<?>) new JsonResult(data);
    }

    /**
     * THE redirect of these handlers: the URL comes from a typed {@link RouteTarget}, never
     * from a concatenated literal.
     */
    static ActionResult<Object> redirect(@NonNull RouteTarget target) {
        return redirectUntyped(target.toUrl());
    }

    /**
     * Redirect to a URL that is ALREADY a URL and not an endpoint: the sanitized
     * {@code _return} value a form submitted. {@link ReturnTarget} hands that back as a
     * String, so there is no target to compose -- every OTHER redirect here goes through
     * {@link #redirect(RouteTarget)}.
     */
    @SuppressWarnings("unchecked")
    static ActionResult<Object> redirectUntyped(String url) {
        return (ActionResult<Object>) (ActionResult<?>) new RedirectResult(url);
    }

    /**
     * The first violation's own message, so a domain refusal keeps its localized text.
     */
    static @NonNull Microcopy violationMessage(@NonNull Violations violations) {
        return violations.all().isEmpty()
            ? Microcopy.of("refused").withFilter("scope", "violations")
            : violations.all().get(0).message();
    }
}
