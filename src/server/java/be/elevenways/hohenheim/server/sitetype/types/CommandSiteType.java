package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimPaths;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.WorkloadIdentity;
import be.elevenways.hohenheim.server.options.SystemUserOptions;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.sitetype.FaultedSiteHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;

import java.io.File;
import java.util.*;
import org.checkerframework.checker.nullness.qual.Nullable;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.PathKind;

/**
 * Runs an arbitrary command as a managed process.
 */
public class CommandSiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "command");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField START_COMMAND = SETTINGS_SCHEMA.addField(
        StringField.builder().name("start_command").label(HohenheimFormCopy.label("start_command"))
            .help(HohenheimFormCopy.help("start_command")).build());

    public static final StringField WORKING_DIRECTORY = SETTINGS_SCHEMA.addField(
        PathField.builder("working_directory").browserSource(HohenheimPaths.SERVER_FILES, PathKind.DIRECTORY)
            .label(HohenheimFormCopy.label("working_directory"))
            .help(HohenheimFormCopy.help("working_directory")).build());

    public static final StringField PORT_ARGUMENT = SETTINGS_SCHEMA.addField(
        StringField.builder().name("port_argument").label(HohenheimFormCopy.label("port_argument"))
            .help(HohenheimFormCopy.help("port_argument")).build());

    public static final BooleanField WAIT_FOR_READY = SETTINGS_SCHEMA.addField(
        BooleanField.builder("wait_for_ready").defaultValue(false)
            .label(HohenheimFormCopy.label("wait_for_ready")).help(HohenheimFormCopy.help("wait_for_ready")).build());

    public static final IntegerField MINIMUM_PROCESSES = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("minimum_processes").label(HohenheimFormCopy.label("minimum_processes"))
            .help(HohenheimFormCopy.help("minimum_processes")).build());

    public static final IntegerField MAXIMUM_PROCESSES = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("maximum_processes").label(HohenheimFormCopy.label("maximum_processes"))
            .help(HohenheimFormCopy.help("maximum_processes")).build());

    public static final IntegerField DELAY = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("delay").suffix("ms").label(HohenheimFormCopy.label("delay"))
            .help(HohenheimFormCopy.help("delay")).build());

    // Environment variables as an ordered name -> value map
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables").label(HohenheimFormCopy.label("environment_variables"))
            .help(HohenheimFormCopy.help("environment_variables")).build());

    public static final ListField<String> API_KEYS = SETTINGS_SCHEMA.addField(
        ListField.<String>builder(StringField.builder().name("api_key").build()).name("api_keys")
            .label(HohenheimFormCopy.label("api_keys")).help(HohenheimFormCopy.help("api_keys"))
            .secret().build());

    // Discovered system user ("hohenheim:<username>" registry key); null = current user.
    public static final EnumField USER = SETTINGS_SCHEMA.addField(
        RegistryEnumField.builder("user")
            .registry(SystemUserOptions.REGISTRY)
            .label(HohenheimFormCopy.label("run_as_user"))
            .help(HohenheimFormCopy.help("run_as_user"))
            .build());

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() { return "Command"; }

    @Override
    public String getDescription() { return "Run an arbitrary command as a managed process"; }

    @Override
    public Icon getIcon() { return Icon.of("terminal"); }

    @Override
    public String getColor() { return "orange"; }

    @Override
    public boolean supportsEnvInjection() { return true; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        int siteId = site.get(SiteModel.ID);
        String siteName = site.get(SiteModel.NAME);
        try {
            return new CommandProcessHandler(siteId, siteName, settings);
        } catch (IllegalArgumentException e) {
            // Fail fast: a misconfigured site serves an explicit 503 instead of half-starting.
            return new FaultedSiteHandler(siteId, e.getMessage());
        }
    }

    @Override
    public boolean managedProcessEnvironment() { return true; }

    // -----------------------------------------------------------------------

    static class CommandProcessHandler extends ManagedProcessSiteHandler {

        private final String startCommand;
        private final String workingDirectory;
        private final String portArgument;
        private final SystemUsers.@Nullable RunAsUser runAs;

        CommandProcessHandler(int siteId, String siteName, Map<String, Object> settings) {
            super(siteId, siteName, settings,
                NodeSiteType.getPortAllocator(), NodeSiteType.getProcessMonitor());

            this.startCommand = (String) settings.getOrDefault("start_command", "");
            this.workingDirectory = (String) settings.get("working_directory");
            this.portArgument = (String) settings.get("port_argument");
            this.runAs = WorkloadIdentity.forSite(siteId, settings.get("user"));

            if (!startCommand.isEmpty()) {
                startMinimumServers();
            }
        }

        @Override
        protected List<String> buildCommand(String listenTarget) {
            List<String> cmd = new ArrayList<>();

            // Split command by whitespace (simple tokenization)
            String[] parts = startCommand.split("\\s+");
            Collections.addAll(cmd, parts);

            // Command sites stay on TCP ports (they don't opt into socket transport), so
            // listenTarget is always the port number here.
            if (portArgument != null && !portArgument.isEmpty()) {
                cmd.add(portArgument + "=" + listenTarget);
            }

            return cmd;
        }

        @Override
        protected Map<String, String> buildRuntimeEnvironment(int port) {
            return Map.of();
        }

        @Override
        protected SystemUsers.@Nullable RunAsUser getRunAsUser() {
            return runAs;
        }

        @Override
        protected File getWorkingDirectory() {
            if (workingDirectory == null || workingDirectory.isEmpty()) return null;
            return new File(workingDirectory);
        }
    }
}
