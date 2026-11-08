package be.elevenways.hohenheim.server.proxy.auth;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import be.elevenways.zenit.common.security.CallerChannel;
import be.elevenways.zenit.server.http.TrustedProxies;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records who signed in to a protected site: one {@code site_signed_in} fact on the site's history per sign-in, its
 * actor the person as the provider named them, with the address and browser they came from.
 *
 * AIDEV-NOTE: the visitor is no account of this installation, so the fact's actor is a label, never a principal
 * (Accountability.of with no token). It is written off the request: a sign-in is never slowed or failed by its log,
 * and a log that cannot be written is said in the server log instead.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SiteSignIns {

    private SiteSignIns() {
    }

    /** How long one person's sign-ins to one site from one address count as one: a cookieless client signs in anew
     *  on every request, and a log is no request counter. */
    static final Duration COALESCE = Duration.ofMinutes(15);

    /** Entries kept before the expired ones are swept out. */
    private static final int SWEEP_AT = 10_000;

    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    /** Records that {@code subject} signed in to site {@code siteId} through the provider record {@code providerId}. */
    public static void record(@NonNull HttpServerExchange exchange, int siteId, int providerId,
                              @NonNull String subject) {
        if (siteId <= 0) {
            return;
        }
        String ip = TrustedProxies.clientIpOf(exchange);
        if (!firstInWindow(siteId + "\n" + subject + "\n" + ip)) {
            return;
        }
        CallerChannel channel = new CallerChannel(AccountabilityOrigin.WEB, ip,
            exchange.getRequestHeaders().getFirst(Headers.USER_AGENT));
        Accountability visitor = Accountability.of((String) null, subject, channel);
        try {
            HandlerSupport.inBackground(() -> {
                try {
                    Row provider = Models.get(SiteAuthProviderModel.class).findById(providerId);
                    String via = provider == null ? "#" + providerId
                        : String.valueOf((Object) provider.get(SiteAuthProviderModel.NAME));
                    Accountability.runAs(visitor, () -> History.event(Models.get(SiteModel.class), siteId,
                        Change.fact(HohenheimActivityAction.SITE_SIGNED_IN, Map.of(
                            HohenheimActivityAction.NAME_KEY, subject, HohenheimActivityAction.VIA_KEY, via))));
                } catch (RuntimeException unrecorded) {
                    Blast.log("SITE SIGN-IN: could not record", subject, "on site", siteId, "-",
                        unrecorded.getMessage());
                }
            });
        } catch (RuntimeException stopping) {
            // A runtime that is stopping takes no background work: the sign-in itself stands.
            Blast.log("SITE SIGN-IN: not recorded while stopping -", subject, "on site", siteId);
        }
    }

    /** @return whether this key has not been recorded within {@link #COALESCE}, marking it recorded now */
    private static boolean firstInWindow(@NonNull String key) {
        long now = Now.millis();
        long window = COALESCE.toMillis();
        if (LAST.size() > SWEEP_AT) {
            LAST.values().removeIf(at -> now - at >= window);
        }
        boolean[] first = {false};
        LAST.compute(key, (k, at) -> {
            if (at == null || now - at >= window) {
                first[0] = true;
                return now;
            }
            return at;
        });
        return first[0];
    }
}
