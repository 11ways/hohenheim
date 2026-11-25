package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.cms.HohenheimSettingsSections;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.cms.server.page.SettingsOperations;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Hohenheim's settings tools: {@code settings_read} over every section of the settings page, and one
 * {@code settings_<section>_set} per section whose settings this process declares, each argument derived from the
 * section's declared settings by zenit-cms's settings operations, which save as the page does; {@link HohenheimTools}
 * offers them beside its own.
 *
 * AIDEV-NOTE: declarations only: the arguments, their words, the checks and the writer are the settings' own and
 * the page's. A setting set on the host only is refused by its name with the page's words. The spam service declares
 * its own settings, which reach Hohenheim only in its answers, so its section is read here and changed on its page.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimSettingsTools {

    /** A write's receipt, which answers a retried call; a settings file is no table, so outside a transaction. */
    private static final OperationCommand WRITES = OperationCommand.perVerb().onDatasource("default")
        .execution(CommandExecution.OUTSIDE_TRANSACTION);

    private HohenheimSettingsTools() {
    }

    /** @return {@code settings_read}, then each section's write, in the order the settings page lists them */
    static @NonNull List<McpHostTool> declared() {
        List<SettingsOperations.Section> sections = new ArrayList<>();
        for (HohenheimSettingsSections section : HohenheimSettingsSections.values()) sections.add(section.section());
        List<McpHostTool> tools = new ArrayList<>();
        tools.add(McpCodeTool.single("settings_read", HohenheimTools.words("mcp_tool_settings_read"),
            McpHostTool.Annotations.ofReadOnly(), SettingsOperations.read(declared("settings_read", "settings_read",
                null), HohenheimTools.words("mcp_settings_section"), sections), HohenheimSettingsTools::read));
        for (HohenheimSettingsSections section : HohenheimSettingsSections.values()) {
            if (!section.settingsDeclaredHere()) continue;
            String name = "settings_" + section.key() + "_set";
            SettingsOperations.Write write = SettingsOperations.write(declared(name, "settings_set", section.label()),
                section.section());
            if (write == null) continue;
            tools.add(McpCodeTool.single(name, HohenheimTools.words("mcp_tool_" + name), HohenheimTools.writes(),
                write.operation(), HohenheimSettingsTools::written, write.refused()));
        }
        return tools;
    }

    /**
     * @param id      the operation's id path, the tool's name
     * @param word    the key of its words, shared by every section's write
     * @param section the section its words name, null for none
     * @return the declaration of a settings operation
     */
    private static SettingsOperations.@NonNull Declared declared(@NonNull String id, @NonNull String word,
                                                               @Nullable Microcopy section) {
        Microcopy label = HohenheimMicrocopy.HOHENHEIM.of(word);
        Microcopy description = HohenheimMicrocopy.HOHENHEIM.of(word + "_description");
        Microcopy happened = OperationSentences.of(word);
        if (section != null) {
            label = label.withArg("section", section);
            description = description.withArg("section", section);
            happened = happened.withArg("section", section);
        }
        return new SettingsOperations.Declared(HohenheimIds.id(id), label, description, happened,
            HohenheimSources.OPERATOR, WRITES);
    }

    private static @NonNull McpResponse read(@NonNull McpToolContext context,
                                             SettingsOperations.@Nullable Reading reading) {
        return said(reading == null ? List.of() : reading.lines(context.access()));
    }

    private static @NonNull McpResponse written(@NonNull McpToolContext context,
                                                SettingsOperations.@Nullable Written written) {
        return said(written == null ? List.of() : written.lines(context.access()));
    }

    private static @NonNull McpResponse said(@NonNull List<String> lines) {
        McpResponse response = McpResponse.create();
        for (String line : lines) response.line(line);
        return response;
    }
}
