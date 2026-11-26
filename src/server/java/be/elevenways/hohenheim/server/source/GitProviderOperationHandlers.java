package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.source.GitProviderOperations;
import be.elevenways.hohenheim.source.GitProviderOperations.ConnectionTest;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.dry.BlastDrySerializers;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The handler of the git provider connection test, attached once per JVM.
 *
 * AIDEV-NOTE: the probe is an OUTBOUND request to a URL the record's author chose, and on /manage that author is a
 * tenant, so it rides THE provider client ({@link GitProviders#clientFor}), whose outbound guard is decided by the
 * ROW's ownership (SourceOwnership.providerGuard: a tenant-owned provider reaches public addresses only, on either
 * panel), never a request built here. It autoloads, so the receipt's serializer is in before boot verifies every
 * operation and before any replay reads one; {@link #init()} only forces the class to load where a boot asks early.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@BlastAutoLoad
public final class GitProviderOperationHandlers {

    /** The handler and the receipt's serializer, attached once the class loads. */
    public static final boolean LOADED = install();

    private static boolean install() {
        // The test is a command: its outcome is stored as the receipt a retried call answers, so it must serialize.
        BlastDrySerializers.addCustomRegistration(registry -> {
            registry.registerSerializer(ConnectionTest.class, (test, context) -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("repositories", test.repositories());
                map.put("failure", test.failure());
                return map;
            });
            registry.registerReviver(ConnectionTest.class,
                (data, context) -> new ConnectionTest(data.getInteger("repositories"), data.getString("failure")));
        });
        OperationHandlers.attach(GitProviderOperations.TEST_CONNECTION).handle(call -> probe(call.subject()));
        return true;
    }

    private GitProviderOperationHandlers() {
    }

    /** Loads the class, attaching the handler; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    /** @return the repository count, or the client's reason, which is also logged */
    public static @NonNull ConnectionTest probe(@NonNull Row provider) {
        try {
            return new ConnectionTest(GitProviders.clientFor(provider).listRepositories().size(), null);
        } catch (Exception unhealthy) {
            String reason = String.valueOf(HohenheimViolations.reasonOf(unhealthy));
            Blast.slog("hohenheim.git_provider.test_failed", Map.of(
                "provider", String.valueOf((Object) provider.get(GitProviderModel.ID)),
                "reason", reason));
            return new ConnectionTest(null, reason);
        }
    }
}
