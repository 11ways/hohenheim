package be.elevenways.hohenheim.upstream;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.WordedKind;
import be.elevenways.hohenheim.app.PutOnlineGroup;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.ports.PublishedPort;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Common upstream-kind metadata. Lives in src/common so the admin UI (client) can
 * enumerate kinds without server/Undertow dependencies; icon and color facets ride the
 * typed TypeDefinition contract.
 */
public interface UpstreamKindInfo extends WordedKind {

    @Override
    default @NonNull HohenheimMicrocopy labelScope() {
        return HohenheimMicrocopy.UPSTREAM_KIND;
    }

    @Override
    default @NonNull HohenheimMicrocopy descriptionScope() {
        return HohenheimMicrocopy.UPSTREAM_KIND_DESCRIPTION;
    }

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

    /**
     * Whether "Put something online" offers this kind as an app of its own: an address that needs no workload.
     *
     * AIDEV-NOTE: a kind that serves an instance is put online through that instance's own flow, and a kind other
     * machinery creates (a dev session's namespace) is never offered; both answer here, never in the page's list.
     */
    default boolean offeredAsApp() {
        return !this.requiresInstance();
    }

    /** The "Put something online" group this kind is offered under; null when it is not {@link #offeredAsApp}. */
    default @Nullable PutOnlineGroup putOnlineGroup() {
        return this.offeredAsApp() ? PutOnlineGroup.ADDRESS : null;
    }

    /**
     * How a site of this kind is reached from outside, which decides the steps "Put something online" asks: a website
     * an address and HTTPS, a stream the public port it listens on.
     */
    default @NonNull PutOnlineReach putOnlineReach() {
        return PutOnlineReach.WEBSITE;
    }

    /**
     * The public ports a site of this kind listens on itself, on the proxy's own host, read from its settings: each
     * is claimed in the port ledger like any workload's published port. A website listens on the proxy's shared
     * HTTP and HTTPS ports and claims none.
     *
     * @return the ports, the host port being the public one; empty when its settings name none yet
     */
    default @NonNull List<PublishedPort> proxyPorts(@NonNull Map<String, Object> settings) {
        return List.of();
    }
}
