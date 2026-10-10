package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionRequest;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationResult;
import be.elevenways.zenit.common.operation.ResultStep;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two rollback verbs, on a site and on an application, ask and answer the same way.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class RollbackActionsAgreeTest {

    @BeforeAll
    static void setUp() throws Exception {
        TestDatabases.freshBootedDatasource();
    }

    @Test
    void bothRollbacksConfirmWithTheirVerbAndNameWhatTheyRolledBack() {
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

        // 2. Each toast names the record it rolled back.
        Row shop = Models.get(SiteModel.class).createEmptyRow();
        shop.set(SiteModel.NAME, "shop");
        assertThat(toast(site, shop).args().get("name")).as("step 2: the site's toast names the site")
            .isEqualTo("shop");
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, "shop-app");
        assertThat(toast(application, app).args().get("name")).as("step 2: the application's toast names it")
            .isEqualTo("shop-app");
    }

    private static PanelAction<Row> placed(List<PanelAction<Row>> actions, Operation<?, ?, ?> operation) {
        return actions.stream().filter(action -> action.operation() == operation).findFirst()
            .orElseThrow(() -> new AssertionError("no action places " + operation.id()));
    }

    @SuppressWarnings("unchecked")
    private static Microcopy toast(PanelAction<Row> action, Row subject) {
        PanelRequest request = new PanelRequest(Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN)),
            TenantConduits.stubFor(null), AccessContext.anonymous(), null);
        var step = (ResultStep<ActionRequest<Row>, Void, CmsActionResult>) Objects.requireNonNull(action.result());
        var result = (CmsActionResult.Refresh) step.answer(new ActionRequest<>(request, List.of(subject), null),
            new OperationResult<>(UUID.randomUUID(), null));
        return Objects.requireNonNull(result.toastMessage());
    }
}
