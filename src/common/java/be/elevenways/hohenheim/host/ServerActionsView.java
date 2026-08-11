package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The server row actions projected for the overview page, resolved through the SAME
 * zenit-cms action-state translation the list uses (permissions, per-row visibility and
 * dynamic confirmations included). A null member means the action is not visible for
 * this row and this viewer -- hide AND enforce stay one decision.
 */
@HawkeyeClass
public record ServerActionsView(
    @Nullable InvokeActionState sshScan,
    @Nullable InvokeActionState sshConfirm,
    @Nullable InvokeActionState sshRepin,
    @Nullable InvokeActionState sshRotate,
    @Nullable InvokeActionState incusScan,
    @Nullable InvokeActionState incusConfirm,
    @Nullable InvokeActionState incusRepin,
    @Nullable InvokeActionState incusRotate,
    @Nullable InvokeActionState probe,
    @Nullable InvokeActionState preflight,
    @Nullable InvokeActionState admit,
    @Nullable InvokeActionState cordon,
    @Nullable InvokeActionState uncordon,
    @Nullable InvokeActionState drain,
    @Nullable InvokeActionState reap
) {
}
