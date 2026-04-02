package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.PortAllocator;
import be.elevenways.hohenheim.server.process.ProcessMonitor;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;

import java.io.File;
import java.util.*;

/**
 * Manages Node.js child processes.
 */
public class NodeSiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "node");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField SCRIPT = SETTINGS_SCHEMA.addField(
        StringField.builder().name("script").build());

    public static final StringField NODE_PATH = SETTINGS_SCHEMA.addField(
        StringField.builder().name("node_path").build());

    public static final BooleanField WAIT_FOR_READY = SETTINGS_SCHEMA.addField(
        BooleanField.builder("wait_for_ready").defaultValue(false).build());

    public static final IntegerField MINIMUM_PROCESSES = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("minimum_processes").build());

    public static final IntegerField MAXIMUM_PROCESSES = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("maximum_processes").build());

    // Environment variables stored as JSON array of {name, value} objects
    public static final SchemaField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        SchemaField.builder("environment_variables").build());

    // Shared infrastructure (singleton per server)
    private static PortAllocator portAllocator;
    private static ProcessMonitor processMonitor;

    public static void initSharedInfrastructure() {
        portAllocator = new PortAllocator();
        processMonitor = new ProcessMonitor();
        processMonitor.start();
    }

    public static void shutdownSharedInfrastructure() {
        if (processMonitor != null) processMonitor.stop();
    }

    public static PortAllocator getPortAllocator() { return portAllocator; }
    public static ProcessMonitor getProcessMonitor() { return processMonitor; }

    @Override
    public String getDisplayName() { return "Node.js"; }

    @Override
    public String getDescription() { return "Manage Node.js child processes"; }

    @Override
    public String getIcon() { return "terminal"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        int siteId = site.get(be.elevenways.hohenheim.model.SiteModel.ID);
        String siteName = site.get(be.elevenways.hohenheim.model.SiteModel.NAME);

        return new NodeProcessHandler(siteId, siteName, settings, getDefaultArgs());
    }

    /**
     * Override in subclasses (AlchemySiteType) to add default args.
     */
    protected List<String> getDefaultArgs() {
        return List.of();
    }

    // -----------------------------------------------------------------------
    // The actual handler
    // -----------------------------------------------------------------------

    protected static class NodeProcessHandler extends ManagedProcessSiteHandler {

        private final String script;
        private final String nodePath;
        private final List<String> defaultArgs;

        NodeProcessHandler(int siteId, String siteName, Map<String, Object> settings,
                           List<String> defaultArgs) {
            super(siteId, siteName, settings, portAllocator, processMonitor);
            this.script = (String) settings.getOrDefault("script", "");
            this.nodePath = (String) settings.getOrDefault("node_path", "node");
            this.defaultArgs = defaultArgs;

            // Start minimum servers on creation
            if (!script.isEmpty()) {
                startMinimumServers();
            }
        }

        @Override
        protected List<String> buildCommand(int port) {
            List<String> cmd = new ArrayList<>();
            cmd.add(nodePath != null && !nodePath.isEmpty() ? nodePath : "node");
            cmd.add(script);
            cmd.add("--port=" + port);

            // Add a marker arg so processes can identify themselves
            cmd.add("hohenchild");

            cmd.addAll(defaultArgs);
            return cmd;
        }

        @Override
        protected Map<String, String> buildRuntimeEnvironment(int port) {
            return Map.of(
                "NODE_ENV", "production"
            );
        }

        @Override
        protected File getWorkingDirectory() {
            if (script == null || script.isEmpty()) return null;
            File scriptFile = new File(script);
            return scriptFile.getParentFile();
        }
    }
}
