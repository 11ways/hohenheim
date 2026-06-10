package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.sitetype.FaultedSiteHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.util.List;
import java.util.Map;

/**
 * Manages Alchemy (Node.js framework) child processes.
 */
public class AlchemySiteType extends NodeSiteType {

    public static final Identifier ID = Identifier.of("hohenheim", "alchemy");

    @Override
    public String getDisplayName() { return "Alchemy"; }

    @Override
    public String getDescription() { return "Manage Alchemy framework applications"; }

    @Override
    public String getIcon() { return "flask"; }

    @Override
    protected List<String> getDefaultArgs() {
        return List.of("--stream-janeway");
    }

    @Override
    protected boolean useChildWrapper() {
        return true;
    }

    // Alchemy apps always signal readiness via janeway, so each process is held out of the routing
    // pool until it reports ready by default (when the site settings don't specify wait_for_ready).
    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        int siteId = site.get(SiteModel.ID);
        String siteName = site.get(SiteModel.NAME);
        try {
            return new NodeProcessHandler(siteId, siteName, settings, getDefaultArgs(), useChildWrapper()) {
                @Override
                protected boolean defaultWaitForReady() {
                    return true;
                }
            };
        } catch (IllegalArgumentException e) {
            // Fail fast: a misconfigured site serves an explicit 503 instead of half-starting.
            return new FaultedSiteHandler(siteId, e.getMessage());
        }
    }
}
