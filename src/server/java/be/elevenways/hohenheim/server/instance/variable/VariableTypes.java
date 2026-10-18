package be.elevenways.hohenheim.server.instance.variable;

import be.elevenways.hohenheim.instance.VariableTypeRegistry;
import be.elevenways.protoblast.common.registry.Identifier;

/**
 * Registration hook for the compile-time-discovered variable types plus the server-side
 * handler lookup (the InstanceKinds shape). Nothing is registered manually.
 *
 * AIDEV-NOTE: a handler is read out of THE registry, never a private handler map beside it;
 * an entry that is not a server handler fails closed as "unknown type".
 */
public final class VariableTypes {

    private VariableTypes() {}

    /** Compile-time discovery hook (BlastAutoLoadInit). */
    public static void register(VariableTypeHandler handler) {
        VariableTypeRegistry.REGISTRY.add(handler.typeId(), handler);
    }

    public static VariableTypeHandler getHandler(String typeIdentifier) {
        if (typeIdentifier == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(typeIdentifier);
        return id != null && VariableTypeRegistry.registry().get(id) instanceof VariableTypeHandler handler
            ? handler : null;
    }
}
