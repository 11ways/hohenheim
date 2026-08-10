package be.elevenways.hohenheim.test.network;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.incus.ControllerPresence;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.incus.IncusReaper;
import be.elevenways.hohenheim.server.task.ReapIncusControllers;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared-object reaper proven against the REAL daemon, in the direction that matters:
 * a controller that is alive but has deployed nothing for days keeps its isolation ACL and
 * its extra-NIC bridge, while a genuinely departed one loses both -- and the reaper is
 * asked to judge both in the SAME sweep, so "refuses everything" cannot pass.
 *
 * AIDEV-NOTE: opt-in exactly like every other live host test -- absent
 * {@code ~/.config/hohenheim-livehost/incus.properties} it SKIPS green. Every object it
 * touches is minted under tokens generated FOR THIS RUN, so it never asserts a daemon-wide
 * count and cannot see (or be seen by) another live class sharing the host under four
 * parallel forks. The daemon's own listing is the assertion surface: "the API returned
 * success" and "the object is gone" are independent facts here.
 */
class IncusReaperLiveTest {

    private static final String HOST = "live-incus-reap";
    private static final Duration GRACE = Duration.ofHours(168);

    private static SqliteDatasource datasource;
    private static LiveIncusHost remote;
    private static String enrolledFingerprint;
    private static IncusClient incus;

    /** A departed peer and a quiet-but-alive peer, both invented for this run only. */
    private static final String GONE = token();
    private static final String QUIET = token();

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.configured();
        LiveLane.require(LiveLane.Need.INCUS_HOST, remote != null,
            "no live incus host enrolled at " + LiveIncusHost.CONFIG);

        File db = File.createTempFile("hohenheim-incus-reap-live", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per class: the controller identity every name is scoped by resolves
        // through the CURRENT datasource (IncusNetworkIsolationLiveTest's rule verbatim).
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();

        inScope(() -> {
            enrolledFingerprint = remote.enrollThroughProduct(HOST, "hohenheim-live-reap");
            incus = new ServerService().incusClientFor(HOST);
        });
    }

    @AfterAll
    static void tearDown() {
        if (incus != null) {
            // Whatever the assertions did, this host goes back to how it was found.
            for (String name : List.of(
                    aclName(GONE), presenceName(GONE), aclName(QUIET), presenceName(QUIET))) {
                deleteAclQuietly(name);
            }
            for (String name : List.of(bridgeName(GONE), bridgeName(QUIET))) {
                deleteNetworkQuietly(name);
            }
            if (datasource != null) {
                inScope(() -> deleteAclQuietly(ControllerPresence.aclName()));
            }
        }
        if (remote != null) {
            System.out.println("=== cleanup: shared objects -> "
                + remote.releaseControllerSharedObjects());
            System.out.println("=== cleanup: authorized_keys -> " + remote.releaseAuthorizedKeys());
        }
        if (remote != null && enrolledFingerprint != null) {
            try {
                remote.removeTrustEntry(enrolledFingerprint);
            } catch (IOException ignored) {
                // nothing enrolled, nothing to remove
            }
        }
    }

