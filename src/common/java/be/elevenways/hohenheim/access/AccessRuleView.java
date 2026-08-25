package be.elevenways.hohenheim.access;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * One node of an access list's rule tree, flattened for rendering: the tree's shape is
 * carried by {@code depth} (and the {@code indentStyle} it renders as) rather than by nesting, so the page renders one loop and the
 * indentation is a style, not a recursive template.
 *
 * @param path     the node's position as a dotted outline number ("1.2" = the second
 *                 child of the first root rule), which is also how the add form's parent
 *                 select names a group
 * @param summary  what this node decides, already localized (the network, the username,
 *                 the provider's name, the group's mode)
 * @param enabledBadge  the on/off pill, so the switched-ON state is stated rather than
 *                      shown by the absence of a chip
 * @param invokes  the rule resource's OWN row actions for this row and viewer, so move,
 *                 toggle and the confirmed delete arrive with their gates attached
 */
@HawkeyeClass
public record AccessRuleView(
    int id,
    int depth,
    @NonNull String indentStyle,
    @NonNull String path,
    @NonNull String type,
    @NonNull Microcopy typeLabel,
    @NonNull String iconName,
    @NonNull Microcopy summary,
    @NonNull EnumBadgeState enabledBadge,
    boolean isGroup,
    @Nullable RouteTarget editTarget,
    @NonNull List<InvokeActionState> invokes
) {
}
