package be.elevenways.hohenheim.server.upstream;

import be.elevenways.hohenheim.server.process.ProcessInfrastructure;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.registry.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Registration hook for the compile-time-discovered upstream kinds plus the server-side
 * handler map. Concrete UpstreamKindHandler implementations arrive via the generated
 * BlastAutoLoadInit (see the @BlastDiscoverable on the interface); nothing is registered
 * manually.
 */
public class UpstreamKindHandlers {

    private static final Map<Identifier, UpstreamKindHandler> HANDLERS = new HashMap<>();

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
    public static void register(UpstreamKindHandler handler) {
        Identifier id = handler.typeId();
        UpstreamKinds.REGISTRY.add(id, handler);
        HANDLERS.put(id, handler);
    }

    /**
     * One-time boot of the shared process-management infrastructure.
     *
     * AIDEV-NOTE: the model-funnel write hooks (SiteApiKeys, ReservedEnv, dyndns
     * token hashing, the site enable invariant) deliberately do NOT install here
     * anymore: they live in the discovered {@code HohenheimWriteHooks} ZenitModule,
     * whose MODULES boot stage runs before STARTHTTP structurally. Installing them
     * from an explicitly-ordered call site is what let the dyndns hook land AFTER
     * the server had bound.
     */
    public static void boot() {
        ProcessInfrastructure.init();
    }

    public static UpstreamKindHandler getHandler(String typeIdentifier) {
        if (typeIdentifier == null) return null;
        Identifier id = Identifier.tryParse(typeIdentifier);
        return id != null ? HANDLERS.get(id) : null;
    }

    public static UpstreamKindHandler getHandler(Identifier id) {
        return HANDLERS.get(id);
    }
}
