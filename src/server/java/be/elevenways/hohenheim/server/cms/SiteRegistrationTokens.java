package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.upstream.kinds.DevNamespaceUpstreamKind;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.RecordReadLines;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A dev namespace's registration token: made by Hohenheim when a save leaves the site without one or an operator asks
 * a new one, and shown once to the account that caused it on the site's Dev sessions tab ({@link TokenDisclosures}).
 *
 * AIDEV-NOTE: a token the caller typed or gave as {@code registration_token} is its own and parks nothing; any other
 * change of the stored token drops what waits, since a parked token that no longer lets a dev machine in is no secret
 * worth showing.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SiteRegistrationTokens {

    /** Where a made token starts, so a log or a paste reads as one. */
    static final String PREFIX = "zdev_";

    /** The argument of a dev namespace's tool asking a new token, as {@code regenerate_key} asks a new key pair. */
    public static final String REGENERATE = "regenerate_registration_token";

    private SiteRegistrationTokens() {
    }

    /** @return the token made into the settings of a dev namespace that carry none, null when none was made */
    static @Nullable String mintInto(@Nullable String kind, @Nullable Map<String, Object> settings) {
        if (settings == null || !DevNamespaceUpstreamKind.ID.toString().equals(kind)
                || Texts.trimmedOrNull(settings.get(DevNamespaceUpstreamKind.REGISTRATION_TOKEN_KEY)) != null) {
            return null;
        }
        String token = PREFIX + SecureTokens.randomToken(24);
        settings.put(DevNamespaceUpstreamKind.REGISTRATION_TOKEN_KEY, token);
        return token;
    }

    /**
     * Refuses, before anything is written, a save that makes a token while no account stands behind the caller: the
     * token would be shown to nobody.
     *
     * @throws Violations on the token
     */
    static void requireShownTo(@Nullable String minted, @NonNull AccessContext caller) {
        if (minted != null && !TokenDisclosures.accountOf(caller)) {
            throw Violations.ofField(DevNamespaceUpstreamKind.REGISTRATION_TOKEN_KEY, "",
                HohenheimMicrocopy.VIOLATIONS.of("registration_token_no_account"));
        }
    }

    /**
     * After a save of the site's settings: a token it made is parked for the caller, any other change of the stored
     * token drops what waits.
     *
     * @param before the settings as stored before the save, null on a create
     */
    static void saved(@NonNull AccessContext caller, int siteId, @Nullable Map<String, Object> before,
                      @Nullable Map<String, Object> after, @Nullable String minted) {
        if (minted != null) {
            TokenDisclosures.REGISTRATION.replace(caller, siteId, minted);
        } else if (!Objects.equals(tokenOf(before), tokenOf(after))) {
            TokenDisclosures.REGISTRATION.drop(siteId);
        }
    }

    /**
     * Replaces the site's stored token with a new one, parked for the caller.
     *
     * @return who sees it, where and until when
     * @throws Violations when no account stands behind the caller to be shown it
     */
    static @NonNull Microcopy regenerate(@NonNull Row site, @NonNull AccessContext caller) {
        Map<String, Object> settings = new LinkedHashMap<>(RawValues.map(site.get(SiteModel.SETTINGS)));
        settings.remove(DevNamespaceUpstreamKind.REGISTRATION_TOKEN_KEY);
        String token = Objects.requireNonNull(mintInto(DevNamespaceUpstreamKind.ID.toString(), settings));
        requireShownTo(token, caller);
        site.set(SiteModel.SETTINGS, settings);
        Models.get(SiteModel.class).saveWrittenRefusingGone(site);
        int siteId = Objects.requireNonNull(site.get(SiteModel.ID), "a stored site");
        Instant until = Objects.requireNonNull(TokenDisclosures.REGISTRATION.replace(caller, siteId, token),
            "an account stands behind the caller");
        return made(caller, until);
    }

    /** @return the token waiting for the viewer's account on the site, once; null for none */
    static @Nullable String claim(@NonNull AccessContext viewer, int siteId) {
        return TokenDisclosures.REGISTRATION.claim(viewer, siteId);
    }

    /** @return who sees the token waiting for the viewer on the site, where and until when; null for none */
    public static @Nullable Microcopy waitingFor(@NonNull AccessContext viewer, int siteId) {
        Instant until = TokenDisclosures.REGISTRATION.waiting(viewer, List.of(siteId)).get(siteId);
        return until == null ? null : made(viewer, until);
    }

    /** A read's line, for each site a token waits on for the reader: who sees it, where and until when. */
    static @NonNull Map<String, List<RecordReadLines.Line>> readLines(@NonNull Iterable<String> keys,
                                                                     @NonNull AccessContext reader) {
        List<Integer> siteIds = new ArrayList<>();
        for (String key : keys) siteIds.add(Integer.valueOf(key));
        Map<String, List<RecordReadLines.Line>> lines = new HashMap<>();
        TokenDisclosures.REGISTRATION.waiting(reader, siteIds).forEach((siteId, until) ->
            lines.put(String.valueOf(siteId), List.of(new RecordReadLines.Line(
                DevNamespaceUpstreamKind.REGISTRATION_TOKEN_KEY, reader.text(made(reader, until))))));
        return lines;
    }

    private static @NonNull Microcopy made(@NonNull AccessContext caller, @NonNull Instant until) {
        return HohenheimMicrocopy.SITE.of("registration_token_made")
            .withArg("account", TokenDisclosures.shownTo(caller)).withArg("until", until);
    }

    private static @Nullable String tokenOf(@Nullable Map<String, Object> settings) {
        return settings == null ? null
            : Texts.trimmedOrNull(settings.get(DevNamespaceUpstreamKind.REGISTRATION_TOKEN_KEY));
    }
}
