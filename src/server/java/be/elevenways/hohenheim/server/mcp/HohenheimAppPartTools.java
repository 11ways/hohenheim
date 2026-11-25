package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.instance.InstanceScheduleOperations;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.server.cms.AppPartWrites;
import be.elevenways.hohenheim.server.cms.InstanceScheduleParts;
import be.elevenways.hohenheim.server.cms.ScheduleWrites;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Hohenheim's hand-declared tools over an app's parts beside its settings: its volumes, the config files written into
 * containers and its schedules, each over the operations the panel's forms and buttons run; {@link HohenheimTools}
 * offers them beside its own.
 *
 * AIDEV-NOTE: declarations only, as in HohenheimTools: a gate, a check or a writer of a tool here is its operation's.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimAppPartTools {

    private HohenheimAppPartTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool volumeSet = McpCodeTool.builder("volume_set", HohenheimTools.words("mcp_tool_volume_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_volume_set"))
            .creates(AppPartWrites.VOLUMES.create(), null, List.of(AppPartWrites.OWNER,
                InstanceVolumeModel.NAME.getInputName(), InstanceVolumeModel.CONTAINER_PATH.getInputName()),
                HohenheimTools.words("volume_thing"))
            .route(AppPartWrites.VOLUMES.change(), HohenheimMcpAnswers::changed)
            .build();
        // A config file has no code: its owner's code (an app, a template or a stack service) and its path name it.
        McpHostTool configFileSet = McpCodeTool.builder("config_file_set",
                HohenheimTools.words("mcp_tool_config_file_set"), HohenheimTools.writes())
            .subject(AppPartWrites.OWNER, HohenheimTools.words("mcp_code_config_file_owner"))
            .route(AppPartWrites.APP_FILES.set(), HohenheimMcpAnswers::changed)
            .route(AppPartWrites.TEMPLATE_FILES.set(), HohenheimMcpAnswers::changed)
            .route(AppPartWrites.STACK_FILES.set(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool scheduleSet = McpCodeTool.builder("schedule_set", HohenheimTools.words("mcp_tool_schedule_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_schedule_set"))
            .creates(ScheduleWrites.SCHEDULES.create(), null, List.of(ScheduleWrites.APP,
                RecordScheduleModel.CRON.getInputName()), HohenheimTools.words("schedule_thing"))
            .route(ScheduleWrites.SCHEDULES.change(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool scheduleRun = McpCodeTool.builder("schedule_run", HohenheimTools.words("mcp_tool_schedule_run"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_schedule_run"))
            .route(InstanceScheduleOperations.RUN_SCHEDULE, HohenheimAppPartTools::ran)
            .build();
        return List.of(volumeSet, configFileSet, scheduleSet, scheduleRun);
    }

    /** The run a schedule_run made, by its code and how it ended, or that the chain already runs. */
    private static @NonNull McpResponse ran(@NonNull McpToolContext context, @Nullable Integer run) {
        String status = InstanceScheduleParts.runStatus(run);
        if (run == null) return McpResponse.create().line("not run: the chain is already running");
        String key = String.valueOf(run);
        String code = RecordCodes.codes(RecordScheduleRunModel.MODEL_ID, List.of(key), context.access()).get(key);
        return McpResponse.create().line("schedule run " + (code == null ? "made" : code) + ": " + status);
    }
}
