package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.spamservice.client.PageResult;
import be.elevenways.spamservice.client.SpamserviceApiException;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Shared strict-client plumbing for model-independent Spamservice resources. */
abstract class SpamserviceRemoteResource<T> extends Resource<T> {

    private final Supplier<SpamserviceClient> clientSupplier;
    private final ThreadLocal<Long> lastTotal = new ThreadLocal<>();
    private final ThreadLocal<Boolean> unavailable = new ThreadLocal<>();

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

    protected abstract @NonNull PageResult<T> fetchPage(@NonNull SpamserviceClient client,
                                                        TableView.@NonNull Applied<T> applied);

    @Override
    public @NonNull List<T> listRows(TableView.@NonNull Applied<T> applied,
                                     @NonNull AccessContext accessContext) {
        SpamserviceClient client = this.client();
        if (client == null) {
            this.lastTotal.set(-1L);
            this.unavailable.set(true);
            return List.of();
        }
        try {
            PageResult<T> page = this.fetchPage(client, applied);
            this.lastTotal.set(page.total());
            this.unavailable.set(false);
            return page.items();
        } catch (SpamserviceApiException unavailable) {
            this.lastTotal.set(-1L);
            this.unavailable.set(true);
            return List.of();
        }
    }

    @Override
    public final long countRows(TableView.Applied<T> applied, @NonNull AccessContext accessContext) {
        Long total = this.lastTotal.get();
        this.lastTotal.remove();
        return total != null ? total : -1;
    }

    @Override
    public final @Nullable Microcopy listNotice(@NonNull AccessContext accessContext) {
        Boolean failed = this.unavailable.get();
        this.unavailable.remove();
        return Boolean.TRUE.equals(failed)
            ? Microcopy.of("disconnected").withFilter("scope", "spamservice") : null;
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
