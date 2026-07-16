package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DeploymentModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.TaskStatus;
import be.elevenways.zenit.common.task.orm.SystemTaskHistoryModel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gathers the dashboard attention items: certificates in error, sites whose
 * live handler reports DOWN/DEGRADED, failed managed databases, git sites
 * whose LATEST deploy failed, and scheduled tasks whose latest run failed.
 * Server reachability is deliberately NOT probed here (it would SSH/dial
 * every host per dashboard render); the Servers list owns that.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class AttentionCollector {

    private AttentionCollector() {}

    public static @NonNull List<Map<String, Object>> collect() {
        List<Map<String, Object>> items = new ArrayList<>();
        errorCertificates(items);
        unhealthySites(items);
        failedDatabases(items);
        failedDeployments(items);
        failedTasks(items);
        return items;
    }

    private static void errorCertificates(List<Map<String, Object>> items) {
        List<Row> rows = Models.get(CertificateModel.class).find()
            .where(CertificateModel.STATUS.eq(CertificateModel.STATUS_ERROR))
            .all();
        for (Row row : rows) {
            items.add(item("error", "certificate",
                "Certificate " + row.get(CertificateModel.NICE_NAME),
                stringOrEmpty(row.get(CertificateModel.RENEWAL_ERROR)),
                "/admin/certificates/" + row.get(CertificateModel.ID)));
        }
    }

    private static void unhealthySites(List<Map<String, Object>> items) {
        var proxy = ServerMain.getProxyServer();
        if (proxy == null) {
            return;
        }
        List<Row> sites = Models.get(SiteModel.class).find()
            .where(SiteModel.ENABLED.eq(true))
            .where(SiteModel.DELETED_AT.isNull())
            .all();
        for (Row site : sites) {
            Integer siteId = site.get(SiteModel.ID);
            if (siteId == null) {
                continue;
            }
            SiteRequestHandler handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
            SiteHealth health = handler != null ? handler.getHealth() : null;
            if (health == SiteHealth.DOWN || health == SiteHealth.DEGRADED) {
                items.add(item(health == SiteHealth.DOWN ? "error" : "warning", "globe",
                    "Site " + site.get(SiteModel.NAME),
                    health == SiteHealth.DOWN ? "Down" : "Degraded",
                    "/admin/sites/" + siteId));
            }
        }
    }

    private static void failedDatabases(List<Map<String, Object>> items) {
        List<Row> rows = Models.get(DatabaseModel.class).find()
            .where(DatabaseModel.STATUS.eq(DatabaseModel.STATUS_FAILED))
            .all();
        for (Row row : rows) {
            items.add(item("error", "database",
                "Database " + row.get(DatabaseModel.NAME),
                "Provisioning failed",
                "/admin/databases/" + row.get(DatabaseModel.ID)));
        }
    }

    private static void failedDeployments(List<Map<String, Object>> items) {
        var siteModel = Models.get(SiteModel.class);
        var deployModel = Models.get(DeploymentModel.class);
        List<Row> gitSites = siteModel.find()
            .where(SiteModel.ENABLED.eq(true))
            .where(SiteModel.DELETED_AT.isNull())
            .where(SiteModel.SOURCE.eq(SiteModel.SOURCE_GIT))
            .all();
        for (Row site : gitSites) {
            Integer siteId = site.get(SiteModel.ID);
            if (siteId == null) {
                continue;
            }
            List<Row> latest = deployModel.findBySiteId(siteId, 1);
            if (latest.isEmpty()) {
                continue;
            }
            Row deploy = latest.get(0);
            if (DeploymentModel.STATUS_FAILED.equals(deploy.get(DeploymentModel.STATUS))) {
                items.add(item("error", "rocket",
                    "Deploy of " + site.get(SiteModel.NAME),
                    stringOrEmpty(deploy.get(DeploymentModel.ERROR)),
                    "/admin/sites/" + siteId + "/page/deployments"));
            }
        }
    }

    /** Latest history row per task type; failed ones surface (no task UI yet, so no url). */
    private static void failedTasks(List<Map<String, Object>> items) {
        // The task system registers its datasource-scoped model at its own boot
        // stage; a boot without it (tests, tools) simply has no task news.
        if (Models.get(SystemTaskHistoryModel.MODEL_ID) == null) {
            return;
        }
        List<Row> recent = Models.get(SystemTaskHistoryModel.class).find()
            .orderBy(SystemTaskHistoryModel.STARTED_AT, be.elevenways.zenit.common.orm.query.SortOrder.DESC)
            .limit(200)
            .all();
        Map<String, Row> latestPerType = new LinkedHashMap<>();
        for (Row row : recent) {
            String type = row.get(SystemTaskHistoryModel.TASK_TYPE);
            if (type != null) {
                latestPerType.putIfAbsent(type, row);
            }
        }
        Set<String> failed = new LinkedHashSet<>();
        for (Map.Entry<String, Row> entry : latestPerType.entrySet()) {
            if (TaskStatus.FAILED.name().equals(entry.getValue().get(SystemTaskHistoryModel.STATUS))) {
                failed.add(entry.getKey());
            }
        }
        for (String type : failed) {
            items.add(item("warning", "clock",
                "Task " + type,
                "Last run failed",
                null));
        }
    }

    private static @NonNull Map<String, Object> item(String severity, String icon, String title,
                                                     String detail, @Nullable String url) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("severity", severity);
        map.put("icon", icon);
        map.put("title", title);
        map.put("detail", detail);
        map.put("url", url != null ? url : "");
        return map;
    }

    private static @NonNull String stringOrEmpty(@Nullable Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
