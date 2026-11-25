package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.upstream.kinds.StreamUpstreamKind;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * TCP/UDP stream sites saved through the model, so each save claims and frees its ports in the ledger as a panel
 * save does.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class StreamSiteRows {

    private StreamSiteRows() {
    }

    /**
     * @param ports the public ports by listen port, each spelled as the site stores it ({@code 587/tcp})
     * @return the stored site's id
     */
    public static int save(@NonNull String name, @NonNull Map<String, String> ports, boolean enabled) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, name);
        row.set(SiteModel.UPSTREAM_KIND, StreamUpstreamKind.ID.toString());
        row.set(SiteModel.SETTINGS, Map.of(StreamUpstreamKind.FORWARD_HOST.getName(), "10.0.0.20",
            StreamUpstreamKind.PORTS.getName(), ports));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, enabled);
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }

    /** Switches a stored stream site on or off, as the site form's switch does. */
    public static void switchTo(int siteId, boolean enabled) {
        Row row = Models.get(SiteModel.class).findById(siteId);
        row.set(SiteModel.ENABLED, enabled);
        Models.get(SiteModel.class).save(row);
    }
}
