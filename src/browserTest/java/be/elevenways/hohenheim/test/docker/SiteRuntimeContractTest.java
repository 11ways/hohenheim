package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.InstanceResource;
import be.elevenways.hohenheim.server.docker.SiteContainerKind;
import be.elevenways.hohenheim.server.docker.SiteInstances;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ownership half of the canonical runtime-resource contract, no daemon needed:
 * a site-owned instance is a GENERATED row -- writable only inside the site tier's
 * system scope, invisible to the standalone instance resource, and counted by the
 * same per-owner quota machinery as every other instance. Each of these was
 * impossible for the pre-lowering site tier, which ran containers beside the
 * instance tier instead of through it.
 */
class SiteRuntimeContractTest {

    private static final String OPERATOR_BUCKET = InstanceQuota.bucketKeyOf("");

    @BeforeAll
    static void bootRuntime() {
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void cleanUp() {
        // Hard deletes need the system scope (the guard under test refuses them
        // outside it); the remove-hook pairing releases any spent quota slot.
        Model instances = Models.get(InstanceModel.class);
        GeneratedRows.sweeping("site", () -> {
            for (Row row : instances.find()
                    .where(InstanceModel.NAME.startsWith("contract-")).all()) {
                instances.delete(row.get(InstanceModel.ID));
            }
        });
    }

    /**
     * Shared machinery the site tier never had: the per-owner instance quota now
     * counts a Docker site's running release. With zero headroom the site's
     * convergence is REFUSED before anything touches a daemon, and no instance row
     * is born -- pre-lowering, a site container was invisible to every quota.
     */
    @Test
    void siteRuntimesSpendTheSameQuotaAsEveryInstance() {
        Integer previous = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER);
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
            int siteId = 998_001;
            // 2. The site's owned-instance CREATE is refused by the same reservation
            //    hook, with the same named violation any instance create gets.
            Violations refused = assertThrows(Violations.class,
                () -> SiteInstances.ensureRunning(siteId, "contract-quota", Map.of(
                    "image", "alpine", "tag", "latest",
                    "container_port", 8080, "command", "sleep 60")));
            assertThat(refused.getMessage())
                .as("step 2: the refusal is the quota's, by name").contains("quota_reached");

            // 3. STATE: no owned instance row was born and no extra slot was spent.
            assertThat(Models.get(InstanceModel.class).find()
                    .where(InstanceModel.GENERATED_FOR_MODEL.eq(SiteModel.MODEL_ID.toString()))
                    .where(InstanceModel.GENERATED_FOR_ID.eq(siteId))
                    .where(InstanceModel.DELETED_AT.isNull())
                    .all())
                .as("step 3: the refused convergence left no instance behind").isEmpty();
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
     * the site_container kind cannot be authored standalone at all, and the
     * standalone instance resource's own predicate excludes owned rows.
     */
    @Test
    void anOwnedInstanceHasExactlyOneAuthority() throws Exception {
        // 1. The site tier (system scope) authors an owned instance.
        int siteId = 998_002;
        int[] created = new int[1];
        GeneratedRows.as(new GeneratedRows.Attribution(SiteInstances.SOURCE,
                SiteModel.MODEL_ID.toString(), siteId), () -> {
            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, "contract-owned");
            row.set(InstanceModel.KIND, SiteContainerKind.ID.toString());
            row.set(InstanceModel.SETTINGS, Map.of("image", "alpine"));
            Models.get(InstanceModel.class).save(row);
            created[0] = row.get(InstanceModel.ID);
        });
        Row owned = Models.get(InstanceModel.class).findById(created[0]);
        assertThat((String) owned.get(InstanceModel.GENERATED_BY))
            .as("step 1: attribution was DERIVED by the scope").isEqualTo("site");

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

        // 4. The kind itself is site-authored: a standalone create (what the admin
        //    create form would submit) is refused by name.
        Row standalone = Models.get(InstanceModel.class).createEmptyRow();
        standalone.set(InstanceModel.NAME, "contract-standalone");
        standalone.set(InstanceModel.KIND, SiteContainerKind.ID.toString());
        assertThatThrownBy(() -> Models.get(InstanceModel.class).save(standalone))
            .as("step 4: site_container cannot be authored outside the site tier")
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
            .as("step 5: the resource predicate shows the ordinary instance only")
            .extracting(row -> (String) row.get(InstanceModel.NAME))
            .containsExactly("contract-ordinary");
    }
}
