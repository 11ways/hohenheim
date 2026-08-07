package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.ControllerScope;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE authority over per-controller SHARED objects left behind on a shared Incus daemon:
 * the isolation ACL, the {@code hhx-<token>} extra-NIC bridge and the presence marker
 * itself. One object per controller per daemon, so nothing record-scoped ever reclaims
 * them -- and with ephemeral controllers (every live test suite mints a fresh identity)
 * they accumulate without bound. Measured on daystrom 2026-08-07 before this shipped: 72
 * ACLs and 10 bridges, hand-cleaned twice.
 *
 * AIDEV-NOTE: this is a NEW authority and deliberately not an extension of
 * {@code DockerReconciler}. Three reasons, all load-bearing. (1) That reconciler classifies
 * DOCKER resources on DOCKER daemons and does not address an Incus host at all -- by its
 * own docblock, a host it cannot address is not a host it may report on. (2) Its
 * report-only property was a deliberate safety decision and changing it as a side effect
 * of adding a different tier's cleanup would be exactly the kind of quiet authority
 * widening it exists to prevent. (3) Its subject is a resource owned by a RECORD, judged
 * against our own database; the subject here is an object owned by another CONTROLLER,
 * whose database we cannot read -- a different question needing different evidence. What
 * this class does copy from that tier is the {@code OrphanActions} discipline: classify
 * freely, remove narrowly, and RE-VERIFY live truth at the daemon before every removal,
 * because acting on a stale report is the reports-success defect with a delete button.
 *
 * THE SAFETY ARGUMENT, which is the point of the whole class. Reaping a LIVE controller's
 * isolation ACL is catastrophic -- it is the object every one of that controller's
 * workloads references for tenant isolation. Three independent facts must ALL agree before
 * anything is removed, and each one alone would be enough to refuse:
 *
 * 1. The object carries ANOTHER controller's token. Ours is never a candidate.
 * 2. Its {@code used_by} is EMPTY, on a read taken at removal time and not from the plan.
 *    This is structural, not advisory: the daemon itself refuses the delete otherwise
 *    ("Cannot delete an ACL that is in use", verified against Incus 7.3.0), so a live
 *    controller with workloads cannot lose its ACL even to a buggy reaper.
 * 3. Its controller left a {@link ControllerPresence} stamp AND that stamp is older than
 *    the grace period. NO STAMP IS NEVER REAPED -- absent evidence is not evidence of
 *    absence, which is what protects a controller running a build older than this
 *    mechanism, and what leaves the pre-namespace {@code hohenheim-isolation} alone
 *    forever (it is attributable to no controller at all).
 *
 * Fact 3 is what covers the case fact 2 cannot: a controller that is alive but IDLE, with
 * no workloads on this daemon for days. Its ACL is unreferenced, so only its stamp
 * distinguishes it from a departed one -- and the stamp is refreshed by the sweep itself,
 * every 30 minutes against a 7-day default grace.
 *
 * The bounded blast radius of being wrong, stated honestly: an object reaped in error is
 * one no workload referenced, and {@code ensureIsolationAcl}/{@code ensureExtraNetwork}
 * recreate it on that controller's next deploy. What CANNOT happen is a running workload
 * losing its isolation.
 */
public final class IncusReaper {

    /** What a shared object is, in the vocabulary the daemon addresses it by. */
    public enum Kind {
        /** {@code /1.0/network-acls/...}: the isolation ACL and the presence marker. */
        NETWORK_ACL,
        /** {@code /1.0/networks/...}: the {@code hhx-<token>} extra-NIC bridge. */
        NETWORK
    }

    /** The verdict on one shared object. Exactly one of these is reapable. */
    public enum Verdict {
        /** Minted by THIS controller. */
        OURS,
        /** Something on the daemon references it; the daemon would refuse the delete too. */
        IN_USE,
        /** Its controller stamped a presence marker inside the grace period. */
        LIVE,
        /** No presence stamp exists for its token -- or it has no token at all. */
        UNSTAMPED,
        /**
         * A presence marker whose controller still has objects here that this sweep is
         * NOT removing -- kept because it is the evidence those objects are judged by.
         */
        EVIDENCE,
        /** Stamped, expired and referenced by nothing: the ONE reapable verdict. */
        DEPARTED
    }

    /** One classified shared object; {@code token} is empty for an unattributable name. */
    public record Candidate(@NonNull Kind kind, @NonNull String name, @NonNull String token,
                            @NonNull Verdict verdict, @NonNull String reason) {

        /** @return whether an automatic sweep may remove this */
        public boolean reapable() {
            return this.verdict == Verdict.DEPARTED;
        }
    }

