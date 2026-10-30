package be.elevenways.hohenheim.server.cms;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.TestAccessContexts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two rollback verbs, on a site and on an application, ask the same way and answer the same way: as one
 * background run, from the site's row, the application's action band and its Deploys tab alike, landing on the page
 * that shows the run.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class RollbackActionsAgreeTest {


    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    @Test
    void bothRollbacksConfirmWithTheirVerbAndAnswerWithTheirRun() {
        PanelAction<Row> site = placed(SiteActions.operator(), SiteOperations.ROLLBACK_RELEASE);
        PanelAction<Row> application = placed(InstanceActions.placedOperator(), InstanceOperations.ROLLBACK);

        // 1. Each dialog is titled by its verb, its confirm button repeats that verb, and both are destructive.
        for (PanelAction<Row> rollback : List.of(site, application)) {
            ConfirmationSpec confirmation = Objects.requireNonNull(rollback.confirmation());
            assertThat(confirmation.title().key()).as("step 1: %s is titled by its verb", rollback.id())
                .isEqualTo("rollback");
            assertThat(confirmation.confirmLabel()).as("step 1: %s confirms with that same verb", rollback.id())
                .isEqualTo(confirmation.title());
            assertThat(confirmation.style()).as("step 1: %s is painted destructive", rollback.id())
                .isEqualTo(ActionStyle.DESTRUCTIVE);
        }

        // 2. Every rollback is a background run: no toast step. The site's lands on the site itself and its finished
        //    run opens it too (it has no Deploys tab), the application's land on and open the Deploys tab, where the
        //    release it started shows.
        Row siteRow = Models.get(SiteModel.class).createEmptyRow();
        assertThat(site.result()).as("step 2: the site's rollback answers with its run, not a toast").isNull();
        assertThat(site.runOpens()).as("step 2: its finished run opens the site's front door").isNull();
        assertThat(Objects.requireNonNull(site.startedRunOpens()).slugFor(siteRow))
            .as("step 2: its started run lands on the site's front door").isNull();
        PanelAction<Row> tab = placed(new InstanceDeploymentsPage().actions(), InstanceOperations.ROLLBACK);
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, "shop-app");
        for (PanelAction<Row> rollback : List.of(application, tab)) {
            assertThat(rollback.result()).as("step 2: %s answers with its run, not a toast", rollback.placement())
                .isNull();
            assertThat(Objects.requireNonNull(rollback.runOpens()).slugFor(app))
                .as("step 2: the %s run, once finished, opens the Deploys tab", rollback.placement())
                .isEqualTo(HohenheimSlugs.Tab.DEPLOYMENTS);
            assertThat(Objects.requireNonNull(rollback.startedRunOpens()).slugFor(app))
                .as("step 2: the %s run, once started, lands on the Deploys tab", rollback.placement())
                .isEqualTo(HohenheimSlugs.Tab.DEPLOYMENTS);
        }

        // 3. All three spend the deploy lane's one budget.
        for (PanelAction<Row> rollback : List.of(site, application, tab)) {
            assertThat(Objects.requireNonNull(rollback.operation()).rateLimit(TestAccessContexts.anonymous()))
                .as("step 3: %s spends hh_deploy", rollback.id()).isSameAs(HohenheimEndpoints.DEPLOY_LIMIT);
        }
    }

    private static PanelAction<Row> placed(List<PanelAction<Row>> actions, Operation<?, ?, ?> operation) {
        return actions.stream().filter(action -> action.operation() == operation).findFirst()
            .orElseThrow(() -> new AssertionError("no action places " + operation.id()));
    }
}
