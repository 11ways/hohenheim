package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.server.cms.AccessRuleParts;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.cms.common.task.TaskOperations;
import be.elevenways.zenit.server.operation.HistoryOperations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Hohenheim's hand-declared tools that move an access rule among its siblings, take back the caller's own change, and
 * end, pause or resume a system task run, each over the operation the panel's buttons place; {@link HohenheimTools}
 * offers them beside its own.
 *
 * AIDEV-NOTE: declarations only, as in HohenheimTools: the move is core's manual order of the rule tree, the undo is
 * core's one Undo (named by the request id its change was sent with, which core resolves to the change), and the task
 * runs' verbs are zenit-cms's Task runs operations.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimMoveUndoTaskTools {

    private static final String DIRECTION = "direction";
    private static final String UP = "up";
    private static final String DOWN = "down";
    private static final String PAUSED = "paused";
    /** The argument naming the change an undo takes back: core's input calls it its command. */
    private static final String CHANGE = "change";

    private HohenheimMoveUndoTaskTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool move = McpCodeTool.builder("access_rule_move",
                HohenheimTools.words("mcp_tool_access_rule_move"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_access_rule_move"))
            .choice(DIRECTION, HohenheimTools.words("mcp_move_direction"), List.of(UP, DOWN), null)
            .route(AccessRuleParts.ORDER.moveUp(), null, DIRECTION, UP)
            .route(AccessRuleParts.ORDER.moveDown(), null, DIRECTION, DOWN)
            .build();
        McpHostTool undo = McpCodeTool.singleAs("undo", HohenheimTools.words("mcp_tool_undo"), HohenheimTools.writes(),
            HistoryOperations.undo(), HohenheimMcpAnswers::said, Map.of(HistoryOperations.COMMAND.getName(), CHANGE));
        McpHostTool end = McpCodeTool.builder("task_run_end", HohenheimTools.words("mcp_tool_task_run_end"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_task_run_end"))
            .route(TaskOperations.STOP, HohenheimMcpAnswers::said)
            .build();
        McpHostTool pause = McpCodeTool.builder("task_run_pause", HohenheimTools.words("mcp_tool_task_run_pause"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_task_run_pause"))
            .optionalFlag(PAUSED, HohenheimTools.words("mcp_task_run_paused"))
            .route(TaskOperations.PAUSE, HohenheimMcpAnswers::said, PAUSED, "true")
            .route(TaskOperations.RESUME, HohenheimMcpAnswers::said, PAUSED, "false")
            .build();
        return List.of(move, undo, end, pause);
    }
}