    /**
     * Both peers judged in one sweep at the real daemon: the departed one is reaped, the
     * quiet-but-alive one is refused, and the pre-namespace object nobody can attribute is
     * left alone.
     */
    @Test
    void reapsADepartedControllerAndRefusesAQuietOne() {
        inScope(() -> {
            // 1. Both peers exist on the daemon with IDENTICAL daemon-side shape: an
            //    isolation ACL and an extra-NIC bridge, neither referenced by anything.
            //    The ONLY difference is what their presence stamps say.
            createAcl(aclName(GONE));
            createAcl(aclName(QUIET));
            createBridge(bridgeName(GONE));
            createBridge(bridgeName(QUIET));
            stampAs(GONE, Instant.now().minus(Duration.ofDays(30)));
            stampAs(QUIET, Instant.now());

            assertThat(aclExists(aclName(GONE)) && aclExists(aclName(QUIET))
                    && networkExists(bridgeName(GONE)) && networkExists(bridgeName(QUIET)))
                .as("step 1: both peers' shared objects are present at the daemon to begin with")
                .isTrue();

            // 2. Our own presence stamp is written first -- a controller that judges
            //    without announcing itself is judging on evidence it did not produce.
            ControllerPresence.stamp(incus);
            assertThat(ControllerPresence.readStamps(incus.networkAcls()))
                .as("step 2: this controller's own stamp is readable on the daemon")
                .containsKey(ControllerIdentity.token());

            // 3. The plan judges the whole daemon at once. The two peers are
            //    indistinguishable except by stamp, and they land on opposite verdicts.
            List<IncusReaper.Candidate> plan = IncusReaper.plan(
                incus.networkAcls(), incus.networks(), ControllerIdentity.token(),
                Instant.now(), GRACE);
            assertThat(verdictOf(plan, aclName(GONE)))
                .as("step 3: the peer whose stamp expired 30 days ago is DEPARTED")
                .isEqualTo(IncusReaper.Verdict.DEPARTED);
            assertThat(verdictOf(plan, aclName(QUIET)))
                .as("step 3: the peer with an equally unreferenced ACL but a fresh stamp is LIVE")
                .isEqualTo(IncusReaper.Verdict.LIVE);
            assertThat(verdictOf(plan, bridgeName(QUIET)))
                .as("step 3: and its extra-NIC bridge is refused for the same reason")
                .isEqualTo(IncusReaper.Verdict.LIVE);

            // 4. The reap runs, and the DAEMON is asked what happened -- not our own report.
            IncusReaper.Reaped reaped = IncusReaper.reap(incus, plan, false);
            assertThat(aclExists(aclName(GONE)))
                .as("step 4: the departed peer's isolation ACL is gone FROM THE DAEMON")
                .isFalse();
            assertThat(networkExists(bridgeName(GONE)))
                .as("step 4: and so is its extra-NIC bridge (%s)", reaped.refused())
                .isFalse();
            assertThat(aclExists(presenceName(GONE)))
                .as("step 4: its presence marker goes last, with the objects it judged")
                .isFalse();

            // 5. THE REFUSAL, read back off the daemon: the quiet peer survived intact.
            assertThat(aclExists(aclName(QUIET)))
                .as("step 5: a live-but-quiet controller KEEPS the ACL its workloads would need")
                .isTrue();
            assertThat(networkExists(bridgeName(QUIET)))
                .as("step 5: and keeps its extra-NIC bridge")
                .isTrue();
            assertThat(aclExists(presenceName(QUIET)))
                .as("step 5: and keeps the stamp that says so")
                .isTrue();

            // 6. Our own objects are never candidates for our own reap.
            assertThat(aclExists(ControllerPresence.aclName()))
                .as("step 6: this controller's own presence marker survives its own sweep")
                .isTrue();

            // 7. This sweep IS the Incus host heartbeat: the POSITIVE anchor for it, which
            //    only a real daemon can give. The stamp round trip proves the host answered,
            //    so the production task records last_seen_at on the host record -- an Incus
            //    host had no positive liveness signal at all before this.
            Row before = Models.get(ServerModel.class).findByName(HOST);
            before.set(ServerModel.LAST_SEEN_AT, (Instant) null);
            before.set(ServerModel.LAST_ERROR_KIND, "unreachable");
            before.set(ServerModel.LAST_ERROR, "a stale verdict from an earlier probe");
            Models.get(ServerModel.class).save(before);

            ReapIncusControllers.HostOutcome outcome =
                ReapIncusControllers.sweepHost(Models.get(ServerModel.class).findByName(HOST));
            assertThat(outcome.reachable())
                .as("step 7: the real daemon answered, so this is the success lane")
                .isTrue();
            Row seen = Models.get(ServerModel.class).findByName(HOST);
            assertThat((Object) seen.get(ServerModel.LAST_SEEN_AT))
                .as("step 7: a reachable incus host is STAMPED as seen -- the heartbeat")
                .isNotNull();
            assertThat((String) seen.get(ServerModel.LAST_ERROR_KIND))
                .as("step 7: and the stale failure verdict is cleared by the contact")
                .isNull();
        });
    }

