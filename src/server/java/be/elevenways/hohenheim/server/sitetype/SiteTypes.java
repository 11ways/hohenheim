package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.server.process.SiteApiKeys;
import be.elevenways.hohenheim.server.sitetype.types.NodeSiteType;
import be.elevenways.hohenheim.sitetype.SiteTypeRegistry;
import be.elevenways.protoblast.common.registry.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Registration hook for the compile-time-discovered site types plus the
 * server-side handler map. Concrete SiteTypeHandler implementations arrive
 * via the generated BlastAutoLoadInit (see the @BlastDiscoverable on the
 * interface); nothing is registered manually.
 */
public class SiteTypes {

    private static final Map<Identifier, SiteTypeHandler> HANDLERS = new HashMap<>();

    /**
     * Entries arrive via the generated BlastAutoLoadInit; force it so lookups
     * work regardless of which class the JVM touched first. MUST be the LAST
     * static field: the loader re-enters register() while this class is mid
     * init and needs HANDLERS assigned.
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    /** Compile-time discovery hook (BlastAutoLoadInit). */
    public static void register(SiteTypeHandler handler) {
        Identifier id = handler.typeId();
        SiteTypeRegistry.REGISTRY.add(id, handler);
        HANDLERS.put(id, handler);
    }

    /** One-time boot of the shared process-management infrastructure. */
    public static void boot() {
        NodeSiteType.initSharedInfrastructure();
        // Before any site row can be written: no plaintext api key reaches the datasource.
        SiteApiKeys.install();
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
