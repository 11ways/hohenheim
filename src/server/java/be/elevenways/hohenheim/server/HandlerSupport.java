package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.coerce.PrimitiveCoercion;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.HttpConduit;
import be.elevenways.zenit.server.http.RedirectResult;
import be.elevenways.zenit.server.http.ReturnTarget;
import be.elevenways.zenit.server.http.ServeStreamResult;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * The plumbing every host-declared handler class shares: redirects, downloads, form
 * reading, untyped JSON, refusal text, background hand-off and the Fetch-Metadata
 * cross-site decision.
 *
 * AIDEV-NOTE: PUBLIC on purpose. The instance-template handlers, the file endpoints and
 * several cms pages each grew a private copy of one of these (submittedString,
 * firstMessage/messageOf, download); they belong here, and this class is the one home
 * a new handler in any package reaches for.
 */
public final class HandlerSupport {

    /**
     * Every redirect below lands on the OPERATOR panel: these are the installation
     * administration lanes (certificates, DNS zones, databases).
     */
    public static final String ADMIN = HohenheimSlugs.ADMIN;

    /** The delegated panel's slug, for the handlers that serve both lanes. */
    public static final String MANAGE = HohenheimSlugs.MANAGE;

    private HandlerSupport() {
    }

    /** The submitted form as flat strings (last value wins); empty outside an HTTP request. */
    public static @NonNull Map<String, String> formMap(@NonNull Conduit conduit) {
        if (conduit instanceof HttpConduit http) {
            return http.getFormData().toStringMap();
        }
        return Map.of();
    }

    /** One raw submit value as a trimmed string ("" when absent); a list takes its first. */
    public static @NonNull String submittedString(@NonNull Map<String, Object> values,
                                                  @NonNull String name) {
        Object value = values.get(name);
        if (value instanceof List<?> list) {
            value = list.isEmpty() ? null : list.get(0);
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** One raw submit value as an Integer, or null when absent, blank or unparseable. */
    public static @Nullable Integer submittedInteger(@NonNull Map<String, Object> values,
                                                     @NonNull String name) {
        PrimitiveCoercion.Result<Integer> parsed = PrimitiveCoercion.toInteger(
            submittedString(values, name), PrimitiveCoercion.NumberRule.EXACT_VALUE,
            PrimitiveCoercion.TextRule.TRIMMED_BLANK_IS_NULL);
        return parsed.ok() ? parsed.value() : null;
    }

    /** One raw submit value as a positive id, or 0 when absent, unparseable or not positive. */
    public static int submittedId(@NonNull Map<String, Object> values, @NonNull String name) {
        Integer parsed = submittedInteger(values, name);
        return parsed != null && parsed > 0 ? parsed : 0;
    }

    /**
     * Whether this request is a cross-site browser drive (an {@code <img src>}, a
     * cross-origin fetch, a form POST from another site) rather than a same-origin click or
     * a top-level navigation. Reads the browser-set {@code Sec-Fetch-Site} metadata header.
     */
    public static boolean isCrossSiteBrowserRequest(@NonNull Conduit conduit) {
        return isCrossSiteFetch(conduit.getRequestHeader("Sec-Fetch-Site"));
    }

    /**
     * The Fetch-Metadata decision, isolated as a pure function: {@code cross-site} is the ONLY
     * value refused. {@code same-origin}/{@code same-site} (a real click) and {@code none} (a
     * top-level navigation) pass, and so does a header-less client (an old browser, curl or
     * ddclient) -- a request with no ambient cookie is not the CSRF victim this guards.
     */
    public static boolean isCrossSiteFetch(@Nullable String secFetchSite) {
        return secFetchSite != null && secFetchSite.equalsIgnoreCase("cross-site");
    }

    /** Stream a binary body as a downloadable attachment with a sanitized filename. */
    public static void download(@NonNull Conduit conduit, @NonNull String contentType,
                                @NonNull String filename, byte[] body) {
        if (conduit instanceof HttpConduit http) {
            http.setResponseHeader("Content-Type", contentType);
            http.setResponseHeader("Content-Disposition", attachmentDisposition(filename));
        }
        conduit.endWithBytes(contentType, body);
    }

    /**
     * Serve a body of known size as a downloadable attachment WITHOUT buffering it: zenit's
     * ServeStreamResult copies it to the wire (any size, a long) and always closes it.
     */
    @SuppressWarnings("unchecked")
    public static @NonNull ActionResult<Object> downloadStream(@NonNull String contentType,
                                                               @NonNull String filename,
                                                               @NonNull InputStream body, long size) {
        return (ActionResult<Object>) (ActionResult<?>) new ServeStreamResult(body, size)
            .contentType(contentType)
            .cacheControl("no-store")
            .contentDisposition(attachmentDisposition(filename));
    }

    /** THE attachment header of every download: the filename reduced to a safe token set. */
    private static @NonNull String attachmentDisposition(@NonNull String filename) {
        String safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "attachment; filename=\"" + safeName + "\"";
    }

    /** An untyped JSON answer; the one spelling of the generic cast every handler needs. */
    @SuppressWarnings("unchecked")
    public static @NonNull ActionResult<Object> json(@NonNull Map<String, Object> data) {
        return (ActionResult<Object>) (ActionResult<?>) new JsonResult<>(data);
    }

    /**
     * THE redirect of these handlers: the URL comes from a typed {@link RouteTarget}, never
     * from a concatenated literal.
     */
    public static @NonNull ActionResult<Object> redirect(@NonNull RouteTarget target) {
        return redirectUntyped(target.toUrl());
    }

    /**
     * Redirect to a URL that is ALREADY a URL and not an endpoint: the sanitized
     * {@code _return} value a form submitted. {@link ReturnTarget} hands that back as a
     * String, so there is no target to compose -- every OTHER redirect here goes through
     * {@link #redirect(RouteTarget)}.
     */
    @SuppressWarnings("unchecked")
    public static @NonNull ActionResult<Object> redirectUntyped(@NonNull String url) {
        return (ActionResult<Object>) (ActionResult<?>) new RedirectResult(url);
    }

    /**
     * THE first-violation message: the first violation's own Microcopy, so a domain refusal
     * keeps its localized text, and the generic refusal when the set is empty -- never an
     * {@code all().get(0)} that throws on an empty refusal.
     */
    public static @NonNull Microcopy violationMessage(@NonNull Violations violations) {
        return violations.all().isEmpty()
            ? Microcopy.of("refused").withFilter("scope", "violations")
            : violations.all().get(0).message();
    }

    /** {@link #violationMessage} resolved in the request's own locale chain. */
    public static @NonNull String messageOf(@NonNull Conduit conduit, @NonNull Violations violations) {
        return violationMessage(violations).resolve(conduit.getLocales(), conduit.getMessageResolver());
    }

    /**
     * Run request-authorized work on a virtual thread, carrying the request's datasource and
     * its accountability, so the activity rows the work writes still name who asked.
     *
     * AIDEV-NOTE: the tenant gates inside the services read the REQUEST scope, which does not
     * follow the work onto the new thread -- there every gate passes as system work. So a
     * caller authorizes EVERYTHING synchronously before handing off; this method decides
     * nothing and must never be the first thing a handler does.
     */
    public static void inBackground(@NonNull Runnable work) {
        Datasource datasource = Db.currentOrDefault();
        Accountability caller = Accountability.current();
        JobRunner.startVirtualThread(() -> Db.run(datasource, () -> Accountability.runAs(caller, work)));
    }
}
