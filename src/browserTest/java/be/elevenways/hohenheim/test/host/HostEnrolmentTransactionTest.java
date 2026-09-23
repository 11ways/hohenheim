package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.cms.HostEnrolment;
import be.elevenways.hohenheim.server.cms.ServerResource;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Host enrolment is two-phase: the host row commits first, the remote ceremony (key tools, TLS scan,
 * one-use trust token) runs AFTER it and outside any transaction, and a failing ceremony is RECORDED on
 * the committed row instead of rolling it back.
 *
 * WHY IT EXISTS: the ceremony used to run inside the CMS save transaction. A late enrolment failure
 * rolled back the row and the freshly minted client key while the remote daemon kept trusting that
 * certificate and the one-use token was spent, and the SQLite write lock was held across network I/O.
 *
 * No daemon is contacted: the ceremony is replaced by a recording fake through HostEnrolment's seam.
 */
class HostEnrolmentTransactionTest {

    private static final String TOKEN = "enrol-token-one-use";

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void aFailedCeremonyIsRecordedOnTheCommittedHostAndNeverRunsInsideATransaction() {
        Db.run(datasource, () -> {
            ServerResource resource = new ServerResource();
            AccessContext operator = AccessContext.of(TenantConduits.stubFor(null));
            ServerModel servers = Models.get(ServerModel.class);

            // 1. The resource owns its write envelope: the framework must NOT wrap its
            //    create/update in the rollback transaction that used to swallow the row.
            assertThat(resource.verifiesScopeBeforeMutating())
                .as("step 1: the host resource runs its mutations bare (two-phase, owned here)")
                .isTrue();

            // 2. An Incus host whose enrolment the daemon refuses AFTER the token was sent.
            RecordingCeremony refusing = new RecordingCeremony(false, false);
            Object id;
            try (HostEnrolment.Replacement ignored = HostEnrolment.replaceCeremonyForTesting(refusing)) {
                id = resource.persistRow(incusHost("enrol-refused", TOKEN), operator);
            }
            Row created = servers.findById(id);
            assertThat(created)
                .as("step 2: the host row SURVIVES a failed enrolment (it is the pending record)")
                .isNotNull();
            assertThat(refusing.steps)
                .as("step 2: the whole ceremony ran, in order, after the row existed")
                .containsExactly("mint", "pin", "enroll:" + TOKEN);
            assertThat(refusing.insideTransaction)
                .as("step 2: no ceremony step ran inside a write transaction")
                .containsOnly(false);
            assertThat((String) created.get(ServerModel.LAST_ERROR_KIND))
                .as("step 2: the refusal is recorded as a typed failure on the host")
                .isEqualTo(HostProbe.FailureKind.UNTRUSTED.token);
            assertThat((String) created.get(ServerModel.LAST_ERROR))
                .as("step 2: with the daemon's own reason, which the Overview renders")
                .contains("token already spent");
            assertThat((String) created.get(ServerModel.ADMISSION))
                .as("step 2: the host stays blocked -- nothing was admitted by a half ceremony")
                .isEqualTo(ServerModel.ADMISSION_BLOCKED);
            assertThat(storedText(created))
                .as("step 2: the one-use token is never stored anywhere on the row")
                .doesNotContain(TOKEN);

            // 3. A retry with a fresh token completes, and the proven trust clears the record.
            RecordingCeremony accepting = new RecordingCeremony(false, true);
            try (HostEnrolment.Replacement ignored = HostEnrolment.replaceCeremonyForTesting(accepting)) {
                Map<String, Object> retry = new LinkedHashMap<>();
                retry.put("incus_trust_token", "fresh-token");
                resource.updateRow(servers.findById(id), Map.copyOf(retry), operator);
            }
            Row retried = servers.findById(id);
            assertThat(accepting.steps)
                .as("step 3: an update spends the token without re-minting identities")
                .containsExactly("pin", "enroll:fresh-token");
            assertThat((String) retried.get(ServerModel.LAST_ERROR_KIND))
                .as("step 3: a completed enrolment clears the recorded failure")
                .isNull();
            assertThat(retried.get(ServerModel.LAST_SEEN_AT))
                .as("step 3: and counts as a contact, because the daemon answered trusted")
                .isNotNull();

            // 4. A failing identity mint on a Docker host: recorded, not rolled back.
            RecordingCeremony keyless = new RecordingCeremony(true, true);
            Object dockerId;
            try (HostEnrolment.Replacement ignored = HostEnrolment.replaceCeremonyForTesting(keyless)) {
                dockerId = resource.persistRow(Map.of(
                    "name", "enrol-keyless",
                    "runtime", ServerModel.RUNTIME_DOCKER,
                    "ssh_target", "deploy@keyless.example.test"), operator);
            }
            Row keylessRow = servers.findById(dockerId);
            assertThat(keylessRow).as("step 4: the docker host row survives a failed mint").isNotNull();
            assertThat((String) keylessRow.get(ServerModel.LAST_ERROR_KIND))
                .as("step 4: recorded as a missing identity")
                .isEqualTo(HostProbe.FailureKind.NO_IDENTITY.token);
            assertThat(keylessRow.get(ServerModel.MODE))
                .as("step 4: phase one still staged the docker transport")
                .isEqualTo(ServerModel.MODE_SSH);

            // 5. A token on a host it can never enrol on is refused BEFORE phase one writes.
            RecordingCeremony untouched = new RecordingCeremony(false, true);
            try (HostEnrolment.Replacement ignored = HostEnrolment.replaceCeremonyForTesting(untouched)) {
                assertThatThrownBy(() -> resource.persistRow(Map.of(
                        "name", "enrol-wrong-lane",
                        "runtime", ServerModel.RUNTIME_DOCKER,
                        "ssh_target", "deploy@wrong.example.test",
                        "incus_trust_token", TOKEN), operator))
                    .as("step 5: a trust token on a docker host is refused")
                    .isInstanceOf(Violations.class);
            }
            assertThat(servers.findByName("enrol-wrong-lane"))
                .as("step 5: and no row was written for the refused create")
                .isNull();
            assertThat(untouched.steps)
                .as("step 5: nor did any ceremony step run")
                .isEmpty();

            // 6. The enrolment itself refuses to run inside a write transaction.
            assertThatThrownBy(() -> datasource.withTransaction(
                    transaction -> HostEnrolment.afterUpdate(id, null)))
                .as("step 6: enrolment inside a transaction is a programming error, refused loudly")
                .hasStackTraceContaining("outside any write transaction");
        });
    }

