package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.proxy.auth.SignedInIdentity;
import be.elevenways.protoblast.common.http.Uri;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.Function;

/**
 * The request headers that tell an app who passed its site's sign-in: members of Hohenheim's own header family.
 *
 * AIDEV-NOTE: the security half is the REMOVAL, which is the family's ({@link OwnHeaders}): every member a visitor
 * sent is gone from every request the forwarding stage passes on, signed-in or not, on every site and route,
 * WebSocket upgrades included, a linked server's too; only then does {@link #apply} set these from what a gate
 * vouched for on this request.
 * Without that a visitor could simply send X-Hohenheim-User himself. It runs after the operator's own header rules,
 * and a rule naming a member never applies, so no rule can set one either. Values are percent-encoded UTF-8
 * (RFC 3986 unreserved characters kept, as {@link Uri#encodeComponent} writes them), so a name outside ASCII arrives
 * intact and no value can carry a line break into the request.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.10.0
 */
public enum IdentityHeader {

    /** The provider's stable id of the person: the Proteus {@code sub}, a password gate's user name. */
    USER("X-Hohenheim-User", SignedInIdentity::user),

    /** The person's address, sent only when the provider vouched for it. */
    EMAIL("X-Hohenheim-Email", SignedInIdentity::vouchedEmail),

    /** The person's name as the provider gave it. */
    NAME("X-Hohenheim-Name", SignedInIdentity::name);

    private final HttpString header;
    private final Function<SignedInIdentity, @Nullable String> value;

    IdentityHeader(@NonNull String header, @NonNull Function<SignedInIdentity, @Nullable String> value) {
        this.header = new HttpString(header);
        this.value = value;
    }

    /** @return the header's name as the app receives it */
    public @NonNull String headerName() {
        return this.header.toString();
    }

    /**
     * Set these for the person who signed in, after the family's removal.
     *
     * @param identity who passed a sign-in gate on this request, or null when nobody did
     */
    static void apply(@NonNull HeaderMap headers, @Nullable SignedInIdentity identity) {
        if (identity == null) {
            return;
        }
        for (IdentityHeader member : values()) {
            String value = member.value.apply(identity);
            if (value != null && !value.isEmpty()) {
                headers.put(member.header, Uri.encodeComponent(value));
            }
        }
    }
}
