package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.model.UserModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.server.http.HttpConduit;
import be.elevenways.zenit.server.http.Middleware;
import be.elevenways.zenit.server.http.RedirectResult;

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

        initAuth();
        initTestEndpoints();
        initDashboard(siteModel, certModel, auditModel);
        initSites(siteModel, domainModel, auditModel);
        initDomains(siteModel, domainModel, auditModel);
        initCertificates(certModel);
        initSettings();
        initAuditLog(auditModel);
    }

    // -----------------------------------------------------------------------
    // Test endpoints (for verifying error handling behavior)
    // -----------------------------------------------------------------------

    private static void initTestEndpoints() {
        HohenheimEndpoints.TEST_ERROR.setHandler(conduit -> {
            throw new RuntimeException("Deliberate test error for error handling verification");
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
                    || path.endsWith(".js") || path.endsWith(".css")
                    || path.endsWith(".ico")) {
                    return null;
                }

                // If no users exist, redirect to setup
                if (!AuthHelper.hasAnyUsers()) {
                    conduit.redirect("/setup");
                    return new RedirectResult("/setup");
                }

                // Check session
                Row user = AuthHelper.getCurrentUser(conduit);
                if (user == null) {
                    conduit.redirect("/login");
                    return new RedirectResult("/login");
                }

                return null; // Authenticated, proceed to endpoint
            }
        );
        authMiddleware.setWeight(50);

        // Login page (GET)
        HohenheimEndpoints.LOGIN.setHandler(conduit -> {
            Row user = AuthHelper.getCurrentUser(conduit);
            if (user != null) {
                return redirectUntyped("/");
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

            if (user == null || !AuthHelper.verifyPassword(password, (String) user.get(UserModel.PASSWORD_HASH))) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/login"),
                    Map.of("error", "Invalid email or password")
                );
            }

            Object isDisabledRaw = user.get(UserModel.IS_DISABLED);
            boolean isDisabled = Boolean.TRUE.equals(isDisabledRaw)
                || (isDisabledRaw instanceof Number && ((Number) isDisabledRaw).intValue() == 1);
            if (isDisabled) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/login"),
                    Map.of("error", "Account is disabled")
                );
            }

            int userId = ((Number) user.get(UserModel.ID)).intValue();
            String token = AuthHelper.createSession(userId);
            AuthHelper.setSessionCookie(conduit, token);

            return redirectUntyped("/");
        });

        // Logout (POST)
        HohenheimEndpoints.LOGOUT.setHandler(conduit -> {
            String token = AuthHelper.getCookieValue(conduit, "hh_session");
            AuthHelper.deleteSession(token);
            AuthHelper.clearSessionCookie(conduit);
            return redirectUntyped("/login");
        });

        // Setup page (GET) - first user creation
        HohenheimEndpoints.SETUP.setHandler(conduit -> {
            if (AuthHelper.hasAnyUsers()) {
                return redirectUntyped("/login");
            }

            return renderUntyped(
                Identifier.of("hohenheim", "hohenheim/setup"),
                Map.of("error", "")
            );
        });

        // Setup (POST) - create first user
        HohenheimEndpoints.SETUP_POST.setHandler(conduit -> {
            if (AuthHelper.hasAnyUsers()) {
                return redirectUntyped("/login");
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
            int userId = ((Number) user.get(UserModel.ID)).intValue();
            String token = AuthHelper.createSession(userId);
            AuthHelper.setSessionCookie(conduit, token);

            return redirectUntyped("/");
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
                           + proxy.getDispatcher().getWildcardRouteCount();

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
                List<Row> domains = domainModel.findBySiteId(((Number) row.get(SiteModel.ID)).intValue());
                Map<String, Object> site = new HashMap<>();
                site.put("id", row.get(SiteModel.ID));
                site.put("name", row.get(SiteModel.NAME));
                site.put("siteType", siteTypeDisplayName((String) row.get(SiteModel.SITE_TYPE)));
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
        HohenheimEndpoints.SITES_CREATE_FORM.setHandler(conduit ->
            new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/sites/create"),
                Map.of("error", "")
            )
        );

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

            Row row = siteModel.createEmptyRow();
            row.set(SiteModel.NAME, name);
            row.set(SiteModel.SLUG, slug);
            row.set(SiteModel.SITE_TYPE, siteType);
            row.set(SiteModel.SETTINGS, settings);
            row.set(SiteModel.STATUS, "active");
            siteModel.save(row);

            if (!hostname.isEmpty()) {
                int siteId = ((Number) row.get(SiteModel.ID)).intValue();
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

            String siteType = form.getOrDefault("site_type", (String) site.get(SiteModel.SITE_TYPE));
            Map<String, Object> settings = extractTypeSettings(form, siteType);
            boolean enabled = "on".equals(form.get("enabled")) || "true".equals(form.get("enabled"));

            site.set(SiteModel.NAME, name);
            site.set(SiteModel.SITE_TYPE, siteType);
            site.set(SiteModel.SETTINGS, settings);
            site.set(SiteModel.ENABLED, enabled);
            siteModel.save(site);

            audit(auditModel, conduit, "updated", "site", siteId, name);
            reloadProxy();
            return redirectUntyped("/sites/" + siteId);
        });

        // Site delete (POST /sites/:id/delete)
        HohenheimEndpoints.SITES_DELETE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();

            if (site != null) {
                site.set(SiteModel.DELETED_AT, Instant.now());
                siteModel.save(site);
                audit(auditModel, conduit, "deleted", "site", siteId, (String) site.get(SiteModel.NAME));
                reloadProxy();
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
                Row domainRow = domainModel.createEmptyRow();
                domainRow.set(SiteDomainModel.SITE_ID, siteId);
                domainRow.set(SiteDomainModel.HOSTNAME, hostname);
                domainRow.set(SiteDomainModel.MATCH_TYPE, matchType);
                domainModel.save(domainRow);

                audit(auditModel, conduit, "created", "domain", domainRow.get(SiteDomainModel.ID), hostname);
                reloadProxy();
            }

            return redirectUntyped("/sites/" + siteId);
        });

        // Delete domain (POST /sites/:id/domains/:domainId/delete)
        HohenheimEndpoints.SITES_DELETE_DOMAIN.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Integer domainId = conduit.getParameter(HohenheimEndpoints.DOMAIN_ID);

            Row domain = domainModel.find().where(SiteDomainModel.ID.eq(domainId)).first();
            if (domain != null) {
                String hostname = (String) domain.get(SiteDomainModel.HOSTNAME);
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

    private static void initCertificates(CertificateModel certModel) {
        HohenheimEndpoints.CERTIFICATES_LIST.setHandler(conduit -> {
            List<Row> rows = certModel.find()
                .orderBy(CertificateModel.CREATED_AT, SortOrder.DESC)
                .all();

            // Filter out the internal ACME account key row
            rows.removeIf(r -> "acme_account".equals(r.get(CertificateModel.PROVIDER)));

            List<Map<String, Object>> certs = new ArrayList<>();
            for (Row row : rows) {
                Map<String, Object> cert = new HashMap<>();
                cert.put("id", row.get(CertificateModel.ID));
                cert.put("niceName", row.get(CertificateModel.NICE_NAME));
                cert.put("provider", row.get(CertificateModel.PROVIDER));
                cert.put("expiresOn", row.get(CertificateModel.EXPIRES_ON));
                cert.put("autoRenew", row.get(CertificateModel.AUTO_RENEW));

                String status = (String) row.get(CertificateModel.STATUS);
                cert.put("status", status != null ? status : "active");
                cert.put("renewalError", row.get(CertificateModel.RENEWAL_ERROR));
                cert.put("domains", row.get(CertificateModel.DOMAIN_NAMES_TEXT));
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
                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                cf.generateCertificates(new java.io.ByteArrayInputStream(certPem.getBytes()));
            } catch (Exception e) {
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/upload"),
                    Map.of("error", "Invalid certificate PEM: " + e.getMessage())
                );
            }

            try {
                new org.bouncycastle.openssl.PEMParser(new java.io.StringReader(keyPem)).readObject();
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
                String error = failed != null ? (String) failed.get(CertificateModel.RENEWAL_ERROR) : "Unknown error";
                return renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/request"),
                    Map.of("error", "Certificate request failed: " + (error != null ? error : "Unknown"))
                );
            }

            reloadProxy();
            return redirectUntyped("/certificates");
        });

        // Delete (POST)
        HohenheimEndpoints.CERTIFICATES_DELETE.setHandler(conduit -> {
            Integer certId = conduit.getParameter(HohenheimEndpoints.CERT_ID);
            certModel.find().where(CertificateModel.ID.eq(certId)).delete();
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
            vars.put("adminPort", HohenheimSettings.VALUES.getValue(HohenheimSettings.Admin.PORT));
            vars.put("dbPath", HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PATH));
            vars.put("logAccessToDb", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_DATABASE));
            vars.put("logAccessToFile", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_FILE));
            vars.put("logAccessPath", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_PATH));
            vars.put("logCollectStats", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.COLLECT_STATS));
            vars.put("secLogDomainMisses", HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.LOG_DOMAIN_MISSES));
            vars.put("secDomainMissThreshold", HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD));

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

    private static Map<String, Object> extractTypeSettings(Map<String, String> form, String siteType) {
        Map<String, Object> settings = new HashMap<>();
        SiteTypeHandler handler = SiteTypes.getHandler(siteType);

        if (handler != null) {
            for (var entry : handler.getSchema().getFields().entrySet()) {
                String fieldName = entry.getKey();
                Field<?, ?> field = entry.getValue();
                String formValue = form.get(fieldName);

                if (formValue == null) continue;

                if (field instanceof IntegerField) {
                    try { settings.put(fieldName, Integer.parseInt(formValue)); }
                    catch (NumberFormatException e) { /* skip invalid */ }
                } else if (field instanceof BooleanField) {
                    settings.put(fieldName, "on".equals(formValue) || "true".equals(formValue));
                } else {
                    settings.put(fieldName, formValue);
                }
            }
        }

        return settings;
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
        int siteId = ((Number) site.get(SiteModel.ID)).intValue();
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
        vars.put("error", error);
        return vars;
    }

    private static void audit(AuditLogModel model, be.elevenways.zenit.common.conduit.Conduit conduit,
                              String action, String resourceType, Object resourceId, String resourceName) {
        Row user = AuthHelper.getCurrentUser(conduit);
        Row row = model.createEmptyRow();
        if (user != null) {
            row.set(AuditLogModel.USER_ID, String.valueOf(user.get(UserModel.ID)));
            row.set(AuditLogModel.USER_EMAIL, (String) user.get(UserModel.EMAIL));
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
        SiteTypeHandler handler = SiteTypes.getHandler(typeId);
        return handler != null ? handler.getDisplayName() : typeId;
    }
}
