package be.elevenways.hohenheim.server.instance.variable;

import be.elevenways.hohenheim.instance.VariableTypeRegistry;
import be.elevenways.protoblast.common.registry.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Registration hook for the compile-time-discovered variable types plus the server-side
 * handler map (the InstanceKinds shape). Nothing is registered manually.
 */
public final class VariableTypes {

    private static final Map<Identifier, VariableTypeHandler> HANDLERS = new HashMap<>();

    /**
     * Entries arrive via the generated BlastAutoLoadInit; force it so lookups work
     * regardless of which class the JVM touched first. MUST be the LAST static field:
     * the loader re-enters register() while this class is mid init and needs HANDLERS
     * assigned.
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private VariableTypes() {}

    /** Compile-time discovery hook (BlastAutoLoadInit). */
    public static void register(VariableTypeHandler handler) {
        Identifier id = handler.typeId();
        VariableTypeRegistry.REGISTRY.add(id, handler);
        HANDLERS.put(id, handler);
    }

    public static VariableTypeHandler getHandler(String typeIdentifier) {
        if (typeIdentifier == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(typeIdentifier);
        return id != null ? HANDLERS.get(id) : null;
    }
}
