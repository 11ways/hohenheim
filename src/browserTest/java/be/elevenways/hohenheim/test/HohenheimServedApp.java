package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.HohenheimHost;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.hohenheim.test.live.LiveNamespaces;
import be.elevenways.zenit.auth.SessionStoreKind;
import be.elevenways.zenit.auth.test.AuthServedApp;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.test.support.MigratedDatabase;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.zenit.test.support.ServedAppBinding;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE Hohenheim app a test class runs on: zenit's served app over a migrated SQLite file of the class, booted through
 * the {@link HohenheimHost} main hands the runtime, with every role declared and the task service stopped.
 *
 * AIDEV-NOTE: the task service stays stopped on every class database: a service reconciles and boot-fires (~1s) as it
 * starts, and its cron ticks are a cross-class hazard (SuperviseProxyListeners mutating the counters a proxy test is
 * asserting). A test needing live scheduling opts in with TaskRuntime.optIn, or starts its own service.
 *
 * AIDEV-NOTE: what ServerMain starts after the stages (the listeners, the recovery sweeps) is no part of a class: a
 * test adopts the proxy or DNS server it needs. A class that proves those boots the real main in its own JVM.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HohenheimServedApp {

    private HohenheimServedApp() {
    }

    /** @return the app of a test class, booted the way ServerMain boots */
    public static @NonNull ServedApp app() {
        return AuthServedApp.awaitingSetup(MigratedDatabase.sqliteFile("hohenheim.db"), SessionStoreKind.DATASOURCE)
            .bootedBy(new HohenheimHost())
            .withTaskServiceStopped()
            // Defaults ON and fails CLOSED (503) without a loaded certificate, which every cleartext test proxy lacks;
            // the force-SSL availability tests switch it on themselves.
            .withSetting(HohenheimSettings.Proxy.FORCE_HTTPS, false)
            .with(new FullNodeRoles())
            .with(new FreshControllerIdentity());
    }

    /** The harness declares its role set instead of inheriting it by omission; the host's settings load snapshots it. */
    private static final class FullNodeRoles implements ServedAppBinding {

        @Override
        public void beforeBoot(@NonNull SqlDatasource database) {
            if (ServerZenitRuntime.isReady() || HohenheimRoles.isCaptured()) {
                return;
            }
            HohenheimSettingsBoot.forceDefinitions();
            for (HohenheimRoles.Role role : HohenheimRoles.Role.values()) {
                Zenit.SETTINGS_VALUES.setValue(role.setting(), true);
            }
        }

        @Override
        public void bind(@NonNull SqlDatasource database) {
        }
    }

    /**
     * A class database never keeps the template's controller identity.
     *
     * AIDEV-NOTE: one database is one controller, and every daemon resource name is namespaced by its identity; a
     * copied file would hand several "controllers" the same token while their record ids both restart at 1. The
     * fresh token also names this database's workloads' Docker networks, so it is recorded with LiveNamespaces here,
     * the one place that knows a namespace was born, for the reaper after a killed worker.
     */
    private static final class FreshControllerIdentity implements ServedAppBinding {

        @Override
        public void bind(@NonNull SqlDatasource database) {
            Db.run(database, () -> Models.get(ControllerIdentityModel.class).find().all()
                .forEach(row -> Models.get(ControllerIdentityModel.class).delete(row.get(ControllerIdentityModel.ID))));
            ControllerIdentity.forgetForTest();
            ControllerIdentity.resolve();
            LiveNamespaces.note(ControllerIdentity.token());
        }
    }
}
