package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.spamservice.client.ManagedClient;
import be.elevenways.spamservice.client.ManagedClientKey;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SampleDetail;
import be.elevenways.spamservice.client.SampleSummary;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.OperationVerb;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.ForeignCodes;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.RecordReadLines;
import be.elevenways.zenit.server.operation.RecordSourceOperations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The spam service's own codes (SPC clients, SPK keys, SPS samples) as kinds a read names and the list tool lists:
 * each read and each page asks the service, an administrator alone is shown one, and Hohenheim keeps no copy.
 *
 * AIDEV-NOTE: a record's lines are the settings spam_client_set takes by its argument names, and a key's or a
 * sample's own facts; the client they belong to is named by its code, never by the service's id, and a key's value
 * is never one of them. A service that does not answer refuses in the spam tools' words (SpamserviceRemoteStore),
 * never as a record naming nothing. A page is {@link #PAGE_SIZE} records, the client of each sample asked once a
 * page.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SpamRecordCodes {

    /** How many records one page of a list holds. */
    static final int PAGE_SIZE = 25;

    /** The entry naming the client a key or a sample belongs to, by the code the service gave it. */
    private static final String CLIENT = SpamserviceClientKeysResource.SPAM_CLIENT;

    /** The service's clients, SPC. */
    public static final ForeignCodes.Kind CLIENTS = ForeignCodes.declare(SpamRecordCodes.class, "SPC", CLIENT,
        SpamserviceClientsResource.ID, (code, access) -> {
            if (!admin(access)) return null;
            ManagedClient client = SpamserviceRemoteStore.found(SpamserviceRemoteStore.MANAGED,
                each -> each.client(code));
            return client == null ? null : clientLines(client);
        });

    /** The clients' keys, SPK. */
    public static final ForeignCodes.Kind KEYS = ForeignCodes.declare(SpamRecordCodes.class, "SPK", "spam_key",
        SpamserviceClientKeysResource.ID, (code, access) -> {
            if (!admin(access)) return null;
            ManagedClientKey key = SpamserviceRemoteStore.found(SpamserviceRemoteStore.MANAGED,
                each -> each.key(code));
            return key == null ? null : keyLines(key, SpamserviceClientsResource.codeOf(key.clientId()));
        });

    /** The samples the service kept, SPS. */
    public static final ForeignCodes.Kind SAMPLES = ForeignCodes.declare(SpamRecordCodes.class, "SPS", "spam_sample",
        SpamserviceSamplesResource.ID, (code, access) -> {
            if (!admin(access)) return null;
            SampleDetail sample = SpamserviceRemoteStore.found(SpamserviceRemoteStore.MANAGED,
                each -> each.sample(code));
            return sample == null ? null : sampleLines(sample.summary(), clientCodes());
        });

    /** Lists the spam service's clients, a page at a time. */
    public static final Operation<Void, OperationInput.Values, ForeignCodes.Listing> LIST_CLIENTS =
        list("list_spam_clients", false);

    /** Lists one client's keys, a page at a time, never their values. */
    public static final Operation<Void, OperationInput.Values, ForeignCodes.Listing> LIST_KEYS =
        list("list_spam_keys", true);

    /** Lists the samples the spam service kept, a page at a time, of one client when one is named. */
    public static final Operation<Void, OperationInput.Values, ForeignCodes.Listing> LIST_SAMPLES =
        list("list_spam_samples", true);

    static {
        attach(LIST_CLIENTS, (client, input) -> page(CLIENTS, input, (page, size) -> {
            PageResult<ManagedClient> clients = client.clients(page, size, null, null);
            List<ForeignCodes.Read> reads = new ArrayList<>();
            for (ManagedClient each : clients.items()) reads.add(read(CLIENTS, each.code(), clientLines(each)));
            return new Paged(reads, clients.total());
        }));
        attach(LIST_KEYS, (client, input) -> {
            ManagedClient owner = owner(client, input, true);
            String code = Objects.requireNonNull(owner).code();
            return page(KEYS, input, (page, size) -> {
                PageResult<ManagedClientKey> keys = client.keys(owner.id(), page, size);
                List<ForeignCodes.Read> reads = new ArrayList<>();
                for (ManagedClientKey each : keys.items()) reads.add(read(KEYS, each.code(), keyLines(each, code)));
                return new Paged(reads, keys.total());
            });
        });
        attach(LIST_SAMPLES, (client, input) -> {
            ManagedClient owner = owner(client, input, false);
            Function<String, @Nullable String> codes = clientCodes();
            return page(SAMPLES, input, (page, size) -> {
                PageResult<SampleSummary> samples = client.samples(page, size, owner == null ? null : owner.id(),
                    null, null, null);
                List<ForeignCodes.Read> reads = new ArrayList<>();
                for (SampleSummary each : samples.items()) {
                    reads.add(read(SAMPLES, each.code(), sampleLines(each, codes)));
                }
                return new Paged(reads, samples.total());
            });
        });
    }

    private SpamRecordCodes() {
    }

    /** Declares the three kinds and their lists; boot, idempotent through the class initializer. */
    public static void declare() {
    }

    /** One page of a list as the service answered it. */
    private record Paged(@NonNull List<ForeignCodes.Read> reads, long total) {
    }

    /** One list's read of the service. */
    @FunctionalInterface
    private interface Lister {
        ForeignCodes.@NonNull Listing list(@NonNull SpamserviceClient client, OperationInput.@NonNull Values input);
    }

    /**
     * @param naming whether the list takes {@code of}, the client its records belong to, by its code
     */
    private static @NonNull Operation<Void, OperationInput.Values, ForeignCodes.Listing> list(@NonNull String id,
                                                                                            boolean naming) {
        FormSpec.Builder form = FormSpec.builder();
        if (naming) form.add(RecordSourceOperations.ofEntry());
        form.add(RecordSourceOperations.pageEntry());
        return Operation.declare(HohenheimIds.id(id))
            .happened(OperationSentences.of(id))
            .label(HohenheimMicrocopy.HOHENHEIM.of(id))
            .description(HohenheimMicrocopy.HOHENHEIM.of(id + "_description"))
            .verb(OperationVerb.LIST)
            .noSubject()
            .gate(HohenheimSources.OPERATOR)
            .input(OperationInput.derived(form.build()))
            .result(ForeignCodes.Listing.class)
            .facts(OperationFact.READ_ONLY, OperationFact.REACHES_OUTSIDE)
            .register();
    }

    /** Reads through the service while it answers, refused in the spam tools' words while it does not. */
    private static void attach(@NonNull Operation<Void, OperationInput.Values, ForeignCodes.Listing> operation,
                               @NonNull Lister lister) {
        OperationHandlers.attach(operation)
            .availability((none, access) -> SpamserviceOperations.connectedReason(SpamserviceManager.get()))
            .handle(call -> SpamserviceRemoteStore.answered(() -> lister.list(
                SpamserviceRemoteStore.require(SpamserviceRemoteStore.MANAGED), Objects.requireNonNull(call.input()))));
    }

    /** @return the page the input asks, the first when it names none */
    private static ForeignCodes.@NonNull Listing page(ForeignCodes.@NonNull Kind kind,
                                                      OperationInput.@NonNull Values input,
                                                      @NonNull BiFunction<Integer, Integer, Paged> read) {
        Long asked = OperationForms.integer(input, RecordSourceOperations.PAGE);
        int page = asked == null || asked < 1 ? 1 : (int) Math.min(asked, Integer.MAX_VALUE);
        Paged paged = read.apply(page, PAGE_SIZE);
        return ForeignCodes.Listing.of(kind, paged.reads(), page, PAGE_SIZE, paged.total());
    }

    /**
     * @param required whether the list needs one
     * @return the client {@code of} names by its code, null when none is named and none is needed
     * @throws Violations on {@code of} for a code naming no client, or none where one is needed
     */
    private static @Nullable ManagedClient owner(@NonNull SpamserviceClient client,
                                                 OperationInput.@NonNull Values input, boolean required) {
        String code = OperationForms.text(input, RecordSourceOperations.OF);
        if (code == null || code.isBlank()) {
            if (!required) return null;
            throw Violations.ofField(RecordSourceOperations.OF, null,
                HohenheimMicrocopy.HOHENHEIM.of("mcp_spam_list_of_required"));
        }
        ManagedClient owner = SpamserviceRemoteStore.found(() -> client, each -> each.client(code.trim()));
        if (owner == null) {
            throw Violations.ofField(RecordSourceOperations.OF, code,
                HohenheimMicrocopy.HOHENHEIM.of("mcp_spam_list_of_unknown").withArg("code", code.trim()));
        }
        return owner;
    }

    private static ForeignCodes.@NonNull Read read(ForeignCodes.@NonNull Kind kind, @NonNull String code,
                                                    @NonNull List<RecordReadLines.Line> lines) {
        return new ForeignCodes.Read(code, kind.noun(), lines);
    }

    private static boolean admin(@NonNull AccessContext access) {
        return access.hasPermission(HohenheimSources.ADMIN_ACCESS);
    }

    private static @NonNull List<RecordReadLines.Line> clientLines(@NonNull ManagedClient client) {
        return lines(SpamserviceClientsResource.written(client), null);
    }

    private static @NonNull List<RecordReadLines.Line> keyLines(@NonNull ManagedClientKey key,
                                                                @Nullable String clientCode) {
        return lines(SpamserviceClientKeysResource.values(key), clientCode);
    }

    private static @NonNull List<RecordReadLines.Line> sampleLines(@NonNull SampleSummary sample,
                                                                   @NonNull Function<String, @Nullable String> codes) {
        return lines(SpamserviceSamplesResource.values(sample),
            sample.clientId() == null ? null : codes.apply(sample.clientId()));
    }

    /** @return the code of the client under each service id, each asked of the service once */
    private static @NonNull Function<String, @Nullable String> clientCodes() {
        Map<String, String> asked = new HashMap<>();
        return id -> {
            // A client the service no longer names is remembered as none too, so it is asked once a page.
            if (!asked.containsKey(id)) asked.put(id, SpamserviceClientsResource.codeOf(id));
            return asked.get(id);
        };
    }

    /**
     * @param clientCode the code of the client the record belongs to, null for a client itself or one the service no
     *                   longer names
     * @return one line per value that says something, by name, the client by its code
     */
    private static @NonNull List<RecordReadLines.Line> lines(@NonNull Map<String, Object> values,
                                                             @Nullable String clientCode) {
        List<RecordReadLines.Line> lines = new ArrayList<>();
        if (clientCode != null) lines.add(new RecordReadLines.Line(CLIENT, clientCode));
        new TreeMap<>(values).forEach((name, value) -> {
            String said = value == null ? "" : String.valueOf(value);
            if (!said.isBlank() && !"client_id".equals(name) && !"key".equals(name)) {
                lines.add(new RecordReadLines.Line(name, said));
            }
        });
        return lines;
    }
}
