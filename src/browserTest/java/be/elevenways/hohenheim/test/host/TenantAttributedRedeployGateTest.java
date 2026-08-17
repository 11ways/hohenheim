package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.docker.SiteContainerKind;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The deploy-time posture gate is about WHOSE workload this is, not about which tier
 * authored its kind.
 *
 * WHY IT EXISTS: {@code InstanceService.deploy} ran {@code requireInstancePlacement} only
 * when {@code handler.tenantAuthored()}, and three of the tiers that carry real tenant
 * workloads declare that FALSE -- a managed database's engine, a site's release container
 * and a stack service are operator-AUTHORED kinds owned by a TENANT through the product
 * record above them. Those workloads were posture-checked once, at placement, and then
 * never again: a host whose posture regressed, whose shared-kernel acknowledgement was
 * withdrawn or whose kernel-truth check stopped passing kept taking them back on every
 * redeploy. The gate now asks {@code OwnedInstances.isTenantAttributed}, which reads
 * ownership from grants (the derivation {@code sameOwner} and the quota bucket share).
 *
 * The refusals here all land BEFORE any daemon call: the placement gate sits between the
 * lease acquisition and the container work, so no Docker socket is needed.
 */
class TenantAttributedRedeployGateTest extends HohenheimTestBase {

    private static final String PREFIX = "posture-redeploy-";

    @Test
    void aTenantOwnedWorkloadIsPostureCheckedOnEveryRedeployWhoeverAuthoredItsKind() {
        ServerModel servers = Models.get(ServerModel.class);
        int hostId = acknowledgedSharedHost();

        // 1. A SITE the tenant owns, and the site's release container -- an
        //    operator-authored, generated-only kind. This is the exact shape the old gate
        //    skipped, and the ownership lives on the SITE, never on the instance row.
        int siteId = site();
        int tenantId = user("redeploy-tenant@hohenheim.local");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId,
            HohenheimAccess.MANAGE, true);
        int instanceId = releaseContainer(siteId, hostId);

        assertThat(Models.get(InstanceModel.class).findById(instanceId)
                .get(InstanceModel.KIND))
            .as("step 1: the workload's kind is the operator-authored one")
            .isEqualTo(SiteContainerKind.ID.toString());
        assertThat(OwnedInstances.isTenantAttributed(
                Models.get(InstanceModel.class).findById(instanceId)))
            .as("step 1: but the WORKLOAD answers to a tenant, through the site above it")
            .isTrue();

        // 2. The positive control: while the host IS acknowledged the placement gate is
        //    satisfied, so step 3's refusal is a posture change and not a host that could
        //    never have taken the workload. (Deploy itself is not called here -- past the
        //    gate it does real daemon work, which the default lane must never trigger.)
        assertThat(catchThrowable(() -> HostAdmission.requireInstancePlacement(hostId,
                InstanceKinds.getHandler(SiteContainerKind.ID.toString()).isolation(),
                Models.get(InstanceModel.class).findById(instanceId)
                    .get(InstanceModel.QUOTA_BUCKET))))
            .as("step 2: the acknowledged host passes the whole placement gate")
            .isNull();

        // 3. THE DEFECT: withdraw the shared-kernel acknowledgement -- the posture
        //    REGRESSES under a workload that is already placed -- and the redeploy must
        //    refuse. (Pre-fix it sailed through, because the KIND is not tenant-authored.)
        Row withdraw = servers.createEmptyRow();
        withdraw.set(ServerModel.ID, hostId);
        withdraw.set(ServerModel.ACKNOWLEDGED_POSTURE, null);
        withdraw.set(ServerModel.ACKNOWLEDGED_AT, null);
        withdraw.set(ServerModel.ACKNOWLEDGED_BY, null);
        servers.save(withdraw);
        assertThat(refusalKeys(catchThrowable(() -> new InstanceService().deploy(instanceId))))
            .as("step 3: a tenant-owned workload is refused a redeploy onto a host whose"
                + " shared-kernel acknowledgement was withdrawn")
            .contains("host_posture_unacknowledged");

