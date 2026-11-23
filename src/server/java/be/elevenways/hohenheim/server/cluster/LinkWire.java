package be.elevenways.hohenheim.server.cluster;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.server.peer.PeerWire;
import be.elevenways.zenit.server.peer.SharedRow;
import be.elevenways.zenit.server.peer.SharedRowsPage;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a master asks a server it manages for its shared rows: the generations and rows calls over the link's one
 * fetcher, presenting the key the master holds for that server.
 *
 * AIDEV-NOTE: a call that fails or answers something unreadable throws an IllegalStateException naming the call and
 * its HTTP status only, never a part of the answer (a rows answer carries every secret of its kind); the sync that
 * asked ends there and the next one starts over.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class LinkWire implements PeerWire {

    private final String baseUrl;
    private final String key;

    LinkWire(@NonNull String baseUrl, @NonNull String key) {
        this.baseUrl = baseUrl;
        this.key = key;
    }

    @Override
    public @NonNull Map<Identifier, Long> generations() {
        Map<String, Object> answer = answered("generations", LinkCalls.generations(this.baseUrl, this.key));
        if (!(answer.get("generations") instanceof Map<?, ?> reported)) {
            throw unreadable("generations");
        }
        Map<Identifier, Long> generations = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : reported.entrySet()) {
            Identifier model = entry.getKey() instanceof String id ? Identifier.tryParse(id) : null;
            if (model == null || !(entry.getValue() instanceof Number generation)) {
                throw unreadable("generations");
            }
            generations.put(model, generation.longValue());
        }
        return generations;
    }

    @Override
    public @NonNull SharedRowsPage rows(@NonNull Identifier model, @Nullable String after, int limit) {
        Map<String, Object> answer = answered("rows",
            LinkCalls.rows(this.baseUrl, this.key, model.toString(), after, limit));
        if (!(answer.get("before") instanceof Number before) || !(answer.get("after") instanceof Number afterRows)
                || !(answer.get("rows") instanceof List<?> listed)) {
            throw unreadable("rows");
        }
        List<SharedRow> rows = new ArrayList<>(listed.size());
        for (Object item : listed) {
            if (!(item instanceof Map<?, ?> row) || !(row.get("key") instanceof String rowKey)
                    || !(row.get("title") instanceof String title)
                    || !(row.get("document") instanceof String document)) {
                throw unreadable("rows");
            }
            rows.add(new SharedRow(rowKey, row.get("code") instanceof String code ? code : null, title, document));
        }
        String next = answer.get("next") instanceof String cursor ? cursor : null;
        return new SharedRowsPage(before.longValue(), afterRows.longValue(), rows, next);
    }

    /** @return the answer's JSON object when the call was answered with 200 */
    private static @NonNull Map<String, Object> answered(@NonNull String call, LinkCalls.@NonNull Answer answer) {
        return switch (answer) {
            case LinkCalls.Answered answered when answered.status() == 200 -> answered.body();
            case LinkCalls.Answered answered -> throw new IllegalStateException("The managed server refused the "
                + call + " call (HTTP " + answered.status() + ")");
            case LinkCalls.Failed failed -> throw new IllegalStateException("The managed server did not answer the "
                + call + " call (" + failed.failure().token() + ")");
        };
    }

    private static @NonNull IllegalStateException unreadable(@NonNull String call) {
        return new IllegalStateException("The managed server answered the " + call + " call unreadably");
    }
}
