package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.RenderTemplateResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain CRUD request handlers (nested under sites).
 */
public final class DomainHandlers {

    private DomainHandlers() {
    }

    /** @return the human message for an inline add-domain error token, or "" for none/unknown */
    static String addDomainErrorMessage(String token) {
        if (token == null) return "";
        return switch (token) {
            case "required" -> "A hostname is required to add a domain.";
            case "duplicate" -> "That hostname is already configured for this site.";
            default -> "";
        };
    }

    public static void init() {
        SiteModel siteModel = Models.get(SiteModel.class);
        SiteDomainModel domainModel = Models.get(SiteDomainModel.class);

        // Add domain (POST /sites/:id/domains)
        HohenheimEndpoints.SITES_ADD_DOMAIN.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Map<String, String> form = HandlerSupport.formMap(conduit);

            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();
            if (site == null) return HandlerSupport.redirectUntyped("/sites");

            String hostname = form.getOrDefault("hostname", "").trim();
            String matchType = form.getOrDefault("match_type", SiteDomainModel.MATCH_EXACT);

            if (hostname.isEmpty()) {
                return HandlerSupport.redirectUntyped("/sites/" + siteId + "?domain_error=required");
            }

            // Check for duplicate hostname on this site
            Row existing = domainModel.find()
                .where(SiteDomainModel.SITE_ID.eq(siteId))
                .where(SiteDomainModel.HOSTNAME.eq(hostname))
                .first();
            if (existing != null) {
                return HandlerSupport.redirectUntyped("/sites/" + siteId + "?domain_error=duplicate");
            }

            Row domainRow = domainModel.createEmptyRow();
            domainRow.set(SiteDomainModel.SITE_ID, siteId);
            domainRow.set(SiteDomainModel.HOSTNAME, hostname);
            domainRow.set(SiteDomainModel.MATCH_TYPE, matchType);
            domainModel.save(domainRow);

            HandlerSupport.audit(conduit, AuditLogModel.ACTION_CREATED, AuditLogModel.RESOURCE_DOMAIN,
                domainRow.get(SiteDomainModel.ID), hostname);
            HandlerSupport.reloadProxy();

            return HandlerSupport.redirectUntyped("/sites/" + siteId);
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
            if (site == null || domain == null) return HandlerSupport.redirectUntyped("/sites");

            return HandlerSupport.renderUntyped(
                Identifier.of("hohenheim", "hohenheim/sites/domain-edit"),
                buildDomainEditVars(site, domain, "")
            );
        });

        // Update domain (POST /sites/:id/domains/:domainId)
        HohenheimEndpoints.SITES_UPDATE_DOMAIN.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Integer domainId = conduit.getParameter(HohenheimEndpoints.DOMAIN_ID);
            Map<String, String> form = HandlerSupport.formMap(conduit);

            Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();
            Row domain = domainModel.find()
                .where(SiteDomainModel.ID.eq(domainId))
                .where(SiteDomainModel.SITE_ID.eq(siteId))
                .first();
            if (site == null || domain == null) return HandlerSupport.redirectUntyped("/sites");

            String hostname = form.getOrDefault("hostname", "").trim();
            if (hostname.isEmpty()) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/sites/domain-edit"),
                    buildDomainEditVars(site, domain, "Hostname is required")
                );
            }

            domain.set(SiteDomainModel.HOSTNAME, hostname);
            domain.set(SiteDomainModel.MATCH_TYPE, form.getOrDefault("match_type", SiteDomainModel.MATCH_EXACT));
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

            List<Map<String, String>> customHeaders = HandlerSupport.extractIndexedPairs(form, "header");
            domain.set(SiteDomainModel.CUSTOM_HEADERS, customHeaders.isEmpty() ? null : customHeaders);

            List<Map<String, String>> responseHeaders = HandlerSupport.extractIndexedPairs(form, "response_header");
            domain.set(SiteDomainModel.RESPONSE_HEADERS, responseHeaders.isEmpty() ? null : responseHeaders);

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
            HandlerSupport.audit(conduit, AuditLogModel.ACTION_UPDATED, AuditLogModel.RESOURCE_DOMAIN,
                domainId, hostname);
            HandlerSupport.reloadProxy();
            return HandlerSupport.redirectUntyped("/sites/" + siteId);
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
                HandlerSupport.audit(conduit, AuditLogModel.ACTION_DELETED, AuditLogModel.RESOURCE_DOMAIN,
                    domainId, hostname);
                HandlerSupport.reloadProxy();
            }

            return HandlerSupport.redirectUntyped("/sites/" + siteId);
        });
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
        vars.put("customHeaders", HandlerSupport.nameValuePairs(domain.get(SiteDomainModel.CUSTOM_HEADERS)));
        vars.put("responseHeaders", HandlerSupport.nameValuePairs(domain.get(SiteDomainModel.RESPONSE_HEADERS)));

        // Build certificate list for dropdown (excludes the internal ACME account row)
        var certModel = Models.get(CertificateModel.class);
        vars.put("certificates", HandlerSupport.certificateOptions(certModel.find().all()));

        vars.put("error", error);
        return vars;
    }
}
