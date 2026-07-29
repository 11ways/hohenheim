package be.elevenways.hohenheim.server.process;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.security.SecretShapes;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.RateLimiter;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place site {@code api_keys} are minted, stored and verified: a site
 * key is a bearer credential for the managed-process control API, so only its
 * SHA-256 digest is ever stored (the {@code ApiKeyService} shape) and
 * verification is a constant-time digest compare.
 *
 * AIDEV-NOTE: the stored entry is {@code sha256:<hex>}. The marker is what
 * makes hashing an EXISTING plaintext key in place a valid migration -- the
 * key the operator already configured still hashes to the stored digest, so it
 * keeps working -- and what makes {@link #normalize} idempotent. Anything
 * without the marker is a legacy plaintext key and is hashed on sight.
 *
 * @author Jelle De Loecker
 */
public final class SiteApiKeys {

    /** The settings key inside a site type's JSON settings schema. */
    public static final String SETTING_NAME = "api_keys";

    /** Wire marker of a minted key, so an operator can recognize one. */
    public static final String KEY_MARKER = "hhk_";

    private static final String DIGEST_PREFIX = "sha256:";

    /**
     * Attempts per site per client IP per window on the key-checking path.
     * AIDEV-NOTE: the check lives on the PROXY listener (SiteDispatcher), which
     * never runs the zenit conduit middleware chain, so {@code Endpoint.rateLimit}
     * and the {@code rate_limit.*} settings cannot reach it. Core's RateLimiter --
     * the same bucket store RateLimitMiddleware drives -- is used directly instead.
     */
    private static final int ATTEMPT_CAPACITY = 10;
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(1);

    private static final RateLimiter LIMITER = new RateLimiter();

    private static volatile boolean installed;

    private SiteApiKeys() {}

    /**
     * Register the write-time normalization hook: no plaintext key reaches the
     * datasource, whichever path writes the site (admin form, clone, API).
     * Runs BEFORE validation, so revisions and activity deltas see digests too.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        SiteModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(SiteModel.SETTINGS.getName())) {
                return;
            }
            Object settings = row.get(SiteModel.SETTINGS.getName());
            if (!(settings instanceof Map<?, ?> map) || !map.containsKey(SETTING_NAME)) {
                return;
            }
            refuseWeakPlaintext(map.get(SETTING_NAME));
            List<String> digests = normalize(map.get(SETTING_NAME));
            if (digests.equals(map.get(SETTING_NAME))) {
                return;
            }
            Map<String, Object> updated = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                updated.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            updated.put(SETTING_NAME, digests);
            row.set(SiteModel.SETTINGS, updated);
        });
    }

    /** A fresh plaintext key; only the caller's one-shot disclosure ever sees it. */
    public static @NonNull String mint() {
        return KEY_MARKER + SecureTokens.randomToken();
    }

    /**
     * Entropy floor for ADOPTING operator-typed keys: a weak value would be
     * dictionary-recoverable from its fast SHA-256 digest in a copied database,
     * so it is refused with a violation instead of silently accepted. Runs on
     * the WRITE path only -- already-stored legacy values arrive here as digests
     * (the seeder hashes before save) and pass through untouched.
     *
     * @throws Violations when a plaintext entry fails {@link SecretShapes#isAdoptablePlaintext}
     */
    private static void refuseWeakPlaintext(@Nullable Object raw) {
        if (!(raw instanceof Collection<?> entries)) {
            return;
        }
        for (Object entry : entries) {
            if (entry == null) {
                continue;
            }
            String value = String.valueOf(entry).trim();
            if (value.isEmpty() || isDigest(value)) {
                continue;
            }
            if (!SecretShapes.isAdoptablePlaintext(value)) {
                // Never echo the credential back; the violation value stays blank.
                throw Violations.ofField("settings." + SETTING_NAME, "",
                    Microcopy.of("weak_api_key")
                        .withFilter("scope", "violations")
                        .withArg("min_length", String.valueOf(SecretShapes.MIN_ADOPTABLE_LENGTH)));
            }
        }
    }

    /** @return the stored form of a plaintext key */
    public static @NonNull String digest(@NonNull String plaintext) {
        return DIGEST_PREFIX + SecureTokens.sha256Hex(plaintext);
    }

    /**
     * @return true when the stored entry is already a digest rather than a legacy plaintext key
     *
     * AIDEV-NOTE: the COMPLETE shape is validated ({@code sha256:} + exactly 64
     * lowercase hex chars), never just the prefix -- a legacy plaintext key that
     * happens to start with "sha256:" must be hashed like any other plaintext,
     * not misclassified as a digest and silently invalidated.
     */
    public static boolean isDigest(@Nullable String stored) {
        return SecretShapes.isSha256Digest(stored, DIGEST_PREFIX);
    }

    /**
     * @param raw the {@code api_keys} settings value (a list of strings)
     * @return the same keys as digests, order kept, blanks and duplicates dropped;
     *         entries that are already digests pass through untouched, which is what
     *         makes this safe to run on every write and on already-migrated settings
     */
    public static @NonNull List<String> normalize(@Nullable Object raw) {
        List<String> digests = new ArrayList<>();
        if (!(raw instanceof Collection<?> entries)) {
            return digests;
        }
        for (Object entry : entries) {
            if (entry == null) {
                continue;
            }
            String value = String.valueOf(entry).trim();
            if (value.isEmpty()) {
                continue;
            }
            String stored = isDigest(value) ? value : digest(value);
            if (!digests.contains(stored)) {
                digests.add(stored);
            }
        }
        return digests;
    }

    /**
     * Constant-time membership: every entry is compared, so the number of
     * comparisons never depends on which key was presented.
     */
    public static boolean matches(@NonNull Collection<String> digests, @Nullable String presented) {
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        String candidate = digest(presented);
        boolean found = false;
        for (String stored : digests) {
            found |= SecureTokens.constantTimeEquals(candidate, stored);
        }
        return found;
    }

    /** What a request may do with the managed-process control API. */
    public enum Decision {
        /** Not a control request at all; hand it to the ordinary proxy path. */
        NOT_CONTROL,
        /** This client has spent its attempt budget for this site (429). */
        THROTTLED,
        /** A key was presented and did not verify (403). */
        REFUSED,
        /** The key verified; run the requested actions. */
        ALLOWED
    }

    /**
     * The one control-API admission decision. Only a request that PRESENTS a key
     * spends attempt budget, so ordinary proxied traffic can never trip the
     * throttle, and a wrong key never falls through to the upstream.
     */
    public static @NonNull Decision decide(int siteId, @NonNull Collection<String> digests,
                                           @Nullable String key, @Nullable String actions,
                                           @Nullable String clientIp) {
        if (digests.isEmpty() || key == null || actions == null) {
            return Decision.NOT_CONTROL;
        }
        if (!LIMITER.tryAcquire("hohenheim:site-api-key:" + siteId + ":"
                + (clientIp == null ? "" : clientIp), ATTEMPT_CAPACITY, ATTEMPT_WINDOW).allowed()) {
            return Decision.THROTTLED;
        }
        return matches(digests, key) ? Decision.ALLOWED : Decision.REFUSED;
    }

    /** The attempt-bucket store; exposed so tests can {@code clear()} it. */
    public static @NonNull RateLimiter limiter() {
        return LIMITER;
    }

    /**
     * Rewrite every stored plaintext key to its digest, in place. NO KEY IS
     * INVALIDATED: the key its holder presents still hashes to the stored
     * digest. Idempotent, so it is safe to run again at any time.
     *
     * @return the number of sites rewritten
     */
    public static int hashStoredKeys() {
        SiteModel sites = Models.get(SiteModel.class);
        int rewritten = 0;

        // Every site, soft-deleted ones included: their settings still hold keys.
        for (Row site : sites.find().all()) {
            if (!(site.get(SiteModel.SETTINGS) instanceof Map<?, ?> map)) {
                continue;
            }
            Object stored = map.get(SETTING_NAME);
            List<String> digests = normalize(stored);
            if (digests.equals(stored)) {
                continue;
            }

            Map<String, Object> settings = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                settings.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            settings.put(SETTING_NAME, digests);
            site.set(SiteModel.SETTINGS, settings);
            sites.save(site);
            rewritten++;
        }

        return rewritten;
    }
}
