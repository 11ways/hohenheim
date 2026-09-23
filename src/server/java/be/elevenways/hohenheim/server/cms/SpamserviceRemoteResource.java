package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SpamserviceApiException;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Shared strict-client plumbing for model-independent Spamservice resources. */
abstract class SpamserviceRemoteResource<T> extends Resource<T> {

    /**
     * What the last remote page read of THIS request answered, per resource.
     *
     * AIDEV-NOTE: request-scoped on purpose. This used to be two ThreadLocals, cleared only by
     * the countRows/listNotice read that followed -- and when a listRows was not followed by
     * that read (a lane that lists without rendering the notice), the pooled request thread
     * carried a stale "disconnected" into whatever page it served next. The conduit dies with
     * its request, so nothing here can outlive one. Keyed by the resource id because one
     * request may list more than one of these resources.
     */
    private static final IdentifierKey<Map<Identifier, PageOutcome>> PAGE_OUTCOMES =
        IdentifierKey.of("hohenheim", "spamservice_page_outcomes");

    /**
     * One remote page read: the total the service reported (0 when it could not answer, since
     * nothing was listed) and whether the service could not answer.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    private record PageOutcome(long total, boolean unavailable) {

        static final PageOutcome UNAVAILABLE = new PageOutcome(0L, true);
    }

    /**
     * One remote page read: its rows and what it answered.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    private record PageRead<T>(@NonNull List<T> rows, @NonNull PageOutcome outcome) {
    }

    private final Supplier<SpamserviceClient> clientSupplier;

    protected SpamserviceRemoteResource() {
        this(() -> SpamserviceManager.get().client());
    }

    protected SpamserviceRemoteResource(@NonNull Supplier<SpamserviceClient> clientSupplier) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier cannot be null");
    }

    /**
     * One choice for the whole family: these lists are read through the abuse-protection
     * overview that links to them, and a remote page cannot back a saved view anyway.
     */
    @Override
    public @NonNull ListChrome listChrome() {
        return ListChrome.MINIMAL;
    }

    protected final @Nullable SpamserviceClient client() {
        return this.clientSupplier.get();
    }

    protected final @NonNull SpamserviceClient requireClient() {
        SpamserviceClient client = this.client();
        if (client == null) {
            throw new SpamserviceApiException(503, "spamservice_unavailable", "Spamservice is unavailable");
        }
        return client;
    }

    /** @param accessContext the list read's context, whose request carries any list scoping */
    protected abstract @NonNull PageResult<T> fetchPage(@NonNull SpamserviceClient client,
                                                        TableView.@NonNull Applied<T> applied,
                                                        @NonNull AccessContext accessContext);

    @Override
    public @NonNull List<T> listRows(TableView.@NonNull Applied<T> applied,
                                     @NonNull AccessContext accessContext) {
        return this.read(applied, accessContext).rows();
    }

    /**
     * The total this request's page read reported, reading the page first when nothing listed
     * it yet.
     *
     * AIDEV-NOTE: never negative. zenit-cms dropped the "-1 = unknown total" reading (ffb0a58,
     * 2026-09-02) and its RecordPage refuses a negative total, which turned every disconnected
     * Spamservice list into a 500. An unreachable service listed nothing, so its total IS 0 and
     * the list notice says why. The framework's page walk is no fallback here: a remote page
     * never returns the one-extra row that walk needs to see a next page.
     */
    @Override
    public final long countRows(TableView.Applied<T> applied, @NonNull AccessContext accessContext) {
        PageOutcome outcome = this.outcome(accessContext);
        return outcome != null ? outcome.total() : this.read(applied, accessContext).outcome().total();
    }

    /** One remote page read, remembered on this request for the count and the notice. */
    private @NonNull PageRead<T> read(TableView.@NonNull Applied<T> applied,
                                      @NonNull AccessContext accessContext) {
        PageRead<T> read = this.fetch(applied, accessContext);
        this.remember(accessContext, read.outcome());
        return read;
    }

    private @NonNull PageRead<T> fetch(TableView.@NonNull Applied<T> applied,
                                       @NonNull AccessContext accessContext) {
        SpamserviceClient client = this.client();
        if (client == null) {
            return new PageRead<>(List.of(), PageOutcome.UNAVAILABLE);
        }
        try {
            PageResult<T> page = this.fetchPage(client, applied, accessContext);
            return new PageRead<>(page.items(), new PageOutcome(page.total(), false));
        } catch (SpamserviceApiException unavailable) {
            return new PageRead<>(List.of(), PageOutcome.UNAVAILABLE);
        }
    }

    /**
     * The disconnected notice, off this request's page read; a read with no request to carry
     * the outcome (a direct call) still says so when no client exists at all.
     */
    @Override
    public final @Nullable Microcopy listNotice(@NonNull AccessContext accessContext) {
        PageOutcome outcome = this.outcome(accessContext);
        boolean failed = outcome != null ? outcome.unavailable() : this.client() == null;
        return failed ? Microcopy.of("disconnected").withFilter("scope", "spamservice") : null;
    }

    private void remember(@NonNull AccessContext accessContext, @NonNull PageOutcome outcome) {
        Conduit conduit = accessContext.conduit();
        if (conduit == null) {
            return;
        }
        try {
            Map<Identifier, PageOutcome> outcomes = conduit.getAttribute(PAGE_OUTCOMES);
            if (outcomes == null) {
                outcomes = new HashMap<>();
                conduit.setAttribute(PAGE_OUTCOMES, outcomes);
            }
            outcomes.put(this.id(), outcome);
        } catch (UnsupportedOperationException attributeless) {
            // An attribute-less conduit keeps no outcome: the total reads unknown.
        }
    }

    private @Nullable PageOutcome outcome(@NonNull AccessContext accessContext) {
        Conduit conduit = accessContext.conduit();
        if (conduit == null) {
            return null;
        }
        Map<Identifier, PageOutcome> outcomes = conduit.getAttribute(PAGE_OUTCOMES);
        return outcomes == null ? null : outcomes.get(this.id());
    }

    protected static @Nullable String textFilter(TableView.Applied<?> applied, String name) {
        Object value = applied.filter().get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    protected static @Nullable Boolean booleanFilter(TableView.Applied<?> applied, String name) {
        String value = textFilter(applied, name);
        return value == null ? null : Boolean.valueOf(value);
    }

    /**
     * A remote DTO's REQUIRED text field, read off a partial write with the stored value as
     * the fallback.
     *
     * AIDEV-NOTE: never a bare {@code String.valueOf(values.getOrDefault(...))}. getOrDefault
     * answers null for a key that is PRESENT and null -- which is exactly what a blank
     * submitted entry coerces to -- and String.valueOf(null) is the four characters "null",
     * which this family then PUTs to the live service. It renamed an API key once and a spam
     * filter client once; the guard belongs here rather than in each subclass, because it was
     * fixed one file at a time twice and missed the third.
     *
     * @param stored the value the loaded record carries, or null on a create
     */
    protected static @NonNull String requiredText(@NonNull Map<String, Object> values,
                                                  @NonNull String name, @Nullable String stored) {
        Object value = values.getOrDefault(name, stored);
        return value == null ? "" : String.valueOf(value);
    }
}
