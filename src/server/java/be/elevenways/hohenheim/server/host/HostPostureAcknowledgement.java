package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.HistoryText;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * THE one act by which a host's shared-container risk becomes accepted: it stamps the
 * five acknowledgement columns from the CURRENT attribution, recorded with the typed
 * {@code posture_risk_accepted} fact; it refuses everything that is not a human accepting a stated risk.
 *
 * The {@code HostPins} shape, deliberately: columns are the authority (a gate reads
 * them), the act is a typed fact on their recorded change (a security audit fact the
 * changed columns alone do not name), and the ceremony has exactly one entry point
 * so two callers cannot record two different things.
 */
public final class HostPostureAcknowledgement {

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
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("posture_not_acknowledgeable")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
        if (ServerModel.postureAcknowledged(server)) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("posture_already_acknowledged")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
        Accountability who = Accountability.current();
        String actor = who.actor();
        PrincipalRef principal = who.actorReference();
        if (principal == null || !principal.kind().account() || Accountability.ORIGIN_SYSTEM.equals(who.origin())) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("posture_acknowledgement_needs_actor")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
        String label = HistoryText.actorName(principal, actor, who.actorLabel(), who.origin())
            .resolve(LocaleChain.empty(), MessageResolvers.getDefault());
        String posture = server.get(ServerModel.POSTURE);
        server.set(ServerModel.ACKNOWLEDGED_POSTURE, posture);
        server.set(ServerModel.ACKNOWLEDGED_WARNING_VERSION, ServerModel.POSTURE_WARNING_VERSION);
        server.set(ServerModel.ACKNOWLEDGED_AT, Now.instant());
        ServerModel.ACKNOWLEDGER.write(server, principal);
        server.set(ServerModel.ACKNOWLEDGED_BY_LABEL, label);
        Models.get(ServerModel.class).save(server, Change.fact(HohenheimActivityAction.POSTURE_RISK_ACCEPTED, Map.of(
            HohenheimActivityAction.POSTURE_KEY, String.valueOf(posture),
            HohenheimActivityAction.WARNING_VERSION_KEY, ServerModel.POSTURE_WARNING_VERSION)));
        Blast.slog("hohenheim.host.posture_acknowledged", Map.of(
            "server", String.valueOf((Object) server.get(ServerModel.NAME)),
            "posture", String.valueOf(posture),
            "version", ServerModel.POSTURE_WARNING_VERSION,
            "actor", actor));
    }

}
