package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.source.GitProviderOperations;
import be.elevenways.hohenheim.source.GitProviderOperations.ConnectionTest;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * The handler of the git provider connection test, attached once per JVM.
 *
 * AIDEV-NOTE: the probe is an OUTBOUND request to a URL the record's author chose, and on /manage that author is a
 * tenant, so it rides THE provider client ({@link GitProviders#clientFor}), whose outbound guard is decided by the
 * ROW's ownership (SourceOwnership.providerGuard: a tenant-owned provider reaches public addresses only, on either
 * panel), never a request built here. {@link #init()} only forces the class to load before boot verifies every
 * operation has its handler.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class GitProviderOperationHandlers {

    static {
        OperationHandlers.attach(GitProviderOperations.TEST_CONNECTION).handle(call -> probe(call.subject()));
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
