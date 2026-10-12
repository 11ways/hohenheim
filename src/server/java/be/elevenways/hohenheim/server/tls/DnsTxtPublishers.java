package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.registry.KeyedRegistry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Runtime registry for DNS-01 publishers, including future internal DNS, keyed by the id the
 * {@code ssl.dns_publisher} setting stores.
 *
 * AIDEV-NOTE: an id has one publisher: another publisher under a taken id is a RegistryConflictException, never a
 * silent overwrite that would send a certificate's challenge to whichever registered last. Built-ins register one
 * constant instance each (AcmeService), so a second AcmeService repeats them.
 */
public final class DnsTxtPublishers {

    public static final DnsTxtPublishers INSTANCE = new DnsTxtPublishers();

    private final KeyedRegistry<String, DnsTxtPublisher> publishers =
        KeyedRegistry.create(HohenheimIds.id("dns_txt_publisher"));

    private DnsTxtPublishers() {}

    /**
     * @throws be.elevenways.protoblast.common.registry.RegistryConflictException when another publisher holds the id
     */
    public void register(@NonNull DnsTxtPublisher publisher) {
        this.publishers.add(publisher.id(), publisher);
    }

    public @Nullable DnsTxtPublisher get(@Nullable String id) {
        return this.publishers.get(id);
    }
}
