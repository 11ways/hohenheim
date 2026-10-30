package be.elevenways.hohenheim.test.instance;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.PanelEntryViews;
import be.elevenways.hohenheim.test.PlacedActionClicks;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.zenit.common.orm.activity.DeltaSide;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.ChangeOp;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.history.HistoryEntry;
import be.elevenways.zenit.common.orm.history.HistoryFolding;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import be.elevenways.zenit.test.support.TestAccessContexts;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an instance's history records of its operation state, and how an SFTP client's uploads fold on it.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class InstanceHistoryJourneyTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.standalone();

    private static final UUID OPERATOR = Uuids.v7();
    private static final UUID SFTP_WRITER = Uuids.v7();
    private static final String PINNED = "sha256:pinned";

    private static SqlDatasource datasource;
    private static int hostId;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = APP.database();
        FakeNativeDaemons.register();
        Db.run(datasource, () -> hostId = HostFixtures.admittedIncusHost("history-host"));
    }

    @Test
    void anInstanceRecordsItsOperationStateBesideTheActsThatChangeIt() {
        Db.run(datasource, () -> {
            InstanceModel instances = Models.get(InstanceModel.class);

            // 1. A create records the first status, install state and runtime role, and the pin it was made with.
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, "history-capture");
            row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
            row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
            row.set(InstanceModel.SERVER_ID, hostId);
            row.set(InstanceModel.IMAGE_FINGERPRINT, PINNED);
            instances.save(row);
            int id = row.get(InstanceModel.ID);
            Map<String, Map<String, Object>> created = RecordedChanges.of(instances, id, ChangeOp.CREATE).get(0).delta();
            assertThat(after(created, InstanceModel.STATUS.getName())).as("step 1: the first status")
                .isEqualTo(InstanceModel.STATUS_CREATED);
            assertThat(after(created, InstanceModel.INSTALL_STATE.getName())).as("step 1: the first install state")
                .isEqualTo(InstanceModel.INSTALL_NONE);
            assertThat(after(created, InstanceModel.RUNTIME_ROLE.getName())).as("step 1: the first runtime role")
                .isEqualTo(InstanceModel.ROLE_SERVING);
            assertThat(after(created, InstanceModel.IMAGE_FINGERPRINT.getName())).as("step 1: the pin")
                .isEqualTo(PINNED);
            assertThat(created).as("step 1: an unrecorded stamp stays out of the create")
                .doesNotContainKeys(InstanceModel.CLAIM_FENCE.getName(), InstanceModel.STARTED_AT.getName());

            // 2. A configuration save that changes the image clears the pin inside that same recorded change.
            Row configured = instances.findById(id);
            configured.set(InstanceModel.SETTINGS, Map.of("image", "fake/other"));
            InstanceModel.saveConfiguration(configured);
            List<RecordedChanges.Recorded> updates = RecordedChanges.of(instances, id, ChangeOp.UPDATE);
            assertThat(updates).as("step 2: the save is one update").hasSize(1);
            Map<String, Object> pin = updates.get(0).delta().get(InstanceModel.IMAGE_FINGERPRINT.getName());
            assertThat(pin).as("step 2: the update names the pin").isNotNull();
            assertThat(DeltaSide.BEFORE.of(pin)).as("step 2: as it was").isEqualTo(PINNED);
            assertThat(DeltaSide.AFTER.of(pin)).as("step 2: cleared").isNull();
            assertThat(updates.get(0).delta()).as("step 2: beside the image change itself")
                .containsKey(InstanceModel.SETTINGS.getName());

            // 3. A fenced stamp writing the value already stored records nothing; one that changes it records it.
            InstanceService service = new InstanceService();
            int recorded = RecordedChanges.of(instances, id).size();
            service.assignRuntimeRole(id, InstanceModel.ROLE_SERVING);
            assertThat(RecordedChanges.of(instances, id)).as("step 3: the same role records no change")
                .hasSize(recorded);
            service.assignRuntimeRole(id, InstanceModel.ROLE_RETIRED);
            List<RecordedChanges.Recorded> afterRetire = RecordedChanges.of(instances, id);
            List<Map<String, Object>> roles = afterRetire.subList(recorded, afterRetire.size()).stream()
                .map(change -> change.delta().get(InstanceModel.RUNTIME_ROLE.getName()))
                .filter(Objects::nonNull).toList();
            assertThat(roles).as("step 3: another role is recorded once").hasSize(1);
            assertThat(List.of(DeltaSide.BEFORE.of(roles.get(0)), DeltaSide.AFTER.of(roles.get(0))))
                .as("step 3: from and to").containsExactly(InstanceModel.ROLE_SERVING, InstanceModel.ROLE_RETIRED);
            service.assignRuntimeRole(id, InstanceModel.ROLE_SERVING);

            // 4. A power operation's status change is part of its action, beside its fact: one entry, not two.
            AccessContext reader = TestAccessContexts.allAllowed();
            int entries = History.ofRecord(instances, id, reader, null, 100).entries().size();
            int before = RecordedChanges.of(instances, id).size();
            var panel = PanelEntryViews.of(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES);
            Row panelRow = instances.findById(id);
            Accountability.runAs(operator(OPERATOR), () -> PlacedActionClicks.click(
                PlacedActionClicks.placed(panel, "deploy_instance"), panelRow));
            List<RecordedChanges.Recorded> deployed = RecordedChanges.withFact(instances, id,
                HohenheimActivityAction.DEPLOYED);
            assertThat(deployed).as("step 4: the start recorded its fact").hasSize(1);
            List<RecordedChanges.Recorded> written = RecordedChanges.of(instances, id);
            List<RecordedChanges.Recorded> statusChanges = written.subList(before, written.size()).stream()
                .filter(change -> change.delta().containsKey(InstanceModel.STATUS.getName())).toList();
            assertThat(statusChanges).as("step 4: the start moved the status").isNotEmpty();
            assertThat(statusChanges).as("step 4: in the action carrying the fact")
                .allSatisfy(change -> assertThat(change.actionId()).isEqualTo(deployed.get(0).actionId()));
            assertThat(DeltaSide.AFTER.of(statusChanges.get(statusChanges.size() - 1).delta()
                .get(InstanceModel.STATUS.getName()))).as("step 4: to running").isEqualTo(InstanceModel.STATUS_RUNNING);
            List<HistoryEntry> history = History.ofRecord(instances, id, reader, null, 100).entries();
            assertThat(history).as("step 4: the instance's history gained one entry").hasSize(entries + 1);
            assertThat(history.get(0).action().id()).as("step 4: the start's").isEqualTo(deployed.get(0).actionId());

            FakeNativeDaemons.daemonOf(hostId).remove(FakeNativeDaemons.handleOf(id));
        });
    }

    @Test
    void anSftpClientsConsecutiveUploadsReadAsOneEntry() {
        Db.run(datasource, () -> {
            InstanceModel instances = Models.get(InstanceModel.class);
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, "history-uploads");
            row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
            row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
            row.set(InstanceModel.SERVER_ID, hostId);
            instances.save(row);
            int id = row.get(InstanceModel.ID);
            AccessContext reader = TestAccessContexts.allAllowedReading(LocaleChain.ofTags("en"),
                ShippedCatalogs.shared());
            Duration quiet = Duration.ofMinutes(Objects.requireNonNull(
                HohenheimSettings.Sftp.IDLE_TIMEOUT_MINUTES.getDefaultValue()));
            try {
                // 1. Three consecutive uploads by one SFTP client read as one entry on the instance's history.
                for (String path : List.of("/data/a.txt", "/data/b.txt", "/data/c.txt")) {
                    upload(id, path);
                }
                List<HistoryFolding.Fold> folds = History.ofRecord(instances, id, reader, null, 100).folds();
                assertThat(folds.get(0).kind()).as("step 1: the uploads fold as one session")
                    .isEqualTo(HistoryFolding.Kind.SESSION);
                assertThat(folds.get(0).entries()).as("step 1: of all three").hasSize(3);
                assertThat(HistoryFolding.heading(folds.get(0), reader).resolve(reader.locales(),
                    ShippedCatalogs.shared())).as("step 1: worded as the batch").isEqualTo("Uploaded 3 files");

                // 2. An upload after the quiet period, the SFTP idle timeout, is a new entry.
                Now.setOffset(quiet.plusMinutes(1));
                upload(id, "/data/d.txt");
                folds = History.ofRecord(instances, id, reader, null, 100).folds();
                assertThat(folds.get(0).kind()).as("step 2: the later upload stands alone")
                    .isEqualTo(HistoryFolding.Kind.ACTION);
                assertThat(folds.get(1).kind()).as("step 2: the earlier three stay one session")
                    .isEqualTo(HistoryFolding.Kind.SESSION);
                assertThat(folds.get(1).entries()).as("step 2: of three").hasSize(3);
            } finally {
                Now.setOffset(Duration.ZERO);
            }
        });
    }

    /** Records one upload the way the file manager's verb does, as one SFTP client. */
    private static void upload(int instanceId, @NonNull String path) {
        Accountability client = new Accountability(SFTP_WRITER.toString(),
            PrincipalRef.account(SFTP_WRITER).storedKind(), "SFTP writer", "10.0.0.2", "SSH-2.0-test",
            AccountabilityOrigin.SFTP);
        Accountability.runAs(client, () -> History.event(Models.get(InstanceModel.class), instanceId,
            Change.fact(HohenheimActivityAction.FILES_UPLOAD, Map.of(HohenheimActivityAction.PATH_KEY, path))));
    }

    private static Object after(@NonNull Map<String, Map<String, Object>> delta, @NonNull String field) {
        return DeltaSide.AFTER.of(delta.get(field));
    }

    private static Accountability operator(@NonNull UUID id) {
        return new Accountability(id.toString(), PrincipalRef.account(id).storedKind(), "Operator " + id,
            "10.0.0.1", "junit", AccountabilityOrigin.WEB);
    }
}
