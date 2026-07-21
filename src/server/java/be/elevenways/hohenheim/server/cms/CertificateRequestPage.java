package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.dns.InternalDnsTxtPublisher;
import be.elevenways.hohenheim.server.tls.CommandDnsTxtPublisher;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Let's Encrypt request form, reached from the certificate list header. The
 * POST is the host-declared CERTIFICATES_REQUEST endpoint.
 */
public final class CertificateRequestPage extends PanelPage {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "certificates_request"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("request_le").withFilter("scope", "certificate"); }
    @Override public @NonNull String slug() { return "certificates-request"; }
    @Override public @NonNull Icon icon() { return Icon.of("lock"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit, @NonNull AccessContext accessContext) {
        Map<String, Object> vars = new HashMap<>();
        String error = conduit.getQueryParam("error");
        vars.put("title", Microcopy.of("request_certificate")
            .withFilter("scope", "certificate_request")
            .resolve(conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("error", error != null ? error : "");
        vars.put("dnsHookConfigured", CommandDnsTxtPublisher.isConfigured());
        var dnsServer = ServerMain.getDnsServer();
        vars.put("internalDnsAvailable", dnsServer != null && dnsServer.isRunning()
            && new InternalDnsTxtPublisher().hasZones());
        vars.put("manualToken", "");
        vars.put("dnsRecords", List.of());
        String manualToken = conduit.getQueryParam("manual");
        var proxy = ServerMain.getProxyServer();
        if (manualToken != null && proxy != null) {
            AcmeService.ManualDnsRequest manual = proxy.getAcmeService().manualDnsRequest(manualToken);
            if (manual != null) {
                vars.put("manualToken", manual.token());
                vars.put("dnsRecords", manual.records().stream()
                    .map(record -> Map.<String, Object>of(
                        "name", record.name(),
                        "value", record.value()))
                    .toList());
            } else {
                vars.put("error", Microcopy.of("manual_expired")
                    .withFilter("scope", "certificate_request_error")
                    .resolve(conduit.getLocales(), conduit.getMessageResolver()));
            }
        }
        List<String> domains = prefillFromSite(conduit, vars);
        vars.put("domainForm", CertificateRequestForm.state(accessContext, domains));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/certificate-request"), vars);
    }

    /** A ?site= link (the site's Domains tab) prefills the LE-eligible exact hostnames. */
    private static @NonNull List<String> prefillFromSite(@NonNull Conduit conduit,
                                                         @NonNull Map<String, Object> vars) {
        vars.put("niceName", "");
        String siteParam = conduit.getQueryParam("site");
        if (siteParam == null || siteParam.isEmpty()) {
            return List.of();
        }
        int siteId;
        try {
            siteId = Integer.parseInt(siteParam);
        } catch (NumberFormatException invalid) {
            return List.of();
        }
        Row site = Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId)).first();
        if (site == null) {
            return List.of();
        }
        List<String> hostnames = new ArrayList<>();
        for (Row domain : Models.get(SiteDomainModel.class).findBySiteId(siteId)) {
            if (!SiteDomainModel.MATCH_EXACT.equals(domain.get(SiteDomainModel.MATCH_TYPE))
                || Boolean.TRUE.equals(domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT))) {
                continue;
            }
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            if (hostname != null && !hostname.isEmpty()) {
                hostnames.add(hostname);
            }
        }
        vars.put("niceName", String.valueOf(site.get(SiteModel.NAME)));
        return List.copyOf(hostnames);
    }
}