        // 4. A CORDONED host refuses the same redeploy by its own name, so step 3 is the
        //    whole placement gate running again and not one bespoke posture branch.
        Row cordon = servers.createEmptyRow();
        cordon.set(ServerModel.ID, hostId);
        cordon.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
        servers.save(cordon);
        assertThat(refusalKeys(catchThrowable(() -> new InstanceService().deploy(instanceId))))
            .as("step 4: and the admission half of the same gate refuses too")
            .contains("host_not_admitted");

        // 5. THE EXEMPTION IS STILL REAL, and it is the reason the flag existed: the
        //    IDENTICAL workload with no tenant owner is operator work, and operator work is
        //    not posture-gated. Removing the site's manage grant is the only change, and
        //    both halves of the gate's condition then answer false -- the kind was never
        //    tenant-authored and the workload is no longer tenant-attributed.
        //
        //    Asserted on the CONDITION rather than by calling deploy again: a deploy that
        //    passes the gate goes on to real daemon work, which this lane must not do.
        //    Steps 3 and 4 already prove the condition is what deploy branches on.
        RecordGrants.revoke(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId,
            HohenheimAccess.MANAGE);
        assertThat(OwnedInstances.isTenantAttributed(
                Models.get(InstanceModel.class).findById(instanceId)))
            .as("step 5: with no manage grant above it the workload is operator-owned")
            .isFalse();
        assertThat(InstanceKinds.getHandler(SiteContainerKind.ID.toString()).tenantAuthored())
            .as("step 5: and its kind never was tenant-authored, so the gate does not run")
            .isFalse();
    }

    // -- fixtures ----------------------------------------------------------------

    private static int acknowledgedSharedHost() {
        ServerModel servers = Models.get(ServerModel.class);
        String name = PREFIX + "host";
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.PREFLIGHT_OK, true);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.ACKNOWLEDGED_POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.ACKNOWLEDGED_WARNING_VERSION, ServerModel.POSTURE_WARNING_VERSION);
        row.set(ServerModel.ACKNOWLEDGED_AT, Instant.now());
        row.set(ServerModel.ACKNOWLEDGED_BY, "user:1");
        row.set(ServerModel.ACKNOWLEDGED_BY_LABEL, "Test Operator");
        row.set(ServerModel.LAST_SEEN_AT, Instant.now());
        servers.save(row);
        HostPreflight.store(name, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of(HostPreflight.MEM_TOTAL_FACT, 16L * 1024 * 1024 * 1024),
            true, Instant.now(), null));
        return row.get(ServerModel.ID);
    }

    private static int site() {
        SiteModel sites = Models.get(SiteModel.class);
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, PREFIX + "site");
        row.set(SiteModel.SLUG, PREFIX + "site");
        row.set(SiteModel.SITE_TYPE, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        sites.save(row);
        return row.get(SiteModel.ID);
    }

    /** A site's release container: generated-only, so it is written in the owning scope. */
    private static int releaseContainer(int siteId, int hostId) {
        int[] created = new int[1];
        OwnedInstances.inScopeUnchecked("test", SiteModel.MODEL_ID, siteId, () -> {
            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + "release");
            row.set(InstanceModel.KIND, SiteContainerKind.ID.toString());
            row.set(InstanceModel.SETTINGS,
                new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
            row.set(InstanceModel.SERVER_ID, hostId);
            row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
            Models.get(InstanceModel.class).save(row);
            created[0] = row.get(InstanceModel.ID);
        });
        return created[0];
    }

    private static int user(String email) {
        Row row = AuthModels.users().createEmptyRow();
        row.set(UserModel.EMAIL, email);
        row.set(UserModel.DISPLAY_NAME, email);
        row.set(UserModel.ENABLED, true);
        row.set(UserModel.CREATED_AT, Instant.now());
        row.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(row);
        return row.get(UserModel.ID);
    }

    /** Every violation key a refusal carries; a non-Violations failure yields none. */
    private static List<String> refusalKeys(Throwable thrown) {
        if (!(thrown instanceof Violations violations)) {
            return List.of();
        }
        return violations.all().stream().map(Violation::message)
            .map(message -> message.key()).toList();
    }
}
