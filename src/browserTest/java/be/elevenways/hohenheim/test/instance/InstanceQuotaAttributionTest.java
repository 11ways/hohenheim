package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WHO an OWNED instance is charged to. An owned instance is written inside its product
 * tier's system scope, so the ambient write scope always looks like the system -- and
 * before this wave that is exactly what the charge followed. It made the per-owner cap
 * unable to bind a tenant-held record: every engine or container a tenant's record owned
 * was really booked on a host and charged to the OPERATOR.
 *
 * The pair proven here is the whole claim: the SAME tenant request thread, the SAME
 * GeneratedRows scope, two different answers -- decided by the OWNING RECORD's grants and
 * by nothing else. No daemon is needed: the charge lands on the record write.
 */
class InstanceQuotaAttributionTest extends HohenheimTestBase {

    private static final String PREFIX = "quota-attribution-";

    private static Integer tenantId;
    private static UserPrincipal tenant;
    private static Integer operatorSiteId;
    private static Integer tenantSiteId;
    private static Integer secondTenantSiteId;
    private static Integer tenantCapRowId;

    private static String operatorBucket;
    private static String tenantBucket;

    @BeforeAll
    static void seed() {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "tenant@quota-attribution.test");
        user.set(UserModel.DISPLAY_NAME, "Quota Attribution Tenant");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        tenantId = user.get(UserModel.ID);
        tenant = new UserPrincipal(tenantId, "Quota Attribution Tenant");

