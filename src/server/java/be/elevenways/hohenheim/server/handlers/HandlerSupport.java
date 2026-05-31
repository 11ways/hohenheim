package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.server.http.HttpConduit;
import be.elevenways.zenit.server.http.RedirectResult;

import java.util.Map;
import java.util.Optional;

/**
 * Shared helpers for the Hohenheim request handlers.
 */
public final class HandlerSupport {

    private HandlerSupport() {
    }

    @SuppressWarnings("unchecked")
    public static ActionResult<Object> renderUntyped(Identifier templateId, Map<String, Object> vars) {
        return (ActionResult<Object>) (ActionResult<?>) new RenderTemplateResult(templateId, vars);
    }

    @SuppressWarnings("unchecked")
    public static ActionResult<Object> redirectUntyped(String url) {
        return (ActionResult<Object>) (ActionResult<?>) new RedirectResult(url);
    }

    @SuppressWarnings("unchecked")
    public static ActionResult<Object> jsonUntyped(Map<String, Object> data) {
        return (ActionResult<Object>) (ActionResult<?>) new JsonResult(data);
    }

    public static void reloadProxy() {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null) proxy.reload();
    }

    public static String valueOrEmpty(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    /** Shared identifier-name rule for the resource create forms; returns an error or null. */
    public static String validateName(String name) {
        if (name.isEmpty()) return "Name is required";
        if (!name.matches("[a-z0-9][a-z0-9-]*")) {
            return "Name must be lowercase letters, digits, and dashes";
        }
        return null;
    }

    public static Map<String, String> formMap(Conduit conduit) {
        return ((HttpConduit) conduit).getFormData().toStringMap();
    }

    public static void audit(Conduit conduit, String action, String resourceType,
                             Object resourceId, String resourceName) {
        AuditLogModel model = Models.get(AuditLogModel.class);
        Principal principal = conduit.getAttribute(ConduitAttributes.PRINCIPAL);
        Row row = model.createEmptyRow();
        if (principal != null && !principal.isAnonymous()) {
            row.set(AuditLogModel.USER_ID, (int) principal.id());
            row.set(AuditLogModel.USER_LABEL, principal.displayName());
        }
        row.set(AuditLogModel.ACTION, action);
        row.set(AuditLogModel.RESOURCE_TYPE, resourceType);
        row.set(AuditLogModel.RESOURCE_ID, resourceId != null ? String.valueOf(resourceId) : null);
        row.set(AuditLogModel.RESOURCE_NAME, resourceName);
        model.save(row);
    }

    public static Optional<ManagedProcessSiteHandler> managedHandler(Integer siteId) {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null && siteId != null) {
            var handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
            if (handler instanceof ManagedProcessSiteHandler managed) {
                return Optional.of(managed);
            }
        }
        return Optional.empty();
    }
}
