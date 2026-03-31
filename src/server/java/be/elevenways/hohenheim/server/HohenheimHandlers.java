package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.server.http.HttpConduit;
import be.elevenways.zenit.server.http.RedirectResult;

import java.util.*;

/**
 * Server-side request handlers for Hohenheim endpoints.
 */
public class HohenheimHandlers {

    @SuppressWarnings("unchecked")
    private static ActionResult<Object> render(Identifier templateId, Map<String, Object> vars) {
        return (ActionResult<Object>) (ActionResult<?>) new RenderTemplateResult(templateId, vars);
    }

    @SuppressWarnings("unchecked")
    private static ActionResult<Object> redirect(String url) {
        return (ActionResult<Object>) (ActionResult<?>) new RedirectResult(url);
    }

    public static void init() {
        var ds = HohenheimDatabase.datasource();
        var siteModel = new SiteModel(ds);
        var domainModel = new SiteDomainModel(ds);
        var certModel = new CertificateModel(ds);

        // Dashboard
        HohenheimEndpoints.DASHBOARD.setHandler(conduit -> {
            int siteCount = (int) siteModel.find().where(SiteModel.DELETED_AT.isNull()).count();
            int certCount = (int) certModel.find().count();

            return render(
                Identifier.of("hohenheim", "hohenheim/dashboard"),
                Map.of("siteCount", siteCount, "certCount", certCount)
            );
        });

        // Sites list
        HohenheimEndpoints.SITES_LIST.setHandler(conduit -> {
            List<Row> rows = siteModel.find()
                .where(SiteModel.DELETED_AT.isNull())
                .orderBy(SiteModel.CREATED_AT, SortOrder.DESC)
                .all();

            List<Map<String, Object>> sites = new ArrayList<>();
            for (Row row : rows) {
                List<Row> domains = domainModel.findBySiteId(
                    String.valueOf(row.get(SiteModel.ID))
                );

                Map<String, Object> site = new HashMap<>();
                site.put("id", row.get(SiteModel.ID));
                site.put("name", row.get(SiteModel.NAME));
                site.put("siteType", row.get(SiteModel.SITE_TYPE));
                site.put("status", row.get(SiteModel.STATUS));
                site.put("enabled", row.get(SiteModel.ENABLED));
                site.put("domainCount", domains.size());
                sites.add(site);
            }

            return render(
                Identifier.of("hohenheim", "hohenheim/sites/list"),
                Map.of("sites", sites, "siteCount", sites.size())
            );
        });

        // Site create (POST)
        HohenheimEndpoints.SITES_CREATE.setHandler(conduit -> {
            HttpConduit http = (HttpConduit) conduit;
            Map<String, String> form = http.getFormData().toStringMap();

            String name = form.getOrDefault("name", "").trim();
            String siteType = form.getOrDefault("site_type", "proxy");

            if (name.isEmpty()) {
                return render(
                    Identifier.of("hohenheim", "hohenheim/sites/create"),
                    Map.of("error", "Name is required")
                );
            }

            String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");

            Row row = siteModel.createEmptyRow();
            row.set(SiteModel.NAME, name);
            row.set(SiteModel.SLUG, slug);
            row.set(SiteModel.SITE_TYPE, siteType);
            siteModel.save(row);

            return redirect("/sites");
        });

        // Site create form (GET)
        HohenheimEndpoints.SITES_CREATE_FORM.setHandler(conduit -> {
            return render(
                Identifier.of("hohenheim", "hohenheim/sites/create"),
                Map.of("error", "")
            );
        });

        // Certificates list
        HohenheimEndpoints.CERTIFICATES_LIST.setHandler(conduit -> {
            List<Row> rows = certModel.find()
                .orderBy(CertificateModel.CREATED_AT, SortOrder.DESC)
                .all();

            List<Map<String, Object>> certs = new ArrayList<>();
            for (Row row : rows) {
                Map<String, Object> cert = new HashMap<>();
                cert.put("id", row.get(CertificateModel.ID));
                cert.put("niceName", row.get(CertificateModel.NICE_NAME));
                cert.put("provider", row.get(CertificateModel.PROVIDER));
                cert.put("expiresOn", row.get(CertificateModel.EXPIRES_ON));
                cert.put("autoRenew", row.get(CertificateModel.AUTO_RENEW));
                certs.add(cert);
            }

            return render(
                Identifier.of("hohenheim", "hohenheim/certificates/list"),
                Map.of("certificates", certs)
            );
        });
    }
}
