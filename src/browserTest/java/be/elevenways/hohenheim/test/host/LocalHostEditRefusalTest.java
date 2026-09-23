package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.ServerResource;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The implicit local host's edit form is HONEST: its identity renders read-only (and its trust token not
 * at all), its addresses and posture save, and a submitted identity change is refused by name instead of
 * being dropped while the form says "Saved".
 *
 * WHY IT EXISTS: the local-host branch of ServerResource.updateRow saved addresses and posture and
 * silently discarded every other submitted field, reporting success.
 */
class LocalHostEditRefusalTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void theLocalHostOffersOnlyWhatItSavesAndRefusesTheRest() {
        Db.run(datasource, () -> {
            ServerResource resource = new ServerResource();
            AccessContext operator = AccessContext.of(TenantConduits.stubFor(null));
            ServerModel servers = Models.get(ServerModel.class);
            int localId = ServerModel.localServerId();
            Row local = servers.findById(localId);

            Row remote = servers.createEmptyRow();
            remote.set(ServerModel.NAME, "local-edit-remote");
            remote.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
            remote.set(ServerModel.MODE, ServerModel.MODE_SSH);
            remote.set(ServerModel.SSH_TARGET, "deploy@remote.example.test");
            servers.save(remote);

            // 1. The local host's identity renders read-only; a remote host's stays editable.
            for (String identity : new String[] {"name", "runtime", "ssh_target", "incus_url"}) {
                assertThat(decisionOf(resource, identity, local, operator))
                    .as("step 1: the local host's '%s' is read-only on its form", identity)
                    .isEqualTo(FieldAccess.Decision.READONLY);
                assertThat(decisionOf(resource, identity, remote, operator))
                    .as("step 1: a remote host's '%s' stays editable", identity)
                    .isEqualTo(FieldAccess.Decision.EDITABLE);
                assertThat(decisionOf(resource, identity, null, operator))
                    .as("step 1: and the create form's '%s' too", identity)
                    .isEqualTo(FieldAccess.Decision.EDITABLE);
            }
            assertThat(decisionOf(resource, "incus_trust_token", local, operator))
                .as("step 1: the local host offers no trust token at all")
                .isEqualTo(FieldAccess.Decision.HIDDEN);
            assertThat(decisionOf(resource, "posture", local, operator))
                .as("step 1: while its posture has no binding and stays editable")
                .isNull();

            // 2. A submitted identity change is REFUSED by field, never dropped silently.
            assertThatThrownBy(() -> resource.updateRow(local,
                    Map.of("ssh_target", "evil@intruder.example.test"), operator))
                .as("step 2: changing the local host's ssh target is refused")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("ssh_target");
            assertThat((Object) servers.findById(localId).get(ServerModel.SSH_TARGET))
                .as("step 2: and nothing was written")
                .isNull();

            // 3. The editable half saves -- and a resubmitted UNCHANGED identity is no refusal.
            resource.updateRow(servers.findById(localId), Map.of(
                "name", "local",
                "posture", ServerModel.POSTURE_DEDICATED,
                "public_ipv4", "203.0.113.7"), operator);
            Row saved = servers.findById(localId);
            assertThat((String) saved.get(ServerModel.POSTURE))
                .as("step 3: the local host's posture is stored")
                .isEqualTo(ServerModel.POSTURE_DEDICATED);
            assertThat((String) saved.get(ServerModel.PUBLIC_IPV4))
                .as("step 3: and so is its public address")
                .isEqualTo("203.0.113.7");
            assertThat((String) saved.get(ServerModel.NAME))
                .as("step 3: its name is untouched")
                .isEqualTo("local");
        });
    }

    /** The resource's declared decision for one entry, or null when it binds none. */
    private static FieldAccess.@Nullable Decision decisionOf(@NonNull ServerResource resource,
                                                             @NonNull String entry, @Nullable Row record,
                                                             @NonNull AccessContext context) {
        for (ResourceFieldBinding binding : resource.fieldBindings()) {
            if (binding.path().equals(entry)) {
                return binding.access().decide(context, record);
            }
        }
        return null;
    }
}
