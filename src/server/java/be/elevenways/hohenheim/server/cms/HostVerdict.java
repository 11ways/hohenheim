package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.host.HostStanding;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.PlacementRefusal;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;


/**
 * Whether one host takes new apps, why not, and what clears it: ONE reading of the placement gate
 * ({@link InstancePlacement#hostRefusal}) that the Hosts list's state cell, the host page, the attention band and the
 * checklist's admission step all word.
 *
 * AIDEV-NOTE: the stored admission token alone is not the verdict. An admitted host the gate refuses reads
 * {@link HostStanding#REFUSING} with the gate's own words and remedy; a waiting host says why it waits (its failed
 * required checks, or that it was never checked).
 *
 * @param standing what the host takes
 * @param reason   why, in words; null for a host that takes new apps
 * @param remedy   what clears it, as the operator does it on the host
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
record HostVerdict(@NonNull HostStanding standing, @Nullable Microcopy reason,
                   PlacementRefusal.@NonNull Remedy remedy) {

    /** @return this host's verdict, read from its stored row and the gate */
    static @NonNull HostVerdict of(@NonNull Row server) {
        String admission = server.get(ServerModel.ADMISSION);
        if (ServerModel.ADMISSION_ADMITTED.equals(admission)) {
            Microcopy refusal;
            try {
                refusal = InstancePlacement.hostRefusal(server);
            } catch (RuntimeException unreadable) {
                // One bad host record must never take a list or the dashboard down; it takes nothing either.
                return new HostVerdict(HostStanding.REFUSING, null, PlacementRefusal.Remedy.OPEN_HOST);
            }
            return refusal == null ? new HostVerdict(HostStanding.TAKING, null, PlacementRefusal.Remedy.OPEN_HOST)
                : new HostVerdict(HostStanding.REFUSING, refusal, PlacementRefusal.remedyOf(refusal));
        }
        if (ServerModel.ADMISSION_CORDONED.equals(admission)) {
            return new HostVerdict(HostStanding.PAUSED, HohenheimMicrocopy.HOST_LIST.of("cordoned_detail"),
                PlacementRefusal.Remedy.OPEN_HOST);
        }
        // An unknown or missing token reads as waiting, the state that places nothing: fail closed.
        List<Microcopy> failed = HostAttention.failedRequiredChecks(server);
        Microcopy reason = !failed.isEmpty()
            ? HohenheimMicrocopy.HOST_LIST.of("checks_failed").withArg("count", failed.size()).withArg("checks", failed)
            : server.get(ServerModel.PROBED_AT) == null ? HohenheimMicrocopy.HOST_LIST.of("never_checked")
            : HohenheimMicrocopy.HOST_LIST.of("checks_pass");
        return new HostVerdict(HostStanding.WAITING, reason, PlacementRefusal.Remedy.CHECK_AND_ADMIT);
    }

    /** @return whether an ordinary new app could land on this host */
    boolean takesNewApps() {
        return this.standing == HostStanding.TAKING;
    }

    /**
     * @return the Hosts list's state cell: the standing in words, with why only where no attention item says it; a
     *         standing that raises one ({@link HostStanding#severity}) has its reason in the list's band above,
     *         so the row does not repeat that sentence word for word (board Hosts: the card shows the badge)
     */
    @NonNull StateLineCell cell() {
        return StateLineCell.of(this.standing, this.standing.severity() != null ? null : this.reason);
    }

    /**
     * @param name the host's name, which "Open local" carries
     * @return the remedy as an attention item's worded action
     */
    @NonNull Microcopy remedyAction(@NonNull Object name) {
        return switch (this.remedy) {
            case CHECK_AND_ADMIT -> HohenheimMicrocopy.ATTENTION_ACTION.of("act_check_admit");
            case CHECK_AGAIN -> HohenheimMicrocopy.ATTENTION_ACTION.of("act_check_again");
            case CONFIRM_KEY -> HohenheimMicrocopy.ATTENTION_ACTION.of("act_confirm_host_key");
            case REVIEW_KEY -> HohenheimMicrocopy.ATTENTION_ACTION.of("act_review_host_key");
            case OPEN_HOST -> HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_app").withArg("name", name);
        };
    }
}
