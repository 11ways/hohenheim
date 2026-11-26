package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.server.CredentialDisclosures;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.server.security.AccountDisclosures;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tokens Hohenheim made, shown once to the account behind the call on their record's page, for as long as the host
 * keeps a parked secret ({@link CredentialDisclosures#WINDOW}); never in a tool's answer or a read.
 *
 * AIDEV-NOTE: a key's call has no session whose toast could show a token, so these ride core's
 * {@link AccountDisclosures} in the host's table, as a minted password does: one parking per record and account. A
 * place's scope string is stored in parked rows, so it never changes while a row may wait under it.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class TokenDisclosures {

    /** Dynamic DNS update tokens, on the zone's Records tab, a later call's tokens added beside unread ones. */
    static final TokenDisclosures DYNDNS = new TokenDisclosures("hohenheim.dns_zone_records");

    /** A dev namespace's registration token, on the site's Dev sessions tab; a new one replaces every unread one. */
    static final TokenDisclosures REGISTRATION = new TokenDisclosures("hohenheim.site_registration_token");

    /** The URLs a tool made of an app's build hooks, on the app's Build hooks tab, beside unread ones. */
    static final TokenDisclosures BUILD_HOOKS = new TokenDisclosures("hohenheim.build_hooks");

    /** The keys a tool made for a spam client, on the client's Keys tab, beside unread ones; keyed by its id. */
    static final TokenDisclosures SPAM_KEYS = new TokenDisclosures("hohenheim.spamservice_keys");

    private final @NonNull String place;

    private TokenDisclosures(@NonNull String place) {
        this.place = place;
    }

    /** @return whether an account stands behind the caller to be shown a token, and a table to park it in */
    static boolean accountOf(@NonNull AccessContext caller) {
        return AccountDisclosures.accountOf(caller) != null && AccountDisclosures.HOST_STORE.peek() != null;
    }

    /** @return the person a token the caller makes is shown to, by name; empty for none */
    static @NonNull String shownTo(@NonNull AccessContext caller) {
        return Objects.toString(CredentialDisclosures.shownTo(caller), "");
    }

    /**
     * Parks the tokens one call made on a record for the caller's account, one per line, beside those an earlier call
     * parked there unread: only a digest is kept, so a token replaced unread is lost for good.
     *
     * @return until when they wait, null when no account or table can hold them
     */
    @Nullable Instant park(@NonNull AccessContext caller, @NonNull Object recordId, @NonNull String tokens) {
        AccountDisclosures parked = parked();
        if (parked == null) return null;
        Secret earlier = parked.claim(caller, this.scope(recordId));
        String all = earlier == null ? tokens : earlier.reveal() + "\n" + tokens;
        return parked.park(caller, this.scope(recordId), Secret.of(all));
    }

    /**
     * Parks the record's one token for the caller's account, dropping what any account had parked there: the token it
     * replaces stopped working.
     *
     * @return until when it waits, null when no account or table can hold it
     */
    @Nullable Instant replace(@NonNull AccessContext caller, @NonNull Object recordId, @NonNull String token) {
        AccountDisclosures parked = parked();
        if (parked == null) return null;
        parked.drop(this.scope(recordId));
        return parked.park(caller, this.scope(recordId), Secret.of(token));
    }

    /** @return the tokens parked for the viewer's account on the record, once; null for none */
    @Nullable String claim(@NonNull AccessContext viewer, @NonNull Object recordId) {
        AccountDisclosures parked = parked();
        Secret tokens = parked == null ? null : parked.claim(viewer, this.scope(recordId));
        return tokens == null ? null : tokens.reveal();
    }

    /**
     * AIDEV-TODO: a read of the host store's columns, which core's {@link AccountDisclosures} offers no peek for; it
     * belongs there as {@code waitingUntil(viewer, scopes)}.
     *
     * @return until when a token waits for the viewer's account, by each record it waits on, left parked
     */
    @NonNull Map<Integer, Instant> waiting(@NonNull AccessContext viewer, @NonNull Collection<Integer> recordIds) {
        UUID account = AccountDisclosures.accountOf(viewer);
        AccountDisclosures.Store store = AccountDisclosures.HOST_STORE.peek();
        if (account == null || store == null || recordIds.isEmpty()) return Map.of();
        Map<String, Integer> byScope = new HashMap<>();
        for (Integer recordId : recordIds) byScope.put(this.scope(recordId), recordId);
        Map<Integer, Instant> waiting = new HashMap<>();
        for (Row row : store.model().get().find().noCache().where(store.account().eq(account))
                .and(store.scope().in(byScope.keySet())).and(store.expiresAt().gt(Now.instant())).all()) {
            waiting.put(byScope.get(row.get(store.scope())), row.get(store.expiresAt()));
        }
        return waiting;
    }

    /** Drops what every account had parked on the record: its token stopped working, or the record went. */
    void drop(@NonNull Object recordId) {
        AccountDisclosures parked = parked();
        if (parked != null) parked.drop(this.scope(recordId));
    }

    private static @Nullable AccountDisclosures parked() {
        AccountDisclosures.Store store = AccountDisclosures.HOST_STORE.peek();
        return store == null ? null : AccountDisclosures.of(CredentialDisclosures.WINDOW, store);
    }

    /** Per record, by its key (a row's number, a remote record's id), so another record's tab never shows a token. */
    private @NonNull String scope(@NonNull Object recordId) {
        return this.place + "|" + recordId;
    }
}
