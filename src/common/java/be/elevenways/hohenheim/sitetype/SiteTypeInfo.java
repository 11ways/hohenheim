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
     * injected environment variables. True for host-process types (the process dials the
     * database's published loopback port) AND for the Docker site type (the container
     * joins each attached database's link network and dials it by container hostname);
     * proxy/redirect/static types run no workload of their own.
     */
    default boolean supportsEnvInjection() {
        return false;
    }

    /**
     * Whether this type's runtime is a CONTAINER rather than a host process. Drives the
     * attachment rules: a container site needs its database on the SAME server (it joins
     * the database's link network there), while a host-process site needs it on the
     * local server (it dials the published loopback port on the controller host).
     */
    default boolean containerRuntime() {
        return false;
    }
}
