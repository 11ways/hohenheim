package be.elevenways.hohenheim.upstream;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Common upstream-kind metadata. Lives in src/common so the admin UI (client) can
 * enumerate kinds without server/Undertow dependencies; icon and color facets ride the
 * typed TypeDefinition contract.
 */
public interface UpstreamKindInfo extends TypeDefinition {

    /** @return the registry identifier; its string form is the stored column value */
    @NonNull Identifier typeId();

    /** Short description shown in the upstream selector UI. */
    @NonNull Microcopy getDescription();

    /**
     * Whether this kind resolves to an INSTANCE record, which is what makes
     * {@code sites.instance_id} required (and, for every other kind, forbidden).
     *
     * AIDEV-NOTE: a declaration on the kind, not an id comparison in the write hook, for
     * the reason InstanceKindHandler.generatedOnly records: a hook naming one id needs
     * editing the day a second instance-backed kind lands, and the two would then be one
     * edit apart from disagreeing.
     */
    default boolean requiresInstance() {
        return false;
    }
}
