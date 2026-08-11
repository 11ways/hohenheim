package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import be.elevenways.zenit.cms.common.render.action.LinkActionState;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The instance's own row actions projected for the overview page, resolved through the
 * SAME zenit-cms action-state translation the list uses -- permissions, per-record
 * capability predicates and confirmations included. A null member means the action is not
 * visible for this record and this viewer, so hide AND enforce stay one decision.
 *
 * The /manage projection fills this from {@code ManageInstanceResource.rowActions()},
 * which is a shorter list: the members it does not declare simply arrive null.
 */
@HawkeyeClass
public record InstanceActionsView(
    @Nullable InvokeActionState deploy,
    @Nullable InvokeActionState stop,
    @Nullable InvokeActionState restart,
    @Nullable InvokeActionState install,
    @Nullable InvokeActionState reinstall,
    @Nullable InvokeActionState appUpdate,
    @Nullable InvokeActionState snapshot,
    @Nullable InvokeActionState backup,
    @Nullable LinkActionState migrate
) {
}
