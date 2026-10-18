package be.elevenways.hohenheim.upstream;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Registry;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE declaring home of a site's upstream vocabulary: what a hostname resolves TO. Drives
 * SiteModel's RegistryMemberField, its {@code schemaFrom} settings, the admin selector and
 * the proxy's handler dispatch.
 *
 * AIDEV-NOTE: renamed from SiteTypeRegistry on 2026-08-22 because
 * the old vocabulary conflated two questions -- "where do requests go" (static, redirect,
 * address, instance, tls passthrough, dev namespace) and "what workload runs" (docker, node,
 * java, command, alchemy). The second question moved to the INSTANCE kind, so a site now has
 * exactly one typed upstream and no opinion about how the thing upstream is run.
 *
 * AIDEV-NOTE: every read goes through {@link #registry()}; {@link #REGISTRY} itself is only for fields that hold
 * the registry object (a RegistryMemberField) and for the discovery hook's writes.
 */
public class UpstreamKinds {

    public static final Registry<UpstreamKindInfo> REGISTRY =
        Registry.create(HohenheimIds.id("upstream_kind"));

    private UpstreamKinds() {}

    /** Runs the autoload index first so every discovered member is registered. */
    public static @NonNull Registry<UpstreamKindInfo> registry() {
        Blast.ensureAutoLoaded();
        return REGISTRY;
    }
}
