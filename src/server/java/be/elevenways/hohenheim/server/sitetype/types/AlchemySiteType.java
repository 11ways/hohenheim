package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.protoblast.common.registry.Identifier;

import java.util.List;

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

}
