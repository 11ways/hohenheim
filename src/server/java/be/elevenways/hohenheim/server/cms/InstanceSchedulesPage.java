package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Schedules tab on an instance: its record schedules, linking into the (nav-hidden)
 * schedule resource forms.
 */
public final class InstanceSchedulesPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_schedules"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_schedule"); }
    @Override public @NonNull String slug() { return "schedules"; }
    @Override public @NonNull Icon icon() { return Icon.of("clock"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        String basePath = CmsSupport.panelBase(conduit);

        List<Map<String, Object>> schedules = new ArrayList<>();
        for (Row schedule : Models.get(RecordScheduleModel.class)
                .findForRecord(InstanceModel.MODEL_ID, instanceId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", schedule.get(RecordScheduleModel.ID));
            entry.put("name", schedule.get(RecordScheduleModel.NAME));
            entry.put("cron", schedule.get(RecordScheduleModel.CRON));
            entry.put("timezone", schedule.get(RecordScheduleModel.TIMEZONE));
            entry.put("enabled", Boolean.TRUE.equals(schedule.get(RecordScheduleModel.ENABLED)));
            entry.put("disabledReason", schedule.get(RecordScheduleModel.DISABLED_REASON));
            entry.put("editUrl", basePath + "/instance-schedules/" + schedule.get(RecordScheduleModel.ID));
            entry.put("stepsUrl", basePath + "/instance-schedules/"
                + schedule.get(RecordScheduleModel.ID) + "/page/steps");
            schedules.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME) + " - Schedules");
        vars.put("instanceId", instanceId);
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("schedules", schedules);
        vars.put("basePath", basePath);
        vars.put("canEdit", HohenheimAccess.isAdmin(accessContext)
            || HohenheimAccess.canManageInstance(accessContext, instanceId));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-schedules"), vars);
    }
}
