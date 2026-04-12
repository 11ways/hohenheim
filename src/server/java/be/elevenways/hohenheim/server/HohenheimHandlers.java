package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.server.setting.ServerSettings;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.NodeVersionModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.model.UserModel;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.server.source.GitProvisioner;
import be.elevenways.hohenheim.server.process.ManagedProcess;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.ProcessTerminalHandler;
import be.elevenways.hohenheim.server.stats.DashboardWebSocketHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.server.http.HttpConduit;
import be.elevenways.zenit.server.http.Middleware;
import be.elevenways.zenit.server.http.RedirectResult;

import org.bouncycastle.openssl.PEMParser;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Server-side request handlers for Hohenheim endpoints.
 */
public class HohenheimHandlers {

    @SuppressWarnings("unchecked")
    private static ActionResult<Object> renderUntyped(Identifier templateId, Map<String, Object> vars) {
        return (ActionResult<Object>) (ActionResult<?>) new RenderTemplateResult(templateId, vars);
    }

    @SuppressWarnings("unchecked")
    private static ActionResult<Object> redirectUntyped(String url) {
        return (ActionResult<Object>) (ActionResult<?>) new RedirectResult(url);
    }

    public static void init() {
        var ds = HohenheimDatabase.datasource();
        var siteModel = new SiteModel(ds);
        var domainModel = new SiteDomainModel(ds);
        var certModel = new CertificateModel(ds);
        var auditModel = new AuditLogModel(ds);
        var accessListModel = new AccessListModel(ds);

        initAuth();
        initTestEndpoints();
        initDashboard(siteModel, certModel, auditModel);
        initSites(siteModel, domainModel, auditModel);
        initDomains(siteModel, domainModel, auditModel);
        initCertificates(certModel, auditModel);
        initSettings();
        initAuditLog(auditModel);
        initAccessLists(accessListModel, auditModel);
        initProcessControl(auditModel);
        initWebSockets();
    }

    private static void initWebSockets() {
        HohenheimEndpoints.DASHBOARD_LIVE.setHandlerFactory(
            DashboardWebSocketHandler::new);

        HohenheimEndpoints.PROCESS_TERMINAL.setHandlerFactory(session -> {
            Integer siteId = session.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = session.getParameter(HohenheimEndpoints.PID);
            var proxy = ServerMain.getProxyServer();
            ManagedProcess proc = null;
            if (proxy != null && siteId != null && pid != null) {
                var handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
                if (handler instanceof ManagedProcessSiteHandler managed) {
                    proc = managed.getProcess(pid);
                }
            }
            return new ProcessTerminalHandler(session, proc);
        });
    }

    // -----------------------------------------------------------------------
    // Test endpoints (for verifying error handling behavior)
    // -----------------------------------------------------------------------

    private static void initTestEndpoints() {
        HohenheimEndpoints.TEST_ERROR.setHandler(conduit -> {
            throw new RuntimeException("Deliberate test error for error handling verification");
        });

        // Health check endpoint (no auth required -- handled in middleware)
        HohenheimEndpoints.HEALTH.setHandler(conduit -> {
            var proxy = ServerMain.getProxyServer();
            Map<String, Object> status = new HashMap<>();
            status.put("status", "ok");
            status.put("httpState", proxy != null ? proxy.getHttpState().name() : "UNKNOWN");
            status.put("httpsState", proxy != null ? proxy.getHttpsState().name() : "UNKNOWN");
            return new JsonResult<>(status);
        });
    }

    // -----------------------------------------------------------------------
    // Auth
    // -----------------------------------------------------------------------

