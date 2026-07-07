package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.process.IpcChannel;
import be.elevenways.hohenheim.server.process.ManagedProcess;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.ProcessTerminalHandler;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.domino.common.DominoFile;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.server.http.HttpConduit;
import be.elevenways.zenit.server.http.RedirectResult;
import be.elevenways.zenit.server.setting.DrySettingsWriter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handlers for the host-declared endpoints beside the zenit-cms panel:
 * downloads, uploads, process control, the terminal WebSocket, the settings
 * write path, and the health check.
 */
public final class HohenheimHandlers {

    /** Overridable so tests never clobber the developer's real local.dry. */
    private static Path localSettingsFile() {
        return Path.of(System.getProperty("hohenheim.local_settings", "settings/local.dry"));
    }

    private HohenheimHandlers() {
    }

    public static void init() {
        initHealth();
        initSettings();
        initCertificates();
        initDatabases();
        initProcessControl();
        initTerminal();
    }

    private static void initHealth() {
        HohenheimEndpoints.ROOT.setHandler(conduit -> redirectUntyped("/admin"));
        HohenheimEndpoints.HEALTH.setHandler(conduit ->
            jsonUntyped(Map.of("status", "ok")));
    }

    // -----------------------------------------------------------------------
    // Settings: apply to the live context AND persist into settings/local.dry.
    // -----------------------------------------------------------------------

    private static void initSettings() {
        HohenheimEndpoints.SETTINGS_UPDATE.setHandler(conduit -> {
            Map<String, String> form = formMap(conduit);

            Integer httpPort = parseBoundedInt(form.get("proxy_http_port"), 1, 65535);
            Integer httpsPort = parseBoundedInt(form.get("proxy_https_port"), 1, 65535);
            Integer threshold = parseBoundedInt(form.get("sec_domain_miss_threshold"), 1, Integer.MAX_VALUE);

            String error = null;
            if (form.get("proxy_http_port") != null && httpPort == null) {
                error = "Proxy HTTP port must be a number between 1 and 65535.";
            } else if (form.get("proxy_https_port") != null && httpsPort == null) {
                error = "Proxy HTTPS port must be a number between 1 and 65535.";
            } else if (form.get("sec_domain_miss_threshold") != null && threshold == null) {
                error = "Domain-miss ban threshold must be a positive number.";
            }
            if (error != null) {
                return redirectUntyped("/admin/settings?error="
                    + URLEncoder.encode(error, StandardCharsets.UTF_8));
            }

            DrySettingsWriter writer = new DrySettingsWriter(localSettingsFile());
            var values = HohenheimSettings.VALUES;

            if (httpPort != null) {
                values.setValue(HohenheimSettings.Proxy.HTTP_PORT, httpPort);
                writer.set(HohenheimSettings.Proxy.HTTP_PORT, httpPort);
            }
            if (httpsPort != null) {
                values.setValue(HohenheimSettings.Proxy.HTTPS_PORT, httpsPort);
                writer.set(HohenheimSettings.Proxy.HTTPS_PORT, httpsPort);
            }

            String fallback = form.getOrDefault("proxy_fallback", "");
            values.setValue(HohenheimSettings.Proxy.FALLBACK_ADDRESS, fallback);
            writer.set(HohenheimSettings.Proxy.FALLBACK_ADDRESS, fallback);

            boolean forceHttps = form.containsKey("proxy_force_https");
            values.setValue(HohenheimSettings.Proxy.FORCE_HTTPS, forceHttps);
            writer.set(HohenheimSettings.Proxy.FORCE_HTTPS, forceHttps);

            String ipv6 = form.getOrDefault("proxy_ipv6_address", "").trim();
            values.setValue(HohenheimSettings.Proxy.IPV6_ADDRESS, ipv6);
            writer.set(HohenheimSettings.Proxy.IPV6_ADDRESS, ipv6);

            boolean logToDb = form.containsKey("log_access_to_db");
            values.setValue(HohenheimSettings.Logging.ACCESS_TO_DATABASE, logToDb);
            writer.set(HohenheimSettings.Logging.ACCESS_TO_DATABASE, logToDb);

            boolean logToFile = form.containsKey("log_access_to_file");
            values.setValue(HohenheimSettings.Logging.ACCESS_TO_FILE, logToFile);
            writer.set(HohenheimSettings.Logging.ACCESS_TO_FILE, logToFile);

            String accessPath = form.getOrDefault("log_access_path",
                values.getValue(HohenheimSettings.Logging.ACCESS_PATH));
            values.setValue(HohenheimSettings.Logging.ACCESS_PATH, accessPath);
            writer.set(HohenheimSettings.Logging.ACCESS_PATH, accessPath);

            boolean logMisses = form.containsKey("sec_log_domain_misses");
            values.setValue(HohenheimSettings.Security.LOG_DOMAIN_MISSES, logMisses);
            writer.set(HohenheimSettings.Security.LOG_DOMAIN_MISSES, logMisses);

            if (threshold != null) {
                values.setValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD, threshold);
                writer.set(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD, threshold);
            }

            boolean leEnabled = form.containsKey("ssl_le_enabled");
            values.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, leEnabled);
            writer.set(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, leEnabled);

            String leEmail = form.getOrDefault("ssl_le_email", "");
            values.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL, leEmail);
            writer.set(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL, leEmail);

