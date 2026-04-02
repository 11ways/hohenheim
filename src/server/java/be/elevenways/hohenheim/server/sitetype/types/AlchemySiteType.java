package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.util.List;
import java.util.Map;

/**
 * Manages Alchemy (Node.js framework) child processes.
 * Extends NodeSiteType with --stream-janeway default arg and wait_for_ready=true.
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
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        // Default wait_for_ready to true if not explicitly set
        if (!settings.containsKey("wait_for_ready")) {
            settings = new java.util.HashMap<>(settings);
            settings.put("wait_for_ready", true);
        }
        return super.createHandler(site, settings);
    }
}
