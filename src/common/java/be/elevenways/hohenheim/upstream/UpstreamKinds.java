package be.elevenways.hohenheim.upstream;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;

/**
 * THE declaring home of a site's upstream vocabulary: what a hostname resolves TO. Drives
 * SiteModel's RegistryEnumField, its {@code schemaFrom} settings, the admin selector and
 * the proxy's handler dispatch.
 *
 * AIDEV-NOTE: renamed from SiteTypeRegistry on 2026-08-22 (phase-0 design section 3) because
 * the old vocabulary conflated two questions -- "where do requests go" (static, redirect,
 * address, instance, tls passthrough, dev namespace) and "what workload runs" (docker, node,
 * java, command, alchemy). The second question moved to the INSTANCE kind, so a site now has
 * exactly one typed upstream and no opinion about how the thing upstream is run.
 */
public class UpstreamKinds {

    public static final Registry<UpstreamKindInfo> REGISTRY =
        new Registry.Simple<>(Identifier.of("hohenheim", "upstream_kinds"));

    /**
     * Entries arrive via the generated BlastAutoLoadInit (UpstreamKindHandler is
     * discoverable); force it so direct REGISTRY consumers see the entries.
     * MUST be the LAST static field (re-entrant init reads REGISTRY above).
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private UpstreamKinds() {}
}
