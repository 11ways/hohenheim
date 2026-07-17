package be.elevenways.hohenheim.server.tls;

import org.checkerframework.checker.nullness.qual.NonNull;

/** Publishes and removes ACME DNS-01 TXT values. */
public interface DnsTxtPublisher {

    @NonNull String id();

    void publish(@NonNull DnsTxtRecord record) throws Exception;

    void cleanup(@NonNull DnsTxtRecord record) throws Exception;

    /** @return true when published values are queryable the moment publish() returns (skips the propagation wait) */
    default boolean servesImmediately() {
        return false;
    }
}
