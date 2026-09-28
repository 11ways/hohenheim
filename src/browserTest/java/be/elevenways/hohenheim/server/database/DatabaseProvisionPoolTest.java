package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provisioning pool runs every task as system work on its SUBMITTER's behalf: a tenant's allocation stays the
 * tenant's action, and work nobody asked for stays the system's.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
class DatabaseProvisionPoolTest {

    @BeforeAll
    static void boot() {
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void poolWorkIsSystemAuthorityAttributedToWhoeverSubmittedIt() throws Exception {
        // 1. A tenant submits: the task runs with system authority, attributed to the tenant.
        UserPrincipal tenant = new UserPrincipal(4242, "Pool Tenant");
        CompletableFuture<List<Object>> tenantTask = new CompletableFuture<>();
        TenantConduits.as(tenant, () -> DatabaseService.submit(() -> tenantTask.complete(observed())));
        assertThat(tenantTask.get(10, TimeUnit.SECONDS))
            .as("step 1: system authority, the tenant's attribution")
            .containsExactly(true, "4242");

        // 2. A thread with no caller submits: the task is the system's, attributed to nobody.
        CompletableFuture<List<Object>> systemTask = new CompletableFuture<>();
        ExecutionIdentity.runDetachedAsSystem("pool-test",
            () -> DatabaseService.submit(() -> systemTask.complete(observed())));
        assertThat(systemTask.get(10, TimeUnit.SECONDS))
            .as("step 2: system authority, system attribution")
            .containsExactly(true, Accountability.ORIGIN_SYSTEM);
    }

    /** Whether the pool thread is system work, and its attribution's actor (else its origin). */
    private static List<Object> observed() {
        Accountability attribution = Accountability.current();
        return List.of(ExecutionIdentity.isSystem(),
            attribution.actor() != null ? attribution.actor() : attribution.origin());
    }
}
