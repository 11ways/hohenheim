package be.elevenways.hohenheim.test.instance;

import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.panel.PartsWrites;
import be.elevenways.zenit.cms.server.panel.PartsForms;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.test.PanelEntryViews;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.cms.server.page.FormConcurrency;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.TestAccessContexts;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Configuration saves through both CMS projections cannot resurrect an operation's runtime state or fence. */
class InstanceConfigurationSaveTest extends HohenheimTestBase {

    private static final AtomicReference<Runnable> DURING_WRITE = new AtomicReference<>();

    static {
        InstanceModel.SCHEMA.addBeforeWriteHook(context -> {
            Runnable winner = DURING_WRITE.getAndSet(null);
            if (winner != null) winner.run();
        });
    }

    @Test
    void bothResourceWritersPreserveTheWinningStatusAndFence() {
        int id = instance("configuration-direct");
        try {
            for (PanelResource<Row> resource : List.of(PanelEntryViews.of(HohenheimSlugs.ADMIN,
                HohenheimSlugs.INSTANCES),
                    PanelEntryViews.of(HohenheimSlugs.MANAGE, HohenheimSlugs.INSTANCES))) {
                outcome(id, InstanceModel.STATUS_RUNNING, 7L);
                Row stale = Models.get(InstanceModel.class).findById(id);
                // 1. An existing holder still carries its old operation fields, even if they were explicitly staged.
                stale.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
                stale.set(InstanceModel.CLAIM_FENCE, 7L);
                outcome(id, InstanceModel.STATUS_STOPPED, 8L);
                PartsWrites.updateRow(resource, stale, Map.of("name", "configuration-renamed"),
                    TestAccessContexts.allAllowed());
                assertWinner(id, "step 2: " + resource.id());
            }
        } finally {
            removeFixture(id);
        }
    }

    @Test
    void theManageRequestCannotRestoreRuntimeStateLoadedBeforeItsWrite() throws Exception {
        int id = instance("configuration-request");
        try {
            outcome(id, InstanceModel.STATUS_RUNNING, 7L);
            PanelResource<Row> resource = PanelEntryViews.of(HohenheimSlugs.MANAGE, HohenheimSlugs.INSTANCES);
            Row loaded = Models.get(InstanceModel.class).findById(id);
            String snapshot = FormConcurrency.token(PartsForms.formSpec(resource),
                PartsReads.valuesFromRow(resource, loaded));
            // 1. The winner advances after the request has loaded its row, before the configuration statement.
            DURING_WRITE.set(() -> outcome(id, InstanceModel.STATUS_STOPPED, 8L));
            var response = adminPostForm("/manage/instances/" + id,
                "name=configuration-request-renamed&crash_policy="
                    + URLEncoder.encode(String.valueOf((Object) loaded.get(InstanceModel.CRASH_POLICY)),
                            StandardCharsets.UTF_8)
                    + "&cms__snapshot=" + URLEncoder.encode(snapshot, StandardCharsets.UTF_8));
            assertThat(response.statusCode()).as("step 1: the real configuration request succeeds").isIn(200, 302, 303);
            assertThat(DURING_WRITE.get()).as("step 1: the winner ran inside the request's write").isNull();
            assertWinner(id, "step 2: real manage request");
            assertThat(Models.get(InstanceModel.class).findById(id).get(InstanceModel.NAME))
                .as("step 3: the requested configuration itself lands").isEqualTo("configuration-request-renamed");
        } finally {
            DURING_WRITE.set(null);
            removeFixture(id);
        }
    }

    private static int instance(String name) {
        FakeNativeDaemons.register();
        int host = HostFixtures.admittedIncusHost(name + "-host");
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceModel.SERVER_ID, host);
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static void outcome(int id, String status, long fence) {
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
            .assign(InstanceModel.STATUS, status).assign(InstanceModel.CLAIM_FENCE, fence).updateAll();
    }

    private static void removeFixture(int id) {
        Row row = Models.get(InstanceModel.class).findById(id);
        int host = row.get(InstanceModel.SERVER_ID);
        HardDeletes.byId(Models.get(InstanceModel.class), id);
        Models.get(ServerModel.class).delete(host);
    }

    private static void assertWinner(int id, String step) {
        Row winner = Models.get(InstanceModel.class).findById(id);
        assertThat(winner.get(InstanceModel.STATUS)).as(step + ": winner's status stands")
                .isEqualTo(InstanceModel.STATUS_STOPPED);
        assertThat(winner.get(InstanceModel.CLAIM_FENCE)).as(step + ": winner's fence stands").isEqualTo(8L);
        assertThat(Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
            .and(InstanceModel.CLAIM_FENCE.eq(7L)).assign(InstanceModel.STATUS, InstanceModel.STATUS_ERROR).updateAll())
            .as(step + ": the old holder's late fenced statement still matches zero").isZero();
    }
}
