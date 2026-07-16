package be.elevenways.hohenheim.sitetype;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Common site type metadata. Lives in src/common so admin UI (client) can
 * access type information without server/Undertow dependencies.
 * Icon and color facets ride the typed TypeDefinition contract.
 */
public interface SiteTypeInfo extends TypeDefinition {

    /** @return the registry identifier; its string form is the stored column value */
    @NonNull Identifier typeId();

    /**
     * Short description shown in the type selector UI.
     */
    String getDescription();

    /**
     * Whether sites of this type can receive managed-database connection details as
     * injected environment variables. True only for types whose runtime is a process
     * spawned on the host itself: a Docker-site container cannot reach a database's
     * 127.0.0.1-published host port, and proxy/redirect/static types run no process.
     */
    default boolean supportsEnvInjection() {
        return false;
    }
}
