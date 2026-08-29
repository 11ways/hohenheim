package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A failed stack deploy ALWAYS leaves a deployment row carrying the reason, and every
 * surface that says "Failed" says why.
 *
 * Pinned defect (QA 2026-08-29, F3): a stack read "Failed" with "No deployments yet" --
 * the row was minted only once the worker reached {@code runDeploy}, so whatever died
 * before that (or the boot sweep after a restart) stamped the status without a record.
 * The failure forced here needs no daemon: a service depending on a service that does
 * not exist is refused while the spec is resolved from the records, which is the
 * earliest point a deploy can fail.
 */
class StackDeployFailureRecordTest extends HohenheimTestBase {

    private static final String STACK_NAME = "hhfail-" + Long.toHexString(System.nanoTime());

    private static Integer stackId;

    @BeforeAll
    static void seed() {
        Model stacks = Models.get(StackModel.class);
        Row stack = stacks.createEmptyRow();
        stack.set(StackModel.NAME, STACK_NAME);
        stack.set(StackModel.ENABLED, true);
        stack.set(StackModel.SERVER_ID, ServerModel.localServerId());
        stacks.save(stack);
        stackId = stack.get(StackModel.ID);

        Model services = Models.get(StackServiceModel.class);
        Row service = services.createEmptyRow();
        service.set(StackServiceModel.STACK_ID, stackId);
        service.set(StackServiceModel.NAME, "app");
        service.set(StackServiceModel.ENABLED, true);
        service.set(StackServiceModel.IMAGE, "alpine:latest");
        service.set(StackServiceModel.RESTART_POLICY, "no");
        Row depends = new Row();
        depends.set(StackServiceModel.DEPENDS_SERVICE, "ghost");
        depends.set(StackServiceModel.DEPENDS_CONDITION, StackServiceModel.CONDITION_STARTED);
        service.setRecords(StackServiceModel.DEPENDS_ON, List.of(depends));
        services.save(service);
    }

    @AfterAll
    static void cleanUp() {
        if (stackId == null) {
            return;
        }
        Model deployments = Models.get(StackDeploymentModel.class);
        for (Row row : deployments.find().where(StackDeploymentModel.STACK_ID.eq(stackId)).all()) {
            deployments.delete(row.get(StackDeploymentModel.ID));
        }
        Model services = Models.get(StackServiceModel.class);
        for (Row row : services.find().where(StackServiceModel.STACK_ID.eq(stackId)).all()) {
            services.delete(row.get(StackServiceModel.ID));
        }
        Models.get(StackModel.class).delete(stackId);
    }

    @Test
    void aFailedDeployLeavesItsRowAndReasonEverywhereAndARetryAddsAnother() throws Exception {
        // 1. The deploy fails before it touches any daemon, naming the missing dependency.
        assertThatThrownBy(() -> StackRuntime.get().deploy(stackId, "manual"))
            .as("step 1: the deploy refuses by name")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("ghost");

        // 2. STATE: the stack is failed AND a settled deployment row explains it.
        Row stack = Models.get(StackModel.class).findById(stackId);
        assertThat(stack.get(StackModel.STATUS))
            .as("step 2: the stack reads failed").isEqualTo(StackModel.STATUS_FAILED);
        List<Row> rows = deployments();
        assertThat(rows).as("step 2: exactly one deployment row exists").hasSize(1);
        Row first = rows.get(0);
        assertThat(first.get(StackDeploymentModel.STATUS))
            .as("step 2: it settled as failed").isEqualTo(StackDeploymentModel.STATUS_FAILED);
        assertThat((String) first.get(StackDeploymentModel.ERROR))
            .as("step 2: the error names the cause").contains("ghost");
        assertThat((String) first.get(StackDeploymentModel.LOG))
            .as("step 2: the log carries the failure line").contains("FAILED:");
        assertThat((Object) first.get(StackDeploymentModel.FINISHED_AT))
            .as("step 2: it is finalized, never left running").isNotNull();

        // 3. SURFACES: the services tab (the front door) states the reason and links the
        //    log; the deployments tab lists the failed row with its error; the stacks
        //    list carries the reason under the Failed badge.
        HttpResponse<String> services = adminGet("/admin/stacks/" + stackId + "/page/services");
        assertThat(services.statusCode()).isEqualTo(200);
        assertThat(services.body())
            .as("step 3: the front door names the failure and links the log")
            .contains("data-stack-failure")
            .contains("depends on unknown or disabled service")
            .contains("/admin/stacks/" + stackId + "/page/deployments");

        HttpResponse<String> history = adminGet("/admin/stacks/" + stackId + "/page/deployments");
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(history.body())
            .as("step 3: the history shows the failed row with its reason")
            .contains("hh-deploy-error")
            .contains("depends on unknown or disabled service")
            .doesNotContain("No deployments yet");

        HttpResponse<String> list = adminGet("/admin/stacks");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body())
            .as("step 3: the list explains the Failed badge inline")
            .contains("depends on unknown or disabled service");

        // 4. RETRY: a second attempt is a second row; the first stays as history.
        assertThatThrownBy(() -> StackRuntime.get().deploy(stackId, "manual"))
            .isInstanceOf(IOException.class);
        List<Row> afterRetry = deployments();
        assertThat(afterRetry).as("step 4: the retry recorded its own row").hasSize(2);
        assertThat(afterRetry.get(1).get(StackDeploymentModel.ID))
            .as("step 4: the first row survived the retry")
            .isEqualTo(first.get(StackDeploymentModel.ID));

        // 5. A deploy the controller died under: the boot sweep settles its running row
        //    with the one reason a restart can give, so the tab never shows a row that
        //    is "running" forever beside a stack that is "failed".
        Model deployments = Models.get(StackDeploymentModel.class);
        Row interrupted = deployments.createEmptyRow();
        interrupted.set(StackDeploymentModel.STACK_ID, stackId);
        interrupted.set(StackDeploymentModel.STATUS, StackDeploymentModel.STATUS_RUNNING);
        interrupted.set(StackDeploymentModel.REASON, "manual");
        interrupted.set(StackDeploymentModel.STARTED_AT, Instant.now());
        deployments.save(interrupted);
        Models.get(StackModel.class).find().where(StackModel.ID.eq(stackId))
            .assign(StackModel.STATUS, StackModel.STATUS_DEPLOYING).updateAll();

        StackRuntime.get().resetInterruptedDeploys();

        Row swept = deployments.findById(interrupted.get(StackDeploymentModel.ID));
        assertThat(swept.get(StackDeploymentModel.STATUS))
            .as("step 5: the interrupted row settled as failed")
            .isEqualTo(StackDeploymentModel.STATUS_FAILED);
        assertThat((String) swept.get(StackDeploymentModel.ERROR))
            .as("step 5: with the restart named as the reason")
            .isEqualTo(StackRuntime.INTERRUPTED_BY_RESTART);
        assertThat(Models.get(StackModel.class).findById(stackId).get(StackModel.STATUS))
            .as("step 5: and the stack no longer claims to be deploying")
            .isNotEqualTo(StackModel.STATUS_DEPLOYING);
    }

    private static List<Row> deployments() {
        return Models.get(StackDeploymentModel.class).find()
            .where(StackDeploymentModel.STACK_ID.eq(stackId))
            .orderBy(StackDeploymentModel.ID, SortOrder.DESC)
            .all();
    }
}