        operatorSiteId = site(PREFIX + "operator-site");
        tenantSiteId = site(PREFIX + "tenant-site");
        secondTenantSiteId = site(PREFIX + "tenant-site-two");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, tenantSiteId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, secondTenantSiteId,
            HohenheimAccess.MANAGE, true);

        operatorBucket = InstanceQuota.bucketKeyOf("");
        tenantBucket = InstanceQuota.bucketKeyOf(
            HohenheimAccess.packSubjects(Set.of("user:" + tenantId)));
    }

    @AfterAll
    static void cleanUp() {
        if (tenantCapRowId != null) {
            Models.get(InstanceQuotaModel.class).delete(tenantCapRowId);
        }
        // Hard delete: the remove-hook pairing hands every remaining reservation back, so
        // this class leaves the shared server's buckets exactly as it found them.
        Model instances = Models.get(InstanceModel.class);
        // Inside the sweeping scope: these rows carry generated attribution, which is
        // read-only (and undeletable) outside their owning tier's system scope.
        GeneratedRows.sweeping("site", () -> {
            for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
                instances.delete(row.get(InstanceModel.ID));
            }
        });
        Model sites = Models.get(SiteModel.class);
        for (Row row : sites.find().where(SiteModel.NAME.startsWith(PREFIX)).all()) {
            sites.delete(row.get(SiteModel.ID));
        }
    }

    // -- fixtures -------------------------------------------------------------

    private static int site(String name) {
        Model sites = Models.get(SiteModel.class);
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, name);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.ENABLED, false);
        sites.save(row);
        return row.get(SiteModel.ID);
    }

    /**
     * Converge an instance OWNED by a site, exactly the way ApplicationReleases and
     * DatabaseInstances do it: inside the owning record's GeneratedRows attribution scope,
     * on a thread that is carrying a tenant's request.
     */
    private static int convergeOwned(int siteId, String name) {
        int[] created = new int[1];
        TenantConduits.as(tenant, () -> OwnedInstances.inScopeUnchecked("site",
            SiteModel.MODEL_ID, siteId, () -> {
                Model instances = Models.get(InstanceModel.class);
                Row row = instances.createEmptyRow();
                row.set(InstanceModel.NAME, name);
                row.set(InstanceModel.KIND, "hohenheim:docker_container");
                row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
                    Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
                row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
                instances.save(row);
                created[0] = row.get(InstanceModel.ID);
            }));
        return created[0];
    }

    /** A per-OWNER cap on the tenant alone; the operator's ledger stays uncapped. */
    private static void capTenantAt(int maximum) {
        Model quotas = Models.get(InstanceQuotaModel.class);
        String packed = HohenheimAccess.packSubjects(Set.of("user:" + tenantId));
        Row row = quotas.find().where(InstanceQuotaModel.SUBJECTS.eq(packed)).first();
        if (row == null) {
            row = quotas.createEmptyRow();
            row.set(InstanceQuotaModel.SUBJECTS, packed);
        }
        row.set(InstanceQuotaModel.MAX_INSTANCES, maximum);
        quotas.save(row);
        tenantCapRowId = row.get(InstanceQuotaModel.ID);
    }

    private static Row instanceRow(int id) {
        return Models.get(InstanceModel.class).findById(id);
    }

    private static String violationKeys(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }

    // -- the journey ----------------------------------------------------------

    @Test
    void ownedInstancesAreChargedToTheOwnerOfTheRecordThatOwnsThemAndTheCapBinds() {
        long operatorBefore = Quotas.usedOf(operatorBucket);
        long tenantBefore = Quotas.usedOf(tenantBucket);

        // 1. THE SITE-CONTAINER CASE, unchanged: an OPERATOR-owned site converging its
        //    container on a tenant's request thread still charges the operator. The
        //    ambient scope and the owner happen to agree here, which is precisely why
        //    the old ambient-scope rule looked correct.
        int operatorInstance = convergeOwned(operatorSiteId, PREFIX + "operator-owned");
        assertThat((String) instanceRow(operatorInstance).get(InstanceModel.QUOTA_BUCKET))
            .as("step 1: an operator-owned site's container is charged to the operator")
            .isEqualTo(operatorBucket);
        assertThat(Quotas.usedOf(operatorBucket))
            .as("step 1: and the operator bucket is the one that moved")
            .isEqualTo(operatorBefore + 1);
        assertThat(Quotas.usedOf(tenantBucket))
            .as("step 1: the tenant bucket did not move")
            .isEqualTo(tenantBefore);

        // 2. THE FIX: the SAME thread, the SAME scope, a site the TENANT holds manage on.
        //    The charge follows the owning record's grants, not the write scope.
        int tenantInstance = convergeOwned(tenantSiteId, PREFIX + "tenant-owned");
        assertThat((String) instanceRow(tenantInstance).get(InstanceModel.QUOTA_BUCKET))
            .as("step 2: a tenant-owned site's container is charged to THAT TENANT")
            .isEqualTo(tenantBucket);
        assertThat(Quotas.usedOf(tenantBucket))
            .as("step 2: the tenant bucket carries the charge")
            .isEqualTo(tenantBefore + 1);
        assertThat(Quotas.usedOf(operatorBucket))
            .as("step 2: and the operator bucket did NOT absorb it")
            .isEqualTo(operatorBefore + 1);

        // 3. THE ATTACK the charge exists to stop: with THIS TENANT's own cap exactly
        //    spent (a per-owner override row, so the operator's ledger is untouched and
        //    cannot be what refuses), another owned instance for a record they own must
        //    be refused. Under the old rule it landed happily, charged to the operator --
        //    an unbounded supply of really-booked workloads behind a cap that never saw
        //    them.
        capTenantAt((int) Quotas.usedOf(tenantBucket));
        Throwable overCap = catchThrowable(() ->
            convergeOwned(secondTenantSiteId, PREFIX + "over-cap"));
        assertThat(violationKeys(overCap))
            .as("step 3: the tenant's own cap refuses their next owned instance BY NAME")
            .contains("quota_reached");
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "over-cap")).all())
            .as("step 3: and no row landed -- a refusal that still wrote is not a refusal")
            .isEmpty();

        // 4. Releasing the tenant's charge frees the tenant's slot and nobody else's:
        //    the soft-delete transition, the exact write InstanceService.destroy performs.
        OwnedInstances.inScopeUnchecked("site", SiteModel.MODEL_ID, tenantSiteId, () -> {
            Row live = instanceRow(tenantInstance);
            live.set(InstanceModel.DELETED_AT, Instant.now());
            Models.get(InstanceModel.class).save(live);
        });
        assertThat(Quotas.usedOf(tenantBucket))
            .as("step 4: the tenant's soft delete hands the tenant's slot back")
            .isEqualTo(tenantBefore);
        assertThat(Quotas.usedOf(operatorBucket))
            .as("step 4: and takes nothing from the operator")
            .isEqualTo(operatorBefore + 1);

        int readmitted = convergeOwned(secondTenantSiteId, PREFIX + "readmitted");
        assertThat((String) instanceRow(readmitted).get(InstanceModel.QUOTA_BUCKET))
            .as("step 4: the freed slot admits the tenant's next owned instance")
            .isEqualTo(tenantBucket);
    }
}
