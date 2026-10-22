package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A fixed-port Deploy refused because another owner holds the port names that owner only to a caller who may read
 * it: a delegate of B who cannot see A learns nothing of A from the refusal or the toast built from it, while the
 * operator still gets the named refusal.
 *
 * No daemon is needed: the ledger refuses before any container exists.
 */
class PortConflictDisclosureTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "portdisclose-";
    private static final String HOLDER_NAME = PREFIX + "secret-holder";
    private static final String HOLDER_NOTE = "secret holder note";
    private static final int PORT = 18_481;

    private static HostFixtures.LocalHostState localHost;
    private static int holderId;
    private static int deployerId;
    private static UserPrincipal delegate;

    @BeforeAll
    static void seed() {
        localHost = HostFixtures.captureLocal();
        HostFixtures.makeLocalPlaceable(16 * 1024);
        holderId = instance(HOLDER_NAME);
        deployerId = instance(PREFIX + "deployer");
        UUID delegateId = ApiSupport.user("delegate@portdisclose.test", "Port Delegate");
        delegate = new UserPrincipal(delegateId, "Port Delegate");
        // B's delegate may deploy B (power implies view) and holds nothing on A.
        RecordGrants.grant(GrantSubjectType.USER, delegateId, InstanceModel.MODEL_ID, deployerId,
            HohenheimCapabilities.POWER, true);
        // A already deployed and holds the fixed port.
        PortLedger.claimPreallocated(ServerModel.localServerId(), "", PORT, "tcp", InstanceModel.MODEL_ID, holderId,
            HOLDER_NOTE);
    }

    @AfterAll
    static void cleanUp() {
        PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, holderId);
        PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, deployerId);
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).withTrashed().all()) {
            HardDeletes.byId(instances, row.get(InstanceModel.ID));
        }
        localHost.restore();
    }

    @Test
    void aDelegateWhoCannotSeeTheHolderIsRefusedWithoutItsName() {
        // 1. B's delegate deploys B onto the port A holds: refused by the ledger, with the holder-free refusal.
        Throwable refused = catchThrowable(() ->
            TenantConduits.as(delegate, () -> new InstanceService().deploy(deployerId)));
        assertThat(refused).as("step 1: the deploy is refused").isInstanceOf(Violations.class);
        assertThat(keys(refused)).as("step 1: by the holder-free port refusal")
            .containsExactly("port_conflict_unnamed");

        // 2. THE ASSERTION: neither the refusal's rendered arguments nor the toast's text (its first message, as the
        //    panel projects it) carries A's title, note or id.
        for (String shown : List.of(refused.getMessage(), toast(refused))) {
            assertThat(shown).as("step 2: nothing of A reaches the delegate: " + shown)
                .doesNotContain(HOLDER_NAME)
                .doesNotContain(HOLDER_NOTE)
                .doesNotContain("#" + holderId);
        }
        assertThat(toast(refused)).as("step 2: the toast still says which port").contains(String.valueOf(PORT));

        // 3. The operator deploying B gets the named refusal: the holder stays an operator's diagnostic.
        Row operator = TenantConduits.operatorUser();
        UserPrincipal operatorPrincipal = new UserPrincipal(operator.get(UserModel.ID), "Test Admin");
        Throwable named = catchThrowable(() ->
            TenantConduits.as(operatorPrincipal, () -> new InstanceService().deploy(deployerId)));
        assertThat(keys(named)).as("step 3: the operator's refusal names the holder").containsExactly("port_conflict");
        assertThat(toast(named)).as("step 3: by its title").contains(HOLDER_NAME);
    }

    private static List<String> keys(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        List<String> keys = new ArrayList<>();
        for (var violation : ((Violations) thrown).all()) {
            keys.add(violation.message().key());
        }
        return keys;
    }

    /** The first message resolved in English, the text the panel's refusal toast shows. */
    private static String toast(Throwable thrown) {
        ShippedCatalogs catalogs = new ShippedCatalogs();
        return ((Violations) thrown).all().getFirst().message().resolve(LocaleChain.ofTags("en"),
            catalogs::resolveSource);
    }

    private static int instance(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest",
            "command", "sleep 300", "container_port", 80, "host_port", PORT)));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }
}
