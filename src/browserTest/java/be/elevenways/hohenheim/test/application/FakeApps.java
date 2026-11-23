package be.elevenways.hohenheim.test.application;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A class's lane of authored apps over {@link FakeDockerDaemon}: the daemon installed, the release settings a test
 * deploy needs (a short probe, no drain), the local host admitted, and apps whose image the daemon answers for.
 *
 * AIDEV-NOTE: {@link #close} puts every setting it changed back and restores the daemon, so a class beside this one
 * sees the installation it had.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class FakeApps implements AutoCloseable {

    private final FakeDockerDaemon daemon;
    private final Integer savedProbeTimeout;
    private final Integer savedProbeInterval;
    private final Integer savedDrain;
    private final String savedDataPath;

    private FakeApps(@NonNull FakeDockerDaemon daemon) {
        this.daemon = daemon;
        this.savedProbeTimeout = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        this.savedProbeInterval = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        this.savedDrain = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        this.savedDataPath = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
    }

    /** Install the lane over the class's database; close it in the class's teardown. */
    public static @NonNull FakeApps install(@NonNull SqlDatasource datasource) throws Exception {
        FakeDockerDaemon daemon = new FakeDockerDaemon();
        daemon.install();
        FakeApps lane = new FakeApps(daemon);
        try {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 2);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, 50);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 0);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, "/srv/hoh-test");
            Db.run(datasource, HostFixtures::admitLocal);
        } catch (RuntimeException failed) {
            lane.close();
            throw failed;
        }
        return lane;
    }

    public @NonNull FakeDockerDaemon daemon() {
        return this.daemon;
    }

    /** An authored app record with an image source the fake daemon answers for, at this tag. */
    public static int application(@NonNull String name, @NonNull String tag) {
        Row application = Models.get(InstanceModel.class).createEmptyRow();
        application.set(InstanceModel.NAME, name);
        application.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        application.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        application.set(InstanceModel.SETTINGS, settingsFor(tag));
        Models.get(InstanceModel.class).save(application);
        return application.get(InstanceModel.ID);
    }

    /** Point the app at another tag of the fake image: its next deploy is a new version. */
    public static void setSettings(int applicationId, @NonNull String tag) {
        Row application = Models.get(InstanceModel.class).findById(applicationId);
        application.set(InstanceModel.SETTINGS, settingsFor(tag));
        Models.get(InstanceModel.class).save(application);
    }

    /** @return the settings of an app serving the fake image at this tag on port 8080 */
    public static @NonNull Map<String, Object> settingsFor(@NonNull String tag) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", tag);
        settings.put("container_port", 8080);
        return settings;
    }

    @Override
    public void close() {
        FakeDockerDaemon.restore();
        this.daemon.close();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, this.savedProbeTimeout);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, this.savedProbeInterval);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, this.savedDrain);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, this.savedDataPath);
    }
}
