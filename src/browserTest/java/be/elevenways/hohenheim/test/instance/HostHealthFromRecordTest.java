package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.cms.InstanceAttention;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceStatusReconciler;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.RenewalFailure;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Health is read from the record, never from its history: a crashed workload, a workload whose start failed and a
 * certificate whose renewal failed read exactly the same once their activity rows are gone.
 *
 * AIDEV-NOTE: audit A3-13 (folio-history-DESIGN-v3.2 G.7, P3). Health once parsed activity rows and their English
 * detail; the history wave prunes those rows and stops writing that detail, so health would have changed with it.
 * Every fact here is written by production's own lanes (the reconciler, a failed restart, the renewal bookkeeping).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class HostHealthFromRecordTest {

    private static final LocaleChain EN = LocaleChain.ofTags("en");

    private static SqlDatasource datasource;
    private static int hostId;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        FakeNativeDaemons.register();
        Db.run(datasource, () -> hostId = HostFixtures.admittedIncusHost("health-record-host"));
    }

    @Test
    void healthReadsTheSameWithTheHistoryEmptied() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();

            // 1. A workload that opted out of restarts dies with nobody watching: the reconciler corrects it to error.
            int crashed = BackupLaneFixture.instanceRecord("health-crashed", hostId);
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(crashed))
                .assign(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_NONE).updateAll();
            service.deploy(crashed);
            FakeNativeDaemons.daemonOf(hostId).get(FakeNativeDaemons.handleOf(crashed)).running = false;
            new InstanceStatusReconciler().reconcile(crashed);

            // 2. A workload whose restart cannot start it again: a foreign owner took its container mid-stop.
            int failed = BackupLaneFixture.instanceRecord("health-start-failed", hostId);
            service.deploy(failed);
            String handle = FakeNativeDaemons.handleOf(failed);
            boolean[] stolen = {false};
            InstanceService sabotaged = new InstanceService(HostLeases.production(), () -> {
                if (!stolen[0]) {
                    stolen[0] = true;
                    FakeNativeDaemons.daemonOf(hostId).get(handle).ownerId = "999999999";
                }
            });
            assertThat(catchThrowable(() -> sabotaged.restart(failed))).as("step 2: the restart is refused")
                .isNotNull();

            // 3. A certificate whose renewal failed, its bookkeeping saved as the renewal sweep saves it.
            CertificateModel certificates = Models.get(CertificateModel.class);
            Row cert = certificates.createEmptyRow();
            cert.set(CertificateModel.NICE_NAME, "health-record.test");
            cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
            cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
            cert.set(CertificateModel.DOMAIN_NAMES_TEXT, "health-record.test");
            certificates.save(cert);
            int certId = cert.get(CertificateModel.ID);
            AcmeService.recordRenewalFailure(cert, "This installation does not serve health-record.test");
            certificates.save(cert);

            // 4. Each reads its health: the crash in words and why nothing restarted it, the failed start as one, and
            //    the renewal failure with its moment.
            Map<String, String> before = readings(crashed, failed, certId);
            assertThat(before.get("crashed")).as("step 4: the crash is named, and why nothing restarted it")
                .contains("health-crashed exited unexpectedly and was not restarted")
                .contains("Its crash policy does not restart it");
            assertThat(before.get("failed")).as("step 4: the failed start is named as one")
                .contains("health-start-failed could not be started").contains("Technically: ");
            assertThat(before.get("certificate")).as("step 4: the renewal failure has its moment")
                .doesNotEndWith("@null");

            // 5. Their history is emptied: every reading is the same.
            emptyHistory(InstanceModel.MODEL_ID.toString(), crashed);
            emptyHistory(InstanceModel.MODEL_ID.toString(), failed);
            emptyHistory(CertificateModel.MODEL_ID.toString(), certId);
            assertThat(Models.get(ActivityModel.class).find()
                .where(ActivityModel.RECORD_ID.in(List.of(String.valueOf(crashed), String.valueOf(failed))))
                .where(ActivityModel.MODEL.eq(InstanceModel.MODEL_ID.toString())).all())
                .as("step 5: the workloads keep no history").isEmpty();
            assertThat(readings(crashed, failed, certId)).as("step 5: health reads exactly as before")
                .isEqualTo(before);

            FakeNativeDaemons.daemonOf(hostId).remove(FakeNativeDaemons.handleOf(crashed));
            FakeNativeDaemons.daemonOf(hostId).remove(handle);
            certificates.delete(certId);
        });
    }

    /** Deletes every activity row of one record. */
    private static void emptyHistory(String model, int recordId) {
        Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(model))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(recordId)))
            .delete();
    }

    /** @return each subject's health as the dashboard and the certificate pages read it, in English */
    private static Map<String, String> readings(int crashed, int failed, int certId) {
        List<AttentionItem> items = new ArrayList<>();
        InstanceAttention.crashedInstances(items);
        Map<String, String> readings = new LinkedHashMap<>();
        readings.put("crashed", itemOf(items, crashed));
        readings.put("failed", itemOf(items, failed));
        RenewalFailure failure = Objects.requireNonNull(
            RenewalFailure.of(Models.get(CertificateModel.class).findById(certId)), "the certificate is failing");
        readings.put("certificate", failure.reason() + "@" + failure.at());
        return readings;
    }

    /** @return the dashboard item of one workload as its title, detail and technical line read */
    private static String itemOf(List<AttentionItem> items, int instanceId) {
        AttentionItem item = items.stream().filter(candidate -> candidate.target().toUrl()
            .contains("/instances/" + instanceId + "/")).findFirst().orElseThrow();
        return text(item.title()) + " | " + text(item.detail()) + " | " + text(item.note());
    }

    private static String text(Microcopy words) {
        return words == null ? "" : words.resolve(EN, Zenit.getMessageResolver());
    }
}
