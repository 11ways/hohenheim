package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.NodeVersionModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.source.GitProvisioner;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.RenderTemplateResult;

import java.time.Instant;
import java.util.*;

/**
 * Site CRUD request handlers.
 */
public final class SiteHandlers {

    private SiteHandlers() {
    }

    public static void init() {
        SiteModel siteModel = Models.get(SiteModel.class);
        SiteDomainModel domainModel = Models.get(SiteDomainModel.class);

        // Sites list
        HohenheimEndpoints.SITES_LIST.setHandler(conduit -> {
            List<Row> rows = siteModel.find()
                .where(SiteModel.DELETED_AT.isNull())
                .orderBy(SiteModel.CREATED_AT, SortOrder.DESC)
                .all();

            List<Map<String, Object>> sites = new ArrayList<>();
            for (Row row : rows) {
                List<Row> domains = domainModel.findBySiteId(row.get(SiteModel.ID));
                Map<String, Object> site = new HashMap<>();
                site.put("id", row.get(SiteModel.ID));
                site.put("name", row.get(SiteModel.NAME));
                site.put("siteType", siteTypeDisplayName(row.get(SiteModel.SITE_TYPE)));
                site.put("status", row.get(SiteModel.STATUS));
                site.put("enabled", row.get(SiteModel.ENABLED));
                site.put("domainCount", domains.size());
                sites.add(site);
            }

            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/sites/list"),
                Map.of("sites", sites)
            );
        });

