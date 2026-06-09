package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deletes Let's Encrypt certificates whose hostnames no longer belong to any enabled site.
 * A certificate is orphaned only when ALL of its SAN hostnames are unlinked; user-uploaded
 * (custom) certificates and the ACME account row are never touched. Runs daily.
 */
public class CleanOrphanCertificates extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Delete orphaned Let's Encrypt certificates";

    public static List<ScheduleDeclaration> defaultSchedules() {
        return List.of(ScheduleDeclaration.fallback("45 4 * * *"));
    }

    @Override
    public void executor(TaskContext ctx) {
        clean();
    }

    /** Delete letsencrypt certs whose every SAN hostname is absent from enabled sites' domains. */
    public static void clean() {
        try {
            var certModel = Models.get(CertificateModel.class);
            Set<String> linkedHostnames = enabledSiteHostnames();

            List<Row> certs = certModel.find()
                .where(CertificateModel.PROVIDER.eq(CertificateModel.PROVIDER_LETSENCRYPT))
                .all();

            int deleted = 0;
            for (Row cert : certs) {
                String domainsText = cert.get(CertificateModel.DOMAIN_NAMES_TEXT);
                if (domainsText == null || domainsText.isBlank()) {
                    continue;
                }

                boolean anyLinked = false;
                for (String hostname : domainsText.split(",")) {
                    if (linkedHostnames.contains(hostname.trim().toLowerCase(Locale.ROOT))) {
                        anyLinked = true;
                        break;
                    }
                }

                if (!anyLinked) {
                    certModel.find().where(CertificateModel.ID.eq(cert.get(CertificateModel.ID))).delete();
                    deleted++;
                    Blast.log("TASK: CleanOrphanCertificates removed",
                        cert.get(CertificateModel.NICE_NAME), "(" + domainsText + ")");
                }
            }

            if (deleted > 0) {
                Blast.log("TASK: CleanOrphanCertificates removed", deleted, "orphaned certificates");
            }
        } catch (Exception e) {
            Blast.log("TASK: CleanOrphanCertificates failed:", e.getMessage());
        }
    }

    /** All hostnames of domains that belong to an enabled site. */
    private static Set<String> enabledSiteHostnames() {
        var siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);

        Set<Integer> enabledSiteIds = new HashSet<>();
        for (Row site : siteModel.find().where(SiteModel.ENABLED.eq(true)).all()) {
            enabledSiteIds.add(site.get(SiteModel.ID));
        }

        Set<String> hostnames = new HashSet<>();
        for (Row domain : domainModel.find().all()) {
            Integer siteId = domain.get(SiteDomainModel.SITE_ID);
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            if (siteId != null && hostname != null && enabledSiteIds.contains(siteId)) {
                hostnames.add(hostname.trim().toLowerCase(Locale.ROOT));
            }
        }
        return hostnames;
    }
}
