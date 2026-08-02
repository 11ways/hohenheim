package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimPaths;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.dns.DnsApiErrorResponse;
import be.elevenways.hohenheim.dns.DnsRecordDeleteResponse;
import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsRecordListResponse;
import be.elevenways.hohenheim.dns.DnsRecordMutationResponse;
import be.elevenways.hohenheim.dns.DnsValidationErrorResponse;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.cms.DnsRecordEdits;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.hohenheim.server.dns.DnsZoneFiles;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.CertificateRequestForm;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.devtunnel.DevTunnelServerHandler;
import be.elevenways.hohenheim.server.dns.InternalDnsTxtPublisher;
import be.elevenways.hohenheim.server.process.IpcChannel;
import be.elevenways.hohenheim.server.process.ManagedProcess;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.ProcessTerminalHandler;
import be.elevenways.hohenheim.server.sitetype.SiteHandlers;
import be.elevenways.hohenheim.server.source.GitSiteRequestHandler;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateAuthority;
import be.elevenways.hohenheim.server.tls.CommandDnsTxtPublisher;
import be.elevenways.domino.common.DominoFile;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
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
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.HttpConduit;
import be.elevenways.zenit.server.http.RedirectResult;
import be.elevenways.zenit.server.http.ReturnTarget;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import be.elevenways.zenit.forms.server.path.FilesystemBrowserRegistry;
import be.elevenways.zenit.forms.server.path.FilesystemBrowserSource;

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
        FilesystemBrowserRegistry.INSTANCE.register(FilesystemBrowserSource.of(
            HohenheimPaths.SERVER_FILES, HohenheimPanel.ACCESS, Path.of("/")));
        initHealth();
        initCertificates();
        initDnsZones();
        initDnsRecordApi();
        initDnsRemoteRecords();
        initDynamicDns();
        initDatabases();
        initProcessControl();
        initDeployControl();
        initTerminal();
        initDevTunnel();
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
        // GET / is owned by zenit-cms's landing redirect (CmsPanels, installed by
        // HohenheimHostWiring): operators land on /admin, manage-only tenants on /manage.
        HohenheimEndpoints.HEALTH.setHandler(conduit ->
            jsonUntyped(Map.of("status", "ok")));
    }

    // -----------------------------------------------------------------------
    // Certificates: Let's Encrypt request + PEM bundle download.
    // -----------------------------------------------------------------------

    private static void initCertificates() {
        CertificateModel certModel = Models.get(CertificateModel.class);

        HohenheimEndpoints.CERTIFICATES_REQUEST.setHandler(conduit -> {
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);

            String manualToken = submittedString(form, "manual_token");
            if (!manualToken.isEmpty()) {
                var proxy = ServerMain.getProxyServer();
                if (proxy == null) {
                    return requestError(conduit, certificateError("proxy_unavailable"));
                }
                int certId = proxy.getAcmeService().completeManualDnsCertificate(manualToken);
                if (certId < 0) {
                    return requestError(conduit, certificateError("dns_validation_failed"));
                }
                ActivityLog.record(certModel, certId, "requested", "manual DNS-01");
                return redirectUntyped("/admin/certificates");
            }

            List<String> hostnames = CertificateRequestForm.submittedDomains(form);
            String niceName = submittedString(form, "nice_name");
            String email = submittedString(form, "letsencrypt_email");
            String challengeType = submittedString(form, "challenge_type");
            String dnsMode = submittedString(form, "dns_mode");
            if (challengeType.isEmpty()) challengeType = CertificateModel.CHALLENGE_HTTP;
            if (dnsMode.isEmpty()) dnsMode = CertificateModel.DNS_PUBLISHER_MANUAL;

            if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                return requestError(conduit, certificateError("invalid_email").withArg("email", email));
            }
            if (hostnames.isEmpty()) {
                return requestError(conduit, certificateError("domain_required"));
            }
            if (niceName.isEmpty()) {
                niceName = hostnames.get(0);
            }

            boolean dns = CertificateModel.CHALLENGE_DNS.equals(challengeType);
            if (!dns && !CertificateModel.CHALLENGE_HTTP.equals(challengeType)) {
                return requestError(conduit, certificateError("unknown_validation")
                    .withArg("method", challengeType));
            }
            if (!dns && hostnames.stream().anyMatch(name -> name.startsWith("*."))) {
                return requestError(conduit, certificateError("wildcard_requires_dns"));
            }
            List<String> invalid = AcmeService.invalidHostnames(hostnames, dns);
            if (!invalid.isEmpty()) {
                return requestError(conduit, certificateError("invalid_hostnames")
                    .withArg("hostnames", String.join(", ", invalid)));
            }

            // Input validation first: only a request that could actually be
            // ordered gets refused on operational grounds.
            var proxy = ServerMain.getProxyServer();
            if (proxy == null) {
                return requestError(conduit, certificateError("proxy_unavailable"));
            }

            var requester = CertificateAuthority.Requester.of(AccessContext.of(conduit));

            if (dns && CertificateModel.DNS_PUBLISHER_MANUAL.equals(dnsMode)) {
                try {
                    var manual = proxy.getAcmeService().prepareManualDnsCertificate(
                        hostnames, niceName, email.isEmpty() ? null : email, requester);
                    return redirectUntyped("/admin/certificates-request?manual="
                        + URLEncoder.encode(manual.token(), StandardCharsets.UTF_8));
                } catch (CertificateAuthority.Refused refused) {
                    return requestError(conduit, refusalMessage(refused));
                } catch (Exception e) {
                    return requestError(conduit, certificateError("dns_start_failed")
                        .withArg("reason", e.getMessage()));
                }
            }

            if (dns && CertificateModel.DNS_PUBLISHER_INTERNAL.equals(dnsMode)) {
                var dnsServer = ServerMain.getDnsServer();
                if (dnsServer == null || !dnsServer.isRunning()) {
                    return requestError(conduit, certificateError("dns_server_disabled"));
                }
                InternalDnsTxtPublisher internal = new InternalDnsTxtPublisher();
                List<String> unhosted = hostnames.stream()
                    .map(name -> name.startsWith("*.") ? name.substring(2) : name)
                    .filter(name -> !internal.canPublishFor("_acme-challenge." + name))
                    .toList();
                if (!unhosted.isEmpty()) {
                    return requestError(conduit, certificateError("zone_not_hosted")
                        .withArg("hostnames", String.join(", ", unhosted)));
                }
            }
            else if (dns && !CommandDnsTxtPublisher.ID.equals(dnsMode)) {
                return requestError(conduit, certificateError("unknown_dns_mode")
                    .withArg("mode", dnsMode));
            }

            String publisher = dns ? dnsMode : null;
            if (dns && CommandDnsTxtPublisher.ID.equals(dnsMode) && !CommandDnsTxtPublisher.isConfigured()) {
                return requestError(conduit, certificateError("hook_not_configured"));
            }
            int certId;
            try {
                certId = proxy.getAcmeService().requestCertificate(hostnames, niceName,
                    email.isEmpty() ? null : email, challengeType, publisher, requester);
            } catch (CertificateAuthority.Refused refused) {
                return requestError(conduit, refusalMessage(refused));
            }

            if (certId < 0) {
                Row failed = certModel.find()
                    .where(CertificateModel.STATUS.eq("error"))
                    .orderBy(CertificateModel.CREATED_AT, SortOrder.DESC)
                    .first();
                String reason = failed != null ? failed.get(CertificateModel.RENEWAL_ERROR) : null;
                if (reason == null) {
                    reason = certificateError("unknown_reason")
                        .resolve(conduit.getLocales(), conduit.getMessageResolver());
                }
                return requestError(conduit, certificateError("request_failed")
                    .withArg("reason", reason));
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

    // -----------------------------------------------------------------------
    // DNS: zone-file import (the zone-file tab's paste form).
    // -----------------------------------------------------------------------

    private static void initDnsZones() {
        HohenheimEndpoints.DNS_ZONE_IMPORT.setHandler(conduit -> {
            Integer zoneId = conduit.getParameter(HohenheimEndpoints.ZONE_ID);
            Row zone = Models.get(DnsZoneModel.class).find()
                .where(DnsZoneModel.ID.eq(zoneId)).first();
            if (zone == null) {
                return redirectUntyped("/admin/dns-zones");
            }

            String backUrl = "/admin/dns-zones/" + zoneId + "/page/zonefile";
            Map<String, String> form = formMap(conduit);
            String text = form.getOrDefault("zone_text", "");
            if (text.isBlank()) {
                return redirectUntyped(backUrl + "?error=" + URLEncoder.encode(
                    Microcopy.of("import_empty").withFilter("scope", "dns_zone")
                        .resolve(conduit.getLocales(), conduit.getMessageResolver()),
                    StandardCharsets.UTF_8));
            }

            try {
                DnsZoneFiles.ImportResult result = DnsZoneFiles.importText(zone, text);
                ActivityLog.record(Models.get(DnsZoneModel.class), zoneId, "imported",
                    zone.get(DnsZoneModel.ORIGIN));
                StringBuilder target = new StringBuilder(backUrl)
                    .append("?imported=").append(result.imported());
                if (!result.skipped().isEmpty()) {
                    target.append("&skipped=").append(URLEncoder.encode(
                        String.join("; ", result.skipped()), StandardCharsets.UTF_8));
                }
                return redirectUntyped(target.toString());
            }
            catch (Exception e) {
                return redirectUntyped(backUrl + "?error="
                    + URLEncoder.encode(String.valueOf(e.getMessage()), StandardCharsets.UTF_8));
            }
        });
    }

    // -----------------------------------------------------------------------
    // DNS records peer/automation API: the edit-forwarding channel other
    // Hohenheim instances use to edit zones THIS instance owns (plus a plain
    // automation API). API-key principals only; primary zones only.
    // -----------------------------------------------------------------------

    private static final List<String> RECORD_FIELDS =
        List.of("name", "type", "value", "ttl", "priority", "weight", "port", "enabled");

    private static void initDnsRecordApi() {
        DnsRecordModel model = Models.get(DnsRecordModel.class);

        HohenheimEndpoints.API_DNS_RECORDS.setHandler(conduit -> {
            Row zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            List<DnsRecordDto> records = new ArrayList<>();
            for (Row record : model.find()
                    .where(DnsRecordModel.ZONE_ID.eq(zone.get(DnsZoneModel.ID)))
                    .orderBy(DnsRecordModel.NAME, SortOrder.ASC)
                    .all()) {
                records.add(recordDto(record));
            }
            return new JsonResult<Object>(new DnsRecordListResponse(
                zone.get(DnsZoneModel.ORIGIN), zone.get(DnsZoneModel.SERIAL), records));
        });

        HohenheimEndpoints.API_DNS_RECORD_CREATE.setHandler(conduit -> {
            Row zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            int zoneId = zone.get(DnsZoneModel.ID);
            Map<String, Object> values = recordValues(formMap(conduit));
            values.put("zone_id", zoneId);
            try {
                DnsRecordEdits.validate(values, null, model);
            }
            catch (Violations violations) {
                return validationError(conduit, violations);
            }
            Row row = model.createEmptyRow();
            row.set(DnsRecordModel.ZONE_ID, zoneId);
            applyRecordValues(row, values);
            model.save(row);
            ActivityLog.record(model, row.get(DnsRecordModel.ID), "created", "api");
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
            return new JsonResult<Object>(
                new DnsRecordMutationResponse(row.get(DnsRecordModel.ID)));
        });

        HohenheimEndpoints.API_DNS_RECORD_UPDATE.setHandler(conduit -> {
            Row zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            Row record = apiRecord(conduit, zone, model);
            if (record == null) {
                return null;
            }
            // Only submitted fields change; absent keys keep their stored value.
            Map<String, Object> values = recordValues(formMap(conduit));
            try {
                DnsRecordEdits.validate(values, record, model);
            }
            catch (Violations violations) {
                return validationError(conduit, violations);
            }
            applyRecordValues(record, values);
            model.save(record);
            ActivityLog.record(model, record.get(DnsRecordModel.ID), "updated", "api");
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zone.get(DnsZoneModel.ID));
            return new JsonResult<Object>(
                new DnsRecordMutationResponse(record.get(DnsRecordModel.ID)));
        });

        HohenheimEndpoints.API_DNS_RECORD_DELETE.setHandler(conduit -> {
            Row zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            Row record = apiRecord(conduit, zone, model);
            if (record == null) {
                return null;
            }
            Integer recordId = record.get(DnsRecordModel.ID);
            model.delete(record);
            ActivityLog.record(model, recordId, "deleted", "api");
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zone.get(DnsZoneModel.ID));
            return new JsonResult<Object>(new DnsRecordDeleteResponse("deleted"));
        });
    }

    private static DnsRecordDto recordDto(Row record) {
        return new DnsRecordDto(
            record.get(DnsRecordModel.ID),
            record.get(DnsRecordModel.NAME),
            record.get(DnsRecordModel.TYPE),
            record.get(DnsRecordModel.TTL),
            record.get(DnsRecordModel.VALUE),
            record.get(DnsRecordModel.PRIORITY),
            record.get(DnsRecordModel.WEIGHT),
            record.get(DnsRecordModel.PORT),
            Boolean.TRUE.equals(record.get(DnsRecordModel.ENABLED)),
            record.get(DnsRecordModel.MANAGED_BY));
    }

    /** Gate + zone resolution shared by every DNS api handler; null = response already written. */
    private static @org.checkerframework.checker.nullness.qual.Nullable Row apiPrimaryZone(Conduit conduit) {
        if (!(conduit.getAttribute(ConduitAttributes.PRINCIPAL) instanceof ApiKeyPrincipal)) {
            conduit.forbidden();
            return null;
        }
        String rawOrigin = conduit.getParameter(HohenheimEndpoints.DNS_ORIGIN);
        String origin = DnsNames.normalizeOrigin(rawOrigin != null ? rawOrigin : "");
        Row zone = origin != null ? Models.get(DnsZoneModel.class).findByOrigin(origin) : null;
        if (zone == null) {
            conduit.notFound();
            return null;
        }
        if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            // Edits belong on the owning instance; a replica must never fork.
            conduit.setResponseStatus(409);
            new JsonResult<Object>(new DnsApiErrorResponse("not_primary"))
                .finalizeConduit(conduit);
            return null;
        }
        return zone;
    }

    private static @org.checkerframework.checker.nullness.qual.Nullable Row apiRecord(
            Conduit conduit, Row zone, DnsRecordModel model) {
        Integer recordId = conduit.getParameter(HohenheimEndpoints.DNS_RECORD_ID);
        Row record = model.find()
            .where(DnsRecordModel.ID.eq(recordId))
            .where(DnsRecordModel.ZONE_ID.eq(zone.get(DnsZoneModel.ID)))
            .first();
        if (record == null) {
            conduit.notFound();
            return null;
        }
        return record;
    }

    /** Submitted record fields as a validation map; absent keys fall back to the existing row. */
    private static Map<String, Object> recordValues(Map<String, String> form) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : RECORD_FIELDS) {
            if (form.containsKey(field)) {
                values.put(field, form.get(field));
            }
        }
        return values;
    }

    private static void applyRecordValues(Row row, Map<String, Object> values) {
        if (values.containsKey("name")) {
            row.set(DnsRecordModel.NAME, String.valueOf(values.get("name")));
        }
        if (values.containsKey("type")) {
            row.set(DnsRecordModel.TYPE, String.valueOf(values.get("type")));
        }
        if (values.containsKey("value")) {
            row.set(DnsRecordModel.VALUE, String.valueOf(values.get("value")));
        }
        if (values.containsKey("ttl")) {
            row.set(DnsRecordModel.TTL, DnsRecordEdits.intOrNull(values.get("ttl")));
        }
        if (values.containsKey("priority")) {
            row.set(DnsRecordModel.PRIORITY, DnsRecordEdits.intOrNull(values.get("priority")));
        }
        if (values.containsKey("weight")) {
            row.set(DnsRecordModel.WEIGHT, DnsRecordEdits.intOrNull(values.get("weight")));
        }
        if (values.containsKey("port")) {
            row.set(DnsRecordModel.PORT, DnsRecordEdits.intOrNull(values.get("port")));
        }
        if (values.containsKey("enabled")) {
            row.set(DnsRecordModel.ENABLED, Boolean.parseBoolean(String.valueOf(values.get("enabled"))));
        }
        else if (row.get(DnsRecordModel.ENABLED) == null) {
            row.set(DnsRecordModel.ENABLED, true);
        }
    }

    // -----------------------------------------------------------------------
    // Dynamic DNS: the public dyndns2 update endpoint (/nic/update). The update
    // token travels in HTTP Basic auth (password, or username as a fallback) or
    // a ?token= param; the service refuses anything the token does not unlock.
    // -----------------------------------------------------------------------

    private static void initDynamicDns() {
        DynamicDnsService service = new DynamicDnsService(DnsZoneStore.INSTANCE);
        HohenheimEndpoints.DYNDNS_UPDATE.setHandler(conduit -> {
            String token = dyndnsToken(conduit);
            String hostname = conduit.getQueryParam("hostname");
            String myip = conduit.getQueryParam("myip");
            String callerIp = conduit.getRemoteIp();
            DynamicDnsService.UpdateResult result = service.update(token, hostname, myip, callerIp);
            conduit.endWithContentType("text/plain", result.wire());
            return null;
        });
    }

    /** The update token from HTTP Basic auth (password preferred, username fallback) or ?token=. */
    private static @org.checkerframework.checker.nullness.qual.Nullable String dyndnsToken(Conduit conduit) {
        String authorization = conduit.getRequestHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(java.util.Base64.getDecoder()
                    .decode(authorization.substring(6).trim()), java.nio.charset.StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                String user = colon >= 0 ? decoded.substring(0, colon) : decoded;
                String pass = colon >= 0 ? decoded.substring(colon + 1) : "";
                // dyndns2 puts the credential in the password; tolerate clients that
                // send it as the username with an empty password.
                if (pass.startsWith(DynamicDnsService.TOKEN_MARKER)) {
                    return pass;
                }
                if (user.startsWith(DynamicDnsService.TOKEN_MARKER)) {
                    return user;
                }
            }
            catch (IllegalArgumentException ignored) {
                // Malformed base64: fall through to the query param.
            }
        }
        return conduit.getQueryParam("token");
    }

    private static ActionResult<Object> validationError(Conduit conduit, Violations violations) {
        Violation first = violations.all().isEmpty() ? null : violations.all().get(0);
        conduit.setResponseStatus(422);
        if (first == null) {
            return new JsonResult<Object>(new DnsApiErrorResponse("validation"));
        }
        return new JsonResult<Object>(new DnsValidationErrorResponse(
            "validation",
            first.fieldName(),
            first.message().key(),
            first.message().resolve(conduit.getLocales(), conduit.getMessageResolver())));
    }

    // -----------------------------------------------------------------------
    // Remote-record edit forwarding: the admin form POST on a SECONDARY
    // zone's Records tab, forwarded to the owning peer's API above.
    // -----------------------------------------------------------------------

    private static void initDnsRemoteRecords() {
        HohenheimEndpoints.DNS_REMOTE_RECORD.setHandler(conduit -> {
            Integer zoneId = conduit.getParameter(HohenheimEndpoints.ZONE_ID);
            Row zone = Models.get(DnsZoneModel.class).find()
                .where(DnsZoneModel.ID.eq(zoneId)).first();
            if (zone == null || !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
                return redirectUntyped("/admin/dns-zones");
            }
            String backUrl = "/admin/dns-zones/" + zoneId + "/page/records";

            Integer peerId = zone.get(DnsZoneModel.PRIMARY_PEER_ID);
            Row peer = peerId != null ? Models.get(DnsPeerModel.class).findById(peerId) : null;
            DnsPeerApi api = DnsPeerApi.forPeer(peer);
            if (api == null) {
                return redirectUntyped(backUrl + "?error=" + URLEncoder.encode(
                    Microcopy.of("peer_not_configured").withFilter("scope", "dns_remote")
                        .resolve(conduit.getLocales(), conduit.getMessageResolver()),
                    StandardCharsets.UTF_8));
            }

            Map<String, String> form = formMap(conduit);
            String origin = zone.get(DnsZoneModel.ORIGIN);
            String action = form.getOrDefault("action", "save");
            String recordId = form.getOrDefault("record_id", "").trim();
            Map<String, String> fields = new LinkedHashMap<>();
            for (String field : RECORD_FIELDS) {
                if (form.containsKey(field)) {
                    fields.put(field, form.get(field));
                }
            }

            try {
                if ("delete".equals(action) && !recordId.isEmpty()) {
                    api.deleteRecord(origin, Integer.parseInt(recordId));
                }
                else if (!recordId.isEmpty()) {
                    api.updateRecord(origin, Integer.parseInt(recordId), fields);
                }
                else {
                    api.createRecord(origin, fields);
                }
            }
            catch (NumberFormatException e) {
                return redirectUntyped(backUrl);
            }
            catch (DnsPeerApi.PeerApiException e) {
                // A validation refusal round-trips by microcopy key (same catalogs
                // on both instances); transport failures show the raw message.
                String message = e.getViolationKey() != null
                    ? Microcopy.of(e.getViolationKey()).withFilter("scope", "violations")
                        .resolve(conduit.getLocales(), conduit.getMessageResolver())
                    : String.valueOf(e.getMessage());
                return redirectUntyped(backUrl + "?error="
                    + URLEncoder.encode(message, StandardCharsets.UTF_8));
            }

            return redirectUntyped(backUrl + "?saved=1");
        });
    }

    private static Microcopy certificateError(String key) {
        return Microcopy.of(key).withFilter("scope", "certificate_request_error");
    }

    private static ActionResult<Object> requestError(Conduit conduit, Microcopy message) {
        String resolved = message.resolve(conduit.getLocales(), conduit.getMessageResolver());
        return redirectUntyped("/admin/certificates-request?error="
            + URLEncoder.encode(resolved, StandardCharsets.UTF_8));
    }

    /**
     * The user-facing rendering of an authority refusal.
     *
     * AIDEV-NOTE: this MAPS a decision, it does not make one -- the decision lives in
     * CertificateAuthority, inside the service, so every entry point (this form, the manual
     * DNS lane, the renewal sweep) answers to the same rule. The old hostname-eligibility
     * check that lived here compared hostnames with HOSTNAME.eq, so a name covered only by
     * a wildcard row was never seen at all.
     */
    private static Microcopy refusalMessage(CertificateAuthority.Refused refused) {
        String key = switch (refused.refusal()) {
            case NOT_SERVED -> "hostname_not_served";
            case NOT_MANAGED -> "hostname_not_managed";
            case EXCLUDED -> "excluded_hostnames";
        };
        return certificateError(key).withArg("hostnames", refused.hostname());
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
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            managedHandler(siteId).ifPresent(managed -> {
                managed.startProcess();
                ActivityLog.record(Models.get(SiteModel.class), siteId, "started_process", null);
            });
            return redirectUntyped(processesPageUrl(conduit, siteId));
        });

        HohenheimEndpoints.SITES_PROCESS_KILL.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = conduit.getParameter(HohenheimEndpoints.PID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            managedHandler(siteId).ifPresent(managed -> {
                ManagedProcess proc = managed.getProcess(pid);
                if (proc != null) {
                    proc.kill();
                    ActivityLog.record(Models.get(SiteModel.class), siteId, "killed_process", "PID " + pid);
                }
            });
            return redirectUntyped(processesPageUrl(conduit, siteId));
        });

        HohenheimEndpoints.SITES_PROCESS_ISOLATE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = conduit.getParameter(HohenheimEndpoints.PID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            managedHandler(siteId).ifPresent(managed -> {
                ManagedProcess proc = managed.getProcess(pid);
                if (proc != null) {
                    proc.setIsolated(!proc.isIsolated());
                    ActivityLog.record(Models.get(SiteModel.class), siteId, "isolated_process", "PID " + pid);
                }
            });
            return redirectUntyped(processesPageUrl(conduit, siteId));
        });
    }

    /**
     * The submitted {@code _return} target (the /admin or /manage page the form
     * rendered on), validated by {@link ReturnTarget}; forged values fall back
     * to the admin page.
     */
    private static String processesPageUrl(Conduit conduit, Integer siteId) {
        return ReturnTarget.or(ReturnTarget.read(conduit),
            "/admin/sites/" + siteId + "/page/processes");
    }

    // -----------------------------------------------------------------------
    // Deploy control (forms on the site's Deployments tab).
    // -----------------------------------------------------------------------

    private static void initDeployControl() {
        HohenheimEndpoints.SITES_DEPLOY.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            gitHandler(siteId).ifPresent(git -> {
                git.enqueueDeploy("manual");
                ActivityLog.record(Models.get(SiteModel.class), siteId, "deploy_triggered", null);
            });
            return redirectUntyped(deploymentsPageUrl(conduit, siteId));
        });

        HohenheimEndpoints.SITES_DEPLOY_CANCEL.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            gitHandler(siteId).ifPresent(git -> {
                if (git.cancelCurrentDeploy()) {
                    ActivityLog.record(Models.get(SiteModel.class), siteId, "deploy_cancelled", null);
                }
            });
            return redirectUntyped(deploymentsPageUrl(conduit, siteId));
        });

        HohenheimEndpoints.SITES_ROLLBACK.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            gitHandler(siteId).ifPresent(git -> {
                git.enqueueRollback();
                ActivityLog.record(Models.get(SiteModel.class), siteId, "rollback_triggered", null);
            });
            return redirectUntyped(deploymentsPageUrl(conduit, siteId));
        });
    }

    private static String deploymentsPageUrl(Conduit conduit, Integer siteId) {
        return ReturnTarget.or(ReturnTarget.read(conduit),
            "/admin/sites/" + siteId + "/page/deployments");
    }

    private static void initDevTunnel() {
        HohenheimEndpoints.DEV_TUNNEL.setHandlerFactory(DevTunnelServerHandler::new);
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
            return new ProcessTerminalHandler(session, siteId, proc, ipc);
        });
    }

    // -----------------------------------------------------------------------
    // Shared plumbing.
    // -----------------------------------------------------------------------

    /** Site-scoped gate: admin or a manage grant on THIS site; true = 403 already written. */
    private static boolean refusedSiteAccess(Conduit conduit, Integer siteId) {
        if (siteId != null && HohenheimAccess.canManageSite(conduit, siteId)) {
            return false;
        }
        conduit.forbidden();
        return true;
    }

    private static Optional<ManagedProcessSiteHandler> managedHandler(Integer siteId) {
        return Optional.ofNullable(SiteHandlers.managedProcess(siteId));
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

    private static String submittedString(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value instanceof List<?> list) {
            value = list.isEmpty() ? null : list.get(0);
        }
        return value == null ? "" : String.valueOf(value).trim();
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
