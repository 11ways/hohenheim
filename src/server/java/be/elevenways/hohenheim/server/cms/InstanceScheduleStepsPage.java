package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Steps tab on a schedule: the ordered chain plus its recent runs (per-step verdicts
 * inline), linking into the (nav-hidden) step resource forms.
 */
public final class InstanceScheduleStepsPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_schedule_steps"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "schedule_step"); }
    @Override public @NonNull String slug() { return "steps"; }
    @Override public @NonNull Icon icon() { return Icon.of("list-ol"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row schedule) {
        Integer scheduleId = schedule.get(RecordScheduleModel.ID);
        String panel = CmsSupport.panelSlug(conduit);

        List<Map<String, Object>> steps = new ArrayList<>();
        for (Row step : Models.get(RecordScheduleStepModel.class).findChain(scheduleId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", step.get(RecordScheduleStepModel.ID));
            entry.put("position", step.get(RecordScheduleStepModel.POSITION));
            entry.put("action", step.get(RecordScheduleStepModel.ACTION));
            entry.put("offsetSeconds", step.get(RecordScheduleStepModel.OFFSET_SECONDS));
            entry.put("failurePolicy", step.get(RecordScheduleStepModel.FAILURE_POLICY));
            entry.put("editTarget", CmsRoutes.detail(panel, "instance-schedule-steps",
                step.get(RecordScheduleStepModel.ID)));
            steps.add(entry);
        }

        List<Map<String, Object>> runs = new ArrayList<>();
        for (Row run : Models.get(RecordScheduleRunModel.class)
                .findRecentForSchedule(scheduleId, 10)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", run.get(RecordScheduleRunModel.ID));
            entry.put("status", run.get(RecordScheduleRunModel.STATUS));
            entry.put("trigger", run.get(RecordScheduleRunModel.TRIGGER));
            entry.put("startedAt", String.valueOf(run.get(RecordScheduleRunModel.STARTED_AT)));
            entry.put("summary", InstanceScheduleRunResource.describeSteps(run));
            entry.put("error", run.get(RecordScheduleRunModel.ERROR));
            runs.add(entry);
        }

        int instanceId = InstanceScheduleResource.parseInstanceId(
            schedule.get(RecordScheduleModel.RECORD_ID));

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "schedule_step",
            schedule.get(RecordScheduleModel.NAME)));
        vars.put("scheduleId", scheduleId);
        vars.put("scheduleName", schedule.get(RecordScheduleModel.NAME));
        vars.put("steps", steps);
        vars.put("runs", runs);
        boolean canEdit = HohenheimAccess.isAdmin(accessContext)
            || HohenheimAccess.hasInstanceCapability(
                accessContext, instanceId, HohenheimAccess.CONFIG);
        vars.put("canEdit", canEdit);
        // Create form + prefill query parameter: composed off CmsEndpoints, since
        // CmsRoutes.create returns the RouteTarget interface (no with(...)).
        // AIDEV-NOTE: gated on the SAME boolean the template's {% if %} uses. A declared
        // template variable is serialized into the hydration payload whether or not any
        // element renders it, so an ungated target would publish an editor route to a
        // viewer who may not edit (the certificates-request leak SiteDomainsPage hit).
        vars.put("addStepTarget", canEdit ? CmsEndpoints.CREATE_FORM
            .with(CmsEndpoints.PANEL_PARAM, panel)
            .with(CmsEndpoints.RESOURCE_PARAM, "instance-schedule-steps")
            .with(HohenheimParams.SCHEDULE_ID_PREFILL, scheduleId) : null);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-schedule-steps"), vars);
    }
}
