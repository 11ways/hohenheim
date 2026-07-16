package be.elevenways.hohenheim.server.tls;

import org.checkerframework.checker.nullness.qual.NonNull;

/** Publishes and removes ACME DNS-01 TXT values. */
public interface DnsTxtPublisher {

    @NonNull String id();

    void publish(@NonNull DnsTxtRecord record) throws Exception;

    void cleanup(@NonNull DnsTxtRecord record) throws Exception;
}
