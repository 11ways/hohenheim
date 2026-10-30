package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.test.live.LiveNamespaces;
import be.elevenways.zenit.auth.AuthSettings;
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
 * THE Hohenheim app a test class runs on: zenit's served app over a migrated SQLite file of the class, which
 * {@code HohenheimDatabase.init()} opens as production does (database.url), with the task service stopped.
 *
 * AIDEV-NOTE: the task service stays stopped on every class database: a service reconciles and boot-fires (~1s) as it
 * starts, and its cron ticks are a cross-class hazard (SuperviseProxyListeners mutating the counters a proxy test is
 * asserting). A test needing live scheduling opts in with TaskRuntime.optIn, or starts its own service.
 *
 * AIDEV-NOTE: two pre-boots, one per lane, because the lanes never share a JVM: the served lanes (HohenheimTestBase)
 * boot as ServerMain does (settings chain, endpoints, auth baselines); the standalone lane boots the runtime alone
 * with every role declared.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HohenheimServedApp {

    private HohenheimServedApp() {
    }

    /** @return the app of a class served over HTTP, booted the way ServerMain boots */
    public static @NonNull ServedApp served() {
        return app(new ServedPreBoot());
    }

    /** @return the app of a class that drives the runtime directly, with every role declared */
    public static @NonNull ServedApp standalone() {
        return app(new StandalonePreBoot());
    }

    private static @NonNull ServedApp app(@NonNull ServedAppBinding preBoot) {
        return AuthServedApp.awaitingSetup(MigratedDatabase.sqliteFile("hohenheim.db"), SessionStoreKind.DATASOURCE)
            .registeredByHost(() -> {
                HohenheimDatabase.init();
                return HohenheimDatabase.datasource();
            })
            .withTaskServiceStopped()
            // Defaults ON and fails CLOSED (503) without a loaded certificate, which every cleartext test proxy lacks;
            // the force-SSL availability tests switch it on themselves.
            .withSetting(HohenheimSettings.Proxy.FORCE_HTTPS, false)
            .with(preBoot)
            .with(new FreshControllerIdentity());
    }

    /**
     * Declares the full-node role set unless roles were captured already: a test that never loads the hohenheim
     * settings file still hits role gates (task declarations at the TASKS stage, panel filtering, the security
     * engine). A role-restricted test declares its own set and captures first.
     */
    public static void declareFullNodeRolesOnce() {
        if (!HohenheimRoles.isCaptured()) {
            HohenheimSettingsBoot.forceDefinitions();
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.PROXY, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.DNS, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.FIREWALL, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.STACKS, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.DATABASES, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.INSTANCES, true);
            HohenheimRoles.capture();
        }
    }

    /**
     * Declares hohenheim's grantable models once per JVM, before the boot stages.
     *
     * AIDEV-NOTE: zenit-auth's record-access page registry is a MODULES-stage snapshot, and RecordAccessCoverage
     * refuses a model that becomes grantable after the drain; a class owning its own boot (a solo class) calls this
     * itself before it boots.
     */
    public static synchronized void declareAccessModelsOnce() {
        if (!accessModelsDeclared) {
            accessModelsDeclared = true;
            HohenheimAccess.declareGrantableModels();
        }
    }

    private static boolean accessModelsDeclared;

    /** The served lanes' pre-boot: ServerMain's, before the first boot of the JVM. */
    private static final class ServedPreBoot implements ServedAppBinding {

        @Override
        public void beforeBoot(@NonNull SqlDatasource database) {
            if (ServerZenitRuntime.isReady()) {
                return;
            }
            // The harness declares its role set instead of inheriting it by omission; load() snapshots it.
            HohenheimSettingsBoot.forceDefinitions();
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.PROXY, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.DNS, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.FIREWALL, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.STACKS, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.DATABASES, true);
            // The lane's zenit.settings.root points settings/local.dry into the build directory, so the settings page
            // writes there and never into the developer's own file.
            HohenheimSettingsBoot.load();
            HohenheimEndpoints.init();
            // Before the migrations, as ServerMain: the declarations carry the liveness zenit-auth's purge consults.
            declareAccessModelsOnce();
            // zenit-auth's own default panel stays off (HohenheimPanel holds users and roles); the drain reads it.
            Zenit.SETTINGS_VALUES.setValue(AuthSettings.CMS_AUTO_PANEL, false);
            ServerMain.installAuthBaselines();
        }

        @Override
        public void bind(@NonNull SqlDatasource database) {
        }
    }

    /** The standalone lane's pre-boot: every role and the grantable models, before the first boot of the JVM. */
    private static final class StandalonePreBoot implements ServedAppBinding {

        @Override
        public void beforeBoot(@NonNull SqlDatasource database) {
            if (ServerZenitRuntime.isReady()) {
                return;
            }
            declareFullNodeRolesOnce();
            declareAccessModelsOnce();
            Zenit.SETTINGS_VALUES.setValue(AuthSettings.CMS_AUTO_PANEL, false);
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
