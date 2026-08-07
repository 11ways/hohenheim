package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.ControllerScope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verdict logic behind the shared-object reaper, and above all its REFUSALS.
 *
 * A reaper test that only proves it removes things is half a test and the wrong half:
 * removing a live controller's isolation ACL strips the boundary every one of that
 * controller's workloads references. So the journey below walks one daemon listing through
 * every shape a real host carries and asserts the verdict on each -- with the departed
 * controller present throughout as the POSITIVE ANCHOR, without which "refuses everything"
 * would pass every refusal assertion.
 */
class IncusReaperTest {

    private static final String OURS = "ourtoken";
    private static final String QUIET = "quiettok";
    private static final String GONE = "gonetok1";
    private static final String BUSY = "busytok1";
    private static final Duration GRACE = Duration.ofHours(168);

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");
    /** Well inside the grace: a controller that has not deployed anything in an hour. */
    private static final Instant RECENT = NOW.minus(Duration.ofHours(1));
    /** Inside the grace but only just: idle for six days is still ALIVE. */
    private static final Instant IDLE_SIX_DAYS = NOW.minus(Duration.ofHours(144));
    /** Past the grace: nothing has refreshed this stamp for eight days. */
    private static final Instant EXPIRED = NOW.minus(Duration.ofHours(192));

    /**
     * One daemon listing carrying every shape at once, judged in a single plan.
     *
     * The dangerous direction is steps 2 and 3: a controller that is alive but has NOT
     * deployed anything for days looks exactly like a departed one at the daemon -- an
     * unreferenced ACL and an unreferenced bridge -- and the ONLY thing that separates
     * them is the presence stamp.
     */
    @Test
    void onlyAStampedExpiredUnreferencedControllerIsEverReaped() {
        List<Map<String, Object>> acls = new ArrayList<>();
        List<Map<String, Object>> networks = new ArrayList<>();

        // Ours.
        acls.add(acl(ControllerScope.PREFIX + "-" + OURS + "-isolation", List.of()));
        acls.add(presence(OURS, RECENT));
        // A quiet-but-alive peer: nothing references its objects, but it stamped recently.
        acls.add(acl(ControllerScope.PREFIX + "-" + QUIET + "-isolation", List.of()));
        acls.add(presence(QUIET, IDLE_SIX_DAYS));
        networks.add(network("hhx-" + QUIET, List.of()));
        // A busy peer whose stamp EXPIRED but whose ACL a workload still references.
        acls.add(acl(ControllerScope.PREFIX + "-" + BUSY + "-isolation",
            List.of("/1.0/instances/hohenheim-" + BUSY + "-instance-7")));
        acls.add(presence(BUSY, EXPIRED));
        // A genuinely departed peer: stamped, expired, referenced by nothing.
        acls.add(acl(ControllerScope.PREFIX + "-" + GONE + "-isolation", List.of()));
        acls.add(presence(GONE, EXPIRED));
        networks.add(network("hhx-" + GONE, List.of()));
        // A pre-namespace object, attributable to no controller at all.
        acls.add(acl(ControllerScope.PREFIX + "-isolation", List.of()));
        // A peer that never stamped: unknown, not departed.
        acls.add(acl(ControllerScope.PREFIX + "-nostamp1-isolation", List.of()));
        // Things this authority must not even look at.
        acls.add(acl(ControllerScope.PREFIX + "-" + GONE + "-instance-3", List.of()));
        networks.add(network("incusbr0", List.of("/1.0/profiles/default")));
        networks.add(network("docker0", List.of()));

        List<IncusReaper.Candidate> plan =
            IncusReaper.plan(acls, networks, OURS, NOW, GRACE);

        // 1. THE POSITIVE ANCHOR: a departed controller's ACL, bridge and its own presence
        //    marker are all reapable, or every refusal below would be vacuous.
        assertThat(reapableNames(plan))
            .as("step 1: exactly the departed controller's three shared objects are reapable")
            .containsExactlyInAnyOrder(
                ControllerScope.PREFIX + "-" + GONE + "-isolation",
                ControllerScope.PREFIX + "-" + GONE + "-presence",
                "hhx-" + GONE);

        // 2. THE DANGEROUS DIRECTION: a quiet-but-alive controller is refused although its
        //    objects are as unreferenced as the departed one's.
        assertThat(verdictOf(plan, ControllerScope.PREFIX + "-" + QUIET + "-isolation"))
            .as("step 2: a peer idle for six days is LIVE, not departed -- its stamp says so")
            .isEqualTo(IncusReaper.Verdict.LIVE);
        assertThat(verdictOf(plan, "hhx-" + QUIET))
            .as("step 2: the same peer's extra-NIC bridge is refused for the same reason")
            .isEqualTo(IncusReaper.Verdict.LIVE);

        // 3. An expired stamp is NOT enough on its own: a referenced object is refused,
        //    which is the structural guard the daemon itself also enforces.
        assertThat(verdictOf(plan, ControllerScope.PREFIX + "-" + BUSY + "-isolation"))
            .as("step 3: an expired peer whose ACL a workload references is IN_USE")
            .isEqualTo(IncusReaper.Verdict.IN_USE);
        // ...and its presence marker survives WITH it: reaping the stamp would leave the
        // surviving ACL unattributable to any controller for ever after.
        assertThat(verdictOf(plan, ControllerScope.PREFIX + "-" + BUSY + "-presence"))
            .as("step 3: the evidence outlives the objects it judges")
            .isEqualTo(IncusReaper.Verdict.EVIDENCE);

        // 4. No stamp is never departure: absent evidence is not evidence of absence.
        assertThat(verdictOf(plan, ControllerScope.PREFIX + "-nostamp1-isolation"))
            .as("step 4: a peer that never stamped is UNSTAMPED, whatever its age")
            .isEqualTo(IncusReaper.Verdict.UNSTAMPED);
        assertThat(verdictOf(plan, ControllerScope.PREFIX + "-isolation"))
            .as("step 4: a pre-namespace name belongs to no controller and is never reaped")
            .isEqualTo(IncusReaper.Verdict.UNSTAMPED);

        // 5. Our own objects are named but never touched.
        assertThat(verdictOf(plan, ControllerScope.PREFIX + "-" + OURS + "-isolation"))
            .as("step 5: this controller's own isolation ACL is OURS")
            .isEqualTo(IncusReaper.Verdict.OURS);

        // 6. Out of scope entirely: a RECORD-scoped name has an owning record to answer
        //    for it, and a foreign network is not ours to classify.
        assertThat(namesIn(plan))
            .as("step 6: record-scoped and unrelated objects are not this authority's subject")
            .doesNotContain(ControllerScope.PREFIX + "-" + GONE + "-instance-3",
                "incusbr0", "docker0");
    }