    /** What a reap did: what it removed, and what it refused on the re-read. */
    public record Reaped(@NonNull List<String> removed, @NonNull List<String> refused) {}

    private IncusReaper() {
    }

    // -- classification (pure) ------------------------------------------------

    /**
     * Classify every per-controller shared object on one daemon.
     *
     * @param acls     the daemon's recursed network-ACL listing
     * @param networks the daemon's recursed network listing
     * @param ourToken this controller's identity token
     * @param grace    how long a controller may go unstamped before it counts as departed
     */
    public static @NonNull List<Candidate> plan(@NonNull List<Map<String, Object>> acls,
                                                @NonNull List<Map<String, Object>> networks,
                                                @NonNull String ourToken,
                                                @NonNull Instant now,
                                                @NonNull Duration grace) {
        Map<String, Instant> stamps = ControllerPresence.readStamps(acls);
        List<Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> acl : acls) {
            Candidate candidate = classifyAcl(acl, ourToken, stamps, now, grace);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        for (Map<String, Object> network : networks) {
            Candidate candidate = classifyNetwork(network, ourToken, stamps, now, grace);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return List.copyOf(keepEvidenceAlive(candidates));
    }

    /**
     * Demote a presence marker that is still doing its job.
     *
     * AIDEV-NOTE: found by its own test. A controller whose stamp expired while a workload
     * still references its isolation ACL is refused on that ACL (IN_USE) -- but its
     * presence marker is unreferenced and expired, so the naive rule reaped THE VERY
     * EVIDENCE the surviving object was judged by. The next sweep would then find that
     * object unattributable forever. The marker is therefore the LAST thing to go: it is
     * reapable only once every other shared object of the same controller is going with
     * it.
     */
    private static List<Candidate> keepEvidenceAlive(List<Candidate> candidates) {
        List<Candidate> settled = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (candidate.verdict() != Verdict.DEPARTED
                    || !isPresenceMarker(candidate)
                    || !hasSurvivingSibling(candidates, candidate)) {
                settled.add(candidate);
                continue;
            }
            settled.add(new Candidate(candidate.kind(), candidate.name(), candidate.token(),
                Verdict.EVIDENCE, "controller '" + candidate.token() + "' still has shared"
                    + " objects on this daemon that are not being removed; its stamp is what"
                    + " judges them and must outlive them"));
        }
        return settled;
    }

    private static boolean isPresenceMarker(Candidate candidate) {
        ControllerScope.SharedScoped scoped = ControllerScope.parseShared(candidate.name());
        return scoped != null && ControllerPresence.isPresenceSuffix(scoped.suffix());
    }

    private static boolean hasSurvivingSibling(List<Candidate> candidates, Candidate marker) {
        for (Candidate other : candidates) {
            if (other != marker && other.token().equals(marker.token())
                    && other.verdict() != Verdict.DEPARTED) {
                return true;
            }
        }
        return false;
    }

    /**
     * A hohenheim-prefixed ACL, or null when the name is nobody's business of ours.
     *
     * AIDEV-NOTE: a hohenheim-prefixed ACL that parses as the RECORD-scoped shape
     * ({@code hohenheim-<token>-<kind>-<id>}) is deliberately NOT a candidate here. Only
     * shared objects belong to this authority; a record-scoped leftover is a different
     * question with a different owner, and answering it from a token alone would let this
     * class delete something a record still points at.
     */
    private static @Nullable Candidate classifyAcl(Map<String, Object> acl, String ourToken,
                                                   Map<String, Instant> stamps, Instant now,
                                                   Duration grace) {
        if (!(acl.get("name") instanceof String name)
                || !name.startsWith(ControllerScope.PREFIX + "-")) {
            return null;
        }
        if (ControllerScope.parse(name) != null) {
            return null;   // record-scoped, not a shared object
        }
        ControllerScope.SharedScoped scoped = ControllerScope.parseShared(name);
        if (scoped == null) {
            return new Candidate(Kind.NETWORK_ACL, name, "", Verdict.UNSTAMPED,
                "hohenheim-prefixed but carries no controller token (a pre-namespace"
                    + " object, or a stranger's): attributable to NO controller, so no"
                    + " evidence of departure can ever exist for it");
        }
        return judge(Kind.NETWORK_ACL, name, scoped.token(), acl, ourToken, stamps, now, grace);
    }