    /**
     * The OPERATOR lane, which the scheduled sweep deliberately cannot reach: an object
     * whose controller never stamped at all is refused by the sweep and removed by the
     * explicit action -- and the live-but-quiet peer survives BOTH, because widening what
     * counts as abandoned must never widen what counts as gone.
     */
    @Test
    void theOperatorLaneTakesUnstampedObjectsButStillRefusesALiveController() {
        inScope(() -> {
            // Its own peers, so this method neither depends on nor disturbs another's.
            String never = token();
            String alive = token();
            createAcl(aclName(never));
            createAcl(aclName(alive));
            stampAs(alive, Instant.now());
            try {
                // 1. The automatic classification refuses the unstamped object: nothing on
                //    this daemon can prove its controller is gone rather than merely old.
                List<IncusReaper.Candidate> plan = IncusReaper.plan(
                    incus.networkAcls(), incus.networks(), ControllerIdentity.token(),
                    Instant.now(), GRACE);
                assertThat(verdictOf(plan, aclName(never)))
                    .as("step 1: a controller that never stamped is UNSTAMPED, not departed")
                    .isEqualTo(IncusReaper.Verdict.UNSTAMPED);
                assertThat(IncusReaper.reap(incus, plan, false).removed())
                    .as("step 1: and the automatic sweep removes nothing here")
                    .isEmpty();
                assertThat(aclExists(aclName(never)))
                    .as("step 1: the unstamped object is still at the daemon after the sweep")
                    .isTrue();

                // 2. The operator lane widens the SAME reap to unstamped objects, and the
                //    object goes -- proven at the daemon, not by the return value.
                //
                //    AIDEV-NOTE: the plan is narrowed to THIS METHOD'S OWN peers before it
                //    is widened. ReapIncusControllers.reapIncludingUnstamped is host-wide
                //    by design (an operator names one host and means all of it), and
                //    calling it here would delete the unreferenced isolation ACL of every
                //    OTHER live test class sharing this daemon under four parallel forks.
                //    It emptied daystrom of 72 ACLs and 9 bridges the one time it ran.
                List<IncusReaper.Candidate> ours = plan.stream()
                    .filter(candidate -> candidate.name().contains(never)
                        || candidate.name().contains(alive))
                    .toList();
                IncusReaper.Reaped reaped = IncusReaper.reap(incus, ours, true);
                assertThat(aclExists(aclName(never)))
                    .as("step 2: the operator lane removed it FROM THE DAEMON (%s)",
                        reaped.refused())
                    .isFalse();

                // 3. THE REFUSAL THAT MUST SURVIVE THE WIDENING: the live peer is untouched
                //    even by the operator lane, because its stamp still says it is alive.
                assertThat(aclExists(aclName(alive)))
                    .as("step 3: a stamped, live controller is refused by the operator lane too")
                    .isTrue();
            } finally {
                deleteAclQuietly(aclName(never));
                deleteAclQuietly(aclName(alive));
                deleteAclQuietly(presenceName(alive));
            }
        });
    }

    // -- scoping ---------------------------------------------------------------

    /** A body that may talk to a daemon. */
    private interface DaemonWork {
        void run() throws Exception;
    }

    /**
     * Run inside this class's datasource scope.
     *
     * AIDEV-NOTE: an AssertionError is an Error, so it passes through untouched -- the
     * wrapping is only for the checked IOException every daemon call declares, and it must
     * never be allowed to turn a failed assertion into something else.
     */
    private static void inScope(DaemonWork body) {
        Db.run(datasource, () -> {
            try {
                body.run();
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });
    }

    // -- daemon helpers (every one of them reads or writes the real host) ------

    private static void createAcl(String name) throws IOException {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name);
        definition.put("description", "hohenheim reaper live test fixture");
        definition.put("egress", List.of());
        definition.put("ingress", List.of());
        definition.put("config", Map.of());
        incus.createNetworkAcl(definition);
    }

    /** A presence marker for an INVENTED peer, stamped at an instant we choose. */
    private static void stampAs(String peer, Instant seen) throws IOException {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", presenceName(peer));
        definition.put("description", "hohenheim reaper live test fixture");
        definition.put("egress", List.of());
        definition.put("ingress", List.of());
        definition.put("config", Map.of(
            ControllerPresence.CONFIG_CONTROLLER, peer,
            ControllerPresence.CONFIG_SEEN, seen.toString()));
        incus.createNetworkAcl(definition);
    }

    private static void createBridge(String name) throws IOException {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name);
        definition.put("type", "bridge");
        definition.put("description", "hohenheim reaper live test fixture");
        definition.put("config", Map.of());
        incus.createNetwork(definition);
    }

    private static boolean aclExists(String name) throws IOException {
        return incus.networkAcl(name) != null;
    }

    private static boolean networkExists(String name) throws IOException {
        return incus.network(name) != null;
    }

    private static void deleteAclQuietly(String name) {
        try {
            incus.deleteNetworkAcl(name);
        } catch (IOException ignored) {
            // already gone, or never created
        }
    }

    private static void deleteNetworkQuietly(String name) {
        try {
            incus.deleteNetwork(name);
        } catch (IOException ignored) {
            // already gone, or never created
        }
    }

    // -- naming ---------------------------------------------------------------

    private static String aclName(String peer) {
        return ControllerScope.PREFIX + "-" + peer + "-isolation";
    }

    private static String presenceName(String peer) {
        return ControllerScope.PREFIX + "-" + peer + "-" + ControllerPresence.SUFFIX;
    }

    private static String bridgeName(String peer) {
        return IncusNetworkPolicy.EXTRA_NETWORK_PREFIX + "-" + peer;
    }

    /** A token-shaped identity for an invented peer; unique per run, never a fixed name. */
    private static String token() {
        SecureRandom random = new SecureRandom();
        StringBuilder token = new StringBuilder("z");
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        while (token.length() < ControllerIdentity.TOKEN_LENGTH) {
            token.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return token.toString();
    }

    private static IncusReaper.Verdict verdictOf(List<IncusReaper.Candidate> plan, String name) {
        return plan.stream().filter(candidate -> candidate.name().equals(name))
            .map(IncusReaper.Candidate::verdict).findFirst()
            .orElseThrow(() -> new AssertionError("no candidate named '" + name + "' in the plan"));
    }
}
