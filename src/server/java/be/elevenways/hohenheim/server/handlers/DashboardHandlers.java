package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.RenderTemplateResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard request handler.
 */
public final class DashboardHandlers {

    private DashboardHandlers() {
    }

    public static void init() {
        SiteModel siteModel = Models.get(SiteModel.class);
        CertificateModel certModel = Models.get(CertificateModel.class);
        AuditLogModel auditModel = Models.get(AuditLogModel.class);

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
                activity.add(HandlerSupport.auditEntryToMap(row));
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
}
