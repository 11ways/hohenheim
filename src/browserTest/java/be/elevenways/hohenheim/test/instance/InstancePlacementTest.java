package be.elevenways.hohenheim.test.instance;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What the chooser ACTUALLY decides today, pinned: the eligible set, the
 * fewest-live-instances score, the lowest-id tie-break, and the dedicated posture's
 * exclusivity. None of these was asserted anywhere before -- the Proxmox-use inventory
 * named them as untested behaviour.
 *
 * AIDEV-NOTE: this is deliberately a CHARACTERIZATION of a COUNT-based score, not an
 * endorsement of it. Placement consults no CPU, memory or disk figure, and the decision
 * to leave it that way for now is recorded in docs/proxmox-use-inventory.md item 12 --
 * the short version is that per-workload limits are OPTIONAL and usually absent
 * (ResourceLimits: "null means unlimited"), so a budget summed over declared limits
 * would be zero for the common workload. When a declared per-kind footprint lands, THIS
 * test is the thing that must change with it, and its failure is the signal that the
 * score's meaning changed rather than a regression.
 */
class InstancePlacementTest {

    private static final String PREFIX = "placement-";
    private static final String BUCKET = "placement-owner";
    private static final String OTHER_BUCKET = "placement-stranger";

    private static SqliteDatasource datasource;

    private final List<Integer> hosts = new ArrayList<>();
    private final List<Integer> instances = new ArrayList<>();

    /**
     * Its OWN database: the chooser walks EVERY host row, so a shared fixture where a
     * neighbouring class left one admitted incus host behind would silently change
     * every answer here.
     */
    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-placement-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void cleanUp() {
        Db.run(datasource, () -> {
                for (Integer id : this.instances) {
                    Models.get(InstanceModel.class).delete(id);
                }
                for (Integer id : this.hosts) {
                    Models.get(ServerModel.class).delete(id);
                }
        });
        this.instances.clear();
        this.hosts.clear();
    }

