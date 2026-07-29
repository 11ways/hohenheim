package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimWriteHooks;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.SiteApiKeySeeder;
import be.elevenways.hohenheim.server.process.SiteApiKeys;
import be.elevenways.hohenheim.server.process.SiteApiKeys.Decision;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.common.ZenitModules;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.seed.Seeds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static be.elevenways.hohenheim.test.ProxyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Site control-API keys: a key configured before hashing keeps working, the
 * migration rewrites it in place, a wrong key is refused rather than proxied,
 * and the refusal path is throttled.
 */
class SiteApiKeyTest {

    private static final String LEGACY_KEY = "legacy-plaintext-key";
    private static final String IP = "203.0.113.9";

    private static boolean initialized = false;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;
        bootRuntime();
    }

    @AfterEach
    void cleanup() {
        SiteApiKeys.limiter().clear();
    }

    @Test
    void apiKeyJourney() {
        SiteApiKeys.limiter().clear();

        // 1. A site configured BEFORE hashing holds its key in plaintext. That is
        //    the shape every existing install has on disk; updateAll skips the
        //    write hook, which is the only way to reproduce it now.
        Row site = setupSite("hohenheim:command", "Api Key Site", "api-key-site", baseSettings());
        int siteId = site.get(SiteModel.ID);
        Map<String, Object> legacy = baseSettings();
        legacy.put(SiteApiKeys.SETTING_NAME, List.of(LEGACY_KEY));
        Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId))
            .assign(SiteModel.SETTINGS, legacy).bypassBehaviours().updateAll();
        assertThat(storedKeys(siteId))
            .as("1. the pre-migration datasource holds the key in plaintext")
            .containsExactly(LEGACY_KEY);

        // 2. A handler built from those settings admits the configured key: the
        //    handler hashes what it reads, so migrating can never strand a key.
        Set<String> beforeMigration = digestsOf(siteId);
        assertThat(beforeMigration)
            .as("2. the handler must hold the digest, never the plaintext")
            .containsExactly(SiteApiKeys.digest(LEGACY_KEY));
        assertThat(SiteApiKeys.decide(siteId, beforeMigration, LEGACY_KEY, "broadcast", IP))
            .as("2. a key configured before hashing must still authenticate")
            .isEqualTo(Decision.ALLOWED);

        // 3. The migration rewrites the stored key to its digest, in place.
        assertThat(SiteApiKeys.hashStoredKeys())
            .as("3. the migration must rewrite exactly the one site holding plaintext")
            .isEqualTo(1);
        List<String> migrated = storedKeys(siteId);
        assertThat(migrated)
            .as("3. the migration must replace the plaintext with its digest")
            .containsExactly(SiteApiKeys.digest(LEGACY_KEY));
        assertThat(migrated.get(0))
            .as("3. no plaintext may survive in the stored value")
            .doesNotContain(LEGACY_KEY);
        assertThat(SiteApiKeys.hashStoredKeys())
            .as("3. re-running the migration must be a no-op")
            .isZero();

        // 4. The operator's configured key still authenticates afterwards, on a
        //    handler rebuilt from the MIGRATED settings.
        Set<String> afterMigration = digestsOf(siteId);
        assertThat(SiteApiKeys.decide(siteId, afterMigration, LEGACY_KEY, "broadcast", IP))
            .as("4. the same configured key must authenticate after the migration")
            .isEqualTo(Decision.ALLOWED);

        // 5. A wrong key is refused outright, never handed to the upstream.
        assertThat(SiteApiKeys.decide(siteId, afterMigration, "not-the-key", "broadcast", IP))
            .as("5. a wrong key must be refused, not proxied")
            .isEqualTo(Decision.REFUSED);

        // 6. A minted key is stored only as a digest and admits by digest.
        String minted = SiteApiKeys.mint();
        appendKey(siteId, SiteApiKeys.digest(minted));
        assertThat(storedKeys(siteId))
            .as("6. a minted key must be stored as a digest, never as plaintext")
            .contains(SiteApiKeys.digest(minted))
            .doesNotContain(minted);
        assertThat(SiteApiKeys.decide(siteId, digestsOf(siteId), minted, "broadcast", IP))
            .as("6. the minted plaintext must authenticate against its stored digest")
            .isEqualTo(Decision.ALLOWED);

        // 7. Repeated wrong keys exhaust the attempt budget.
        SiteApiKeys.limiter().clear();
        Decision throttled = null;
        for (int attempt = 0; attempt < 40 && throttled != Decision.THROTTLED; attempt++) {
            throttled = SiteApiKeys.decide(siteId, afterMigration, "guess-" + attempt, "broadcast", IP);
        }
        assertThat(throttled)
            .as("7. repeated wrong keys must be throttled on the key-checking path")
            .isEqualTo(Decision.THROTTLED);
        assertThat(SiteApiKeys.decide(siteId, afterMigration, LEGACY_KEY, "broadcast", "198.51.100.4"))
            .as("7. the throttle is per client, so another caller is unaffected")
            .isEqualTo(Decision.ALLOWED);

        // 8. Ordinary traffic never spends the budget: with no key headers the
        //    request is not a control request at all, even while throttled.
        assertThat(SiteApiKeys.decide(siteId, afterMigration, null, null, IP))
            .as("8. keyless traffic must not spend the api-key attempt budget")
            .isEqualTo(Decision.NOT_CONTROL);

        // 9. The one-time sweep is wired to boot, not just callable.
        assertThat(Seeds.discovered())
            .as("9. the migration must run itself at boot")
            .anyMatch(seeder -> seeder instanceof SiteApiKeySeeder);
    }

    /**
     * The digest marker validates its COMPLETE shape, adoption enforces the
     * entropy floor, and the write hooks are owned by the discovered
     * HohenheimWriteHooks module (the structural before-STARTHTTP seam).
     */
    @Test
    void digestShapeAndAdoptionPolicy() {
        // 1. Only the complete sha256:<64 lowercase hex> shape is a digest.
        String realDigest = SiteApiKeys.digest("any-key");
        assertThat(SiteApiKeys.isDigest(realDigest))
            .as("1. a real digest must be recognized").isTrue();
        assertThat(SiteApiKeys.isDigest("sha256:looks-like-a-digest-but-is-a-key"))
            .as("1. a plaintext key that merely starts with sha256: is NOT a digest").isFalse();
        assertThat(SiteApiKeys.isDigest("sha256:" + "a".repeat(63)))
            .as("1. 63 hex chars are not a digest").isFalse();
        assertThat(SiteApiKeys.isDigest(realDigest.toUpperCase()))
            .as("1. uppercase hex is not the stored shape").isFalse();

        // 2. A LEGACY plaintext key that happens to start with "sha256:" is hashed
        //    by the sweep like any other plaintext -- it must keep authenticating,
        //    never be misclassified as a digest and silently invalidated.
        String trickyKey = "sha256:looks-like-a-digest-but-is-a-key";
        Row site = setupSite("hohenheim:command", "Tricky Key Site", "tricky-key-site", baseSettings());
        int siteId = site.get(SiteModel.ID);
        Map<String, Object> legacy = baseSettings();
        legacy.put(SiteApiKeys.SETTING_NAME, List.of(trickyKey));
        Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId))
            .assign(SiteModel.SETTINGS, legacy).bypassBehaviours().updateAll();

        assertThat(SiteApiKeys.hashStoredKeys())
            .as("2. the sweep must rewrite the sha256:-prefixed plaintext key")
            .isGreaterThanOrEqualTo(1);
        assertThat(storedKeys(siteId))
            .as("2. the stored value must be the full digest of the tricky key")
            .containsExactly(SiteApiKeys.digest(trickyKey));
        assertThat(SiteApiKeys.decide(siteId, digestsOf(siteId), trickyKey, "broadcast", IP))
            .as("2. the operator's tricky key must still authenticate after the sweep")
            .isEqualTo(Decision.ALLOWED);

        // 3. Adopting a WEAK operator-typed key is refused with the specific
        //    violation: its fast digest would be dictionary-recoverable.
        SiteModel model = Models.get(SiteModel.class);
        Row weakSite = model.findById(siteId);
        Map<String, Object> weakSettings = baseSettings();
        weakSettings.put(SiteApiKeys.SETTING_NAME,
            List.of(SiteApiKeys.digest(trickyKey), "hunter2-password"));
        weakSite.set(SiteModel.SETTINGS, weakSettings);
        Violations violations = catchThrowableOfType(() -> model.save(weakSite), Violations.class);
        assertThat((Object) violations)
            .as("3. adopting a weak key must throw Violations").isNotNull();
        Violation violation = violations.all().get(0);
        assertThat(violation.fieldName())
            .as("3. the violation targets the api_keys field")
            .isEqualTo("settings.api_keys");
        assertThat(violation.message().key())
            .as("3. the violation carries the weak_api_key message")
            .isEqualTo("weak_api_key");
        assertThat(violation.value())
            .as("3. the violation must never echo the credential")
            .isEqualTo("");
        assertThat(storedKeys(siteId))
            .as("3. the refused save must leave the stored keys untouched")
            .containsExactly(SiteApiKeys.digest(trickyKey));

        // 4. A strong operator key (>= 32 chars, varied) is adopted as a digest.
        String strongKey = "operator-key-0123456789abcdefghijklmnop";
        Row strongSite = model.findById(siteId);
        Map<String, Object> strongSettings = baseSettings();
        strongSettings.put(SiteApiKeys.SETTING_NAME, List.of(strongKey));
        strongSite.set(SiteModel.SETTINGS, strongSettings);
        model.save(strongSite);
        assertThat(storedKeys(siteId))
            .as("4. the strong key must be adopted as its digest, never stored plaintext")
            .containsExactly(SiteApiKeys.digest(strongKey));
        assertThat(SiteApiKeys.decide(siteId, digestsOf(siteId), strongKey, "broadcast", IP))
            .as("4. the adopted strong key must authenticate")
            .isEqualTo(Decision.ALLOWED);

        // 5. The write hooks install through the DISCOVERED module, so the ordering
        //    (MODULES stage, before STARTHTTP binds) is structural: this very
        //    refusal in step 3 came from that install path.
        assertThat(ZenitModules.all())
            .as("5. HohenheimWriteHooks must be a discovered ZenitModule")
            .anyMatch(module -> module instanceof HohenheimWriteHooks);
    }

    // ------------------------------------------------------------------

    /** An empty start_command means the handler manages no child process. */
    private static Map<String, Object> baseSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("start_command", "");
        settings.put("minimum_processes", 1);
        return settings;
    }

    /** Build the real site handler from the STORED settings and read its digests. */
    @SuppressWarnings("unchecked")
    private static Set<String> digestsOf(int siteId) {
        Row site = Models.get(SiteModel.class).findById(siteId);
        Map<String, Object> settings = site.get(SiteModel.SETTINGS) instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
        ManagedProcessSiteHandler handler = (ManagedProcessSiteHandler)
            SiteTypes.getHandler("hohenheim:command").createHandler(site, settings);
        try {
            return Set.copyOf(handler.getApiKeyDigests());
        } finally {
            handler.destroy();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> storedKeys(int siteId) {
        Object settings = Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS);
        Object keys = settings instanceof Map<?, ?> map ? map.get(SiteApiKeys.SETTING_NAME) : null;
        return keys instanceof List<?> list ? (List<String>) list : List.of();
    }

    /**
     * Writes a digest straight into the stored settings, bypassing the CMS -- the
     * write hook must leave an already-hashed entry alone.
     */
    private static void appendKey(int siteId, String digest) {
        SiteModel model = Models.get(SiteModel.class);
        Row site = model.findById(siteId);
        Map<String, Object> settings = new LinkedHashMap<>();
        if (site.get(SiteModel.SETTINGS) instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                settings.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        List<String> keys = new ArrayList<>(storedKeys(siteId));
        keys.add(digest);
        settings.put(SiteApiKeys.SETTING_NAME, keys);
        site.set(SiteModel.SETTINGS, settings);
        model.save(site);
    }
}
