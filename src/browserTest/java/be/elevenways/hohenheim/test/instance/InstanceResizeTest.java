package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.InstanceResource;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceResize;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A resource-ceiling resize REACHES THE DAEMON: saving a new memory or CPU limit on a
 * running workload recreates its container, the way the database tier already did.
 *
 * AIDEV-NOTE: the defect this pins was silent by construction. The save persisted the
 * setting and moved BOTH ledgers -- the host budget and the owner budget, through the
 * write hooks -- while {@code HostConfig.Memory} kept the old number until somebody
 * pressed Restart, so every surface said the resize had happened and only the cgroup
 * disagreed. Proven on production robbedoes 2026-09-01. The assertion that matters is
 * therefore never "the row saved": it is that the recreate was ASKED FOR, which is what
 * the seam below records.
 *
 * No daemon is contacted: the recreate lane is replaced, so what is under test is the
 * DECISION and its wiring, not Docker.
 */
class InstanceResizeTest {

    private static final String PREFIX = "resize-";
    private static final String DOCKER_KIND = "hohenheim:docker_container";

    private static SqlDatasource datasource;

    private final List<Integer> recreated = new ArrayList<>();
    private final List<Integer> instances = new ArrayList<>();
    private int host;

    /** Its OWN database: a neighbour's live rows would move the budget this places against. */
    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void cleanUp() {
        InstanceResize.resetRecreaterForTesting();
        Db.run(datasource, () -> {
            for (Integer id : this.instances) {
                Models.get(InstanceModel.class).delete(id);
            }
        });
        this.instances.clear();
        this.recreated.clear();
    }

    @Test
    void aCeilingChangeOnALiveWorkloadRecreatesItAndOnlyThen() {
        InstanceResize.setRecreaterForTesting(this.recreated::add);
        Db.run(datasource, () -> {
            this.host = host();

            // 1. THE DECISION, in isolation: only a moved ceiling on a LIVE workload can
            //    need a recreate. An unchanged ceiling must not bounce a workload just
            //    because the operator pressed Save -- a recreate drops every connection.
            assertThat(InstanceResize.requiresRecreate(limits(512, null), limits(512, null),
                    InstanceModel.STATUS_RUNNING))
                .as("step 1: an unchanged ceiling is a no-op").isFalse();
            assertThat(InstanceResize.requiresRecreate(limits(512, null), limits(1024, null),
                    InstanceModel.STATUS_RUNNING))
                .as("step 1: a moved memory ceiling needs the container back").isTrue();
            assertThat(InstanceResize.requiresRecreate(limits(512, null), limits(512, 1.5),
                    InstanceModel.STATUS_RUNNING))
                .as("step 1: so does a moved CPU ceiling").isTrue();
            assertThat(InstanceResize.requiresRecreate(limits(512, null), limits(1024, null),
                    InstanceModel.STATUS_STOPPED))
                .as("step 1: a stopped workload has no container carrying the old ceiling")
                .isFalse();

            // 2. THE WIRING, through the real admin surface: a running workload resized on
            //    the form persists the new ceiling AND asks for the recreate.
            int running = save(workload("running", 512, InstanceModel.STATUS_RUNNING));
            update(running, 1024);
            assertThat(this.recreated)
                .as("step 2: the resize asked for exactly this workload's recreate")
                .containsExactly(running);
            assertThat(memoryOf(running))
                .as("step 2: and the new ceiling really landed on the record").isEqualTo(1024);

            // 3. FALSIFICATION: a save that moves something OTHER than the ceilings must
            //    not recreate anything. Without this the test would pass on a resource that
            //    redeployed on every save, which is the opposite defect.
            this.recreated.clear();
            Row renamed = Models.get(InstanceModel.class).findById(running);
            new InstanceResource().updateRow(renamed,
                Map.of(InstanceModel.NAME.getName(), PREFIX + "running-renamed"),
                AccessContext.anonymous());
            assertThat(this.recreated)
                .as("step 3: a rename is not a resize -- nothing was bounced").isEmpty();

            // 4. A STOPPED workload's resize persists and stays stopped: the next start
            //    reads the stored settings anyway, so bringing it up would be a resize
            //    silently becoming a deploy.
            int stopped = save(workload("stopped", 512, InstanceModel.STATUS_STOPPED));
            update(stopped, 256);
            assertThat(this.recreated)
                .as("step 4: a stopped workload is not started by a resize").isEmpty();
            assertThat(memoryOf(stopped))
                .as("step 4: but its new ceiling is stored for the next start").isEqualTo(256);
        });
    }

    // -- helpers --------------------------------------------------------------

    private static Map<String, Object> limits(Integer memoryMb, Double cpus) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/image");
        if (memoryMb != null) {
            settings.put("memory_limit_mb", memoryMb);
        }
        if (cpus != null) {
            settings.put("cpu_limit", cpus);
        }
        return settings;
    }

    private int host() {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, PREFIX + "host");
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        HostFixtures.acknowledgePosture(row);
        HostPreflight.store(PREFIX + "host", new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of(HostPreflight.MEM_TOTAL_FACT, 65536L * 1024 * 1024),
            true, Instant.now(), null));
        return row.get(ServerModel.ID);
    }

    private Row workload(String name, Integer memoryMb, String status) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, PREFIX + name);
        row.set(InstanceModel.KIND, DOCKER_KIND);
        row.set(InstanceModel.SETTINGS, limits(memoryMb, null));
        row.set(InstanceModel.SERVER_ID, this.host);
        row.set(InstanceModel.STATUS, status);
        return row;
    }

    private int save(Row row) {
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        this.instances.add(id);
        return id;
    }

    /** The resize as the admin form performs it: the stored row plus the submitted values. */
    private void update(int instanceId, int memoryMb) {
        Row existing = Models.get(InstanceModel.class).findById(instanceId);
        new InstanceResource().updateRow(existing,
            Map.of(InstanceModel.SETTINGS.getName(), limits(memoryMb, null)),
            AccessContext.anonymous());
    }

    private static Integer memoryOf(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        return row.get(InstanceModel.SETTINGS) instanceof Map<?, ?> settings
            && settings.get("memory_limit_mb") instanceof Number number
                ? number.intValue() : null;
    }
}
