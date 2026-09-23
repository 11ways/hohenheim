package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The Docker preflight battery against a SCRIPTED daemon: what it stores, and whether a
 * host it once failed can ever be admitted again.
 *
 * AIDEV-NOTE: the defect this pins. {@code container_kernel} was written only when the
 * probe container FAILED, and {@link HostPreflight#store} merges, so a later passing run
 * never overwrote it: {@link HostPreflight#failedRequirementNow} kept finding the stale
 * FAIL and every placement on that host was refused forever. Production hosts may already
 * carry such an entry, so step 4 replays exactly that record shape.
 */
class HostPreflightBatteryTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    /**
     * A probe container that failed once and answers now: the passing run clears the
     * stored failure, and the host is placeable again.
     */
    @Test
    void aHostWhoseKernelProbeFailedOnceIsAdmittedAfterAPassingRun() {
        Db.run(datasource, () -> {
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.createEmptyRow();
            host.set(ServerModel.NAME, "battery-a");
            host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
            host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
            host.set(ServerModel.POSTURE, ServerModel.POSTURE_TRUSTED_ONLY);
            model.save(host);

            // 1. The probe container's exec FAILS: container_kernel is a required FAIL.
            ScriptedDocker docker = new ScriptedDocker();
            docker.execExit = 1;
            HostPreflight.Report failing = HostPreflight.run(docker, new EchoNft());
            assertThat(failing.check(HostPreflight.CONTAINER_KERNEL_CHECK).status())
                .as("step 1: a probe that could not exec fails container_kernel")
                .isEqualTo(HostPreflight.STATUS_FAIL);
            assertThat(failing.passed()).as("step 1: and sinks the verdict").isFalse();
            HostPreflight.store("battery-a", failing, HostPreflight.DOCKER_BATTERY);
            assertThat(HostPreflight.failedRequirementNow(model.findByName("battery-a")))
                .as("step 1: the stored verdict names the failed check")
                .isEqualTo(HostPreflight.CONTAINER_KERNEL_CHECK);

            // 2. The host is fixed: the SAME battery now writes container_kernel as a PASS.
            docker.execExit = 0;
            HostPreflight.Report passing = HostPreflight.run(docker, new EchoNft());
            assertThat(passing.check(HostPreflight.CONTAINER_KERNEL_CHECK))
                .as("step 2: a probe that answered still WRITES container_kernel").isNotNull();
            assertThat(passing.check(HostPreflight.CONTAINER_KERNEL_CHECK).status())
                .as("step 2: as a pass").isEqualTo(HostPreflight.STATUS_PASS);
            assertThat(passing.passed()).as("step 2: every required check passed").isTrue();
            for (HostPreflight.Check check : passing.checks()) {
                assertThat(HostPreflight.DOCKER_BATTERY)
                    .as("step 2: check '%s' is declared in DOCKER_BATTERY, or a partial run"
                        + " would drop it", check.name())
                    .contains(check.name());
            }

            // 3. Stored, the stale FAIL is gone and the posture gate admits the host.
            HostPreflight.store("battery-a", passing, HostPreflight.DOCKER_BATTERY);
            Row fixed = model.findByName("battery-a");
            assertThat(HostPreflight.storedCheckStatus(fixed, HostPreflight.CONTAINER_KERNEL_CHECK))
                .as("step 3: the passing run overwrote the stored failure")
                .isEqualTo(HostPreflight.STATUS_PASS);
            assertThat(HostPreflight.failedRequirementNow(fixed))
                .as("step 3: no required check fails any more").isNull();
            assertThat(catchThrowable(() -> HostAdmission.requirePreflightVerdictForPosture(fixed)))
                .as("step 3: the host is admitted for placement").isNull();

            // 4. PRODUCTION SHAPE: a record written by the old battery (container_kernel
            //    FAIL left behind by a plain merge) plus an entry no Docker run can ever
            //    produce (an Incus check left from a runtime flip). The next battery run
            //    clears both.
            HostPreflight.store("battery-a", new HostPreflight.Report(List.of(
                new HostPreflight.Check(HostPreflight.CONTAINER_KERNEL_CHECK,
                    HostPreflight.STATUS_FAIL, true, "probe container failed: old run"),
                new HostPreflight.Check("trusted", HostPreflight.STATUS_FAIL, true,
                    "left by the Incus battery")),
                Map.of(), false, Now.instant(), null));
            Row stale = model.findByName("battery-a");
            assertThat(catchThrowable(() -> HostAdmission.requirePreflightVerdictForPosture(stale)))
                .as("step 4: the stale record refuses placement, as production's would")
                .isInstanceOf(Violations.class);
            HostPreflight.store("battery-a", HostPreflight.run(docker, new EchoNft()),
                HostPreflight.DOCKER_BATTERY);
            Row healed = model.findByName("battery-a");
            assertThat(HostPreflight.storedCheckStatus(healed, "trusted"))
                .as("step 4: a check outside the Docker battery is dropped").isNull();
            assertThat(HostPreflight.failedRequirementNow(healed))
                .as("step 4: and nothing stale refuses the host any more").isNull();
        });
    }

    /** Docker API versions compare per component, never as a double. */
    @Test
    void apiVersionsCompareNumericallyPerComponent() {
        // 1. The floor itself and a newer two-digit minor pass.
        assertThat(apiStatus("1.41")).as("step 1: the floor passes")
            .isEqualTo(HostPreflight.STATUS_PASS);
        assertThat(apiStatus("1.47")).as("step 1: a newer minor passes")
            .isEqualTo(HostPreflight.STATUS_PASS);

        // 2. THE DEFECT: as doubles, 1.100 read 1.1 (refused) and 1.9 read 1.9 (admitted).
        assertThat(apiStatus("1.100")).as("step 2: 1.100 is newer than 1.41")
            .isEqualTo(HostPreflight.STATUS_PASS);
        assertThat(apiStatus("1.9")).as("step 2: 1.9 is older than 1.41")
            .isEqualTo(HostPreflight.STATUS_FAIL);
        assertThat(apiStatus("2.0")).as("step 2: a new major passes")
            .isEqualTo(HostPreflight.STATUS_PASS);

        // 3. Garbage fails closed.
        assertThat(apiStatus("")).as("step 3: empty fails").isEqualTo(HostPreflight.STATUS_FAIL);
        assertThat(apiStatus("1.x")).as("step 3: non-numeric fails")
            .isEqualTo(HostPreflight.STATUS_FAIL);
        assertThat(apiStatus("1..41")).as("step 3: an empty component fails")
            .isEqualTo(HostPreflight.STATUS_FAIL);
    }

    /** The controller version carries the build stamp's commit when the classpath has one. */
    @Test
    void theControllerVersionNamesTheBuildCommit() {
        String version = HostPreflight.controllerVersion();
        // 1. The browser-test classpath carries hohenheim's own build stamp (the protoblast
        //    plugin puts it on every source set's runtime classpath), so the version must
        //    name a commit rather than the bare manifest version.
        assertThat(version)
            .as("step 1: the version carries a short sha after '+': %s", version)
            .containsPattern("\\+([0-9a-f]{8}(\\.dirty)?|mixed)$");
    }

    private static String apiStatus(String api) {
        ScriptedDocker docker = new ScriptedDocker();
        docker.apiVersion = api;
        return HostPreflight.run(docker, new EchoNft()).check("api_version").status();
    }

    /** An nft lane that accepts every transaction and reads back the table it was asked for. */
    private static final class EchoNft implements NftRunner {
        @Override
        public NftRunner.Result run(List<String> nftArgs, String stdin) {
            String stdout = nftArgs.size() >= 4 && "list".equals(nftArgs.get(0))
                ? "table inet " + nftArgs.get(3) + " {\n}\n" : "";
            return new NftRunner.Result(0, stdout, "");
        }
    }

    /**
     * A daemon answering exactly the calls the battery makes; nothing reaches a transport.
     *
     * AIDEV-NOTE: the kernel reads are scripted to what a hardened container on a healthy
     * cgroup v2 host prints, with pids.max echoing the configured cap, so every
     * kernel-truth check passes whenever {@code execExit} is zero.
     */
    private static final class ScriptedDocker extends DockerClient {

        int execExit = 0;
        String apiVersion = "1.47";

        ScriptedDocker() {
            super(new RefusingTransport());
        }

        @Override
        public Map<String, Object> version() {
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("Version", "27.0.0");
            version.put("ApiVersion", this.apiVersion);
            version.put("KernelVersion", "6.9.0");
            return version;
        }

        @Override
        public Map<String, Object> info() {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("OperatingSystem", "scripted");
            info.put("OSType", "linux");
            info.put("NCPU", 4);
            info.put("MemTotal", 8L * 1024 * 1024 * 1024);
            return info;
        }

        @Override
        public void ensureImage(String image, String tag) {
        }

        @Override
        public String createContainer(String name, Map<String, Object> spec,
                                      ContainerHardening.Profile profile) {
            return name;
        }

        @Override
        public void startContainer(String id) {
        }

        @Override
        public ExecResult exec(String containerId, List<String> command) {
            if (this.execExit != 0) {
                return new ExecResult(this.execExit, "", "exec refused");
            }
            return new ExecResult(0, "cpuset cpu io memory pids\n---\n"
                + ContainerHardening.pidsLimit() + "\n---\nSeccomp:\t2\nNoNewPrivs:\t1\n---\n"
                + "0 100000 65536\n---\ndocker-default (enforce)\n", "");
        }

        @Override
        public void removeContainer(String id, boolean force) {
        }

        @Override
        public List<Object> listNetworks() {
            return new ArrayList<>();
        }

        @Override
        public String createNetwork(String name, Map<String, String> labels, String subnet,
                                    String gateway, boolean enableIpv6) {
            return name;
        }

        @Override
        public void removeNetwork(String idOrName) {
        }
    }

    /** Every call the scripted client does not override fails loudly. */
    private static final class RefusingTransport implements DockerTransport {
        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs) throws IOException {
            throw new IOException("scripted daemon: unexpected call");
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes)
                throws IOException {
            throw new IOException("scripted daemon: unexpected call");
        }
    }
}
