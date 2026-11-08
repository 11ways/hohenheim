package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.instance.HungWorkloads;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.PublishedPortProbe;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.ServedApp;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workload a site serves that keeps connecting without answering is restarted, and only that: a refused connect, an
 * error answer, a stopped record or a crash policy of none is never a hang.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class HungWorkloadsTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    /** How the fake workload answers the probe. */
    private enum Answer { OK, SERVER_ERROR, SILENT, REFUSED, LANE_FAILED }

    @Test
    void aWorkloadThatConnectsWithoutAnsweringIsRestartedAfterThreeSweeps() {
        Db.run(APP.database(), () -> {
            Answer[] answer = {Answer.SILENT};
            List<Integer> restarted = new ArrayList<>();
            HungWorkloads watch = new HungWorkloads(new InstanceService(), server -> probe(answer),
                (id, server, name) -> restarted.add(id));

            int app = served("hang-app", 41871, InstanceModel.CRASH_RESTART);

            // 1. Two silent sweeps are patience, not a verdict.
            assertThat(watch.sweep()).as("step 1: the first silent sweep restarts nothing").doesNotContain(app);
            assertThat(watch.sweep()).as("step 1: nor the second").doesNotContain(app);

            // 2. An answer in between, even an error one, starts the count over: the process answers.
            answer[0] = Answer.SERVER_ERROR;
            assertThat(watch.sweep()).as("step 2: a 500 is an answer").doesNotContain(app);
            answer[0] = Answer.SILENT;
            assertThat(watch.sweep()).as("step 2: so silence counts from one again").doesNotContain(app);
            assertThat(watch.sweep()).doesNotContain(app);

            // 3. A refused connect is no evidence of a hang (a host the probe cannot reach refuses every connect),
            //    and neither is a probe lane that could not run (no curl on the host, ssh down).
            answer[0] = Answer.REFUSED;
            assertThat(watch.sweep()).as("step 3: a refused connect restarts nothing").doesNotContain(app);
            answer[0] = Answer.SILENT;
            watch.sweep();
            watch.sweep();
            answer[0] = Answer.LANE_FAILED;
            assertThat(watch.sweep()).as("step 3: a lane that could not run restarts nothing").doesNotContain(app);
            answer[0] = Answer.SILENT;
            watch.sweep();
            watch.sweep();
            assertThat(restarted).as("step 3: nothing restarted yet").doesNotContain(app);

            // 4. The third silent sweep in a row restarts it, once, and says so in its history.
            assertThat(watch.sweep()).as("step 4: three silent sweeps in a row restart it").contains(app);
            assertThat(restarted).as("step 4: through the restart lane, once").containsOnlyOnce(app);
            assertThat(RecordedChanges.withFact(Models.get(InstanceModel.class), app,
                    HohenheimActivityAction.RESTARTED_UNANSWERED))
                .as("step 4: the history names why").hasSize(1);
            assertThat(watch.sweep()).as("step 4: the count starts over after a restart").doesNotContain(app);

            // 5. A workload that keeps hanging after its restarts is left alone: a restart is evidently no cure.
            watch.sweep();
            assertThat(watch.sweep()).as("step 5: the second restart").contains(app);
            watch.sweep();
            watch.sweep();
            assertThat(watch.sweep()).as("step 5: the third restart").contains(app);
            for (int sweep = 0; sweep < 6; sweep++) {
                assertThat(watch.sweep()).as("step 5: no fourth restart within the hour").doesNotContain(app);
            }
            assertThat(restarted.stream().filter(id -> id == app).count()).as("step 5: three restarts in all")
                .isEqualTo(3);

            // 6. A workload whose crash policy asks for no restart is never restarted.
            int kept = served("hang-kept", 41872, InstanceModel.CRASH_NONE);
            for (int sweep = 0; sweep < 4; sweep++) {
                assertThat(watch.sweep()).as("step 6: crash policy none is left alone").doesNotContain(kept);
            }

            // 7. A stopped record is no workload to probe.
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(app))
                .assign(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED).updateAll();
            for (int sweep = 0; sweep < 4; sweep++) {
                assertThat(watch.sweep()).as("step 7: a stopped record is not probed").doesNotContain(app);
            }
        });
    }

    /** A running docker-container record holding {@code port}, exposed by an enabled site. */
    private static int served(String name, int port, String crashPolicy) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", "v1");
        settings.put("container_port", 8080);
        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        instance.set(InstanceModel.NAME, name);
        instance.set(InstanceModel.KIND, DockerContainerKind.ID.toString());
        instance.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instance.set(InstanceModel.SETTINGS, settings);
        instance.set(InstanceModel.CRASH_POLICY, crashPolicy);
        Models.get(InstanceModel.class).save(instance);
        int id = instance.get(InstanceModel.ID);
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
            .assign(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING).updateAll();
        PortLedger.recordObserved(ServerModel.localServerId(), "127.0.0.1", port, "tcp", InstanceModel.MODEL_ID, id,
            "hung workload test");
        // A site only routes, and so only reaches its workload, through a domain with a hostname.
        ProxyTestSupport.addDomain(ProxyTestSupport.setupInstanceSite("Site " + name, name, id),
            name + ".hang.test", SiteDomainModel.MATCH_EXACT, null, false);
        return id;
    }

    /** A probe lane answering as {@code answer} says. */
    private static PublishedPortProbe probe(Answer[] answer) {
        return new PublishedPortProbe() {
            @Override
            public boolean accepts(int port) {
                return answer[0] != Answer.REFUSED;
            }

            @Override
            public int httpStatus(int port, @NonNull String path) throws IOException {
                return switch (answer[0]) {
                    case OK -> 200;
                    case SERVER_ERROR -> 500;
                    case SILENT -> 0;
                    case REFUSED -> throw new IOException("connection refused");
                    case LANE_FAILED -> throw new IOException("the host could not run the probe (exit 127)");
                };
            }

            @Override
            public byte @NonNull [] exchange(int port, byte @NonNull [] request, @NonNull Predicate<byte[]> complete,
                                             int maxBytes, @NonNull Duration wait) {
                return new byte[0];
            }
        };
    }
}