    /**
     * The evidence rules that decide freshness, at their boundaries -- these are what make
     * step 2 above hold for a real daemon rather than for a tidy fixture.
     */
    @Test
    void unreadableEvidenceAlwaysFallsToTheRefusingSide() {
        // 1. A stamp that is not a parseable instant yields NO stamp, so its controller
        //    lands in the never-reaped bucket instead of the reapable one.
        Map<String, Object> corrupt = presence(GONE, EXPIRED);
        castConfig(corrupt).put(ControllerPresence.CONFIG_SEEN, "last tuesday");
        assertThat(ControllerPresence.readStamps(List.of(corrupt)))
            .as("step 1: an unparseable seen stamp is absent evidence, not stale evidence")
            .doesNotContainKey(GONE);

        List<IncusReaper.Candidate> plan = IncusReaper.plan(
            List.of(acl(ControllerScope.PREFIX + "-" + GONE + "-isolation", List.of()), corrupt),
            List.of(), OURS, NOW, GRACE);
        assertThat(reapableNames(plan))
            .as("step 1: nothing is reapable once the only stamp became unreadable")
            .isEmpty();

        // 2. An unreadable used_by reads as REFERENCED. A guard that fails open is not a
        //    guard, so an object whose references cannot be read is never removed.
        Map<String, Object> opaque = acl(ControllerScope.PREFIX + "-" + GONE + "-isolation",
            List.of());
        opaque.remove("used_by");
        assertThat(ControllerPresence.usedBy(opaque))
            .as("step 2: an absent used_by field reads as referenced, never as free")
            .isNotEmpty();
        List<IncusReaper.Candidate> opaquePlan = IncusReaper.plan(
            List.of(opaque, presence(GONE, EXPIRED)), List.of(), OURS, NOW, GRACE);
        assertThat(verdictOf(opaquePlan, opaque.get("name").toString()))
            .as("step 2: an object whose references cannot be read is refused as IN_USE")
            .isEqualTo(IncusReaper.Verdict.IN_USE);
        assertThat(reapableNames(opaquePlan))
            .as("step 2: and its controller's stamp is kept as the evidence judging it")
            .isEmpty();

        // 3. A stamp dated in the FUTURE is not evidence of life either: our own refresh
        //    rewrites it rather than trusting a clock we do not own.
        assertThat(ControllerPresence.needsRefresh(NOW.plus(Duration.ofDays(2)), NOW))
            .as("step 3: a future-dated stamp is rewritten, never trusted as fresh")
            .isTrue();
        assertThat(ControllerPresence.needsRefresh(NOW.minus(Duration.ofMinutes(1)), NOW))
            .as("step 3: a stamp inside the refresh window is left alone")
            .isFalse();
    }

    // -- fixtures -------------------------------------------------------------

    private static Map<String, Object> acl(String name, List<String> usedBy) {
        Map<String, Object> acl = new LinkedHashMap<>();
        acl.put("name", name);
        acl.put("config", new LinkedHashMap<String, Object>());
        acl.put("used_by", usedBy);
        return acl;
    }

    private static Map<String, Object> presence(String token, Instant seen) {
        Map<String, Object> acl = acl(
            ControllerScope.PREFIX + "-" + token + "-" + ControllerPresence.SUFFIX, List.of());
        castConfig(acl).put(ControllerPresence.CONFIG_CONTROLLER, token);
        castConfig(acl).put(ControllerPresence.CONFIG_SEEN, seen.toString());
        return acl;
    }

    private static Map<String, Object> network(String name, List<String> usedBy) {
        Map<String, Object> network = new LinkedHashMap<>();
        network.put("name", name);
        network.put("used_by", usedBy);
        return network;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castConfig(Map<String, Object> acl) {
        return (Map<String, Object>) acl.get("config");
    }

    private static List<String> reapableNames(List<IncusReaper.Candidate> plan) {
        return plan.stream().filter(IncusReaper.Candidate::reapable)
            .map(IncusReaper.Candidate::name).toList();
    }

    private static List<String> namesIn(List<IncusReaper.Candidate> plan) {
        return plan.stream().map(IncusReaper.Candidate::name).toList();
    }

    private static IncusReaper.Verdict verdictOf(List<IncusReaper.Candidate> plan, String name) {
        return plan.stream().filter(candidate -> candidate.name().equals(name))
            .map(IncusReaper.Candidate::verdict).findFirst()
            .orElseThrow(() -> new AssertionError("no candidate named '" + name + "' in the plan"));
    }
}
