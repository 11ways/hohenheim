package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
import java.util.Map;

/**
 * THE one act by which a host's shared-container risk becomes accepted: it stamps the
 * five acknowledgement columns from the CURRENT attribution, records the activity row
 * beside them, and refuses everything that is not a human accepting a stated risk.
 *
 * The {@code HostPins} shape, deliberately: columns are the authority (a gate reads
 * them), the activity row is history, and the ceremony has exactly one entry point so
 * two callers cannot record two different things.
 */
public final class HostPostureAcknowledgement {

    /**
     * The activity DETAIL the acknowledgement is recorded under, beside the generic
     * {@code update} action -- the spelling every other trust act on this model uses
     * ({@code host_key_pinned}, {@code host_key_verified}, {@code host_key_repinned}).
     */
    public static final String ACTIVITY_DETAIL = "posture_acknowledged";

    private HostPostureAcknowledgement() {
    }

    /**
     * Record that the acting operator accepted this host's posture risk at the current
     * {@link ServerModel#POSTURE_WARNING_VERSION}.
     *
     * AIDEV-NOTE: an ACTOR is required, and a system-originated call is refused rather
     * than recorded with a null one. The clause this implements asks for "actor, timestamp
     * and warning version"; an acknowledgement with no actor is the forged record the
     * clause exists to prevent, and it would read on the overview page as though somebody
     * had decided. There is deliberately no CLI or seed lane for this act.
     *
     * @throws Violations {@code posture_not_acknowledgeable} (this posture needs no
     *         acknowledgement), {@code posture_already_acknowledged} or
     *         {@code posture_acknowledgement_needs_actor}
     */
    public static void record(@NonNull Row server) {
        if (!ServerModel.postureNeedsAcknowledgement(server)) {
            throw Violations.ofForm(violation("posture_not_acknowledgeable")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
        if (ServerModel.postureAcknowledged(server)) {
            throw Violations.ofForm(violation("posture_already_acknowledged")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
        Accountability who = Accountability.current();
        String actor = who.actor();
        if (actor == null || actor.isBlank()) {
            throw Violations.ofForm(violation("posture_acknowledgement_needs_actor")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
        String label = who.actorLabel() != null ? who.actorLabel() : actor;
        String posture = server.get(ServerModel.POSTURE);
        ActivityLog.withAction(ActivityLog.ACTION_UPDATE, ACTIVITY_DETAIL, () -> {
            server.set(ServerModel.ACKNOWLEDGED_POSTURE, posture);
            server.set(ServerModel.ACKNOWLEDGED_WARNING_VERSION,
                ServerModel.POSTURE_WARNING_VERSION);
            server.set(ServerModel.ACKNOWLEDGED_AT, Instant.now());
            server.set(ServerModel.ACKNOWLEDGED_BY, actor);
            server.set(ServerModel.ACKNOWLEDGED_BY_LABEL, label);
            Models.get(ServerModel.class).save(server);
        });
        Blast.slog("hohenheim.host.posture_acknowledged", Map.of(
            "server", String.valueOf((Object) server.get(ServerModel.NAME)),
            "posture", String.valueOf(posture),
            "version", ServerModel.POSTURE_WARNING_VERSION,
            "actor", actor));
    }

    private static Microcopy violation(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
