package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An instance its host will refuse offers Deploy, Restart and the other host-bound verbs DEAD, saying why, with the
 * same words the overview's "cannot start yet" notice uses; on /manage the words never name the host.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class InstancePowerAvailabilityJourneyTest extends HohenheimTestBase {

    @Test
    void anInstanceItsHostRefusesOffersItsVerbsDeadWithTheReason() {
        Row host = Models.get(ServerModel.class).createEmptyRow();
        host.set(ServerModel.NAME, "power-availability-host");
        host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        host.set(ServerModel.MODE, ServerModel.MODE_SSH);
        host.set(ServerModel.SSH_TARGET, "operator@power-availability-host.invalid");
        host.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
        Models.get(ServerModel.class).save(host);
        Row instance = instance("power-availability", host.get(ServerModel.ID));

        // 1. The host is not admitted: the one reason source names it.
        Microcopy refusal = OwnedInstances.placementRefusal(instance);
        assertThat(refusal).as("step 1: the host's refusal is known before any click").isNotNull();
        assertThat(refusal.key()).as("step 1: it is the placement gate's own words").isEqualTo("host_not_admitted");

        // 2. The operator's Deploy and Restart are offered dead with exactly those words.
        AccessContext operator = TenantConduits.operator();
        for (String verb : List.of(InstanceOperations.START.id().toString(), InstanceOperations.RESTART.id()
                .toString())) {
            PanelAction<Row> action = action(InstanceActions.placedOperator(), verb);
            assertThat(action.disabledFor(instance, operator))
                .as("step 2: " + verb + " is dead with the host's reason").isNotNull();
            assertThat(action.disabledFor(instance, operator).key()).isEqualTo("host_not_admitted");
        }

        // 3. On /manage the same Deploy is dead too, but its words never name the host: a tenant reads that the
        //    operator has to clear it.
        PanelAction<Row> delegated = action(InstanceActions.placedDelegated(), InstanceOperations.START.id()
                .toString());
        AccessContext tenant = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(
            ApiSupport.user("power-availability-tenant@hohenheim.local", "Power Tenant"), "Power Tenant")));
        assertThat(delegated.disabledFor(instance, tenant).key())
            .as("step 3: the tenant reads the host-free sentence").isEqualTo("deploy_blocked_delegated");

        // 3b. The operator reading /manage is the one who clears it: their words name the fix, still host-free.
        assertThat(delegated.disabledFor(instance, operator).key())
            .as("step 3b: the operator on /manage reads how to clear it, never that their operator must")
            .isEqualTo("deploy_blocked_operator");

        // 4. Once the instance sits on a host that takes it, every verb is live again.
        var local = HostFixtures.captureLocal();
        try {
            HostFixtures.makeLocalPlaceable(16L * 1024);
            Row localHost = Models.get(ServerModel.class).findById(ServerModel.localServerId());
            localHost.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
            Models.get(ServerModel.class).save(localHost);
            HostFixtures.acknowledgePosture(Models.get(ServerModel.class).findById(ServerModel.localServerId()));
            instance.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
            Models.get(InstanceModel.class).save(instance);
            Row placed = Models.get(InstanceModel.class).findById(instance.get(InstanceModel.ID));
            assertThat(OwnedInstances.placementRefusal(placed)).as("step 4: an admitted host refuses nothing").isNull();
            assertThat(action(InstanceActions.placedOperator(), InstanceOperations.START.id().toString())
                .disabledFor(placed, operator)).as("step 4: Deploy is live").isNull();
        } finally {
            local.restore();
        }
    }

    private static PanelAction<Row> action(List<PanelAction<Row>> actions, String id) {
        return actions.stream().filter(action -> action.id().toString().equals(id)).findFirst().orElseThrow();
    }

    private static Row instance(String name, int serverId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        row.set(InstanceModel.SERVER_ID, serverId);
        Models.get(InstanceModel.class).save(row);
        return Models.get(InstanceModel.class).findById(row.get(InstanceModel.ID));
    }
}
