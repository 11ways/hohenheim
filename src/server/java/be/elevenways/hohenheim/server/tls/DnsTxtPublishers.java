package be.elevenways.hohenheim.server.tls;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime registry for DNS-01 publishers, including future internal DNS. */
public final class DnsTxtPublishers {

    public static final DnsTxtPublishers INSTANCE = new DnsTxtPublishers();

    private final Map<String, DnsTxtPublisher> publishers = new ConcurrentHashMap<>();

    private DnsTxtPublishers() {}

    public void register(@NonNull DnsTxtPublisher publisher) {
        this.publishers.put(publisher.id(), publisher);
    }

    public @Nullable DnsTxtPublisher get(@Nullable String id) {
        return id != null ? this.publishers.get(id) : null;
    }
}
