package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.protoblast.common.registry.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Registration hook for the compile-time-discovered instance kinds plus the
 * server-side handler map (the SiteTypes shape). Concrete InstanceKindHandler
 * implementations arrive via the generated BlastAutoLoadInit; nothing is
 * registered manually.
 */
public final class InstanceKinds {

    private static final Map<Identifier, InstanceKindHandler> HANDLERS = new HashMap<>();

    /**
     * Entries arrive via the generated BlastAutoLoadInit; force it so lookups
     * work regardless of which class the JVM touched first. MUST be the LAST
     * static field: the loader re-enters register() while this class is mid
     * init and needs HANDLERS assigned.
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private InstanceKinds() {}

    /** Compile-time discovery hook (BlastAutoLoadInit). */
    public static void register(InstanceKindHandler handler) {
        Identifier id = handler.typeId();
        InstanceKindRegistry.REGISTRY.add(id, handler);
        HANDLERS.put(id, handler);
    }

    public static InstanceKindHandler getHandler(String typeIdentifier) {
        if (typeIdentifier == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(typeIdentifier);
        return id != null ? HANDLERS.get(id) : null;
    }
}
