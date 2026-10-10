package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SpamserviceApiException;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.resource.ChildStorePages;
import be.elevenways.zenit.cms.common.resource.StorePages;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.PageWindow;
import be.elevenways.zenit.common.data.RecordPage;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The strict-client plumbing the remote Spamservice entries share: their store pages over the management API, the
 * disconnected list notice, and the readers of a remote write's values.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class SpamserviceRemoteStore {

    /** The client of the managed runtime, null while it is not ready. */
    static final Supplier<SpamserviceClient> MANAGED = () -> SpamserviceManager.get().client();

    /**
     * Whether the last remote page read of THIS request could not be answered, per entry.
     *
     * AIDEV-NOTE: request-scoped on purpose. This used to be two ThreadLocals, cleared only by the read that followed
     * -- and when a list read was not followed by that read, the pooled request thread carried a stale "disconnected"
     * into whatever page it served next. The conduit dies with its request, so nothing here can outlive one. Keyed by
     * the entry id because one request may list more than one of these entries.
     */
    private static final IdentifierKey<Map<Identifier, Boolean>> PAGE_OUTCOMES =
        IdentifierKey.of("hohenheim", "spamservice_page_outcomes");

    /**
     * One remote page read of an entry.
     *
     * @param <T> the remote record type
     */
    @FunctionalInterface
    interface Fetch<T> {

        @NonNull PageResult<T> fetch(@NonNull SpamserviceClient client, TableView.@NonNull Applied<T> applied,
                                     @NonNull AccessContext access);
    }

    /**
     * One remote page read of an entry under its parent's key.
     *
     * @param <T> the remote record type
     */
    @FunctionalInterface
    interface FetchUnder<T> {

        @NonNull PageResult<T> fetch(@NonNull SpamserviceClient client, @NonNull Object parentKey,
                                     TableView.@NonNull Applied<T> applied, @NonNull AccessContext access);
    }

    private SpamserviceRemoteStore() {
    }

    /** @throws SpamserviceApiException 503 while the runtime is not ready */
    static @NonNull SpamserviceClient require(@NonNull Supplier<SpamserviceClient> clients) {
        SpamserviceClient client = clients.get();
        if (client == null) {
            throw new SpamserviceApiException(503, "spamservice_unavailable", "Spamservice is unavailable");
        }
        return client;
    }

    /**
     * The entry's store pages: an unreachable service lists nothing, totals 0 and remembers why for the notice.
     *
     * AIDEV-NOTE: never a negative total. zenit-cms dropped the "-1 = unknown total" reading (ffb0a58, 2026-09-02),
     * which turned every disconnected Spamservice list into a 500. An unreachable service listed nothing, so its total
     * IS 0 and the list notice says why.
     *
     * @param fields        the fields the management API answers for one record, behind the list's filters
     * @param searchColumns the columns the management API's own search parameter matches; the fetch forwards
     *                      {@code applied.searchTerm()} to it, so no fetched page is ever searched here
     */
    static <T> @NonNull StorePages<T> pages(@NonNull Identifier entry, @NonNull Supplier<SpamserviceClient> clients,
                                            @NonNull List<Field<?, ?>> fields, @NonNull List<String> searchColumns,
                                            @NonNull Fetch<T> fetch) {
        List<Field<?, ?>> declared = List.copyOf(fields);
        List<String> searched = List.copyOf(searchColumns);
        return new StorePages<>() {
            @Override
            public @NonNull RecordPage<T> page(TableView.@NonNull Applied<T> applied, @NonNull AccessContext access) {
                return read(entry, clients, applied, access, client -> fetch.fetch(client, applied, access));
            }

            @Override
            public @NonNull List<Field<?, ?>> fields() {
                return declared;
            }

            @Override
            public @NonNull List<String> searchColumns() {
                return searched;
            }
        };
    }

    /** @return store pages that ask the service nothing, over the fields the entry's records carry */
    static <T> @NonNull StorePages<T> nothing(@NonNull List<Field<?, ?>> fields) {
        List<Field<?, ?>> declared = List.copyOf(fields);
        return new StorePages<>() {
            @Override
            public @NonNull RecordPage<T> page(TableView.@NonNull Applied<T> applied, @NonNull AccessContext access) {
                return empty(applied);
            }

            @Override
            public @NonNull List<Field<?, ?>> fields() {
                return declared;
            }
        };
    }

    /** The entry's pages under a parent key, read and remembered like {@link #pages}. */
    static <T> @NonNull ChildStorePages<T> pagesUnder(@NonNull Identifier entry,
                                                      @NonNull Supplier<SpamserviceClient> clients,
                                                      @NonNull FetchUnder<T> fetch) {
        return (parentKey, applied, access) -> read(entry, clients, applied, access,
            client -> fetch.fetch(client, parentKey, applied, access));
    }

    /**
     * The disconnected notice, off this request's page read; a read with no request to carry the outcome still says
     * so when no client exists at all.
     */
    static @NonNull Function<AccessContext, @Nullable Microcopy> notice(@NonNull Identifier entry,
                                                                       @NonNull Supplier<SpamserviceClient> clients) {
        return access -> {
            Boolean unavailable = outcome(entry, access);
            boolean failed = unavailable != null ? unavailable : clients.get() == null;
            return failed ? HohenheimMicrocopy.SPAMSERVICE.of("disconnected") : null;
        };
    }

    /** @return an empty first page of the applied size, for a list that asks the service nothing */
    static <T> @NonNull RecordPage<T> empty(TableView.@NonNull Applied<T> applied) {
        return new RecordPage<>(List.of(),
            PageWindow.of(applied.page(), 0, applied.schema().pageSize(), PageWindow.OutOfRange.EMPTY), 0);
    }

    private static <T> @NonNull RecordPage<T> read(@NonNull Identifier entry,
                                                   @NonNull Supplier<SpamserviceClient> clients,
                                                   TableView.@NonNull Applied<T> applied,
                                                   @NonNull AccessContext access,
                                                   @NonNull Function<SpamserviceClient, PageResult<T>> fetch) {
        SpamserviceClient client = clients.get();
        if (client == null) {
            remember(entry, access, true);
            return empty(applied);
        }
        try {
            PageResult<T> page = fetch.apply(client);
            remember(entry, access, false);
            return new RecordPage<>(page.items(),
                PageWindow.of(applied.page(), page.total(), applied.schema().pageSize(), PageWindow.OutOfRange.EMPTY),
                page.total());
        } catch (SpamserviceApiException unavailable) {
            remember(entry, access, true);
            return empty(applied);
        }
    }

    private static void remember(@NonNull Identifier entry, @NonNull AccessContext access, boolean unavailable) {
        Conduit conduit = access.conduit();
        if (conduit == null) {
            return;
        }
        // An attribute-less conduit keeps no outcome: the notice falls back to the client's presence.
        CmsSupport.memo(conduit, PAGE_OUTCOMES, HashMap::new).put(entry, unavailable);
    }

    private static @Nullable Boolean outcome(@NonNull Identifier entry, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        if (conduit == null) {
            return null;
        }
        Map<Identifier, Boolean> outcomes = conduit.getAttribute(PAGE_OUTCOMES);
        return outcomes == null ? null : outcomes.get(entry);
    }

    static @Nullable String textFilter(TableView.@NonNull Applied<?> applied, @NonNull String name) {
        Object value = applied.filter().get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    static @Nullable Boolean booleanFilter(TableView.@NonNull Applied<?> applied, @NonNull String name) {
        String value = textFilter(applied, name);
        return value == null ? null : Boolean.valueOf(value);
    }

    /**
     * A remote DTO's REQUIRED text field, read off a partial write with the stored value as the fallback.
     *
     * AIDEV-NOTE: never a bare {@code String.valueOf(values.getOrDefault(...))}. getOrDefault answers null for a key
     * that is PRESENT and null -- which is exactly what a blank submitted entry coerces to -- and String.valueOf(null)
     * is the four characters "null", which this family then PUTs to the live service. It renamed an API key once and
     * a spam filter client once; the guard belongs here, because it was fixed one file at a time twice.
     *
     * @param stored the value the loaded record carries, or null on a create
     */
    static @NonNull String requiredText(@NonNull Map<String, Object> values, @NonNull String name,
                                        @Nullable String stored) {
        Object value = values.getOrDefault(name, stored);
        return value == null ? "" : String.valueOf(value);
    }

    /** @return the UUID, or null when the text is not one */
    static @Nullable UUID uuidOrNull(@NonNull String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** @return a remote map as the {@code name}/{@code value} rows of a two-column table, each value as text */
    static @NonNull List<Map<String, Object>> nameValueRows(@NonNull Map<String, Object> values) {
        return values.entrySet().stream().map(entry -> Map.<String, Object>of(
            "name", entry.getKey(), "value", String.valueOf(entry.getValue()))).toList();
    }

    /** @return the id as a UUID value, or "" for a blank one */
    static @NonNull Object uuidOrBlank(@Nullable String value) {
        return value == null || value.isBlank() ? "" : UUID.fromString(value);
    }

    static @NonNull Supplier<SpamserviceClient> requireNonNull(@NonNull Supplier<SpamserviceClient> clients) {
        return Objects.requireNonNull(clients, "clients cannot be null");
    }
}
