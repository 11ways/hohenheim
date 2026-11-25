package be.elevenways.hohenheim.server.cms;

import be.elevenways.zenit.auth.server.CredentialDisclosures;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.server.security.AccountDisclosures;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Dynamic DNS tokens a tool made, shown once to the account behind the call on the zone's Records tab, for as long as
 * the host keeps a parked secret ({@link CredentialDisclosures#WINDOW}); never in the tool's answer.
 *
 * AIDEV-NOTE: the panel's own token action shows its token in the toast of the session that asked, which a key's call
 * has none of: these ride core's {@link AccountDisclosures} in the host's table, as a minted password does. One
 * parking per zone and account, a later call's tokens added beside what an earlier one left unread.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class DnsTokenDisclosures {

    private DnsTokenDisclosures() {
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
     * Parks the tokens one call made in a zone for the caller's account, one per line with its name and type, beside
     * those an earlier call parked there unread: only a digest is kept, so a token replaced unread is lost for good.
     *
     * @return until when they wait, null when no account or table can hold them
     */
    static @Nullable Instant park(@NonNull AccessContext caller, int zoneId, @NonNull String tokens) {
        AccountDisclosures parked = parked();
        if (parked == null) return null;
        Secret earlier = parked.claim(caller, scope(zoneId));
        String all = earlier == null ? tokens : earlier.reveal() + "\n" + tokens;
        return parked.park(caller, scope(zoneId), Secret.of(all));
    }

    /** @return the tokens parked for the viewer's account in the zone, once; null for none */
    static @Nullable String claim(@NonNull AccessContext viewer, int zoneId) {
        AccountDisclosures parked = parked();
        Secret tokens = parked == null ? null : parked.claim(viewer, scope(zoneId));
        return tokens == null ? null : tokens.reveal();
    }

    private static @Nullable AccountDisclosures parked() {
        AccountDisclosures.Store store = AccountDisclosures.HOST_STORE.peek();
        return store == null ? null : AccountDisclosures.of(CredentialDisclosures.WINDOW, store);
    }

    /** Per zone, so another zone's tab never shows a token under the wrong origin. */
    private static @NonNull String scope(int zoneId) {
        return "hohenheim.dns_zone_records|" + zoneId;
    }
}
