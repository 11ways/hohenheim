package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.schedule.ScheduleRunStatuses;
import be.elevenways.hohenheim.schedule.ScheduleRunView;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.render.table.DateTimeCellState;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.server.page.ChildListSections;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Steps tab on a schedule: the framework's child list section over the chain's steps, then the schedule's recent runs
 * with their per-step verdicts.
 *
 * AIDEV-NOTE: the runs stay hand-drawn here because the run entry has no parent declaration and no /manage twin, so
 * no child list can carry them; the steps section is {@link InstanceScheduleStepParts#STEPS}, embedded.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceScheduleStepsPage implements RecordTab.Rendered<Row> {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_schedule_steps"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.SCHEDULE_STEP.of("plural"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.STEPS; }
    @Override public @NonNull Icon icon() { return Icon.of("list-ol"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row schedule) {
        Conduit conduit = request.conduit();
        Integer scheduleId = schedule.get(RecordScheduleModel.ID);

        RelativeTimeWording wording = CmsSupport.timeWording(conduit);
        List<ScheduleRunView> runs = new ArrayList<>();
        for (Row run : Models.get(RecordScheduleRunModel.class)
                .findRecentForSchedule(scheduleId, 10)) {
            // The verdict's label, icon and colour come from ScheduleRunStatuses -- the ONE
            // table over the framework's six statuses. The template used to call everything
            // that was not "completed" destructive, which painted a still-RUNNING chain red.
            String error = run.get(RecordScheduleRunModel.ERROR);
            Instant started = run.get(RecordScheduleRunModel.STARTED_AT);
            runs.add(new ScheduleRunView(
                run.get(RecordScheduleRunModel.ID),
                ScheduleRunStatuses.badgeFor(run.get(RecordScheduleRunModel.STATUS)),
                started != null ? new DateTimeCellState(started.toString(), wording) : null,
                InstanceScheduleRunParts.describeSteps(run),
                error != null ? error : ""));
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, HohenheimMicrocopy.SCHEDULE_STEP,
            schedule.get(RecordScheduleModel.NAME)));
        vars.put("sections", ChildListSections.embedded(request,
            CmsSupport.rowEntry(request.panel(), HohenheimSlugs.INSTANCE_SCHEDULES), schedule,
            InstanceScheduleStepParts.STEPS));
        vars.put("panelSlug", request.panelSlug());
        vars.put("runs", runs);
        vars.put("head", recordHead(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_SCHEDULE_STEPS, vars);
    }
}
