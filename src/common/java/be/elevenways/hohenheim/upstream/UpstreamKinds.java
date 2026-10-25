package be.elevenways.hohenheim.upstream;

import be.elevenways.hohenheim.HohenheimIds;
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
 * AIDEV-NOTE: an autoloaded Registry: every read runs the index first, so {@link #REGISTRY} and {@link #registry()}
 * are the same object and no accessor calls Blast.ensureAutoLoaded.
 */
public class UpstreamKinds {

    public static final Registry<UpstreamKindInfo> REGISTRY =
        Registry.autoLoaded(HohenheimIds.id("upstream_kind"));

    private UpstreamKinds() {}

    /** @return the live registry */
    public static @NonNull Registry<UpstreamKindInfo> registry() {
        return REGISTRY;
    }
}
