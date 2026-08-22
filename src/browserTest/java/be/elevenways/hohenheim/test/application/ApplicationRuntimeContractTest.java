package be.elevenways.hohenheim.test.application;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.cms.InstanceResource;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ownership half of the canonical runtime-resource contract, no daemon needed:
 * an application-owned release is a GENERATED row -- writable only inside the
 * application tier's system scope, invisible to the standalone instance resource, and
 * counted by the same per-owner quota machinery as every other instance. Each of these
 * was impossible for the pre-lowering site tier, which ran containers beside the
 * instance tier instead of through it.
 */
class ApplicationRuntimeContractTest {

    private static final String OPERATOR_BUCKET = InstanceQuota.bucketKeyOf("");
    private static final String OWNER_MODEL = InstanceModel.MODEL_ID.toString();

    @BeforeAll
    static void bootRuntime() {
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void cleanUp() {
        // Hard deletes need the system scope (the guard under test refuses them
        // outside it); the remove-hook pairing releases any spent quota slot.
        Model instances = Models.get(InstanceModel.class);
        GeneratedRows.sweeping(ApplicationReleases.SOURCE, () -> {
            for (Row row : instances.find()
                    .where(InstanceModel.NAME.startsWith("contract-")).all()) {
                instances.delete(row.get(InstanceModel.ID));
            }
        });
    }

    /**
     * Shared machinery the site tier never had: the per-owner instance quota now
     * counts an application's running release. With zero headroom the application's
     * convergence is REFUSED before anything touches a daemon, and no release row
     * is born -- pre-lowering, a site container was invisible to every quota.
     */
    @Test
    void applicationReleasesSpendTheSameQuotaAsEveryInstance() {
        Integer previous = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER);
        // The APPLICATION is minted before the headroom is closed: it is an instance too,
        // so creating it under the cap would refuse the owner instead of the release.
        int applicationId = application("quota-owner", Map.of(
            "image", "alpine", "tag", "latest",
            "container_port", 8080, "command", "sleep 60"));
        // 1. An ordinary instance spends the operator bucket's LAST slot (a cap of 0
        //    would mean uncapped, so headroom is exhausted by consumption, not by 0).
        Row filler = Models.get(InstanceModel.class).createEmptyRow();
        filler.set(InstanceModel.NAME, "contract-filler");
        filler.set(InstanceModel.KIND, "hohenheim:docker_container");
        Models.get(InstanceModel.class).save(filler);
        long used = Quotas.usedOf(OPERATOR_BUCKET);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER, (int) used);
        try {
            // 2. The application's owned-release CREATE is refused by the same
            //    reservation hook, with the same named violation any create gets.
            Violations refused = assertThrows(Violations.class,
                () -> ApplicationReleases.converge(applicationId, Map.of()));
            assertThat(refused.getMessage())
                .as("step 2: the refusal is the quota's, by name").contains("quota_reached");

            // 3. STATE: no owned instance row was born and no extra slot was spent.
            assertThat(Models.get(InstanceModel.class).find()
                    .where(InstanceModel.GENERATED_FOR_MODEL.eq(OWNER_MODEL))
                    .where(InstanceModel.GENERATED_FOR_ID.eq(applicationId))
                    .where(InstanceModel.DELETED_AT.isNull())
                    .all())
                .as("step 3: the refused convergence left no release behind").isEmpty();
            assertThat(Quotas.usedOf(OPERATOR_BUCKET))
                .as("step 3: the refused reservation was not spent").isEqualTo(used);
        } finally {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER,
                previous == null ? 0 : previous);
        }
    }

    /**
     * No second UI over the same records, enforced at the WRITE PIPELINE, not by
     * page layout: an owned instance refuses every out-of-scope write and delete,
     * the release kind cannot be authored standalone at all, and the
     * standalone instance resource's own predicate excludes owned rows.
     */
    @Test
    void anOwnedInstanceHasExactlyOneAuthority() throws Exception {
        // 1. The application tier (system scope) authors an owned release.
        int applicationId = application("owner", Map.of("image", "alpine"));
        int[] created = new int[1];
        GeneratedRows.as(new GeneratedRows.Attribution(ApplicationReleases.SOURCE,
                OWNER_MODEL, applicationId), () -> {
            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, "contract-owned");
            row.set(InstanceModel.KIND, ReleaseKind.ID.toString());
            row.set(InstanceModel.SETTINGS, Map.of("image", "alpine"));
            Models.get(InstanceModel.class).save(row);
            created[0] = row.get(InstanceModel.ID);
        });
        Row owned = Models.get(InstanceModel.class).findById(created[0]);
        assertThat((String) owned.get(InstanceModel.GENERATED_BY))
            .as("step 1: attribution was DERIVED by the scope")
            .isEqualTo(ApplicationReleases.SOURCE);

        // 2. Outside the scope, editing the owned row is refused -- this is what makes
        //    the admin instance form structurally incapable of becoming a second UI.
        owned.set(InstanceModel.NAME, "contract-hijacked");
        assertThatThrownBy(() -> Models.get(InstanceModel.class).save(owned))
            .as("step 2: an out-of-scope write to an owned instance is refused")
            .isInstanceOf(Violations.class);

        // 3. Deleting it out of scope is refused the same way.
        assertThatThrownBy(() -> Models.get(InstanceModel.class).delete(created[0]))
            .as("step 3: an out-of-scope delete is refused")
            .isInstanceOf(Violations.class);

        // 4. The kind itself is application-authored: a standalone create (what the
        //    admin create form would submit) is refused by name.
        Row standalone = Models.get(InstanceModel.class).createEmptyRow();
        standalone.set(InstanceModel.NAME, "contract-standalone");
        standalone.set(InstanceModel.KIND, ReleaseKind.ID.toString());
        assertThatThrownBy(() -> Models.get(InstanceModel.class).save(standalone))
            .as("step 4: release cannot be authored outside the application tier")
            .isInstanceOf(Violations.class);

        // 5. The standalone instance resource's OWN predicate excludes owned rows
        //    (list, record routes, actions and subpages all scope through it), while
        //    an ordinary instance stays visible through the same predicate.
        Row ordinary = Models.get(InstanceModel.class).createEmptyRow();
        ordinary.set(InstanceModel.NAME, "contract-ordinary");
        ordinary.set(InstanceModel.KIND, "hohenheim:docker_container");
        Models.get(InstanceModel.class).save(ordinary);
        AccessDecision decision = new InstanceResource().accessFunction().decide(null);
        List<Row> visible = Models.get(InstanceModel.class).find()
            .where(decision.predicate().criteria())
            .where(InstanceModel.NAME.startsWith("contract-"))
            .all();
        assertThat(visible)
            .as("step 5: the predicate hides the OWNED release and shows every ordinary"
                + " instance -- the owning application included, because an application"
                + " is authored through this very resource")
            .extracting(row -> (String) row.get(InstanceModel.NAME))
            .containsExactlyInAnyOrder("contract-owner", "contract-ordinary");
    }

    /**
     * The release engine's WRITE DISCIPLINE, no daemon needed: a role transition may
     * never carry the rest of a stale Row back to the database.
     *
     * The engine loads the serving row early (before a sandbox build that can take
     * minutes), and everything that writes that row in between -- the volume-name heal,
     * the fenced status stamp of a deploy -- used to be silently reverted by the
     * role-flip's whole-row {@code Models.save}, while the operation reported success.
     * This walks a row through exactly that interleaving and asserts the PERSISTED
     * values, not a status.
     */
    @Test
    void aRoleFlipNeverCarriesAStaleRowBackToTheDatabase() throws Exception {
        int applicationId = application("flip-owner", Map.of("image", "alpine"));
        String staleMount = "/srv/stale";
        String freshMount = "/srv/fresh";
        int[] created = new int[1];

        // 1. A serving release exists, holding the mount its owner declared first.
        GeneratedRows.as(new GeneratedRows.Attribution(ApplicationReleases.SOURCE,
                OWNER_MODEL, applicationId), () -> {
            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, "contract-flip");
            row.set(InstanceModel.KIND, ReleaseKind.ID.toString());
            row.set(InstanceModel.SETTINGS, Map.of("image", "alpine",
                ApplicationReleases.VOLUME_MOUNTS, Map.of(staleMount, "/data")));
            row.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_SERVING);
            row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
            Models.get(InstanceModel.class).save(row);
            created[0] = row.get(InstanceModel.ID);
        });
        int instanceId = created[0];

        // 2. The engine's OWN reference to that row, taken before the spec is resolved.
        //    Every column is present on it, which is what makes a later save a rewrite.
        Row stale = Models.get(InstanceModel.class).findById(instanceId);
        assertThat((String) stale.get(InstanceModel.RUNTIME_ROLE))
            .as("step 2: the engine starts from a serving row")
            .isEqualTo(InstanceModel.ROLE_SERVING);

        // 3. While the release is still resolving its spec, two OTHER writers touch that
        //    same record: one rewrites the stored mount, the deploy stamps a fenced status.
        //
        // AIDEV-NOTE: the first writer used to be {@code SiteVolumes.heal}, which brief 7
        // deleted along with named site volumes (a release binds HOST PATHS now). The
        // journey's subject was never the heal -- it is that ANY write landing between the
        // engine's row load and its role flip must survive the flip -- so the writer is
        // spelled as the set-based settings update every fenced writer here uses.
        Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine",
                ApplicationReleases.VOLUME_MOUNTS, Map.of(freshMount, "/data"))))
            .updateAll();
        Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING)
            .updateAll();

        // 4. THE SWITCH: the engine flips the role through the production seam. No
        //    GeneratedRows scope is needed (and none is claimed): the fenced write is a
        //    set-based update, so the owned-row write guard -- a save-pipeline hook --
        //    does not run. That is the same trade every other fenced outcome write makes.
        new InstanceService().assignRuntimeRole(instanceId, InstanceModel.ROLE_RETIRED);

        // 5. STATE, read back from the database: the role moved AND neither concurrent
        //    write was lost. Before the fix, the persisted mount was the stale one and
        //    the status was back to 'stopped' -- with the release reporting success and
        //    the rollback target pointing at the wrong host directory.
        Row persisted = Models.get(InstanceModel.class).findById(instanceId);
        assertThat((String) persisted.get(InstanceModel.RUNTIME_ROLE))
            .as("step 5: the role transition landed").isEqualTo(InstanceModel.ROLE_RETIRED);
        // Both surviving writes in ONE assertion: a pre-fix flip loses both, and a
        // per-value assertion would only ever report whichever one it reached first.
        assertThat(Map.of(
                "volume_mounts", storedMountNames(persisted),
                "status", String.valueOf((Object) persisted.get(InstanceModel.STATUS))))
            .as("step 5: every write made to the row between load and flip SURVIVED it")
            .isEqualTo(Map.of(
                "volume_mounts", List.of(freshMount),
                "status", InstanceModel.STATUS_RUNNING));
    }

    private static List<String> storedMountNames(Row instance) {
        Object settings = instance.get(InstanceModel.SETTINGS);
        if (!(settings instanceof Map<?, ?> map)
                || !(map.get(ApplicationReleases.VOLUME_MOUNTS) instanceof Map<?, ?> mounts)) {
            return List.of();
        }
        return mounts.keySet().stream().map(String::valueOf).toList();
    }

    /** An application instance on the local server, the owner every release hangs off. */
    private static int application(String name, Map<String, Object> settings) {
        Row application = Models.get(InstanceModel.class).createEmptyRow();
        application.set(InstanceModel.NAME, "contract-" + name);
        application.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        application.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        application.set(InstanceModel.SETTINGS, new LinkedHashMap<>(settings));
        Models.get(InstanceModel.class).save(application);
        return application.get(InstanceModel.ID);
    }
}