    private static void initAuth() {
        // Auth middleware: protect all routes except /login, /setup, and static assets
        Middleware authMiddleware = new Middleware(
            Identifier.of("hohenheim", "auth"),
            "/",
            (middlePath, conduit) -> {
                String path = conduit.getPath();

                // Allow login, setup, and asset paths through
                if (path.equals("/login") || path.equals("/setup")
                    || path.startsWith("/api/health")
                    || path.endsWith(".js") || path.endsWith(".css")
                    || path.endsWith(".ico")) {
                    return null;
                }

                // If no users exist, redirect to setup
                if (!AuthHelper.hasAnyUsers()) {
                    return conduit.hardRedirect("/setup");
                }

                // Check session
                Row user = AuthHelper.getCurrentUser(conduit);
                if (user == null) {
                    return conduit.hardRedirect("/login");
                }

                return null; // Authenticated, proceed to endpoint
            }
        );
        authMiddleware.setWeight(50);

        // Login page (GET)
        HohenheimEndpoints.LOGIN.setHandler(conduit -> {
            Row user = AuthHelper.getCurrentUser(conduit);
            if (user != null) {
                return conduit.hardRedirect("/");
            }

            return renderUntyped(
                Identifier.of("hohenheim", "hohenheim/login"),
                Map.of("error", "")
            );
        });

        // Login (POST)
        HohenheimEndpoints.LOGIN_POST.setHandler(conduit -> {
            HttpConduit http = (HttpConduit) conduit;
            Map<String, String> form = http.getFormData().toStringMap();

            String email = form.getOrDefault("email", "").trim();
            String password = form.getOrDefault("password", "");

            if (email.isEmpty() || password.isEmpty()) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/login"),
                    Map.of("error", "Email and password are required")
                );
            }

            var ds = HohenheimDatabase.datasource();
            var userModel = new UserModel(ds);
            Row user = userModel.find().where(UserModel.EMAIL.eq(email)).first();

            if (user == null || !AuthHelper.verifyPassword(password, user.get(UserModel.PASSWORD_HASH))) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/login"),
                    Map.of("error", "Invalid email or password")
                );
            }

            boolean isDisabled = Boolean.TRUE.equals(user.get(UserModel.IS_DISABLED));
            if (isDisabled) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/login"),
                    Map.of("error", "Account is disabled")
                );
            }

            int userId = user.get(UserModel.ID);
            String token = AuthHelper.createSession(userId);
            AuthHelper.setSessionCookie(conduit, token);

            return conduit.hardRedirect("/");
        });

        // Logout (POST)
        HohenheimEndpoints.LOGOUT.setHandler(conduit -> {
            String token = AuthHelper.getCookieValue(conduit, "hh_session");
            AuthHelper.deleteSession(token);
            AuthHelper.clearSessionCookie(conduit);
            return conduit.hardRedirect("/login");
        });

        // Setup page (GET) - first user creation
        HohenheimEndpoints.SETUP.setHandler(conduit -> {
            if (AuthHelper.hasAnyUsers()) {
                return conduit.hardRedirect("/login");
            }

            return renderUntyped(
                Identifier.of("hohenheim", "hohenheim/setup"),
                Map.of("error", "")
            );
        });

        // Setup (POST) - create first user
        HohenheimEndpoints.SETUP_POST.setHandler(conduit -> {
            if (AuthHelper.hasAnyUsers()) {
                return conduit.hardRedirect("/login");
            }

            HttpConduit http = (HttpConduit) conduit;
            Map<String, String> form = http.getFormData().toStringMap();

            String email = form.getOrDefault("email", "").trim();
            String name = form.getOrDefault("name", "").trim();
            String password = form.getOrDefault("password", "");
            String confirm = form.getOrDefault("confirm_password", "");

            if (email.isEmpty() || name.isEmpty() || password.isEmpty()) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/setup"),
                    Map.of("error", "All fields are required")
                );
            }

            if (!password.equals(confirm)) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/setup"),
                    Map.of("error", "Passwords do not match")
                );
            }

            if (password.length() < 8) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/setup"),
                    Map.of("error", "Password must be at least 8 characters")
                );
            }

            Row user = AuthHelper.createUser(email, name, password);
            int userId = user.get(UserModel.ID);
            String token = AuthHelper.createSession(userId);
            AuthHelper.setSessionCookie(conduit, token);

            return conduit.hardRedirect("/");
        });
    }

    // -----------------------------------------------------------------------
    // Dashboard
    // -----------------------------------------------------------------------

    private static void initDashboard(SiteModel siteModel, CertificateModel certModel, AuditLogModel auditModel) {
        HohenheimEndpoints.DASHBOARD.setHandler(conduit -> {
            int siteCount = (int) siteModel.find().where(SiteModel.DELETED_AT.isNull()).count();
            int certCount = (int) certModel.find().count();

            var proxy = ServerMain.getProxyServer();
            int routeCount = 0;
            String httpStatus = "Not initialized";
            String httpsStatus = "Not initialized";

            if (proxy != null) {
                routeCount = proxy.getDispatcher().getExactRouteCount()
                           + proxy.getDispatcher().getWildcardRouteCount()
                           + proxy.getDispatcher().getRegexRouteCount();

                httpStatus = switch (proxy.getHttpState()) {
                    case RUNNING -> "Running";
                    case FAILED -> "Failed: " + proxy.getHttpFailureReason();
                    case STOPPED -> "Stopped";
                };
                httpsStatus = switch (proxy.getHttpsState()) {
                    case RUNNING -> "Running (" + proxy.getCertificateStore().getCertificateCount() + " certs)";
                    case FAILED -> "Failed: " + proxy.getHttpsFailureReason();
                    case STOPPED -> proxy.getHttpsFailureReason() != null
                        ? proxy.getHttpsFailureReason() : "Stopped";
                };
            }

            List<Row> recentAudit = auditModel.findRecent(10);
            List<Map<String, Object>> activity = new ArrayList<>();
            for (Row row : recentAudit) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("action", row.get(AuditLogModel.ACTION));
                entry.put("resourceType", row.get(AuditLogModel.RESOURCE_TYPE));
                entry.put("resourceName", row.get(AuditLogModel.RESOURCE_NAME));
                entry.put("createdAt", row.get(AuditLogModel.CREATED_AT));
                activity.add(entry);
            }

            Map<String, Object> vars = new HashMap<>();
            vars.put("siteCount", siteCount);
            vars.put("certCount", certCount);
            vars.put("httpStatus", httpStatus);
            vars.put("httpsStatus", httpsStatus);
            vars.put("routeCount", routeCount);
            vars.put("activity", activity);

            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/dashboard"),
                vars
            );
        });
    }

    // -----------------------------------------------------------------------
    // Sites CRUD
    // -----------------------------------------------------------------------

    private static void initSites(SiteModel siteModel, SiteDomainModel domainModel, AuditLogModel auditModel) {

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
                Map.of("sites", sites, "siteCount", sites.size())
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
            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/sites/create"), vars);
        });

        // Site create (POST)
        HohenheimEndpoints.SITES_CREATE.setHandler(conduit -> {
            HttpConduit http = (HttpConduit) conduit;
            Map<String, String> form = http.getFormData().toStringMap();

            String name = form.getOrDefault("name", "").trim();
            String siteType = form.getOrDefault("site_type", "hohenheim:proxy");

            if (name.isEmpty()) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/sites/create"),
                    Map.of("error", "Name is required")
                );
            }

            String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            Map<String, Object> settings = extractTypeSettings(form, siteType);
            String hostname = form.getOrDefault("hostname", "").trim();
            String source = form.getOrDefault("source", "");

            Row row = siteModel.createEmptyRow();
            row.set(SiteModel.NAME, name);
            row.set(SiteModel.SLUG, slug);
            row.set(SiteModel.SITE_TYPE, siteType);
            row.set(SiteModel.SETTINGS, settings);
            row.set(SiteModel.STATUS, "active");

            // Git source provisioning
            if ("git".equals(source)) {
                row.set(SiteModel.SOURCE, "git");
                Map<String, Object> sourceSettings = extractSchemaSettings(form, GitSourceSchema.SCHEMA);
                // Auto-generate webhook secret on first save
                if (sourceSettings.get("webhook_secret") == null || ((String) sourceSettings.getOrDefault("webhook_secret", "")).isEmpty()) {
                    sourceSettings.put("webhook_secret", java.util.UUID.randomUUID().toString());
                }
                row.set(SiteModel.SOURCE_SETTINGS, sourceSettings);
            }

            siteModel.save(row);

            if (!hostname.isEmpty()) {
                int siteId = row.get(SiteModel.ID);
                Row domainRow = domainModel.createEmptyRow();
                domainRow.set(SiteDomainModel.SITE_ID, siteId);
                domainRow.set(SiteDomainModel.HOSTNAME, hostname);
                domainRow.set(SiteDomainModel.MATCH_TYPE, "exact");
                domainModel.save(domainRow);
            }

            audit(auditModel, conduit, "created", "site", row.get(SiteModel.ID), name);
            reloadProxy();
            return redirectUntyped("/sites");
        });

        // Site edit form (GET /sites/:id)
        HohenheimEndpoints.SITES_EDIT.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();

            if (site == null) {
                return new RenderTemplateResult(
                    Identifier.of("hohenheim", "hohenheim/sites/list"),
                    Map.of("sites", List.of(), "siteCount", 0)
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
            HttpConduit http = (HttpConduit) conduit;
            Map<String, String> form = http.getFormData().toStringMap();

            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();
            if (site == null) return redirectUntyped("/sites");

            String name = form.getOrDefault("name", "").trim();
            if (name.isEmpty()) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/sites/edit"),
                    buildEditVars(site, domainModel, "Name is required")
                );
            }

            String siteType = form.getOrDefault("site_type", site.get(SiteModel.SITE_TYPE));
            Map<String, Object> settings = extractTypeSettings(form, siteType);
            boolean enabled = "on".equals(form.get("enabled")) || "true".equals(form.get("enabled"));
            String source = form.getOrDefault("source", "");

            site.set(SiteModel.NAME, name);
            site.set(SiteModel.SITE_TYPE, siteType);
            site.set(SiteModel.SETTINGS, settings);
            site.set(SiteModel.ENABLED, enabled);

            // Git source provisioning
            if ("git".equals(source)) {
                site.set(SiteModel.SOURCE, "git");
                Map<String, Object> sourceSettings = extractSchemaSettings(form, GitSourceSchema.SCHEMA);
                // Preserve existing webhook secret if not in form
                if (sourceSettings.get("webhook_secret") == null || ((String) sourceSettings.getOrDefault("webhook_secret", "")).isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingSource = (Map<String, Object>) site.get(SiteModel.SOURCE_SETTINGS);
                    String existingSecret = existingSource != null ? (String) existingSource.get("webhook_secret") : null;
                    if (existingSecret != null && !existingSecret.isEmpty()) {
                        sourceSettings.put("webhook_secret", existingSecret);
                    } else {
                        sourceSettings.put("webhook_secret", java.util.UUID.randomUUID().toString());
                    }
                }
                site.set(SiteModel.SOURCE_SETTINGS, sourceSettings);
            } else {
                site.set(SiteModel.SOURCE, null);
                site.set(SiteModel.SOURCE_SETTINGS, null);
            }

            siteModel.save(site);

            audit(auditModel, conduit, "updated", "site", siteId, name);
            reloadProxy();
            return redirectUntyped("/sites/" + siteId);
        });

        // Site toggle enabled (POST /sites/:id/toggle)
        HohenheimEndpoints.SITES_TOGGLE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();

            if (site != null) {
                boolean current = Boolean.TRUE.equals(site.get(SiteModel.ENABLED));
                site.set(SiteModel.ENABLED, !current);
                siteModel.save(site);
                audit(auditModel, conduit, current ? "disabled" : "enabled", "site",
                      siteId, site.get(SiteModel.NAME));
                reloadProxy();
            }

            return redirectUntyped("/sites");
        });

        // Site clone (POST /sites/:id/clone)
        HohenheimEndpoints.SITES_CLONE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();
            if (site == null) return redirectUntyped("/sites");

            String name = site.get(SiteModel.NAME) + " (copy)";
            String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");

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
                clonedSourceSettings.put("webhook_secret", java.util.UUID.randomUUID().toString());
            }
            clone.set(SiteModel.SOURCE_SETTINGS, clonedSourceSettings);
            clone.set(SiteModel.STATUS, "active");
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

            audit(auditModel, conduit, "cloned", "site", newSiteId, name);
            return redirectUntyped("/sites/" + newSiteId);
        });

        // Site delete (POST /sites/:id/delete)
        HohenheimEndpoints.SITES_DELETE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();

            if (site != null) {
                site.set(SiteModel.DELETED_AT, Instant.now());
                siteModel.save(site);
                audit(auditModel, conduit, "deleted", "site", siteId, site.get(SiteModel.NAME));
                reloadProxy();

                // Clean up git repo directory if git-provisioned
                String source = site.get(SiteModel.SOURCE);
                if ("git".equals(source)) {
                    GitProvisioner.deleteSiteDirectory(siteId);
                }
            }

            return redirectUntyped("/sites");
        });
    }

    // -----------------------------------------------------------------------
    // Domain CRUD (nested under sites)
    // -----------------------------------------------------------------------

    private static void initDomains(SiteModel siteModel, SiteDomainModel domainModel, AuditLogModel auditModel) {

        // Add domain (POST /sites/:id/domains)
        HohenheimEndpoints.SITES_ADD_DOMAIN.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            HttpConduit http = (HttpConduit) conduit;
            Map<String, String> form = http.getFormData().toStringMap();

            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();
            if (site == null) return redirectUntyped("/sites");

            String hostname = form.getOrDefault("hostname", "").trim();
            String matchType = form.getOrDefault("match_type", "exact");

            if (!hostname.isEmpty()) {
                // Check for duplicate hostname on this site
                Row existing = domainModel.find()
                    .where(SiteDomainModel.SITE_ID.eq(siteId))
                    .where(SiteDomainModel.HOSTNAME.eq(hostname))
                    .first();

                if (existing == null) {
                    Row domainRow = domainModel.createEmptyRow();
                    domainRow.set(SiteDomainModel.SITE_ID, siteId);
                    domainRow.set(SiteDomainModel.HOSTNAME, hostname);
                    domainRow.set(SiteDomainModel.MATCH_TYPE, matchType);
                    domainModel.save(domainRow);

                    audit(auditModel, conduit, "created", "domain", domainRow.get(SiteDomainModel.ID), hostname);
                    reloadProxy();
                }
            }

            return redirectUntyped("/sites/" + siteId);
        });

        // Edit domain form (GET /sites/:id/domains/:domainId)
        HohenheimEndpoints.SITES_EDIT_DOMAIN.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Integer domainId = conduit.getParameter(HohenheimEndpoints.DOMAIN_ID);

            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();
            Row domain = domainModel.find()
                .where(SiteDomainModel.ID.eq(domainId))
                .where(SiteDomainModel.SITE_ID.eq(siteId))
                .first();
            if (site == null || domain == null) return redirectUntyped("/sites");

            return renderUntyped(
                Identifier.of("hohenheim", "hohenheim/sites/domain-edit"),
                buildDomainEditVars(site, domain, "")
            );
        });

        // Update domain (POST /sites/:id/domains/:domainId)
        HohenheimEndpoints.SITES_UPDATE_DOMAIN.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Integer domainId = conduit.getParameter(HohenheimEndpoints.DOMAIN_ID);
            HttpConduit http = (HttpConduit) conduit;
            Map<String, String> form = http.getFormData().toStringMap();

            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();
            Row domain = domainModel.find()
                .where(SiteDomainModel.ID.eq(domainId))
                .where(SiteDomainModel.SITE_ID.eq(siteId))
                .first();
            if (site == null || domain == null) return redirectUntyped("/sites");

            String hostname = form.getOrDefault("hostname", "").trim();
            if (hostname.isEmpty()) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/sites/domain-edit"),
                    buildDomainEditVars(site, domain, "Hostname is required")
                );
            }

            domain.set(SiteDomainModel.HOSTNAME, hostname);
            domain.set(SiteDomainModel.MATCH_TYPE, form.getOrDefault("match_type", "exact"));
            domain.set(SiteDomainModel.FORCE_SSL, form.containsKey("force_ssl"));
            domain.set(SiteDomainModel.HSTS_ENABLED, form.containsKey("hsts_enabled"));
            domain.set(SiteDomainModel.HSTS_SUBDOMAINS, form.containsKey("hsts_subdomains"));
            domain.set(SiteDomainModel.HTTP2_SUPPORT, form.containsKey("http2_support"));
            domain.set(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT, form.containsKey("exclude_from_letsencrypt"));

            String certIdStr = form.getOrDefault("certificate_id", "").trim();
            if (certIdStr.isEmpty()) {
                domain.set(SiteDomainModel.CERTIFICATE_ID, null);
            } else {
                try {
                    domain.set(SiteDomainModel.CERTIFICATE_ID, Integer.parseInt(certIdStr));
                } catch (NumberFormatException ignored) {
                    domain.set(SiteDomainModel.CERTIFICATE_ID, null);
                }
            }

            String path = form.getOrDefault("path", "").trim();
            domain.set(SiteDomainModel.PATH, path.isEmpty() ? null : path);
            domain.set(SiteDomainModel.STRIP_PATH, form.containsKey("strip_path"));

            String listenOn = form.getOrDefault("listen_on", "").trim();
            domain.set(SiteDomainModel.LISTEN_ON, listenOn.isEmpty() ? null : listenOn);

            List<Map<String, String>> customHeaders = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String headerName = form.get("header_name_" + i);
                if (headerName == null) break;
                String headerValue = form.getOrDefault("header_value_" + i, "");
                if (!headerName.isBlank()) {
                    customHeaders.add(Map.of("name", headerName.trim(), "value", headerValue));
                }
            }
            domain.set(SiteDomainModel.CUSTOM_HEADERS, customHeaders.isEmpty() ? null : customHeaders);

            String portStr = form.getOrDefault("port", "").trim();
            if (!portStr.isEmpty()) {
                try {
                    int port = Integer.parseInt(portStr);
                    domain.set(SiteDomainModel.PORT, (port >= 1 && port <= 65535) ? port : null);
                } catch (NumberFormatException e) {
                    domain.set(SiteDomainModel.PORT, null);
                }
            } else {
                domain.set(SiteDomainModel.PORT, null);
            }

            domainModel.save(domain);
            audit(auditModel, conduit, "updated", "domain", domainId, hostname);
            reloadProxy();
            return redirectUntyped("/sites/" + siteId);
        });

        // Delete domain (POST /sites/:id/domains/:domainId/delete)
        HohenheimEndpoints.SITES_DELETE_DOMAIN.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Integer domainId = conduit.getParameter(HohenheimEndpoints.DOMAIN_ID);

            Row domain = domainModel.find()
                .where(SiteDomainModel.ID.eq(domainId))
                .where(SiteDomainModel.SITE_ID.eq(siteId))
                .first();
            if (domain != null) {
                String hostname = domain.get(SiteDomainModel.HOSTNAME);
                domainModel.find().where(SiteDomainModel.ID.eq(domainId)).delete();
                audit(auditModel, conduit, "deleted", "domain", domainId, hostname);
                reloadProxy();
            }

            return redirectUntyped("/sites/" + siteId);
        });
    }

    // -----------------------------------------------------------------------
    // Certificates
    // -----------------------------------------------------------------------

    private static void initCertificates(CertificateModel certModel, AuditLogModel auditModel) {
        HohenheimEndpoints.CERTIFICATES_LIST.setHandler(conduit -> {
            List<Row> rows = certModel.find()
                .orderBy(CertificateModel.CREATED_AT, SortOrder.DESC)
                .all();

            // Filter out the internal ACME account key row
            rows.removeIf(r -> "acme_account".equals(r.get(CertificateModel.PROVIDER)));

            Instant now = Instant.now();
            List<Map<String, Object>> certs = new ArrayList<>();
            for (Row row : rows) {
                Map<String, Object> cert = new HashMap<>();
                cert.put("id", row.get(CertificateModel.ID));
                cert.put("niceName", row.get(CertificateModel.NICE_NAME));
                cert.put("provider", row.get(CertificateModel.PROVIDER));

                Object expiresOnObj = row.get(CertificateModel.EXPIRES_ON);
                cert.put("expiresOn", expiresOnObj);
                cert.put("autoRenew", row.get(CertificateModel.AUTO_RENEW));

                String status = row.get(CertificateModel.STATUS);
                cert.put("status", status != null ? status : "active");
                cert.put("renewalError", row.get(CertificateModel.RENEWAL_ERROR));
                cert.put("domains", row.get(CertificateModel.DOMAIN_NAMES_TEXT));

                // Compute expiry warning level
                if (expiresOnObj instanceof Instant expiresAt && "active".equals(status)) {
                    long daysLeft = Duration.between(now, expiresAt).toDays();
                    if (daysLeft < 0) {
                        cert.put("expiryStatus", "expired");
                    } else if (daysLeft <= 7) {
                        cert.put("expiryStatus", "critical");
                    } else if (daysLeft <= 30) {
                        cert.put("expiryStatus", "warning");
                    } else {
                        cert.put("expiryStatus", "ok");
                    }
                    cert.put("daysLeft", daysLeft);
                }

                certs.add(cert);
            }

            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/certificates/list"),
                Map.of("certificates", certs, "certCount", certs.size())
            );
        });

        // Upload form (GET)
        HohenheimEndpoints.CERTIFICATES_UPLOAD_FORM.setHandler(conduit ->
            new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/certificates/upload"),
                Map.of("error", "")
            )
        );

        // Upload (POST)
        HohenheimEndpoints.CERTIFICATES_UPLOAD.setHandler(conduit -> {
            HttpConduit http = (HttpConduit) conduit;
            Map<String, String> form = http.getFormData().toStringMap();

            String niceName = form.getOrDefault("nice_name", "").trim();
            String certPem = form.getOrDefault("certificate_pem", "").trim();
            String keyPem = form.getOrDefault("private_key_pem", "").trim();

            if (niceName.isEmpty() || certPem.isEmpty() || keyPem.isEmpty()) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/upload"),
                    Map.of("error", "Name, certificate, and private key are required")
                );
            }

            // Validate PEM format before saving
            try {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                cf.generateCertificates(new ByteArrayInputStream(certPem.getBytes()));
            } catch (Exception e) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/upload"),
                    Map.of("error", "Invalid certificate PEM: " + e.getMessage())
                );
            }

            try {
                new PEMParser(new StringReader(keyPem)).readObject();
            } catch (Exception e) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/upload"),
                    Map.of("error", "Invalid private key PEM: " + e.getMessage())
                );
            }

            Row cert = certModel.createEmptyRow();
            cert.set(CertificateModel.NICE_NAME, niceName);
            cert.set(CertificateModel.CERTIFICATE_PEM, certPem);
            cert.set(CertificateModel.PRIVATE_KEY_PEM, keyPem);
            cert.set(CertificateModel.PROVIDER, "custom");
            cert.set(CertificateModel.STATUS, "active");
            certModel.save(cert);

            audit(auditModel, conduit, "uploaded", "certificate", cert.get(CertificateModel.ID), niceName);
            reloadProxy();
            return redirectUntyped("/certificates");
        });

        // Let's Encrypt request form (GET)
        HohenheimEndpoints.CERTIFICATES_REQUEST_FORM.setHandler(conduit ->
            new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/certificates/request"),
                Map.of("error", "")
            )
        );

        // Let's Encrypt request (POST)
        HohenheimEndpoints.CERTIFICATES_REQUEST.setHandler(conduit -> {
            HttpConduit http = (HttpConduit) conduit;
            Map<String, String> form = http.getFormData().toStringMap();

            String domains = form.getOrDefault("domains", "").trim();
            String niceName = form.getOrDefault("nice_name", "").trim();

            if (domains.isEmpty()) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/request"),
                    Map.of("error", "At least one domain is required")
                );
            }

            if (niceName.isEmpty()) {
                niceName = domains.split("[,\\s]+")[0];
            }

            var proxy = ServerMain.getProxyServer();
            if (proxy == null) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/request"),
                    Map.of("error", "Proxy server not initialized")
                );
            }

            List<String> hostnames = new ArrayList<>();
            for (String d : domains.split("[,\\s]+")) {
                d = d.trim();
                if (!d.isEmpty()) hostnames.add(d);
            }

            int certId = proxy.getAcmeService().requestCertificate(hostnames, niceName);

            if (certId < 0) {
                // Lookup the specific cert row to get the error message
                var ds2 = HohenheimDatabase.datasource();
                var certModel2 = new CertificateModel(ds2);
                // requestCertificate creates a row even on failure -- find it by provider+status
                Row failed = certModel2.find()
                    .where(CertificateModel.STATUS.eq("error"))
                    .orderBy(CertificateModel.CREATED_AT, SortOrder.DESC)
                    .first();
                String error = failed != null ? failed.get(CertificateModel.RENEWAL_ERROR) : "Unknown error";
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/request"),
                    Map.of("error", "Certificate request failed: " + (error != null ? error : "Unknown"))
                );
            }

            audit(auditModel, conduit, "requested", "certificate", certId, niceName);
            reloadProxy();
            return redirectUntyped("/certificates");
        });

        // Download (GET /certificates/:id/download)
        HohenheimEndpoints.CERTIFICATES_DOWNLOAD.setHandler(conduit -> {
            Integer certId = conduit.getParameter(HohenheimEndpoints.CERT_ID);
            Row cert = certModel.findById(certId);
            if (cert == null) return redirectUntyped("/certificates");

            String certPem = cert.get(CertificateModel.CERTIFICATE_PEM);
            String keyPem = cert.get(CertificateModel.PRIVATE_KEY_PEM);
            String niceName = cert.get(CertificateModel.NICE_NAME);
            if (certPem == null) certPem = "";
            if (keyPem == null) keyPem = "";

            // Build a simple concatenated PEM bundle
            String bundle = "# Certificate: " + niceName + "\n\n" + certPem + "\n" + keyPem;

            if (conduit instanceof HttpConduit http) {
                String safeName = (niceName != null ? niceName : "certificate")
                    .replaceAll("[^a-zA-Z0-9._-]", "_");
                http.setResponseHeader("Content-Type", "application/x-pem-file");
                http.setResponseHeader("Content-Disposition",
                    "attachment; filename=\"" + safeName + ".pem\"");
            }
            conduit.endWithContentType("application/x-pem-file", bundle);
            return null;
        });

        // Delete (POST)
        HohenheimEndpoints.CERTIFICATES_DELETE.setHandler(conduit -> {
            Integer certId = conduit.getParameter(HohenheimEndpoints.CERT_ID);
            Row cert = certModel.findById(certId);
            String niceName = cert != null ? cert.get(CertificateModel.NICE_NAME) : null;
            certModel.find().where(CertificateModel.ID.eq(certId)).delete();
            audit(auditModel, conduit, "deleted", "certificate", certId, niceName);
            reloadProxy();
            return redirectUntyped("/certificates");
        });
    }

    // -----------------------------------------------------------------------
    // Settings
    // -----------------------------------------------------------------------

    private static void initSettings() {

        HohenheimEndpoints.SETTINGS.setHandler(conduit -> {
            Map<String, Object> vars = new HashMap<>();
            String saved = conduit.getQueryParam("saved");
            vars.put("saved", saved != null ? saved : "");
            vars.put("proxyHttpPort", HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT));
            vars.put("proxyHttpsPort", HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTPS_PORT));
            vars.put("proxyFallback", valueOrEmpty(HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FALLBACK_ADDRESS)));
            vars.put("proxyForceHttps", HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FORCE_HTTPS));
            vars.put("proxyIpv6Address", valueOrEmpty(HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.IPV6_ADDRESS)));
            // Read-only: the admin listener is Zenit's HTTP server, whose port
            // is ServerSettings.Network.PORT. Surfaced here so operators can
            // see where the admin UI is reachable.
            vars.put("adminPort", ServerSettings.VALUES.getValue(ServerSettings.Network.PORT));
            vars.put("dbPath", HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PATH));
            vars.put("logAccessToDb", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_DATABASE));
            vars.put("logAccessToFile", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_FILE));
            vars.put("logAccessPath", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_PATH));
            vars.put("logCollectStats", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.COLLECT_STATS));
            vars.put("secLogDomainMisses", HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.LOG_DOMAIN_MISSES));
            vars.put("secDomainMissThreshold", HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD));
            vars.put("sslLeEnabled", HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED));
            vars.put("sslLeEmail", valueOrEmpty(HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL)));
            vars.put("sslLeStaging", HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_STAGING));

            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/settings"),
                vars
            );
        });

        HohenheimEndpoints.SETTINGS_UPDATE.setHandler(conduit -> {
            HttpConduit http = (HttpConduit) conduit;
            Map<String, String> form = http.getFormData().toStringMap();

            // Proxy settings
            String httpPort = form.get("proxy_http_port");
            if (httpPort != null) {
                try { HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, Integer.parseInt(httpPort)); }
                catch (NumberFormatException ignored) {}
            }
            String httpsPort = form.get("proxy_https_port");
            if (httpsPort != null) {
                try { HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, Integer.parseInt(httpsPort)); }
                catch (NumberFormatException ignored) {}
            }
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FALLBACK_ADDRESS,
                form.getOrDefault("proxy_fallback", ""));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FORCE_HTTPS,
                form.containsKey("proxy_force_https"));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.IPV6_ADDRESS,
                form.getOrDefault("proxy_ipv6_address", "").trim());

            // Logging settings
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Logging.ACCESS_TO_DATABASE,
                form.containsKey("log_access_to_db"));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Logging.ACCESS_TO_FILE,
                form.containsKey("log_access_to_file"));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Logging.ACCESS_PATH,
                form.getOrDefault("log_access_path", "/var/log/hohenheim/access.log"));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Logging.COLLECT_STATS,
                form.containsKey("log_collect_stats"));

            // Security settings
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.LOG_DOMAIN_MISSES,
                form.containsKey("sec_log_domain_misses"));
            String threshold = form.get("sec_domain_miss_threshold");
            if (threshold != null) {
                try { HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD, Integer.parseInt(threshold)); }
                catch (NumberFormatException ignored) {}
            }

            // SSL settings
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED,
                form.containsKey("ssl_le_enabled"));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL,
                form.getOrDefault("ssl_le_email", ""));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_STAGING,
                form.containsKey("ssl_le_staging"));

            return redirectUntyped("/settings?saved=true");
        });
    }

    // -----------------------------------------------------------------------
    // Audit Log
    // -----------------------------------------------------------------------

    private static void initAuditLog(AuditLogModel auditModel) {
        HohenheimEndpoints.AUDIT_LOG.setHandler(conduit -> {
            List<Row> rows = auditModel.findRecent(100);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Row row : rows) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("action", row.get(AuditLogModel.ACTION));
                entry.put("resourceType", row.get(AuditLogModel.RESOURCE_TYPE));
                entry.put("resourceId", row.get(AuditLogModel.RESOURCE_ID));
                entry.put("resourceName", row.get(AuditLogModel.RESOURCE_NAME));
                entry.put("createdAt", row.get(AuditLogModel.CREATED_AT));
                entries.add(entry);
            }

            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/audit"),
                Map.of("entries", entries)
            );
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Walk the type-specific schema and extract each field from the submitted form
     * generically. SchemaField-with-subSchema and ListField are handled by scanning
     * indexed form keys (fieldName[N].childName and fieldName[N]) — no per-field hacks.
     */
    private static Map<String, Object> extractTypeSettings(Map<String, String> form, String siteType) {
        Map<String, Object> settings = new HashMap<>();

        Schema typeSchema = SiteModel.SITE_TYPE.getSchemaForValue(siteType);
        if (typeSchema == null) return settings;

        for (var entry : typeSchema.getFields().entrySet()) {
            String fieldName = entry.getKey();
            Field<?, ?> field = entry.getValue();

            if (field instanceof SchemaField schemaField && schemaField.getSubSchema() != null) {
                settings.put(fieldName, extractNestedSchemaList(form, fieldName, schemaField.getSubSchema()));
            } else if (field instanceof ListField<?> listField) {
                settings.put(fieldName, extractScalarList(form, fieldName, listField.getItemField()));
            } else if (field instanceof BooleanField) {
                // Unchecked checkboxes don't submit a value at all
                String v = form.get(fieldName);
                settings.put(fieldName, "on".equals(v) || "true".equals(v));
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
     * Extract settings from a form based on a fixed schema (not type-polymorphic).
     * Used for git source settings.
     */
    private static Map<String, Object> extractSchemaSettings(Map<String, String> form, Schema schema) {
        Map<String, Object> settings = new HashMap<>();

        for (var entry : schema.getFields().entrySet()) {
            String fieldName = entry.getKey();
            Field<?, ?> field = entry.getValue();

            if (field instanceof SchemaField schemaField && schemaField.getSubSchema() != null) {
                settings.put(fieldName, extractNestedSchemaList(form, fieldName, schemaField.getSubSchema()));
            } else if (field instanceof ListField<?> listField) {
                settings.put(fieldName, extractScalarList(form, fieldName, listField.getItemField()));
            } else if (field instanceof BooleanField) {
                String v = form.get(fieldName);
                settings.put(fieldName, "on".equals(v) || "true".equals(v));
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

    private static String joinNamedValueLines(Object rawValue, String separator) {
        if (!(rawValue instanceof List<?> list) || list.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }

            Object name = map.get("name");
            if (name == null || String.valueOf(name).isBlank()) {
                continue;
            }

            if (!builder.isEmpty()) {
                builder.append('\n');
            }

            Object value = map.get("value");
            builder.append(name).append(separator).append(value != null ? value : "");
        }

        return builder.toString();
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
        vars.put("environmentVariables", extractEnvVarsList(settings));
        vars.put("apiKeys", extractApiKeysList(settings));
        vars.put("error", error);

        // Process data for managed site types
        var proxy = ServerMain.getProxyServer();
        if (proxy != null) {
            var handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
            if (handler instanceof ManagedProcessSiteHandler managed) {
                vars.put("isManaged", true);
                List<Map<String, Object>> processes = new ArrayList<>();
                for (var proc : managed.getProcesses()) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("pid", proc.pid());
                    info.put("port", proc.port());
                    info.put("cpu", proc.cpuPercent());
                    info.put("memoryMb", proc.memoryKb() / 1024);
                    info.put("isolated", proc.isIsolated());
                    info.put("ready", proc.isReady());
                    info.put("fingerprints", proc.activeFingerprintCount());
                    processes.add(info);
                }
                vars.put("processes", processes);
            }
        }

        return vars;
    }

    private static Map<String, Object> buildDomainEditVars(Row site, Row domain, String error) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("siteId", site.get(SiteModel.ID));
        vars.put("siteName", site.get(SiteModel.NAME));
        vars.put("domainId", domain.get(SiteDomainModel.ID));
        vars.put("hostname", domain.get(SiteDomainModel.HOSTNAME));
        vars.put("matchType", domain.get(SiteDomainModel.MATCH_TYPE));
        vars.put("forceSsl", domain.get(SiteDomainModel.FORCE_SSL));
        vars.put("hstsEnabled", domain.get(SiteDomainModel.HSTS_ENABLED));
        vars.put("hstsSubdomains", domain.get(SiteDomainModel.HSTS_SUBDOMAINS));
        vars.put("http2Support", domain.get(SiteDomainModel.HTTP2_SUPPORT));
        vars.put("excludeFromLetsencrypt", domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT));

        String path = domain.get(SiteDomainModel.PATH);
        vars.put("path", path != null ? path : "");
        vars.put("stripPath", domain.get(SiteDomainModel.STRIP_PATH));

        String listenOn = domain.get(SiteDomainModel.LISTEN_ON);
        vars.put("listenOn", listenOn != null ? listenOn : "");

        Integer port = domain.get(SiteDomainModel.PORT);
        vars.put("port", port != null ? String.valueOf(port) : "");

        Integer certId = domain.get(SiteDomainModel.CERTIFICATE_ID);
        vars.put("certificateId", certId != null ? String.valueOf(certId) : "");
        vars.put("customHeadersText", joinNamedValueLines(domain.get(SiteDomainModel.CUSTOM_HEADERS), ": "));
        vars.put("customHeaders", extractCustomHeadersList(domain));

        // Build certificate list for dropdown
        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);
        List<Row> certRows = certModel.find().all();
        certRows.removeIf(r -> "acme_account".equals(r.get(CertificateModel.PROVIDER)));
        List<Map<String, Object>> certs = new ArrayList<>();
        for (Row cr : certRows) {
            Map<String, Object> c = new HashMap<>();
            c.put("id", String.valueOf(cr.get(CertificateModel.ID)));
            c.put("name", cr.get(CertificateModel.NICE_NAME));
            certs.add(c);
        }
        vars.put("certificates", certs);

        vars.put("error", error);
        return vars;
    }

    // -----------------------------------------------------------------------
    // Access Lists
    // -----------------------------------------------------------------------

    private static void initAccessLists(AccessListModel accessListModel, AuditLogModel auditModel) {

        // List
        HohenheimEndpoints.ACCESS_LISTS.setHandler(conduit -> {
            List<Row> rows = accessListModel.find().all();
            List<Map<String, Object>> lists = new ArrayList<>();
            for (Row row : rows) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", row.get(AccessListModel.ID));
                item.put("name", row.get(AccessListModel.NAME));
                item.put("satisfy", row.get(AccessListModel.SATISFY));
                item.put("hasAuth", row.get(AccessListModel.BASIC_AUTH_USER) != null);
                item.put("hasIpRules",
                    row.get(AccessListModel.ALLOWED_IPS) != null
                    || row.get(AccessListModel.DENIED_IPS) != null);
                lists.add(item);
            }
            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/access-lists/list"),
                Map.of("accessLists", lists, "listCount", lists.size())
            );
        });

        // Create form
        HohenheimEndpoints.ACCESS_LISTS_CREATE_FORM.setHandler(conduit ->
            new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/access-lists/create"),
                Map.of("error", "")
            )
        );

        // Create (POST)
        HohenheimEndpoints.ACCESS_LISTS_CREATE.setHandler(conduit -> {
            HttpConduit http = (HttpConduit) conduit;
            Map<String, String> form = http.getFormData().toStringMap();

            String name = form.getOrDefault("name", "").trim();
            if (name.isEmpty()) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/access-lists/create"),
                    Map.of("error", "Name is required")
                );
            }

            Row row = accessListModel.createEmptyRow();
            row.set(AccessListModel.NAME, name);
            row.set(AccessListModel.SATISFY, form.getOrDefault("satisfy", "any"));
            setAccessListFields(row, form);
            accessListModel.save(row);

            audit(auditModel, conduit, "created", "access_list", row.get(AccessListModel.ID), name);
            return redirectUntyped("/access-lists");
        });

        // Edit form
        HohenheimEndpoints.ACCESS_LISTS_EDIT.setHandler(conduit -> {
            Integer id = conduit.getParameter(HohenheimEndpoints.ACCESS_LIST_ID);
            Row row = accessListModel.findById(id);
            if (row == null) return redirectUntyped("/access-lists");

            return renderUntyped(
                Identifier.of("hohenheim", "hohenheim/access-lists/edit"),
                buildAccessListEditVars(row, "")
            );
        });

        // Update (POST)
        HohenheimEndpoints.ACCESS_LISTS_UPDATE.setHandler(conduit -> {
            Integer id = conduit.getParameter(HohenheimEndpoints.ACCESS_LIST_ID);
            HttpConduit http = (HttpConduit) conduit;
            Map<String, String> form = http.getFormData().toStringMap();

            Row row = accessListModel.findById(id);
            if (row == null) return redirectUntyped("/access-lists");

            String name = form.getOrDefault("name", "").trim();
            if (name.isEmpty()) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/access-lists/edit"),
                    buildAccessListEditVars(row, "Name is required")
                );
            }

            row.set(AccessListModel.NAME, name);
            row.set(AccessListModel.SATISFY, form.getOrDefault("satisfy", "any"));
            setAccessListFields(row, form);
            accessListModel.save(row);

            audit(auditModel, conduit, "updated", "access_list", id, name);
            reloadProxy();
            return redirectUntyped("/access-lists");
        });

        // Delete (POST)
        HohenheimEndpoints.ACCESS_LISTS_DELETE.setHandler(conduit -> {
            Integer id = conduit.getParameter(HohenheimEndpoints.ACCESS_LIST_ID);
            Row row = accessListModel.findById(id);
            if (row != null) {
                String name = row.get(AccessListModel.NAME);
                accessListModel.find().where(AccessListModel.ID.eq(id)).delete();
                audit(auditModel, conduit, "deleted", "access_list", id, name);
                reloadProxy();
            }
            return redirectUntyped("/access-lists");
        });
    }

    private static void setAccessListFields(Row row, Map<String, String> form) {
        String user = form.getOrDefault("basic_auth_user", "").trim();
        String pass = form.getOrDefault("basic_auth_pass", "").trim();
        row.set(AccessListModel.BASIC_AUTH_USER, user.isEmpty() ? null : user);
        row.set(AccessListModel.BASIC_AUTH_PASS, pass.isEmpty() ? null : pass);

        String allowed = form.getOrDefault("allowed_ips", "").trim();
        String denied = form.getOrDefault("denied_ips", "").trim();
        row.set(AccessListModel.ALLOWED_IPS, allowed.isEmpty() ? null : allowed);
        row.set(AccessListModel.DENIED_IPS, denied.isEmpty() ? null : denied);
    }

    private static Map<String, Object> buildAccessListEditVars(Row row, String error) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("id", row.get(AccessListModel.ID));
        vars.put("name", row.get(AccessListModel.NAME));
        vars.put("satisfy", row.get(AccessListModel.SATISFY));
        vars.put("basicAuthUser", valueOrEmpty(row.get(AccessListModel.BASIC_AUTH_USER)));
        vars.put("basicAuthPass", valueOrEmpty(row.get(AccessListModel.BASIC_AUTH_PASS)));
        vars.put("allowedIps", valueOrEmpty(row.get(AccessListModel.ALLOWED_IPS)));
        vars.put("deniedIps", valueOrEmpty(row.get(AccessListModel.DENIED_IPS)));
        vars.put("error", error);
        return vars;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void audit(AuditLogModel model, be.elevenways.zenit.common.conduit.Conduit conduit,
                              String action, String resourceType, Object resourceId, String resourceName) {
        Row user = AuthHelper.getCurrentUser(conduit);
        Row row = model.createEmptyRow();
        if (user != null) {
            row.set(AuditLogModel.USER_ID, String.valueOf(user.get(UserModel.ID)));
            row.set(AuditLogModel.USER_EMAIL, user.get(UserModel.EMAIL));
        }
        row.set(AuditLogModel.ACTION, action);
        row.set(AuditLogModel.RESOURCE_TYPE, resourceType);
        row.set(AuditLogModel.RESOURCE_ID, resourceId != null ? String.valueOf(resourceId) : null);
        row.set(AuditLogModel.RESOURCE_NAME, resourceName);
        model.save(row);
    }

    private static void reloadProxy() {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null) proxy.reload();
    }

    private static String valueOrEmpty(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static String siteTypeDisplayName(String typeId) {
        if (typeId == null) return "Unknown";
        // Use the framework's enum field to resolve the display name
        var enumValue = SiteModel.SITE_TYPE.getValues().get(typeId);
        return enumValue != null ? enumValue.getDisplayName() : typeId;
    }

    // -----------------------------------------------------------------------
    // Process control
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static ActionResult<Object> jsonUntyped(Map<String, Object> data) {
        return (ActionResult<Object>) (ActionResult<?>) new JsonResult(data);
    }

    private static void initProcessControl(AuditLogModel auditModel) {

        // GET /sites/:id/processes - JSON process list
        HohenheimEndpoints.SITES_PROCESSES.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            var proxy = ServerMain.getProxyServer();
            Map<String, Object> result = new HashMap<>();

            if (proxy != null) {
                var handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
                if (handler instanceof ManagedProcessSiteHandler managed) {
                    List<Map<String, Object>> processes = new ArrayList<>();

                    for (var proc : managed.getProcesses()) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("pid", proc.pid());
                        info.put("port", proc.port());
                        info.put("cpu", proc.cpuPercent());
                        info.put("memory", proc.memoryKb());
                        info.put("isolated", proc.isIsolated());
                        info.put("ready", proc.isReady());
                        info.put("startTime", proc.startTime().toString());
                        info.put("fingerprints", proc.activeFingerprintCount());
                        processes.add(info);
                    }

                    result.put("running", !processes.isEmpty());
                    result.put("processes", processes);
                } else {
                    result.put("running", false);
                    result.put("processes", List.of());
                    result.put("type", "not_managed");
                }
            }

            return jsonUntyped(result);
        });

        // POST /sites/:id/processes/start - Start a new process
        HohenheimEndpoints.SITES_PROCESS_START.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            var proxy = ServerMain.getProxyServer();

            if (proxy != null) {
                var handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
                if (handler instanceof ManagedProcessSiteHandler managed) {
                    managed.startProcess();
                    audit(auditModel, conduit, "started_process", "site", siteId, null);
                }
            }

            return redirectUntyped("/sites/" + siteId);
        });

        // POST /sites/:id/processes/:pid/kill - Kill a process
        HohenheimEndpoints.SITES_PROCESS_KILL.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = conduit.getParameter(HohenheimEndpoints.PID);
            var proxy = ServerMain.getProxyServer();

            if (proxy != null) {
                var handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
                if (handler instanceof ManagedProcessSiteHandler managed) {
                    var proc = managed.getProcess(pid);
                    if (proc != null) {
                        proc.kill();
                        audit(auditModel, conduit, "killed_process", "site", siteId, "PID " + pid);
                    }
                }
            }

            return redirectUntyped("/sites/" + siteId);
        });

        // POST /sites/:id/processes/:pid/isolate - Toggle isolation
        HohenheimEndpoints.SITES_PROCESS_ISOLATE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = conduit.getParameter(HohenheimEndpoints.PID);
            var proxy = ServerMain.getProxyServer();

            if (proxy != null) {
                var handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
                if (handler instanceof ManagedProcessSiteHandler managed) {
                    var proc = managed.getProcess(pid);
                    if (proc != null) {
                        proc.setIsolated(!proc.isIsolated());
                        audit(auditModel, conduit, "isolated_process", "site", siteId, "PID " + pid);
                    }
                }
            }

            return redirectUntyped("/sites/" + siteId);
        });
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
        var model = new SystemUserModel(HohenheimDatabase.datasource());
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
        var model = new NodeVersionModel(HohenheimDatabase.datasource());
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> extractEnvVarsList(Map<String, Object> settings) {
        List<Map<String, String>> result = new ArrayList<>();
        if (settings != null && settings.get("environment_variables") instanceof List<?> rawList) {
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> map) {
                    String name = map.get("name") != null ? map.get("name").toString() : "";
                    String value = map.get("value") != null ? map.get("value").toString() : "";
                    result.add(Map.of("name", name, "value", value));
                }
            }
        }
        return result;
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> extractCustomHeadersList(Row domain) {
        List<Map<String, String>> result = new ArrayList<>();
        Object raw = domain.get(SiteDomainModel.CUSTOM_HEADERS);
        if (raw instanceof List<?> rawList) {
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> map) {
                    String name = map.get("name") != null ? map.get("name").toString() : "";
                    String value = map.get("value") != null ? map.get("value").toString() : "";
                    result.add(Map.of("name", name, "value", value));
                }
            }
        }
        return result;
    }

}
