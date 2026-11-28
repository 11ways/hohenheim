package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.instance.InstanceBackupOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceSnapshotOperations;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.server.cms.BackupTargetParts;
import be.elevenways.hohenheim.server.cms.DatabaseDumpParts;
import be.elevenways.hohenheim.server.cms.DatabaseParts;
import be.elevenways.hohenheim.server.cms.DatabaseRecordWrites;
import be.elevenways.hohenheim.server.files.InstanceFileOperations;
import be.elevenways.hohenheim.server.files.InstanceFiles;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Hohenheim's hand-declared tools over its data: databases, their dumps, snapshots, an app's files and the move of an
 * app to another host, each over the operations the panel runs; {@link HohenheimTools} offers them beside its own.
 *
 * AIDEV-NOTE: declarations only, as in HohenheimTools: a gate, a check or a writer of a tool here is its operation's.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimDataTools {

    private static final String CLEANUP_ONLY = "cleanup_only";
    private static final String IN_PLACE = "in_place";
    private static final String FOLDER = "folder";

    private HohenheimDataTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool moveToShared = McpCodeTool.builder("database_move_to_shared",
                HohenheimTools.words("mcp_tool_database_move_to_shared"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_database_move"))
            .flag(CLEANUP_ONLY, HohenheimTools.words("mcp_cleanup_only"))
            .route(DatabaseParts.MOVE_TO_SHARED, null, CLEANUP_ONLY, "false")
            .route(DatabaseParts.FINISH_OLD_ENGINE, null, CLEANUP_ONLY, "true")
            .build();
        // One effect per tool: in_place destroys, a restore into a new database only writes; the tool says destroy.
        McpHostTool dumpRestore = McpCodeTool.builder("database_dump_restore",
                HohenheimTools.words("mcp_tool_database_dump_restore"), HohenheimTools.destroys())
            .code(HohenheimTools.words("mcp_code_database_dump"))
            .flag(IN_PLACE, HohenheimTools.words("mcp_in_place_dump"))
            .route(DatabaseDumpParts.RESTORE_TO_NEW, null, IN_PLACE, "false")
            .route(DatabaseDumpParts.RESTORE_IN_PLACE, null, IN_PLACE, "true")
            .build();
        // An app's code (AP12) takes a snapshot of it; a snapshot's code (SN14) changes the note kept with it.
        McpHostTool snapshotSet = McpCodeTool.builder("snapshot_set",
                HohenheimTools.words("mcp_tool_snapshot_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_snapshot_set"))
            .route(InstanceOperations.SNAPSHOT, HohenheimDataTools::snapshot)
            .route(InstanceSnapshotOperations.CHANGE_NOTE, HohenheimDataTools::snapshot)
            .build();
        McpHostTool snapshotRestore = McpCodeTool.builder("snapshot_restore",
                HohenheimTools.words("mcp_tool_snapshot_restore"), HohenheimTools.destroys())
            .code(HohenheimTools.words("mcp_code_snapshot_restore"))
            .route(InstanceSnapshotOperations.RESTORE, null)
            .build();
        // An app's code (AP12) reaches its volumes, a site's (S27) its own stored files: one verb set, two subjects.
        McpHostTool fileRead = McpCodeTool.builder("file_read", HohenheimTools.words("mcp_tool_file_read"),
                McpHostTool.Annotations.ofReadOnly())
            .code(HohenheimTools.words("mcp_code_file_read"))
            .route(InstanceFileOperations.READ, HohenheimDataTools::fileRead)
            .route(InstanceFileOperations.READ_SITE, HohenheimDataTools::fileRead)
            .build();
        McpHostTool fileWrite = McpCodeTool.builder("file_write", HohenheimTools.words("mcp_tool_file_write"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_file_write"))
            .flag(FOLDER, HohenheimTools.words("mcp_folder"))
            .route(InstanceFileOperations.WRITE, HohenheimDataTools::fileDone, FOLDER, "false")
            .route(InstanceFileOperations.MAKE_FOLDER, HohenheimDataTools::fileDone, FOLDER, "true")
            .route(InstanceFileOperations.WRITE_SITE, HohenheimDataTools::fileDone, FOLDER, "false")
            .route(InstanceFileOperations.MAKE_SITE_FOLDER, HohenheimDataTools::fileDone, FOLDER, "true")
            .build();
        McpHostTool fileDelete = McpCodeTool.builder("file_delete",
                HohenheimTools.words("mcp_tool_file_delete"), HohenheimTools.destroys())
            .code(HohenheimTools.words("mcp_code_file_delete"))
            .route(InstanceFileOperations.DELETE, HohenheimDataTools::fileDone)
            .route(InstanceFileOperations.DELETE_SITE, HohenheimDataTools::fileDone)
            .build();
        McpHostTool fileMove = McpCodeTool.builder("file_move", HohenheimTools.words("mcp_tool_file_move"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_file_move"))
            .route(InstanceFileOperations.MOVE, HohenheimDataTools::fileDone)
            .route(InstanceFileOperations.MOVE_SITE, HohenheimDataTools::fileDone)
            .build();
        McpHostTool move = McpCodeTool.builder("app_move", HohenheimTools.words("mcp_tool_app_move"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_app_move"))
            .route(InstanceOperations.MIGRATE, HohenheimDataTools::moved)
            .build();
        McpHostTool databaseSet = McpCodeTool.builder("database_set", HohenheimTools.words("mcp_tool_database_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_database_set"))
            .creates(DatabaseRecordWrites.DATABASES.create(), null, List.of(DatabaseModel.NAME.getInputName(),
                DatabaseModel.ENGINE.getInputName()), HohenheimTools.words("database_thing"))
            .route(DatabaseRecordWrites.DATABASES.change(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool engineSet = McpCodeTool.builder("database_engine_set",
                HohenheimTools.words("mcp_tool_database_engine_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_database_engine_set"))
            .creates(DatabaseRecordWrites.ENGINES.create(), null, List.of(DatabaseEngineModel.NAME.getInputName(),
                DatabaseEngineModel.ENGINE.getInputName()), HohenheimTools.words("database_engine_thing"))
            .route(DatabaseRecordWrites.ENGINES.change(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool attach = McpCodeTool.builder("app_database_set", HohenheimTools.words("mcp_tool_app_database_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_app_database_set"))
            .creates(DatabaseRecordWrites.ATTACHMENTS.create(), null, List.of(DatabaseRecordWrites.APP,
                DatabaseRecordWrites.DATABASE), HohenheimTools.words("database_link_thing"))
            .route(DatabaseRecordWrites.ATTACHMENTS.change(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool targetSet = McpCodeTool.builder("backup_target_set",
                HohenheimTools.words("mcp_tool_backup_target_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_backup_target_set"))
            .creates(BackupTargetParts.WRITES.create(), null, List.of(BackupTargetModel.NAME.getInputName(),
                BackupTargetModel.KIND.getInputName(), BackupTargetParts.SETTINGS),
                HohenheimTools.words("backup_target_thing"))
            .route(BackupTargetParts.WRITES.change(), HohenheimMcpAnswers::changed)
            .build();
        // One effect per tool: in_place destroys, a restore into a new app only writes; the tool says destroy.
        McpHostTool backupRestore = McpCodeTool.builder("backup_restore",
                HohenheimTools.words("mcp_tool_backup_restore"), HohenheimTools.destroys())
            .code(HohenheimTools.words("mcp_code_backup_restore"))
            .flag(IN_PLACE, HohenheimTools.words("mcp_in_place_backup"))
            .route(InstanceBackupOperations.RESTORE_BACKUP, null, IN_PLACE, "false")
            .route(InstanceBackupOperations.RESTORE_BACKUP_IN_PLACE, null, IN_PLACE, "true")
            .build();
        return List.of(databaseSet, engineSet, attach, targetSet, backupRestore, moveToShared, dumpRestore,
            snapshotSet, snapshotRestore, fileRead, fileWrite, fileDelete, fileMove, move);
    }

    /** The host an app now runs on, by its code; its name is what someone typed. */
    private static @NonNull McpResponse moved(@NonNull McpToolContext context, @Nullable Integer host) {
        McpResponse response = McpResponse.create();
        String code = host == null ? null : RecordCodes.codeUnjudged(HostModel.MODEL_ID, String.valueOf(host));
        if (host == null || code == null) return response.line("moved");
        return response.untrustedLine("now runs on host " + code + ": ", "host", HostModel.nameOf(host));
    }

    /** The snapshot a call took or changed, by its code. */
    private static @NonNull McpResponse snapshot(@NonNull McpToolContext context, @Nullable Integer snapshot) {
        String code = snapshot == null ? null
            : RecordCodes.codeUnjudged(InstanceSnapshotModel.MODEL_ID, String.valueOf(snapshot));
        return McpResponse.create().line(code == null ? "done" : "snapshot: " + code);
    }

    /** The path a file write, delete or move acted on; a path is what someone typed. */
    private static @NonNull McpResponse fileDone(@NonNull McpToolContext context, @Nullable String path) {
        return McpResponse.create().untrustedLine("done: ", "path", path);
    }

    /** A folder's entries, folders first, or a file's text; a binary file by its size alone. */
    private static @NonNull McpResponse fileRead(@NonNull McpToolContext context,
                                                 InstanceFileOperations.@Nullable Read read) {
        McpResponse response = McpResponse.create();
        if (read == null) return response.line("nothing read");
        response.untrustedLine("path: ", "path", read.path());
        if (read.entries() != null) {
            StringBuilder rows = new StringBuilder();
            for (InstanceFiles.Entry entry : read.entries()) {
                rows.append("- ").append(entry.name()).append(", ").append(entry.kind().toLowerCase(Locale.ROOT))
                    .append(", ").append(entry.size()).append(" bytes, modified ")
                    // A driver that could not tell answers 0, never a date in 1970.
                    .append(entry.modified() > 0 ? context.text().moment(Instant.ofEpochSecond(entry.modified()))
                        : "unknown")
                    .append(", mode ").append(entry.mode()).append(entry.managed() ? ", managed" : "").append('\n');
            }
            response.line("entries: " + read.entries().size());
            if (!read.entries().isEmpty()) response.untrusted("entries", rows.toString().stripTrailing());
        } else if (read.text() != null) {
            response.untrusted("content", read.text());
        } else {
            response.line("binary file, " + read.size() + " bytes: not shown as text");
        }
        return response.untrustedLine("volumes: ", "volumes", String.join(", ", read.roots()));
    }
}
