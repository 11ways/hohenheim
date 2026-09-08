package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ArtifactOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.BootSettle;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactSourceTest {
    private static SqlDatasource datasource;
    private static String previousDataPath;
    @TempDir static Path tmp;

    @BeforeAll static void setup() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        previousDataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, tmp.toString());
        Db.run(datasource, HostFixtures::admitLocal);
    }

    @AfterAll static void cleanup() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, previousDataPath);
    }

    @Test void restoredSourceSurvivesFailedDeployAndCannotOverwriteAnExistingApplication() throws Exception {
        Path jar = ArtifactStagingTest.jar(tmp.resolve("restored.jar"), "restored");
        Db.run(datasource, () -> {
            int app = application("artifact-restore");
            int site = site(app, "artifact-restore");
            try {
                ArtifactDeploys.restoreSource(app, jar);
                Map<String, Object> source = ArtifactDeploys.sourceOverrides(app);
                Path accepted = ArtifactDeploys.acceptedArtifact(app);
                assertThat(Files.readAllBytes(accepted)).isEqualTo(Files.readAllBytes(jar));
                assertThat(ApplicationReleases.ownedInstances(app)).isEmpty();
                assertThat(Models.get(ArtifactOperationModel.class).find()
                    .where(ArtifactOperationModel.APPLICATION_ID.eq(app)).all()).isEmpty();
                assertThatThrownBy(() -> source.put("commit_sha", "changed"))
                    .isInstanceOf(UnsupportedOperationException.class);
                Path failed = Files.writeString(ArtifactDeploys.uploadPathFor(app), "not a jar");
                Row operation = ArtifactDeploys.accept(site, app, failed);
                int id = operation.get(ArtifactOperationModel.ID);
                ArtifactDeploys.run(id, failed, DeployTrigger.API);
                assertThat(ArtifactDeploys.operation(site, app, id)).containsEntry("status", "failed");
                assertThat(failed).doesNotExist();
                assertThat(ArtifactDeploys.sourceOverrides(app)).isEqualTo(source);
                assertThatThrownBy(() -> ArtifactDeploys.restoreSource(app, jar)).isInstanceOf(IllegalStateException.class);
            } catch (IOException failed) { throw new RuntimeException(failed); }
        });
    }

    @Test void bootInterruptsOldReceiptsButLeavesNewWorkAndAcceptedSourceUntouched() throws Exception {
        Db.run(datasource, () -> {
            int app = application("artifact-recovery");
            int site = site(app, "artifact-recovery");
            try {
                Path old = Files.writeString(ArtifactDeploys.uploadPathFor(app), "old partial upload");
                Row prior = ArtifactDeploys.accept(site, app, old);
                int oldId = prior.get(ArtifactOperationModel.ID);
                datasource.rawUpdate("UPDATE artifact_operations SET created_at = '2000-01-01T00:00:00Z', updated_at = '2000-01-01T00:00:00Z' WHERE id = " + oldId);
                Files.setLastModifiedTime(old, FileTime.from(Instant.EPOCH));
                Path current = Files.writeString(ArtifactDeploys.uploadPathFor(app), "current upload");
                Row live = ArtifactDeploys.accept(site, app, current);
                int liveId = live.get(ArtifactOperationModel.ID);
                ArtifactDeploys.recoverInterrupted();
                assertThat(ArtifactDeploys.operation(site, app, oldId)).containsEntry("status", "interrupted");
                assertThat(ArtifactDeploys.operation(site, app, liveId)).containsEntry("status", "pending");
                assertThat(old).doesNotExist();
                assertThat(current).exists();
                ArtifactDeploys.handoffFailed(live);
                ArtifactDeploys.deleteUpload(current);
            } catch (IOException failed) { throw new RuntimeException(failed); }
        });
    }

    @Test void bootReclaimsUploadsWithoutReceiptsWithoutTouchingCurrentRequests() throws Exception {
        Path uploads = Files.createDirectories(tmp.resolve("orphan-uploads"));
        Path orphan = Files.writeString(uploads.resolve("orphan.jar"), "partial");
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.EPOCH));
        Path current = Files.writeString(uploads.resolve("current.jar"), "live");
        ArtifactDeploys.cleanupUploads(uploads, BootSettle.processStart());
        assertThat(orphan).doesNotExist();
        assertThat(current).exists();
    }

    private static int application(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        row.set(InstanceModel.SETTINGS, Map.of());
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int site(int app, String name) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, name);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:instance");
        row.set(SiteModel.INSTANCE_ID, app);
        row.set(SiteModel.ENABLED, false);
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }
}