        // Site create form (GET)
        HohenheimEndpoints.SITES_CREATE_FORM.setHandler(conduit -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("error", "");
            vars.put("siteTypes", getSiteTypeOptions());
            vars.put("settings", Map.of());
            vars.put("source", "");
            vars.put("sourceSettings", Map.of());
            vars.put("nodeVersions", getNodeVersionOptions());
            vars.put("systemUsers", getSystemUserOptions());
            vars.put("environmentVariables", List.of());
            vars.put("apiKeys", List.of());
            ServerService serverService = new ServerService();
            serverService.ensureLocal();
            vars.put("servers", serverService.names());
            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/sites/create"), vars);
        });

        // Site create (POST)
        HohenheimEndpoints.SITES_CREATE.setHandler(conduit -> {
            Map<String, String> form = HandlerSupport.formMap(conduit);

            String name = form.getOrDefault("name", "").trim();
            String siteType = form.getOrDefault("site_type", "hohenheim:proxy");

            if (name.isEmpty()) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/sites/create"),
                    Map.of("error", "Name is required")
                );
            }

            String slug = HandlerSupport.slug(name);
            Map<String, Object> settings = extractSettings(form, SiteModel.SITE_TYPE.getSchemaForValue(siteType));
            String hostname = form.getOrDefault("hostname", "").trim();
            String source = form.getOrDefault("source", "");

            Row row = siteModel.createEmptyRow();
            row.set(SiteModel.NAME, name);
            row.set(SiteModel.SLUG, slug);
            row.set(SiteModel.SITE_TYPE, siteType);
            row.set(SiteModel.SETTINGS, settings);
            row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);

            // Git source provisioning
            if (SiteModel.SOURCE_GIT.equals(source)) {
                row.set(SiteModel.SOURCE, SiteModel.SOURCE_GIT);
                Map<String, Object> sourceSettings = extractSettings(form, GitSourceSchema.SCHEMA);
                // Auto-generate webhook secret on first save
                if (!hasSecret(sourceSettings)) {
                    sourceSettings.put("webhook_secret", UUID.randomUUID().toString());
                }
                row.set(SiteModel.SOURCE_SETTINGS, sourceSettings);
            }

            siteModel.save(row);

            if (!hostname.isEmpty()) {
                int siteId = row.get(SiteModel.ID);
                Row domainRow = domainModel.createEmptyRow();
                domainRow.set(SiteDomainModel.SITE_ID, siteId);
                domainRow.set(SiteDomainModel.HOSTNAME, hostname);
                domainRow.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
                domainModel.save(domainRow);
            }

            HandlerSupport.audit(conduit, AuditLogModel.ACTION_CREATED, AuditLogModel.RESOURCE_SITE,
                row.get(SiteModel.ID), name);
            HandlerSupport.reloadProxy();
            return HandlerSupport.redirectUntyped("/sites");
        });

        // Site edit form (GET /sites/:id)
        HohenheimEndpoints.SITES_EDIT.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();

            if (site == null) {
                return new RenderTemplateResult(
                    Identifier.of("hohenheim", "hohenheim/sites/list"),
                    Map.of("sites", List.of())
                );
            }

            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/sites/edit"),
                buildEditVars(site, domainModel, "")
            );
        });

        // Site update (POST /sites/:id)
        HohenheimEndpoints.SITES_UPDATE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Map<String, String> form = HandlerSupport.formMap(conduit);

            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();
            if (site == null) return HandlerSupport.redirectUntyped("/sites");

            String name = form.getOrDefault("name", "").trim();
            if (name.isEmpty()) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/sites/edit"),
                    buildEditVars(site, domainModel, "Name is required")
                );
            }

            String siteType = form.getOrDefault("site_type", site.get(SiteModel.SITE_TYPE));
            Map<String, Object> settings = extractSettings(form, SiteModel.SITE_TYPE.getSchemaForValue(siteType));
            boolean enabled = HandlerSupport.checkbox(form, "enabled");
            String source = form.getOrDefault("source", "");

            site.set(SiteModel.NAME, name);
            site.set(SiteModel.SITE_TYPE, siteType);
            site.set(SiteModel.SETTINGS, settings);
            site.set(SiteModel.ENABLED, enabled);

            // Git source provisioning
            if (SiteModel.SOURCE_GIT.equals(source)) {
                site.set(SiteModel.SOURCE, SiteModel.SOURCE_GIT);
                Map<String, Object> sourceSettings = extractSettings(form, GitSourceSchema.SCHEMA);
                // Preserve existing webhook secret if not in form, else generate a fresh one.
                if (!hasSecret(sourceSettings)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingSource = (Map<String, Object>) site.get(SiteModel.SOURCE_SETTINGS);
                    if (existingSource != null && hasSecret(existingSource)) {
                        sourceSettings.put("webhook_secret", existingSource.get("webhook_secret"));
                    } else {
                        sourceSettings.put("webhook_secret", UUID.randomUUID().toString());
                    }
                }
                site.set(SiteModel.SOURCE_SETTINGS, sourceSettings);
            } else {
                site.set(SiteModel.SOURCE, null);
                site.set(SiteModel.SOURCE_SETTINGS, null);
            }

            siteModel.save(site);

            HandlerSupport.audit(conduit, AuditLogModel.ACTION_UPDATED, AuditLogModel.RESOURCE_SITE, siteId, name);
            HandlerSupport.reloadProxy();
            return HandlerSupport.redirectUntyped("/sites/" + siteId);
        });

        // Site toggle enabled (POST /sites/:id/toggle)
        HohenheimEndpoints.SITES_TOGGLE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();

            if (site != null) {
                boolean current = Boolean.TRUE.equals(site.get(SiteModel.ENABLED));
                site.set(SiteModel.ENABLED, !current);
                siteModel.save(site);
                HandlerSupport.audit(conduit,
                      current ? AuditLogModel.ACTION_DISABLED : AuditLogModel.ACTION_ENABLED,
                      AuditLogModel.RESOURCE_SITE, siteId, site.get(SiteModel.NAME));
                HandlerSupport.reloadProxy();
            }

            return HandlerSupport.redirectUntyped("/sites");
        });

        // Site clone (POST /sites/:id/clone)
        HohenheimEndpoints.SITES_CLONE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();
            if (site == null) return HandlerSupport.redirectUntyped("/sites");

            String name = site.get(SiteModel.NAME) + " (copy)";
            String slug = HandlerSupport.slug(name);

            Row clone = siteModel.createEmptyRow();
            clone.set(SiteModel.NAME, name);
            clone.set(SiteModel.SLUG, slug);
            clone.set(SiteModel.SITE_TYPE, site.get(SiteModel.SITE_TYPE));
            clone.set(SiteModel.SETTINGS, site.get(SiteModel.SETTINGS));
            clone.set(SiteModel.SOURCE, site.get(SiteModel.SOURCE));
            // Clone source settings but regenerate webhook secret
            @SuppressWarnings("unchecked")
            Map<String, Object> clonedSourceSettings = site.get(SiteModel.SOURCE_SETTINGS) != null
                ? new HashMap<>((Map<String, Object>) site.get(SiteModel.SOURCE_SETTINGS))
                : null;
            if (clonedSourceSettings != null) {
                clonedSourceSettings.put("webhook_secret", UUID.randomUUID().toString());
            }
            clone.set(SiteModel.SOURCE_SETTINGS, clonedSourceSettings);
            clone.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
            clone.set(SiteModel.ENABLED, false); // Clones start disabled
            siteModel.save(clone);

            // Clone domains too
            int newSiteId = clone.get(SiteModel.ID);
            List<Row> domains = domainModel.findBySiteId(siteId);
            for (Row domain : domains) {
                Row domainClone = domainModel.createEmptyRow();
                domainClone.set(SiteDomainModel.SITE_ID, newSiteId);
                domainClone.set(SiteDomainModel.HOSTNAME, domain.get(SiteDomainModel.HOSTNAME) + ".clone");
                domainClone.set(SiteDomainModel.MATCH_TYPE, domain.get(SiteDomainModel.MATCH_TYPE));
                domainClone.set(SiteDomainModel.FORCE_SSL, domain.get(SiteDomainModel.FORCE_SSL));
                domainClone.set(SiteDomainModel.HSTS_ENABLED, domain.get(SiteDomainModel.HSTS_ENABLED));
                domainClone.set(SiteDomainModel.HSTS_SUBDOMAINS, domain.get(SiteDomainModel.HSTS_SUBDOMAINS));
                domainClone.set(SiteDomainModel.PATH, domain.get(SiteDomainModel.PATH));
                domainClone.set(SiteDomainModel.STRIP_PATH, domain.get(SiteDomainModel.STRIP_PATH));
                domainClone.set(SiteDomainModel.HTTP2_SUPPORT, domain.get(SiteDomainModel.HTTP2_SUPPORT));
                domainClone.set(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT, domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT));
                domainClone.set(SiteDomainModel.LISTEN_ON, domain.get(SiteDomainModel.LISTEN_ON));
                domainClone.set(SiteDomainModel.PORT, domain.get(SiteDomainModel.PORT));
                domainClone.set(SiteDomainModel.CUSTOM_HEADERS, domain.get(SiteDomainModel.CUSTOM_HEADERS));
                domainModel.save(domainClone);
            }

            HandlerSupport.audit(conduit, AuditLogModel.ACTION_CLONED, AuditLogModel.RESOURCE_SITE,
                newSiteId, name);
            return HandlerSupport.redirectUntyped("/sites/" + newSiteId);
        });

        // Site delete (POST /sites/:id/delete)
        HohenheimEndpoints.SITES_DELETE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();

            if (site != null) {
                site.set(SiteModel.DELETED_AT, Instant.now());
                siteModel.save(site);
                HandlerSupport.audit(conduit, AuditLogModel.ACTION_DELETED, AuditLogModel.RESOURCE_SITE,
                    siteId, site.get(SiteModel.NAME));
                HandlerSupport.reloadProxy();

                // Clean up git repo directory if git-provisioned
                String source = site.get(SiteModel.SOURCE);
                if (SiteModel.SOURCE_GIT.equals(source)) {
                    GitProvisioner.deleteSiteDirectory(siteId);
                }
            }

            return HandlerSupport.redirectUntyped("/sites");
        });
    }

    /** Whether the source settings already carry a non-blank git webhook secret. */
    private static boolean hasSecret(Map<String, Object> src) {
        Object secret = src.get("webhook_secret");
        return secret != null && !((String) secret).isEmpty();
    }

    private static String siteTypeDisplayName(String typeId) {
        if (typeId == null) return "Unknown";
        // Use the framework's enum field to resolve the display name
        var enumValue = SiteModel.SITE_TYPE.getValues().get(typeId);
        return enumValue != null ? enumValue.getDisplayName() : typeId;
    }

    /**
     * Extract settings from a form based on a schema. SchemaField-with-subSchema and ListField
     * are handled by scanning indexed form keys (fieldName[N].childName and fieldName[N]).
     */
    private static Map<String, Object> extractSettings(Map<String, String> form, Schema schema) {
        Map<String, Object> settings = new HashMap<>();
        if (schema == null) return settings;

        for (var entry : schema.getFields().entrySet()) {
            String fieldName = entry.getKey();
            Field<?, ?> field = entry.getValue();

            if (field instanceof SchemaField schemaField && schemaField.getSubSchema() != null) {
                settings.put(fieldName, extractNestedSchemaList(form, fieldName, schemaField.getSubSchema()));
            } else if (field instanceof ListField<?> listField) {
                settings.put(fieldName, extractScalarList(form, fieldName, listField.getItemField()));
            } else if (field instanceof BooleanField) {
                // Unchecked checkboxes don't submit a value at all
                settings.put(fieldName, HandlerSupport.checkbox(form, fieldName));
            } else {
                String formValue = form.get(fieldName);
                if (formValue == null) continue;
                Object coerced = coerceScalar(field, formValue);
                if (coerced != null) settings.put(fieldName, coerced);
            }
        }

        return settings;
    }

    /**
     * Coerce a raw form-string value to the Java type expected by the given field.
     * Returns null for unparseable numbers so the caller can skip the entry.
     */
    private static Object coerceScalar(Field<?, ?> field, String formValue) {
        // A missing checkbox value is a real "false"; every other field type maps null to null.
        if (field instanceof BooleanField) {
            return "on".equals(formValue) || "true".equals(formValue);
        }
        if (formValue == null) return null;
        if (field instanceof IntegerField) {
            try { return Integer.parseInt(formValue); }
            catch (NumberFormatException e) { return null; }
        }
        return formValue;
    }

    /**
     * Collect `fieldName[N].childName` entries into a list of sub-schema maps.
     * Rows where every sub-field is blank are dropped so that trailing empty editor
     * rows don't pollute the stored value.
     */
    private static List<Map<String, Object>> extractNestedSchemaList(
            Map<String, String> form, String fieldName, Schema subSchema) {
        String prefix = fieldName + "[";
        Set<Integer> indices = new TreeSet<>();
        for (String key : form.keySet()) {
            if (!key.startsWith(prefix)) continue;
            int closeBracket = key.indexOf(']', prefix.length());
            if (closeBracket < 0) continue;
            // Only accept `[N].something` — bare `[N]` belongs to a flat ListField.
            if (closeBracket + 1 >= key.length() || key.charAt(closeBracket + 1) != '.') continue;
            try {
                indices.add(Integer.parseInt(key.substring(prefix.length(), closeBracket)));
            } catch (NumberFormatException e) { /* skip */ }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int idx : indices) {
            Map<String, Object> row = new LinkedHashMap<>();
            String itemPrefix = fieldName + "[" + idx + "].";
            boolean allBlank = true;
            for (var childEntry : subSchema.getFields().entrySet()) {
                String childName = childEntry.getKey();
                Field<?, ?> childField = childEntry.getValue();
                String rawValue = form.get(itemPrefix + childName);
                Object coerced = coerceScalar(childField, rawValue);
                if (coerced != null) row.put(childName, coerced);
                if (rawValue != null && !rawValue.isBlank()) allBlank = false;
            }
            if (!allBlank) result.add(row);
        }
        return result;
    }

    /**
     * Collect `fieldName[N]` entries into a flat list of the item field's type.
     * Blank entries are dropped so trailing empty editor rows don't pollute the list.
     */
    private static List<Object> extractScalarList(
            Map<String, String> form, String fieldName, Field<?, ?> itemField) {
        String prefix = fieldName + "[";
        Map<Integer, String> byIndex = new TreeMap<>();
        for (var entry : form.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix) || !key.endsWith("]")) continue;
            try {
                int idx = Integer.parseInt(key.substring(prefix.length(), key.length() - 1));
                byIndex.put(idx, entry.getValue());
            } catch (NumberFormatException e) { /* skip */ }
        }
        List<Object> result = new ArrayList<>();
        for (String value : byIndex.values()) {
            if (value == null || value.isBlank()) continue;
            Object coerced = coerceScalar(itemField, value.trim());
            if (coerced != null) result.add(coerced);
        }
        return result;
    }

    private static Map<String, Object> siteToMap(Row site) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", site.get(SiteModel.ID));
        map.put("name", site.get(SiteModel.NAME));
        map.put("slug", site.get(SiteModel.SLUG));
        map.put("siteType", site.get(SiteModel.SITE_TYPE));
        map.put("status", site.get(SiteModel.STATUS));
        map.put("enabled", site.get(SiteModel.ENABLED));
        map.put("settings", site.get(SiteModel.SETTINGS));
        map.put("description", site.get(SiteModel.DESCRIPTION));
        return map;
    }

    private static Map<String, Object> buildEditVars(Row site, SiteDomainModel domainModel, String error) {
        Integer siteId = site.get(SiteModel.ID);
        List<Row> domainRows = domainModel.findBySiteId(siteId);
        List<Map<String, Object>> domains = new ArrayList<>();
        for (Row dr : domainRows) {
            Map<String, Object> d = new HashMap<>();
            d.put("id", dr.get(SiteDomainModel.ID));
            d.put("hostname", dr.get(SiteDomainModel.HOSTNAME));
            d.put("matchType", dr.get(SiteDomainModel.MATCH_TYPE));
            d.put("forceSsl", dr.get(SiteDomainModel.FORCE_SSL));
            domains.add(d);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("site", siteToMap(site));
        vars.put("domains", domains);
        vars.put("siteTypes", getSiteTypeOptions());
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) site.get(SiteModel.SETTINGS);
        vars.put("settings", settings != null ? settings : Map.of());

        // Source provisioning data
        String source = site.get(SiteModel.SOURCE);
        vars.put("source", source != null ? source : "");
        @SuppressWarnings("unchecked")
        Map<String, Object> sourceSettings = (Map<String, Object>) site.get(SiteModel.SOURCE_SETTINGS);
        vars.put("sourceSettings", sourceSettings != null ? sourceSettings : Map.of());

        vars.put("nodeVersions", getNodeVersionOptions());
        vars.put("systemUsers", getSystemUserOptions());
        vars.put("environmentVariables",
            HandlerSupport.nameValuePairs(settings != null ? settings.get("environment_variables") : null));
        vars.put("apiKeys", extractApiKeysList(settings));
        ServerService serverService = new ServerService();
        serverService.ensureLocal();
        vars.put("servers", serverService.names());
        vars.put("error", error);

        // Process data for managed site types
        HandlerSupport.managedHandler(siteId).ifPresent(managed -> {
            vars.put("isManaged", true);
            List<Map<String, Object>> processes = new ArrayList<>();
            for (var proc : managed.getProcesses()) {
                Map<String, Object> info = HandlerSupport.processToMap(proc);
                info.put("memoryMb", proc.memoryKb() / 1024);
                processes.add(info);
            }
            vars.put("processes", processes);
        });

        return vars;
    }

    /**
     * Build site type options from the RegistryEnumField for template dropdowns.
     */
    private static List<Map<String, Object>> getSiteTypeOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        for (var entry : SiteModel.SITE_TYPE.getValues().entrySet()) {
            Map<String, Object> option = new HashMap<>();
            option.put("value", entry.getKey());
            option.put("label", entry.getValue().getDisplayName());
            Map<String, Object> props = entry.getValue().getProperties();
            if (props != null && props.containsKey("description")) {
                option.put("description", props.get("description"));
            }
            options.add(option);
        }
        return options;
    }

    private static List<Map<String, Object>> getSystemUserOptions() {
        var model = Models.get(SystemUserModel.class);
        List<Map<String, Object>> options = new ArrayList<>();
        for (Row row : model.findActive()) {
            Map<String, Object> option = new HashMap<>();
            option.put("id", row.get(SystemUserModel.ID));
            option.put("name", row.get(SystemUserModel.NAME));
            option.put("uid", row.get(SystemUserModel.UID));
            option.put("gid", row.get(SystemUserModel.GID));
            options.add(option);
        }
        return options;
    }

    private static List<Map<String, Object>> getNodeVersionOptions() {
        var model = Models.get(NodeVersionModel.class);
        List<Map<String, Object>> options = new ArrayList<>();
        for (Row row : model.findActive()) {
            Map<String, Object> option = new HashMap<>();
            option.put("id", row.get(NodeVersionModel.ID));
            option.put("version", row.get(NodeVersionModel.VERSION));
            option.put("path", row.get(NodeVersionModel.PATH));
            options.add(option);
        }
        return options;
    }

    private static List<String> extractApiKeysList(Map<String, Object> settings) {
        List<String> result = new ArrayList<>();
        if (settings == null) return result;
        Object apiKeysObj = settings.get("api_keys");
        if (apiKeysObj instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    String s = item.toString().trim();
                    if (!s.isEmpty()) result.add(s);
                }
            }
        }
        return result;
    }
}
