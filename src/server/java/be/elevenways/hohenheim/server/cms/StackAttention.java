package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

import static be.elevenways.hohenheim.server.cms.AttentionItems.item;
import static be.elevenways.hohenheim.HohenheimSlugs.ADMIN;

/**
 * The STACKS role's attention items: a stack that stopped (its deploy failed, or none of its services runs) or runs
 * only in part, worded by the stack's own verdict ({@link AppHealth#stackHealth}).
 *
 * AIDEV-NOTE: the STACK_HEALTH alert used to be the only word of it, and since the inbox no longer badges the sidebar
 * (D13a) a lasting condition reaches Needs attention at its root, the stack, for as long as it lasts.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class StackAttention {

    private StackAttention() {
    }

    /** One item per failed or degraded stack, leading to its services. */
    public static void unhealthyStacks(@NonNull List<AttentionItem> items) {
        for (Row stack : Models.get(StackModel.class).find().all()) {
            String status = stack.get(StackModel.STATUS);
            if (!StackModel.STATUS_FAILED.equals(status) && !StackModel.STATUS_DEGRADED.equals(status)) {
                continue;
            }
            RecordHealth verdict = AppHealth.stackHealth(stack);
            AttentionSeverity severity = AttentionSeverity.ofTone(verdict.tone());
            if (severity == null) {
                continue;
            }
            Object name = stack.get(StackModel.NAME);
            AppHealth.Stoppage stoppage = AppHealth.stackStoppage(stack);
            Microcopy title = stoppage != null ? stoppage.title(name)
                : HohenheimMicrocopy.ATTENTION_TITLE.of("stack_degraded").withArg("name", name);
            items.add(item(severity, "layer-group", title, verdict.detail(),
                CmsRoutes.subpage(ADMIN, HohenheimSlugs.STACKS, stack.get(StackModel.ID), HohenheimSlugs.Tab.SERVICES),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_app").withArg("name", name)));
        }
    }
}