            boolean leStaging = form.containsKey("ssl_le_staging");
            values.setValue(HohenheimSettings.Ssl.LETSENCRYPT_STAGING, leStaging);
            writer.set(HohenheimSettings.Ssl.LETSENCRYPT_STAGING, leStaging);

            writer.persist();
            return redirectUntyped("/admin/settings?saved=true");
        });
    }

    // -----------------------------------------------------------------------
    // Certificates: Let's Encrypt request + PEM bundle download.
    // -----------------------------------------------------------------------

    private static void initCertificates() {
        CertificateModel certModel = Models.get(CertificateModel.class);

        HohenheimEndpoints.CERTIFICATES_REQUEST.setHandler(conduit -> {
            Map<String, String> form = formMap(conduit);

            String domains = form.getOrDefault("domains", "").trim();
            String niceName = form.getOrDefault("nice_name", "").trim();
            String email = form.getOrDefault("letsencrypt_email", "").trim();

            if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                return requestError("Invalid account email: " + email);
            }
            if (domains.isEmpty()) {
                return requestError("At least one domain is required");
            }
            if (niceName.isEmpty()) {
                niceName = domains.split("[,\\s]+")[0];
            }

            var proxy = ServerMain.getProxyServer();
            if (proxy == null) {
                return requestError("Proxy server not initialized");
            }

            List<String> hostnames = new ArrayList<>();
            for (String d : domains.split("[,\\s]+")) {
                d = d.trim();
                if (!d.isEmpty()) hostnames.add(d);
            }

            List<String> invalid = AcmeService.invalidHostnames(hostnames);
            if (!invalid.isEmpty()) {
                return requestError("Invalid hostnames: " + String.join(", ", invalid));
            }

            List<String> excluded = excludedFromLetsencrypt(hostnames);
            if (!excluded.isEmpty()) {
                return requestError("Excluded from Let's Encrypt by their domain settings: "
                    + String.join(", ", excluded));
            }

            int certId = proxy.getAcmeService().requestCertificate(hostnames, niceName,
                email.isEmpty() ? null : email);

            if (certId < 0) {
                Row failed = certModel.find()
                    .where(CertificateModel.STATUS.eq("error"))
                    .orderBy(CertificateModel.CREATED_AT, SortOrder.DESC)
                    .first();
                String reason = failed != null ? failed.get(CertificateModel.RENEWAL_ERROR) : null;
                return requestError("Certificate request failed: "
                    + (reason != null ? reason : "Unknown"));
            }

            audit(conduit, AuditLogModel.ACTION_REQUESTED, AuditLogModel.RESOURCE_CERTIFICATE,
                certId, niceName);
            CmsSupport.reloadProxy();
            return redirectUntyped("/admin/certificates");
        });

        HohenheimEndpoints.CERTIFICATES_DOWNLOAD.setHandler(conduit -> {
            Integer certId = conduit.getParameter(HohenheimEndpoints.CERT_ID);
            Row cert = certModel.findById(certId);
            if (cert == null) return redirectUntyped("/admin/certificates");

            String certPem = cert.get(CertificateModel.CERTIFICATE_PEM);
            String keyPem = cert.get(CertificateModel.PRIVATE_KEY_PEM);
            String niceName = cert.get(CertificateModel.NICE_NAME);
            if (certPem == null) certPem = "";
            if (keyPem == null) keyPem = "";

            String bundle = "# Certificate: " + niceName + "\n\n" + certPem + "\n" + keyPem;
            download(conduit, "application/x-pem-file",
                (niceName != null ? niceName : "certificate") + ".pem", bundle.getBytes(StandardCharsets.UTF_8));
            return null;
        });
    }

    private static ActionResult<Object> requestError(String message) {
        return redirectUntyped("/admin/certificates-request?error="
            + URLEncoder.encode(message, StandardCharsets.UTF_8));
    }

    /** The subset of hostnames whose domain record opted out of Let's Encrypt. */
    private static List<String> excludedFromLetsencrypt(List<String> hostnames) {
        var domainModel = Models.get(SiteDomainModel.class);
        List<String> excluded = new ArrayList<>();
        for (String hostname : hostnames) {
            Row domain = domainModel.find()
                .where(SiteDomainModel.HOSTNAME.eq(hostname))
                .where(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.eq(true))
                .first();
            if (domain != null) {
                excluded.add(hostname);
            }
        }
        return excluded;
    }

    // -----------------------------------------------------------------------
    // Managed databases: dump download + restore upload.
    // -----------------------------------------------------------------------

    private static void initDatabases() {
        DatabaseService databaseService = new DatabaseService();

        HohenheimEndpoints.DATABASES_BACKUP.setHandler(conduit -> {
            String name = conduit.getParameter(HohenheimEndpoints.DATABASE_NAME);
            DatabaseService.BackupDownload dump;
            try {
                dump = databaseService.backupDownload(name);
            } catch (IOException e) {
                Blast.log("DB: backup of", name, "failed -", e.getMessage());
                return redirectUntyped("/admin/databases");
            }
            download(conduit, dump.contentType(), dump.filename(), dump.content());
            return null;
        });

        HohenheimEndpoints.DATABASES_RESTORE.setHandler(conduit -> {
            String name = conduit.getParameter(HohenheimEndpoints.DATABASE_NAME);
            String restorePage = restorePageUrl(name);
            if (!(conduit.getFormData().get("dump") instanceof DominoFile file) || file.getSize() == 0) {
                return redirectUntyped(restorePage + "?error=Pick+a+dump+file+to+restore.");
            }
            try {
                Path temp = Files.createTempFile("hohenheim-restore-upload", null);
                try {
                    Files.write(temp, file.getBytes());
                    databaseService.restoreFromFile(name, temp);
                } finally {
                    Files.deleteIfExists(temp);
                }
            } catch (UnsupportedOperationException e) {
                Blast.log("DB: restore of", name, "rejected -", e.getMessage());
                return redirectUntyped(restorePage + "?error="
                    + URLEncoder.encode("This database does not support restore.", StandardCharsets.UTF_8));
            } catch (IOException e) {
                Blast.log("DB: restore of", name, "failed -", e.getMessage());
                return redirectUntyped(restorePage + "?error="
                    + URLEncoder.encode("Restore failed; see the server log for details.", StandardCharsets.UTF_8));
            }
            audit(conduit, AuditLogModel.ACTION_RESTORED, AuditLogModel.RESOURCE_DATABASE, name, name);
            return redirectUntyped(restorePage + "?restored=1");
        });
    }

    /** The CMS restore tab for a named database (falls back to the list when unknown). */
    private static String restorePageUrl(String name) {
        Row row = Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq(name)).first();
        if (row == null) {
            return "/admin/databases";
        }
        return "/admin/databases/" + row.get(DatabaseModel.ID) + "/page/restore";
    }

    // -----------------------------------------------------------------------
    // Process control (forms on the site's Processes tab).
    // -----------------------------------------------------------------------

    private static void initProcessControl() {
        HohenheimEndpoints.SITES_PROCESSES.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Map<String, Object> result = new HashMap<>();
            boolean proxyUp = ServerMain.getProxyServer() != null;
            managedHandler(siteId).ifPresentOrElse(managed -> {
                List<Map<String, Object>> processes = new ArrayList<>();
                for (var proc : managed.getProcesses()) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("pid", proc.pid());
                    info.put("port", proc.port());
                    info.put("cpu", proc.cpuPercent());
                    info.put("isolated", proc.isIsolated());
                    info.put("ready", proc.isReady());
                    info.put("fingerprints", proc.activeFingerprintCount());
                    info.put("memory", proc.memoryKb());
                    info.put("startTime", proc.startTime().toString());
                    processes.add(info);
                }
                result.put("running", !processes.isEmpty());
                result.put("processes", processes);
            }, () -> {
                if (proxyUp) {
                    result.put("running", false);
                    result.put("processes", List.of());
                    result.put("type", "not_managed");
                }
            });
            return jsonUntyped(result);
        });

        HohenheimEndpoints.SITES_PROCESS_START.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            managedHandler(siteId).ifPresent(managed -> {
                managed.startProcess();
                audit(conduit, AuditLogModel.ACTION_STARTED_PROCESS,
                    AuditLogModel.RESOURCE_SITE, siteId, null);
            });
            return redirectUntyped(processesPageUrl(siteId));
        });

        HohenheimEndpoints.SITES_PROCESS_KILL.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = conduit.getParameter(HohenheimEndpoints.PID);
            managedHandler(siteId).ifPresent(managed -> {
                ManagedProcess proc = managed.getProcess(pid);
                if (proc != null) {
                    proc.kill();
                    audit(conduit, AuditLogModel.ACTION_KILLED_PROCESS,
                        AuditLogModel.RESOURCE_SITE, siteId, "PID " + pid);
                }
            });
            return redirectUntyped(processesPageUrl(siteId));
        });

        HohenheimEndpoints.SITES_PROCESS_ISOLATE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = conduit.getParameter(HohenheimEndpoints.PID);
            managedHandler(siteId).ifPresent(managed -> {
                ManagedProcess proc = managed.getProcess(pid);
                if (proc != null) {
                    proc.setIsolated(!proc.isIsolated());
                    audit(conduit, AuditLogModel.ACTION_ISOLATED_PROCESS,
                        AuditLogModel.RESOURCE_SITE, siteId, "PID " + pid);
                }
            });
            return redirectUntyped(processesPageUrl(siteId));
        });
    }

    private static String processesPageUrl(Integer siteId) {
        return "/admin/sites/" + siteId + "/page/processes";
    }

    private static void initTerminal() {
        HohenheimEndpoints.PROCESS_TERMINAL.setHandlerFactory(session -> {
            Integer siteId = session.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = session.getParameter(HohenheimEndpoints.PID);
            ManagedProcess proc = null;
            IpcChannel ipc = null;
            Optional<ManagedProcessSiteHandler> managedOpt = managedHandler(siteId);
            if (managedOpt.isPresent() && pid != null) {
                ManagedProcessSiteHandler managed = managedOpt.get();
                proc = managed.getProcess(pid);
                ipc = managed.getIpcChannel(pid);
            }
            return new ProcessTerminalHandler(session, proc, ipc);
        });
    }

    // -----------------------------------------------------------------------
    // Shared plumbing.
    // -----------------------------------------------------------------------

    private static Optional<ManagedProcessSiteHandler> managedHandler(Integer siteId) {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null && siteId != null) {
            var handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
            if (handler instanceof ManagedProcessSiteHandler managed) {
                return Optional.of(managed);
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> formMap(Conduit conduit) {
        if (conduit instanceof HttpConduit http) {
            return http.getFormData().toStringMap();
        }
        return Map.of();
    }

    /** @return the parsed value when it lies in [min, max], or null for blank/garbage/out-of-range */
    private static Integer parseBoundedInt(String raw, int min, int max) {
        if (raw == null) return null;
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= min && value <= max ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Stream a binary body as a downloadable attachment with a sanitized filename. */
    private static void download(Conduit conduit, String contentType, String filename, byte[] body) {
        if (conduit instanceof HttpConduit http) {
            String safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            http.setResponseHeader("Content-Type", contentType);
            http.setResponseHeader("Content-Disposition", "attachment; filename=\"" + safeName + "\"");
        }
        conduit.endWithBytes(contentType, body);
    }

    private static void audit(Conduit conduit, String action, String resourceType,
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

    @SuppressWarnings("unchecked")
    private static ActionResult<Object> jsonUntyped(Map<String, Object> data) {
        return (ActionResult<Object>) (ActionResult<?>) new JsonResult(data);
    }

    @SuppressWarnings("unchecked")
    private static ActionResult<Object> redirectUntyped(String url) {
        return (ActionResult<Object>) (ActionResult<?>) new RedirectResult(url);
    }
}
