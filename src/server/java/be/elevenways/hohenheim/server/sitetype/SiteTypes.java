package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.server.sitetype.types.AlchemySiteType;
import be.elevenways.hohenheim.server.sitetype.types.DeadSiteType;
import be.elevenways.hohenheim.server.sitetype.types.NodeSiteType;
import be.elevenways.hohenheim.server.sitetype.types.ProxySiteType;
import be.elevenways.hohenheim.server.sitetype.types.RedirectSiteType;
import be.elevenways.hohenheim.server.sitetype.types.StaticSiteType;
import be.elevenways.hohenheim.sitetype.SiteTypeRegistry;
import be.elevenways.protoblast.common.registry.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers all built-in site types and maintains the server-side handler map.
 */
public class SiteTypes {

    private static final Map<Identifier, SiteTypeHandler> HANDLERS = new HashMap<>();

    public static void register() {
        registerType(ProxySiteType.ID, new ProxySiteType());
        registerType(StaticSiteType.ID, new StaticSiteType());
        registerType(RedirectSiteType.ID, new RedirectSiteType());
        registerType(DeadSiteType.ID, new DeadSiteType());
        registerType(NodeSiteType.ID, new NodeSiteType());
        registerType(AlchemySiteType.ID, new AlchemySiteType());

        // Initialize shared process management infrastructure
        NodeSiteType.initSharedInfrastructure();
    }

    private static void registerType(Identifier id, SiteTypeHandler handler) {
        SiteTypeRegistry.REGISTRY.add(id, handler);
        HANDLERS.put(id, handler);
    }

    public static SiteTypeHandler getHandler(String typeIdentifier) {
        if (typeIdentifier == null) return null;
        Identifier id = Identifier.tryParse(typeIdentifier);
        return id != null ? HANDLERS.get(id) : null;
    }

    public static SiteTypeHandler getHandler(Identifier id) {
        return HANDLERS.get(id);
    }
}
