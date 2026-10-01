package be.elevenways.hohenheim.server.upstream;

import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.registry.Identifier;

/**
 * Registration hook for the compile-time-discovered upstream kinds plus the server-side
 * handler lookup. Concrete UpstreamKindHandler implementations arrive via the generated
 * BlastAutoLoadInit (see the @BlastDiscoverable on the interface); nothing is registered
 * manually.
 *
 * AIDEV-NOTE: a handler is read out of THE registry ({@link UpstreamKinds#REGISTRY}), never a
 * private handler map beside it (two homes for one vocabulary); an entry that is not a server
 * handler fails closed as "unknown kind", as in BackupTargetKinds.
 */
public class UpstreamKindHandlers {

    /**
     * Entries arrive via the generated BlastAutoLoadInit; force it so lookups
     * work regardless of which class the JVM touched first. MUST be the LAST
     * static field.
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    /** Compile-time discovery hook (BlastAutoLoadInit). */
    public static void register(UpstreamKindHandler handler) {
        UpstreamKinds.REGISTRY.add(handler.typeId(), handler);
    }

    public static UpstreamKindHandler getHandler(String typeIdentifier) {
        if (typeIdentifier == null) return null;
        Identifier id = Identifier.tryParse(typeIdentifier);
        return id != null ? getHandler(id) : null;
    }

    public static UpstreamKindHandler getHandler(Identifier id) {
        return UpstreamKinds.REGISTRY.get(id) instanceof UpstreamKindHandler handler ? handler : null;
    }
}