    /** Every stored column of the host as one text, table-stored sub-schemas skipped. */
    private static @NonNull String storedText(@NonNull Row row) {
        StringBuilder text = new StringBuilder();
        for (Field<?, ?> field : ServerModel.SCHEMA.getFields().values()) {
            if (field instanceof SchemaField schema && schema.isTableStored()) {
                continue;
            }
            text.append(field.getName()).append('=').append((Object) row.get(field)).append('\n');
        }
        return text.toString();
    }

    private static @NonNull Map<String, Object> incusHost(@NonNull String name, @NonNull String token) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("runtime", ServerModel.RUNTIME_INCUS);
        values.put("incus_url", "https://" + name + ".example.test:8443");
        values.put("incus_trust_token", token);
        return Map.copyOf(values);
    }

    /** A ceremony that records every step and whether it ran inside a transaction. */
    private static final class RecordingCeremony implements HostEnrolment.Ceremony {

        private final boolean failMint;
        private final boolean acceptToken;
        private final List<String> steps = new ArrayList<>();
        private final List<Boolean> insideTransaction = new ArrayList<>();

        private RecordingCeremony(boolean failMint, boolean acceptToken) {
            this.failMint = failMint;
            this.acceptToken = acceptToken;
        }

        @Override
        public void mintIdentities(@NonNull Row server) {
            this.record("mint");
            if (this.failMint) {
                throw Violations.ofForm(CmsSupport.violationText("identity_generation_failed")
                    .withArg("detail", "ssh-keygen is not installed"));
            }
        }

        @Override
        public HostKeys.@NonNull ScanResult pinIncusCertificate(@NonNull Row server) {
            this.record("pin");
            return new HostKeys.ScanResult(HostKeys.ScanOutcome.PINNED, "ab:cd", null);
        }

        @Override
        public void enrollWithToken(@NonNull Row server, @NonNull String token) {
            this.record("enroll:" + token);
            if (!this.acceptToken) {
                throw Violations.ofForm(CmsSupport.violationText("incus_enroll_failed")
                    .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME)))
                    .withArg("detail", "token already spent"));
            }
        }

        private void record(@NonNull String step) {
            this.steps.add(step);
            this.insideTransaction.add(datasource.hasActiveTransaction());
        }
    }
}
