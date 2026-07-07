package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Let's Encrypt request form, reached from the certificate list header. The
 * POST is the host-declared CERTIFICATES_REQUEST endpoint.
 */
public final class CertificateRequestPage extends PanelPage {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "certificates_request"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.certificate.request_le"); }
    @Override public @NonNull String slug() { return "certificates-request"; }
    @Override public @NonNull Icon icon() { return Icon.of("lock"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit, @NonNull AccessContext accessContext) {
        Map<String, Object> vars = new HashMap<>();
        String error = conduit.getQueryParam("error");
        vars.put("title", "Request Let's Encrypt certificate");
        vars.put("error", error != null ? error : "");
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/certificate-request"), vars);
    }
}
