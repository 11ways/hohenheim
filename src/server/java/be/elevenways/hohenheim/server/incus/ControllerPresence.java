package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE liveness signal a controller leaves on a shared Incus daemon, so that a DIFFERENT
 * controller can tell "that one is gone" from "that one is temporarily quiet" without
 * asking a database it has no access to.
 *
 * AIDEV-NOTE: this is the whole design decision behind {@link IncusReaper}, recorded here
 * because everything else follows from it. Two controllers that share a daemon share
 * NOTHING else -- not a database, not a network path, not a clock authority. So zenit's
 * {@code Leases} is the right SHAPE (owner token, expiry, heartbeat) but the wrong STORE:
 * its store is our own control-plane database, which is precisely the thing the two
 * parties do not share. The only medium both can read and only the owner may write is the
 * daemon itself, so the liveness signal is an object ON the daemon, written by its owner,
 * carrying its owner's token and the instant it was last refreshed. A reaper reads it the
 * same way it reads everything else it judges.
 *
 * AIDEV-NOTE: why a SEPARATE object and not a config key on the isolation ACL. An ACL
 * update makes the daemon re-trigger a device update on EVERY NIC referencing that ACL --
 * that is not an optimisation note, it is the reason
 * {@link IncusNetworkPolicy#ensureIsolationAcl()} writes conditionally at all (a
 * retrigger failed live on an UNRELATED instance). Heartbeating into the isolation ACL
 * would re-couple every heartbeat to the health of every workload on the daemon, on a
 * timer. The presence marker is therefore its own rule-less ACL that NOTHING may
 * reference, which is exactly why writing to it retriggers nothing -- and it is itself
 * reaped by the same rule as everything else, so it does not become a new leak.
 */
public final class ControllerPresence {

    /** The shared-name suffix; the object is {@code hohenheim-<token>-presence}. */
    public static final String SUFFIX = "presence";

    /** Config keys. Incus accepts arbitrary {@code user.*} keys on a network ACL. */
    public static final String CONFIG_CONTROLLER = "user.hohenheim.controller";
    public static final String CONFIG_SEEN = "user.hohenheim.seen";

    /**
     * How stale our own stamp may get before a refresh writes it forward.
     *
     * AIDEV-NOTE: this must stay FAR below the reap grace
     * ({@code hohenheim.incus.controller_presence_grace_hours}). The margin between them
     * is how many consecutive refresh failures a live controller may suffer before it
     * starts to look departed; at 30 minutes against a 7-day grace that is 336 of them.
     */
    public static final Duration REFRESH_AFTER = Duration.ofMinutes(30);

    private ControllerPresence() {
    }

    /** This controller's presence marker name. */
    public static @NonNull String aclName() {
        return ControllerScope.scoped(SUFFIX);
    }

    /** Whether a shared-name suffix is the presence marker's. */
    public static boolean isPresenceSuffix(@NonNull String suffix) {
        return SUFFIX.equals(suffix);
    }

    /**
     * Refresh this controller's presence marker on one daemon, creating it when absent.
     *
     * A marker that something REFERENCES is not a presence marker -- it is an ACL somebody
     * attached to a NIC under our name -- so this refuses to write to it rather than
     * silently treating it as ours.
     *
     * @throws IOException when the daemon refuses, or the stamp does not read back
     */
    public static void stamp(@NonNull IncusClient incus) throws IOException {
        String name = aclName();
        Map<String, Object> existing = incus.networkAcl(name);

        if (existing != null && !usedBy(existing).isEmpty()) {
            throw new IOException("REFUSED to stamp controller presence: the '" + name
                + "' ACL is REFERENCED by " + usedBy(existing) + ". A presence marker that"
                + " something uses is not a marker; refusing to write liveness into an"
                + " object whose removal would change enforcement.");
        }

        Instant now = Instant.now();
        if (existing != null && !needsRefresh(seenOf(existing), now)) {
            return;   // fresh enough; a write here buys nothing
        }

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name);
        definition.put("description", "hohenheim controller presence marker: the instant"
            + " this controller was last alive on this daemon. Rule-less on purpose --"
            + " it enforces nothing and must never be referenced by a NIC.");
        definition.put("egress", List.of());
        definition.put("ingress", List.of());
        definition.put("config", Map.of(
            CONFIG_CONTROLLER, ControllerIdentity.token(),
            CONFIG_SEEN, now.toString()));

        if (existing == null) {
            try {
                incus.createNetworkAcl(definition);
            } catch (IncusClient.ApiException raced) {
                // Another boot of THIS controller created it first; having the marker is
                // the goal, not having been the one to create it. The read-back is the gate.
                if (!raced.isAlreadyExists()) {
                    throw raced;
                }
            }
        } else {
            incus.updateNetworkAcl(name, definition);
        }

        Map<String, Object> readBack = incus.networkAcl(name);
        if (readBack == null || seenOf(readBack) == null) {
            throw new IOException("REFUSED to trust this Incus host's presence marker: the"
                + " daemon accepted '" + name + "' but it does not read back with a "
                + CONFIG_SEEN + " stamp. Without a readable stamp this controller would"
                + " look departed to every peer on this daemon.");
        }
    }

    /** Whether a stamp of this age wants writing forward; a missing stamp always does. */
    static boolean needsRefresh(@Nullable Instant seen, @NonNull Instant now) {
        return seen == null || seen.isBefore(now.minus(REFRESH_AFTER))
            || seen.isAfter(now.plus(REFRESH_AFTER));   // a future stamp is not evidence
    }

    /**
     * The {@code token -> last seen} map a daemon's ACL listing carries.
     *
     * A marker whose stamp is unreadable is left OUT: absent evidence, never fresh
     * evidence and never stale evidence, so its controller lands in the never-reaped
     * unstamped bucket rather than in the reapable one.
     */
    public static @NonNull Map<String, Instant> readStamps(
            @NonNull List<Map<String, Object>> acls) {
        Map<String, Instant> stamps = new LinkedHashMap<>();
        for (Map<String, Object> acl : acls) {
            if (!(acl.get("name") instanceof String name)) {
                continue;
            }
            ControllerScope.SharedScoped scoped = ControllerScope.parseShared(name);
            if (scoped == null || !isPresenceSuffix(scoped.suffix())) {
                continue;
            }
            Instant seen = seenOf(acl);
            if (seen != null) {
                stamps.put(scoped.token(), seen);
            }
        }
        return stamps;
    }

    /** The {@code user.hohenheim.seen} instant of one ACL definition, or null. */
    static @Nullable Instant seenOf(@NonNull Map<String, Object> acl) {
        if (!(acl.get("config") instanceof Map<?, ?> config)
                || !(config.get(CONFIG_SEEN) instanceof String text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException unreadable) {
            Blast.log("INCUS PRESENCE: unreadable", CONFIG_SEEN, "stamp on",
                acl.get("name"), "-", text);
            return null;
        }
    }

    /** One object's {@code used_by} references; an unreadable field reads as REFERENCED. */
    public static @NonNull List<?> usedBy(@NonNull Map<String, Object> object) {
        // AIDEV-NOTE: the unreadable case answers "referenced", not "free". This value is
        // the structural guard that keeps a reap away from an object workloads point at,
        // and a guard that fails open is not a guard.
        return object.get("used_by") instanceof List<?> references
            ? references : List.of(UNREADABLE_USED_BY);
    }

    /** The sentinel an unreadable {@code used_by} presents, so the refusal names itself. */
    static final String UNREADABLE_USED_BY = "(used_by unreadable)";
}
