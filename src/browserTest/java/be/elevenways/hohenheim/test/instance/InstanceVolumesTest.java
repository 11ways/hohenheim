package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DECLARATION half of the volume mechanism: where a volume's host path comes from, what
 * a host that cannot deliver one refuses, and what a name is allowed to be.
 *
 * <p>Hermetic: nothing here reaches a filesystem. Every case either derives a path (a pure
 * function) or refuses BEFORE the host is asked -- which is the point of each one, since a
 * refusal that arrives after the deploy already ran is not a refusal.
 */
class InstanceVolumesTest {

    private static SqlDatasource datasource;
    private static String savedDataPath;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        savedDataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, "/srv/hoh-test");
        Db.run(datasource, HostFixtures::admitLocal);
    }

    @AfterAll
    static void tearDown() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, savedDataPath);
    }

    /**
     * A volume's host path is derived from the OWNER and the name, and the derivation is
     * what keeps it inside the root the container hardening rule checks.
     */
    @Test
    void theHostPathIsDerivedFromTheOwnerAndRefusesAnyNameThatIsNotOne() {
        Db.run(datasource, () -> {
            assertThat(VolumeBackends.volumeRoot())
                .as("step 1: the volume root follows the configured data path")
                .isEqualTo("/srv/hoh-test/volumes");
            assertThat(InstanceVolumes.hostPathFor(7, "home"))
                .as("step 2: a volume lives under <root>/<owner>/<name>")
                .isEqualTo("/srv/hoh-test/volumes/7/home");
            assertThat(InstanceVolumes.rootFor(7))
                .as("step 2: and the owner's whole tree is one directory")
                .isEqualTo("/srv/hoh-test/volumes/7");

            // 3. THE containment guarantee. A name is a single path segment; anything that
            //    could climb out of the root is refused HERE, because the hardening rule
            //    downstream can only see the finished path and a symlink would beat it.
            for (String name : new String[] {"..", ".", "", "  ", "a/b", "../etc",
                    "a\\b", "-rf"}) {
                assertThatThrownBy(() -> InstanceVolumes.hostPathFor(7, name))
                    .as("step 3: volume name '" + name + "' is refused")
                    .isInstanceOf(Violations.class)
                    .hasMessageContaining("volume_name_invalid");
            }
        });
    }

    /**
     * An owner with no declared volumes asks the host NOTHING, and one WITH them is refused
     * by name on a host whose backend cannot deliver.
     *
     * AIDEV-NOTE: the empty-declaration fast path is load-bearing, not an optimization. It
     * is what lets an ordinary application deploy on a host with no quota-capable
     * filesystem at all -- the refusal is about the volumes, never about the deploy.
     */
    @Test
    void aHostThatCannotDeliverRefusesByNameAndAnOwnerWithoutVolumesNeverAsks() {
        Db.run(datasource, () -> {
            String local = ServerModel.nameOf(ServerModel.localServerId());
            setBackend(VolumeBackend.NONE);

            // 1. Nothing declared: no host contact, no refusal, an empty mount set.
            assertThat(InstanceVolumes.mountsFor(4242, local))
                .as("step 1: an owner with no volumes mounts nothing and asks nothing")
                .isEqualTo(Map.of());
            assertThat(InstanceVolumes.snapshotAll(4242, local, "predeploy"))
                .as("step 1: and has nothing to snapshot, on any backend").isEmpty();
            assertThat(InstanceVolumes.hasExclusive(4242))
                .as("step 1: and declares no exclusive volume").isFalse();

            // 2. Declared on a backend that cannot create one: refused BY NAME. A degrading
            //    mkdir here would hand the workload a directory with no quota and no
            //    snapshot, which looks identical until it fills the host's disk.
            InstanceVolumes.declare(4242, "data", "/var/lib/app", 1024L, false);
            assertThatThrownBy(() -> InstanceVolumes.mountsFor(4242, local))
                .as("step 2: a NONE backend refuses to deliver a declared volume")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("volume_backend_unimplemented");

            // 3. And the pre-deploy snapshot refuses SEPARATELY, naming the host: quota
            //    and snapshot are two capabilities and therefore two refusals, and a
            //    backend can lose them independently (a filesystem that caps but does not
            //    snapshot, or one this build simply has no operations for).
            setBackend(VolumeBackend.XFS_PRJQUOTA);
            assertThatThrownBy(() -> InstanceVolumes.snapshotAll(4242, local, "predeploy"))
                .as("step 3: a quota-capable backend that cannot snapshot refuses the"
                    + " pre-deploy copy by name")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("volume_no_snapshot_support");

            // 4. An unknown host is its own refusal, never a silent skip.
            assertThatThrownBy(() -> InstanceVolumes.mountsFor(4242, "no-such-host"))
                .as("step 4: an uninventoried host refuses by name")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("volume_host_unknown");

            // 5. The declaration itself is stored with its evidence and its exclusivity.
            Row stored = InstanceVolumes.declaredFor(4242).get(0);
            assertThat(stored.get(InstanceVolumeModel.HOST_PATH))
                .as("step 5: the row records the directory a reclaim would remove")
                .isEqualTo("/srv/hoh-test/volumes/4242/data");
            InstanceVolumes.declare(4242, "data", "/var/lib/app", 1024L, true);
            assertThat(InstanceVolumes.hasExclusive(4242))
                .as("step 5: re-declaring updates in place and the exclusivity is visible")
                .isTrue();
            assertThat(InstanceVolumes.declaredFor(4242))
                .as("step 5: without minting a second row").hasSize(1);

            setBackend(VolumeBackend.NONE);
            Models.get(InstanceVolumeModel.class).find()
                .where(InstanceVolumeModel.INSTANCE_ID.eq(4242)).delete();
        });
    }

    private static void setBackend(VolumeBackend backend) {
        Row server = Models.get(ServerModel.class).findById(ServerModel.localServerId());
        server.set(ServerModel.VOLUME_BACKEND, backend.token());
        Models.get(ServerModel.class).save(server);
    }
}
