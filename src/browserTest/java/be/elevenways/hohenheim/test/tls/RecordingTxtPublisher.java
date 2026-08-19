package be.elevenways.hohenheim.test.tls;

import be.elevenways.hohenheim.server.tls.DnsTxtPublisher;
import be.elevenways.hohenheim.server.tls.DnsTxtRecord;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The DNS-01 publisher of the ACME contract tests: records what was published and what was
 * removed, so an order can be asserted on the values a CA would really look up.
 *
 * The id is a constructor argument because {@link be.elevenways.hohenheim.server.tls.DnsTxtPublishers}
 * is one process-wide registry: two test classes sharing a fork would otherwise take turns
 * overwriting each other's entry and assert against the wrong recorder.
 */
public final class RecordingTxtPublisher implements DnsTxtPublisher {

    public final List<DnsTxtRecord> published = new CopyOnWriteArrayList<>();
    public final List<DnsTxtRecord> removed = new CopyOnWriteArrayList<>();

    private final String id;

    public RecordingTxtPublisher(@NonNull String id) {
        this.id = id;
    }

    @Override
    public @NonNull String id() {
        return this.id;
    }

    @Override
    public void publish(@NonNull DnsTxtRecord record) {
        this.published.add(record);
    }

    @Override
    public void cleanup(@NonNull DnsTxtRecord record) {
        this.removed.add(record);
    }

    @Override
    public boolean servesImmediately() {
        return true;
    }

    /** The value published for one record name, or null. */
    public String valueOf(String name) {
        for (DnsTxtRecord record : this.published) {
            if (record.name().equals(name)) {
                return record.value();
            }
        }
        return null;
    }
}
