package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The pasteable URL of an endpoint the proxy intercepts before hostname routing (the git webhook, a build hook).
 *
 * AIDEV-NOTE: such an endpoint answers on ANY hostname pointing at the proxy, so the URL is a choice, not a route:
 * the first exact hostname of a TLS-terminating site exposing the instance that a visitor's handshake works for
 * (CertificateCoverage.handshakeNames), else the bare path. Never http: such a URL can carry what authenticates its
 * caller (a build hook's token), and an https URL on a name without a working certificate fails every handshake, so
 * a name that cannot do TLS yields no URL at all and the owner reads why on the Build hooks tab.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class HookUrls {

    private HookUrls() {
    }

    /** @return {@code path} over https on the instance's first exposing exact hostname HTTPS works for, else alone */
    public static @NonNull String forInstance(int instanceId, @NonNull String path) {
        List<Integer> siteIds = new ArrayList<>();
        for (Row site : Models.get(SiteModel.class).find().where(SiteModel.INSTANCE_ID.eq(instanceId)).all()) {
            if (SiteModel.terminatesTls(site.get(SiteModel.UPSTREAM_KIND))) {
                siteIds.add(site.get(SiteModel.ID));
            }
        }
        if (siteIds.isEmpty()) {
            return path;
        }
        Set<String> working = CertificateCoverage.handshakeNames();
        for (Row domain : Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.SITE_ID.in(siteIds))
                .where(SiteDomainModel.MATCH_TYPE.eq(SiteDomainModel.MATCH_EXACT))
                .orderBy(SiteDomainModel.ID, SortOrder.ASC).all()) {
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            if (CertificateCoverage.covers(working, hostname)) {
                return "https://" + hostname + path;
            }
        }
        return path;
    }
}