    /** A {@code hhx-<token>} bridge, or null for any other network. */
    private static @Nullable Candidate classifyNetwork(Map<String, Object> network,
                                                       String ourToken,
                                                       Map<String, Instant> stamps, Instant now,
                                                       Duration grace) {
        if (!(network.get("name") instanceof String name)) {
            return null;
        }
        ControllerScope.SharedScoped scoped = ControllerScope.parseShortScoped(
            IncusNetworkPolicy.EXTRA_NETWORK_PREFIX, name);
        if (scoped == null) {
            return null;
        }
        return judge(Kind.NETWORK, name, scoped.token(), network, ourToken, stamps, now, grace);
    }

    /** The three-fact verdict, in refusal-first order. */
    private static Candidate judge(Kind kind, String name, String token,
                                   Map<String, Object> object, String ourToken,
                                   Map<String, Instant> stamps, Instant now, Duration grace) {
        if (token.equals(ourToken)) {
            return new Candidate(kind, name, token, Verdict.OURS, "minted by this controller");
        }
        List<?> usedBy = ControllerPresence.usedBy(object);
        if (!usedBy.isEmpty()) {
            return new Candidate(kind, name, token, Verdict.IN_USE,
                "referenced by " + usedBy);
        }
        Instant seen = stamps.get(token);
        if (seen == null) {
            return new Candidate(kind, name, token, Verdict.UNSTAMPED,
                "controller '" + token + "' left no readable presence stamp on this daemon,"
                    + " so nothing here distinguishes gone from quiet");
        }
        if (!seen.isBefore(now.minus(grace))) {
            return new Candidate(kind, name, token, Verdict.LIVE,
                "controller '" + token + "' was alive here at " + seen);
        }
        return new Candidate(kind, name, token, Verdict.DEPARTED,
            "controller '" + token + "' last seen " + seen + ", older than the "
                + grace.toHours() + "h grace, and nothing references this object");
    }

    // -- removal --------------------------------------------------------------

    /**
     * Remove the reapable objects of a plan, RE-READING each one at the daemon first.
     *
     * @param unstamped whether {@link Verdict#UNSTAMPED} objects are removed too -- never
     *                  true on an automatic sweep, only on a deliberate operator action
     *                  over a daemon whose pre-namespace leftovers a human has looked at
     * @return what was removed and what the re-read refused; a refusal is never a throw,
     *         because one contended object must not abandon the rest of the sweep
     */
    public static @NonNull Reaped reap(@NonNull IncusClient incus,
                                       @NonNull List<Candidate> plan,
                                       boolean unstamped) {
        List<String> removed = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (Candidate candidate : plan) {
            if (!candidate.reapable()
                    && !(unstamped && candidate.verdict() == Verdict.UNSTAMPED)) {
                continue;
            }
            try {
                String refusal = removeVerified(incus, candidate);
                if (refusal == null) {
                    removed.add(candidate.kind() + " " + candidate.name());
                } else {
                    refused.add(candidate.name() + ": " + refusal);
                }
            } catch (IOException failed) {
                refused.add(candidate.name() + ": " + failed.getMessage());
            }
        }
        return new Reaped(List.copyOf(removed), List.copyOf(refused));
    }

    /**
     * Re-read one candidate and delete it when the live object still agrees.
     *
     * @return null when it was removed (or was already gone), else why it was refused
     */
    private static @Nullable String removeVerified(IncusClient incus, Candidate candidate)
            throws IOException {
        Map<String, Object> live = candidate.kind() == Kind.NETWORK_ACL
            ? incus.networkAcl(candidate.name()) : incus.network(candidate.name());
        if (live == null) {
            return null;   // already gone: the plan was stale, the goal is met
        }
        // AIDEV-NOTE: the used_by re-read is the whole guard and it must come from THIS
        // read, never from the plan. A workload can be deployed between the sweep and the
        // removal, and a plan is a report about a moment that has passed.
        List<?> usedBy = ControllerPresence.usedBy(live);
        if (!usedBy.isEmpty()) {
            return "no longer free -- referenced by " + usedBy;
        }
        try {
            if (candidate.kind() == Kind.NETWORK_ACL) {
                incus.deleteNetworkAcl(candidate.name());
            } else {
                incus.deleteNetwork(candidate.name());
            }
        } catch (IncusClient.ApiException e) {
            if (e.isNotFound()) {
                return null;   // raced to gone by someone else
            }
            return e.getMessage();
        }
        return null;
    }

    // -- reporting ------------------------------------------------------------

    /** How many objects each verdict covers, for a one-line sweep report. */
    public static @NonNull Map<Verdict, Integer> tally(@NonNull List<Candidate> plan) {
        Map<Verdict, Integer> counts = new LinkedHashMap<>();
        for (Candidate candidate : plan) {
            counts.merge(candidate.verdict(), 1, Integer::sum);
        }
        return counts;
    }
}
