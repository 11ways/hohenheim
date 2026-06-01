package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.process.ManagedProcess;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        if (conduit instanceof HttpConduit http) {
            return http.getFormData().toStringMap();
        }
        return Map.of();
    }

    /** Whether a checkbox-style form field is checked ("on" or "true"). */
    public static boolean checkbox(Map<String, String> form, String key) {
        String v = form.get(key);
        return "on".equals(v) || "true".equals(v);
    }

    /** Lowercase dash-slug of a display name (collapses non-alphanumerics, trims leading/trailing dashes). */
    public static String slug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /** Stream a string body as a downloadable attachment with a sanitized filename. */
    public static void download(Conduit conduit, String contentType, String filename, String body) {
        if (conduit instanceof HttpConduit http) {
            String safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            http.setResponseHeader("Content-Type", contentType);
            http.setResponseHeader("Content-Disposition", "attachment; filename=\"" + safeName + "\"");
        }
        conduit.endWithContentType(contentType, body);
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

    /** Common audit-entry view map (action/resourceType/resourceName/createdAt) for template lists. */
    public static Map<String, Object> auditEntryToMap(Row row) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("action", row.get(AuditLogModel.ACTION));
        entry.put("resourceType", row.get(AuditLogModel.RESOURCE_TYPE));
        entry.put("resourceName", row.get(AuditLogModel.RESOURCE_NAME));
        entry.put("createdAt", row.get(AuditLogModel.CREATED_AT));
        return entry;
    }

    /** Shared process view map (pid/port/cpu/isolated/ready/fingerprints); callers add memory/startTime. */
    public static Map<String, Object> processToMap(ManagedProcess proc) {
        Map<String, Object> info = new HashMap<>();
        info.put("pid", proc.pid());
        info.put("port", proc.port());
        info.put("cpu", proc.cpuPercent());
        info.put("isolated", proc.isIsolated());
        info.put("ready", proc.isReady());
        info.put("fingerprints", proc.activeFingerprintCount());
        return info;
    }

    /** Read a list of {name, value} maps from a raw stored value into clean string pairs. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, String>> nameValuePairs(Object rawList) {
        List<Map<String, String>> result = new ArrayList<>();
        if (rawList instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String name = map.get("name") != null ? map.get("name").toString() : "";
                    String value = map.get("value") != null ? map.get("value").toString() : "";
                    result.add(Map.of("name", name, "value", value));
                }
            }
        }
        return result;
    }

    /** Parse {@code prefix[i].name}/{@code prefix[i].value} form fields (as emitted by hh-key-value-editor) into pairs. */
    public static List<Map<String, String>> extractIndexedPairs(Map<String, String> form, String prefix) {
        List<Map<String, String>> pairs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String name = form.get(prefix + "[" + i + "].name");
            if (name == null) {
                break;
            }
            if (!name.isBlank()) {
                pairs.add(Map.of("name", name.trim(), "value", form.getOrDefault(prefix + "[" + i + "].value", "")));
            }
        }
        return pairs;
    }

    /** Dropdown {id, name} options from certificate rows, excluding the internal ACME account row. */
    public static List<Map<String, Object>> certificateOptions(List<Row> certRows) {
        List<Map<String, Object>> certs = new ArrayList<>();
        for (Row cr : certRows) {
            if (CertificateModel.PROVIDER_ACME_ACCOUNT.equals(cr.get(CertificateModel.PROVIDER))) {
                continue;
            }
            Map<String, Object> c = new HashMap<>();
            c.put("id", String.valueOf(cr.get(CertificateModel.ID)));
            c.put("name", cr.get(CertificateModel.NICE_NAME));
            certs.add(c);
        }
        return certs;
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
