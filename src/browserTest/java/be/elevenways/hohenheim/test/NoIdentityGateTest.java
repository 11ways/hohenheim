package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Pins that HohenheimAccess's service gates fail CLOSED: only a declared system identity passes
 * unjudged, work with no identity at all is refused, and work a request schedules is judged as
 * that request's caller instead of passing as system.
 */
class NoIdentityGateTest {

    private static final int ANY_INSTANCE = 424242;
    private static final int ANY_DATABASE = 434343;

    /** @return what the gates answer on the calling thread, one line per gate */
    private static String gateAnswers() {
        return answer("operation", () -> HohenheimAccess.requireOperationCapability(ANY_INSTANCE,
                HohenheimAccess.POWER))
            + " " + answer("destroy", () -> HohenheimAccess.requireDestroyPermitted(ANY_INSTANCE))
            + " " + answer("operator", HohenheimAccess::requireOperatorOperation)
            + " " + answer("database", () -> HohenheimAccess.requireDatabaseCapability(ANY_DATABASE,
                HohenheimAccess.DESTROY));
    }

    private static String answer(String gate, Runnable check) {
        Throwable thrown = catchThrowable(check::run);
        if (thrown == null) {
            return gate + "=pass";
        }
        return gate + (thrown instanceof Violations ? "=refused" : "=" + thrown);
    }

    @Test
    void theGatesRefuseWorkThatDeclaredNoIdentityJourney() throws Exception {
        // 1. Declared system work passes every gate unjudged (the harness runs this body as it).
        assertThat(ExecutionIdentity.isSystem()).as("step 1: the harness declares the operator").isTrue();
        assertThat(gateAnswers()).as("step 1: system work passes")
            .isEqualTo("operation=pass destroy=pass operator=pass database=pass");

        // 2. The SAME calls with no identity are refused: absence is never read as system.
        AtomicReference<String> bare = new AtomicReference<>();
        ExecutionIdentity.run(null, () -> {
            assertThat(TenantWrites.isTenantOriginated())
                .as("step 2: work with no identity is judged, never waved through").isTrue();
            assertThat(TenantWrites.acting()).as("step 2: and it has no caller to judge").isNull();
            bare.set(gateAnswers());
        });
        assertThat(bare.get()).as("step 2: every gate refuses work with no identity")
            .isEqualTo("operation=refused destroy=refused operator=refused database=refused");

        // 3. Work scheduled with no identity runs with none on its new thread, and is refused there too.
        AtomicReference<String> scheduledBare = new AtomicReference<>();
        CountDownLatch bareRan = new CountDownLatch(1);
        ExecutionIdentity.run(null, () -> JobRunner.startVirtualThread(() -> {
            scheduledBare.set(gateAnswers());
            bareRan.countDown();
        }));
        assertThat(bareRan.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduledBare.get()).as("step 3: a hop never turns absence into system")
            .isEqualTo("operation=refused destroy=refused operator=refused database=refused");

        // 4. Work a tenant's request schedules runs as that tenant on its new thread -- the
        //    continuation used to read as system there and pass every gate.
        UserPrincipal tenant = new UserPrincipal(987_654, "Tenant");
        AtomicReference<AccessContext> continuationCaller = new AtomicReference<>();
        AtomicReference<Boolean> continuationTenant = new AtomicReference<>();
        AtomicReference<String> continuationAnswer = new AtomicReference<>();
        CountDownLatch continued = new CountDownLatch(1);
        TenantConduits.as(tenant, () -> JobRunner.startVirtualThread(() -> {
            continuationCaller.set(TenantWrites.acting());
            continuationTenant.set(TenantWrites.isTenantOriginated());
            continuationAnswer.set(answer("operator", HohenheimAccess::requireOperatorOperation));
            continued.countDown();
        }));
        assertThat(continued.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(continuationCaller.get()).as("step 4: the continuation has a caller").isNotNull();
        assertThat(continuationCaller.get().principalId())
            .as("step 4: and it is the tenant who scheduled it").isEqualTo(987_654L);
        assertThat(continuationTenant.get()).as("step 4: judged as the tenant").isTrue();
        assertThat(continuationAnswer.get()).as("step 4: an operator-only act refuses the tenant's continuation")
            .isEqualTo("operator=refused");

        // 5. And the operator's own thread is untouched by all of it.
        assertThatCode(HohenheimAccess::requireOperatorOperation)
            .as("step 5: the harness thread is still system").doesNotThrowAnyException();
    }
}
