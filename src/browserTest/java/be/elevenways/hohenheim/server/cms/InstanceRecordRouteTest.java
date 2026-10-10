package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instance list's two shared parts: {@link InstanceParts#recordRoute}, the one way a
 * surface links an instance row (a release row, which the list does not serve, goes to its
 * application's Deploys tab instead of a 404), and {@link InstanceActions}, the one set of
 * action builders both panels offer.
 */
class InstanceRecordRouteTest {

    @BeforeAll
    static void setUp() throws Exception {
        TestDatabases.freshBootedDatasource();
    }

    @Test
    void anInstanceLinkNeverLandsOnARowTheListDoesNotServe() {
        // 1. An authored instance links to itself, or to the subpage asked for.
        Row authored = row(5, "hohenheim:docker_container", null, null);
        assertThat(InstanceParts.recordRoute("admin", authored, null).toUrl())
            .as("step 1: an instance links to its own record, landing on its overview")
            .isEqualTo("/admin/instances/5/page/overview");
        assertThat(InstanceParts.recordRoute("admin", authored, HohenheimSlugs.Tab.CONSOLE).toUrl())
            .as("step 1: or to the subpage the caller names")
            .isEqualTo("/admin/instances/5/page/console");

        // 2. A release row (which the list's accessFunction excludes, so its own URL 404s)
        //    links to the Deploys tab of the application that owns it, whatever subpage
        //    the caller asked for.
        Row release = row(9, ReleaseKind.ID.toString(), InstanceModel.MODEL_ID.toString(), 3);
        assertThat(InstanceParts.recordRoute("admin", release, HohenheimSlugs.Tab.CONSOLE).toUrl())
            .as("step 2: a release links to its application's Deploys tab")
            .isEqualTo("/admin/instances/3/page/" + HohenheimSlugs.Tab.DEPLOYMENTS);

        // 3. A release no application owns links to the list, never to itself.
        Row orphan = row(11, ReleaseKind.ID.toString(), null, null);
        assertThat(InstanceParts.recordRoute("admin", orphan, null).toUrl())
            .as("step 3: an unowned release links to the list, not to a 404")
            .isEqualTo("/admin/instances");
    }

    @Test
    void bothPanelsOfferTheSameBuildersAndTheDelegatedSetIsTheirSubset() {
        List<String> operator = ids(InstanceParts.admin().actions());
        List<String> delegated = ids(InstanceParts.manage().actions());

        // 1. The delegated panel offers exactly the app's own address, power
        //    (deploy, restart and stop), the two artifacts, the app update and the console line.
        assertThat(delegated).as("step 1: the delegated instance verbs")
            .containsExactly("instance_open_site", "start_instance", "restart_instance", "stop_instance",
                "snapshot_instance",
                "backup_instance", "app_update_instance", "console_command_instance");

        // 2. Every one of them is an operator verb too: one builder, two panels.
        assertThat(operator).as("step 2: the operator list holds every delegated verb")
            .containsAll(delegated);

        // 3. Placement, capture and the data-destroying verbs stay operator-only.
        assertThat(delegated).as("step 3: operator acts never reach the delegated panel")
            .doesNotContain("migrate_instance", "capture_template", "destroy_instance_data",
                "install_instance", "reinstall_instance", "expose_instance");
        assertThat(operator).as("step 3: the operator list still offers them")
            .contains("migrate_instance", "capture_template", "destroy_instance_data",
                "restart_instance", "rollback_instance", "expose_instance");
    }

    private static Row row(int id, String kind, String ownerModel, Integer ownerId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.ID, id);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.GENERATED_FOR_MODEL, ownerModel);
        row.set(InstanceModel.GENERATED_FOR_ID, ownerId);
        return row;
    }

    /** The placed operations' ids, in the order the bands draw them. */
    private static List<String> ids(List<PanelAction<Row>> placed) {
        return placed.stream().map(PanelAction::id).map(Identifier::getPath).toList();
    }
}
