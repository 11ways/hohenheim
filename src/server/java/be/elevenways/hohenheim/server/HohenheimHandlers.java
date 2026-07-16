package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.AttentionItems;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.process.IpcChannel;
import be.elevenways.hohenheim.server.process.ManagedProcess;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.ProcessTerminalHandler;
import be.elevenways.hohenheim.server.source.GitSiteRequestHandler;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.domino.common.DominoFile;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.server.http.HttpConduit;
import be.elevenways.zenit.server.http.RedirectResult;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handlers for the host-declared endpoints beside the zenit-cms panel:
 * downloads, uploads, process control, the terminal WebSocket, the settings
 * write path, and the health check.
 */
public final class HohenheimHandlers {

    private HohenheimHandlers() {
    }

    public static void init() {
        AttentionItems.install(AttentionCollector::collect);
        initHealth();
        initCertificates();
        initDatabases();
        initProcessControl();
        initDeployControl();
        initTerminal();
        initApi();
    }

    // -----------------------------------------------------------------------
    // Automation API: znit_ bearer keys (zenit-auth). State-changing calls
    // REFUSE session principals -- only header-carried API keys may act, which
    // is what makes the csrfExempt declaration safe.
    // -----------------------------------------------------------------------

    private static void initApi() {
        HohenheimEndpoints.API_SITES.setHandler(conduit -> {
            List<Map<String, Object>> sites = new ArrayList<>();
            var proxy = ServerMain.getProxyServer();
            for (Row site : Models.get(SiteModel.class).find()
                    .where(SiteModel.DELETED_AT.isNull()).all()) {
                Integer siteId = site.get(SiteModel.ID);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", siteId);
                entry.put("name", site.get(SiteModel.NAME));
                entry.put("slug", site.get(SiteModel.SLUG));
                entry.put("type", String.valueOf(site.get(SiteModel.SITE_TYPE)));
                entry.put("source", site.get(SiteModel.SOURCE));
                entry.put("enabled", Boolean.TRUE.equals(site.get(SiteModel.ENABLED)));
                SiteRequestHandler handler = proxy != null && siteId != null
                    ? proxy.getDispatcher().findHandlerBySiteId(siteId) : null;
                entry.put("health", handler != null
                    ? BlastString.lower(handler.getHealth().name()) : "unknown");
                if (handler instanceof GitSiteRequestHandler git) {
                    entry.put("current_commit", git.getCurrentCommit());
                    entry.put("deploying", git.isDeploying());
                }
                sites.add(entry);
            }
            return jsonUntyped(Map.of("sites", sites));
        });

        HohenheimEndpoints.API_SITES_DEPLOY.setHandler(conduit -> {
            if (!(conduit.getAttribute(ConduitAttributes.PRINCIPAL) instanceof ApiKeyPrincipal)) {
                conduit.forbidden();
                return null;
            }
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            var git = gitHandler(siteId);
            if (git.isEmpty()) {
                conduit.notFound();
                return null;
            }
            git.get().enqueueDeploy("api");
            ActivityLog.record(Models.get(SiteModel.class), siteId, "deploy_triggered", "api");
            return jsonUntyped(Map.of("status", "queued", "site", siteId));
        });
    }

    private static void initHealth() {
        HohenheimEndpoints.ROOT.setHandler(conduit -> redirectUntyped("/admin"));
        HohenheimEndpoints.HEALTH.setHandler(conduit ->
            jsonUntyped(Map.of("status", "ok")));
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

            ActivityLog.record(certModel, certId, "requested", niceName);
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
            ActivityLog.record(Models.get(DatabaseModel.class), name, "backup_downloaded", name);
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
            ActivityLog.record(Models.get(DatabaseModel.class), name, ActivityLog.ACTION_RESTORE, name);
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
        HohenheimEndpoints.SITES_PROCESS_START.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            managedHandler(siteId).ifPresent(managed -> {
                managed.startProcess();
                ActivityLog.record(Models.get(SiteModel.class), siteId, "started_process", null);
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
                    ActivityLog.record(Models.get(SiteModel.class), siteId, "killed_process", "PID " + pid);
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
                    ActivityLog.record(Models.get(SiteModel.class), siteId, "isolated_process", "PID " + pid);
                }
            });
            return redirectUntyped(processesPageUrl(siteId));
        });
    }

    private static String processesPageUrl(Integer siteId) {
        return "/admin/sites/" + siteId + "/page/processes";
    }

    // -----------------------------------------------------------------------
    // Deploy control (forms on the site's Deployments tab).
    // -----------------------------------------------------------------------

    private static void initDeployControl() {
        HohenheimEndpoints.SITES_DEPLOY.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            gitHandler(siteId).ifPresent(git -> {
                git.enqueueDeploy("manual");
                ActivityLog.record(Models.get(SiteModel.class), siteId, "deploy_triggered", null);
            });
            return redirectUntyped(deploymentsPageUrl(siteId));
        });

        HohenheimEndpoints.SITES_DEPLOY_CANCEL.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            gitHandler(siteId).ifPresent(git -> {
                if (git.cancelCurrentDeploy()) {
                    ActivityLog.record(Models.get(SiteModel.class), siteId, "deploy_cancelled", null);
                }
            });
            return redirectUntyped(deploymentsPageUrl(siteId));
        });

        HohenheimEndpoints.SITES_ROLLBACK.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            gitHandler(siteId).ifPresent(git -> {
                git.enqueueRollback();
                ActivityLog.record(Models.get(SiteModel.class), siteId, "rollback_triggered", null);
            });
            return redirectUntyped(deploymentsPageUrl(siteId));
        });
    }

    private static String deploymentsPageUrl(Integer siteId) {
        return "/admin/sites/" + siteId + "/page/deployments";
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
            // Git-sourced sites wrap their process handler; unwrap so process
            // control works for them too.
            if (handler instanceof GitSiteRequestHandler git) {
                handler = git.innerHandler();
            }
            if (handler instanceof ManagedProcessSiteHandler managed) {
                return Optional.of(managed);
            }
        }
        return Optional.empty();
    }

    private static Optional<GitSiteRequestHandler> gitHandler(Integer siteId) {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null && siteId != null
            && proxy.getDispatcher().findHandlerBySiteId(siteId) instanceof GitSiteRequestHandler git) {
            return Optional.of(git);
        }
        return Optional.empty();
    }

    private static Map<String, String> formMap(Conduit conduit) {
        if (conduit instanceof HttpConduit http) {
            return http.getFormData().toStringMap();
        }
        return Map.of();
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


    @SuppressWarnings("unchecked")
    private static ActionResult<Object> jsonUntyped(Map<String, Object> data) {
        return (ActionResult<Object>) (ActionResult<?>) new JsonResult(data);
    }

    @SuppressWarnings("unchecked")
    private static ActionResult<Object> redirectUntyped(String url) {
        return (ActionResult<Object>) (ActionResult<?>) new RedirectResult(url);
    }
}