    private int host(String name, String admission, String posture) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, PREFIX + name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.ADMISSION, admission);
        row.set(ServerModel.POSTURE, posture);
        Models.get(ServerModel.class).save(row);
        int id = row.get(ServerModel.ID);
        this.hosts.add(id);
        return id;
    }

    /**
     * A live workload CHARGED to {@code bucket}.
     *
     * AIDEV-NOTE: the bucket is written in a SECOND save on purpose. InstanceQuota's
     * create hook derives and stamps QUOTA_BUCKET from the acting principal, so a
     * bucket set on the insert is silently replaced by the operator's empty-set one --
     * which would make every dedicated-posture assertion below pass for the wrong
     * reason. The read-back is asserted rather than assumed.
     */
    private int liveInstanceOn(int serverId, String bucket) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, PREFIX + "w" + System.nanoTime());
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, serverId);
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        this.instances.add(id);

        row.set(InstanceModel.QUOTA_BUCKET, bucket);
        Models.get(InstanceModel.class).save(row);
        assertThat((String) Models.get(InstanceModel.class).findById(id)
                .get(InstanceModel.QUOTA_BUCKET))
            .as("fixture: the workload really is charged to '%s'", bucket)
            .isEqualTo(bucket);
        return id;
    }

    private static String keyOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }

    /**
     * The eligible set and the score, in one walk: only an admitted, tenant-accepting,
     * runtime-matching host qualifies; among those the one carrying the fewest live
     * instances wins; a tie goes to the lowest id; and an empty set is a NAMED refusal
     * rather than a silent fall back to the local daemon.
     */
    @Test
    void theChooserPicksTheLeastLoadedEligibleHostAndRefusesByNameWhenThereIsNone() {
        Db.run(datasource, () -> {
            // 1. No eligible host at all: named refusal, never a default.
            int blocked = host("blocked", ServerModel.ADMISSION_BLOCKED,
                ServerModel.POSTURE_SHARED_CONTAINER);
            int trustedOnly = host("trusted-only", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_TRUSTED_ONLY);
            assertThat(keyOf(catchThrowable(() -> InstancePlacement.chooseForBucket(
                    BUCKET, ServerModel.RUNTIME_INCUS, null))))
                .as("step 1: a blocked host and a trusted-only host leave nothing eligible,"
                    + " and that is a refusal by name")
                .isEqualTo("no_placement_available");

            // 2. One admitted shared host: it is chosen, and neither ineligible host is.
            int alpha = host("alpha", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, ServerModel.RUNTIME_INCUS, null))
                .as("step 2: the only admitted tenant-accepting host is chosen")
                .isEqualTo(alpha);
            assertThat(List.of(blocked, trustedOnly))
                .as("step 2: and it is neither of the refused ones")
                .doesNotContain(alpha);

            // 3. A second eligible host, empty, beats the one carrying a workload. THE SCORE
            //    IS A COUNT OF LIVE ROWS -- nothing about how big either host is.
            int beta = host("beta", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER);
            liveInstanceOn(alpha, BUCKET);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, ServerModel.RUNTIME_INCUS, null))
                .as("step 3: the emptier host wins")
                .isEqualTo(beta);

            // 4. Equal load: the LOWEST ID breaks the tie, so a placement is reproducible.
            liveInstanceOn(beta, BUCKET);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, ServerModel.RUNTIME_INCUS, null))
                .as("step 4: an even split goes to the lowest id, deterministically")
                .isEqualTo(Math.min(alpha, beta));

            // 5. The excluded host (the drain lane's source) is never its own destination.
            assertThat(InstancePlacement.chooseForBucket(BUCKET, ServerModel.RUNTIME_INCUS,
                    Math.min(alpha, beta)))
                .as("step 5: excluding the winner picks the other one")
                .isEqualTo(Math.max(alpha, beta));

            // 6. A runtime the fleet does not offer is a refusal, not a wrong-runtime pick.
            assertThat(keyOf(catchThrowable(() -> InstancePlacement.chooseForBucket(
                    BUCKET, "hohenheim:no_such_runtime", null))))
                .as("step 6: no host declares that runtime, so placement refuses by name")
                .isEqualTo("no_placement_available");
        });
    }

    /**
     * The dedicated posture's exclusivity claim: the host belongs to ONE owner, and the
     * chooser has to make that true rather than merely say it. A live workload charged
     * to any other bucket takes the host out of the eligible set entirely.
     */
    @Test
    void aDedicatedHostAcceptsOneBucketAndStopsAcceptingOnceAnotherLandsOnIt() {
        Db.run(datasource, () -> {
            int shared = host("ded-shared", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_SHARED_CONTAINER);
            int dedicated = host("ded-exclusive", ServerModel.ADMISSION_ADMITTED,
                ServerModel.POSTURE_DEDICATED);

            // 1. Empty, a dedicated host accepts anyone -- it has made no promise yet. It is
            //    also emptier than nothing, so it ties with the shared one on id.
            assertThat(InstancePlacement.chooseForBucket(BUCKET, ServerModel.RUNTIME_INCUS,
                    shared))
                .as("step 1: an empty dedicated host is eligible")
                .isEqualTo(dedicated);

            // 2. Once OUR bucket is on it, it still accepts us: exclusivity is per owner,
            //    not one workload.
            liveInstanceOn(dedicated, BUCKET);
            assertThat(InstancePlacement.chooseForBucket(BUCKET, ServerModel.RUNTIME_INCUS,
                    shared))
                .as("step 2: the owner it already carries may land again")
                .isEqualTo(dedicated);

            // 3. A STRANGER is refused it -- and the refusal is total, not a lower score:
            //    with the shared host excluded there is nowhere left to go.
            assertThat(keyOf(catchThrowable(() -> InstancePlacement.chooseForBucket(
                    OTHER_BUCKET, ServerModel.RUNTIME_INCUS, shared))))
                .as("step 3: a dedicated host carrying another owner's workload is not"
                    + " merely deprioritised for a stranger, it is INELIGIBLE")
                .isEqualTo("no_placement_available");
            assertThat(InstancePlacement.chooseForBucket(OTHER_BUCKET,
                    ServerModel.RUNTIME_INCUS, null))
                .as("step 3: the stranger lands on the shared host instead")
                .isEqualTo(shared);

            // 4. The operator's own empty-set bucket is a stranger too -- "dedicated" is not
            //    "dedicated except for us".
            assertThat(keyOf(catchThrowable(() -> InstancePlacement.chooseForBucket(
                    "", ServerModel.RUNTIME_INCUS, shared))))
                .as("step 4: the operator bucket does not get a pass onto a taken"
                    + " dedicated host")
                .isEqualTo("no_placement_available");
        });
    }
}
