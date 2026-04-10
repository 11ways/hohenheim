package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.model.NodeVersionModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
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

    // Foreign-key reference into node_versions. The admin UI renders a dropdown
    // populated from NodeVersionModel.findActive(). Zero/null means "system default".
    public static final IntegerField NODE_VERSION_ID = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("node_version_id").build());

    public static final BooleanField WAIT_FOR_READY = SETTINGS_SCHEMA.addField(
        BooleanField.builder("wait_for_ready").defaultValue(false).build());

    public static final IntegerField MINIMUM_PROCESSES = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("minimum_processes").build());

    public static final IntegerField MAXIMUM_PROCESSES = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("maximum_processes").build());

    public static final IntegerField DELAY = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("delay").build());

    // Sub-schema for each environment variable entry
    public static final Schema ENV_VAR_SCHEMA = new Schema();
    static {
        ENV_VAR_SCHEMA.addField(StringField.builder().name("name").build());
        ENV_VAR_SCHEMA.addField(StringField.builder().name("value").build());
    }

    // Environment variables stored as a JSON list of {name, value} sub-schemas
    public static final SchemaField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        SchemaField.builder("environment_variables").subSchema(ENV_VAR_SCHEMA).build());

    // API keys for X-Hohenheim-Key header validation — stored as a JSON list of strings
    public static final ListField<String> API_KEYS = SETTINGS_SCHEMA.addField(
        ListField.<String>builder(StringField.builder().name("api_key").build()).name("api_keys").build());

    // Foreign-key reference into system_users. The admin UI renders a dropdown
    // populated from SystemUserModel.findActive(). Zero/null means "current user".
    public static final IntegerField SYSTEM_USER_ID = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("system_user_id").build());

    public static final BooleanField USE_PORTS = SETTINGS_SCHEMA.addField(
        BooleanField.builder("use_ports").defaultValue(true).build());

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
        int siteId = site.get(SiteModel.ID);
        String siteName = site.get(SiteModel.NAME);

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
        private final int resolvedUid;
        private final List<String> defaultArgs;

        NodeProcessHandler(int siteId, String siteName, Map<String, Object> settings,
                           List<String> defaultArgs) {
            super(siteId, siteName, settings, portAllocator, processMonitor);
            this.script = (String) settings.getOrDefault("script", "");
            this.nodePath = resolveNodePath(settings.get("node_version_id"));
            this.resolvedUid = resolveUid(settings.get("system_user_id"));
            this.defaultArgs = defaultArgs;

            // Start minimum servers on creation
            if (!script.isEmpty()) {
                startMinimumServers();
            }
        }

        /**
         * Look up the node binary path by node_version_id. Returns "node" (PATH fallback)
         * if no version is referenced, and falls back to "node" if the referenced row is
         * obsolete or missing — so a stale ID doesn't crash the spawn.
         */
        private static String resolveNodePath(Object nodeVersionIdObj) {
            if (!(nodeVersionIdObj instanceof Integer id) || id <= 0) {
                return "node";
            }
            var model = new NodeVersionModel(HohenheimDatabase.datasource());
            Row row = model.findById(id);
            if (row == null) return "node";
            String path = row.get(NodeVersionModel.PATH);
            return path != null && !path.isBlank() ? path : "node";
        }

        /**
         * Look up the uid by system_user_id. Returns 0 (current user) when no user is
         * referenced or the referenced row is missing/obsolete.
         */
        private static int resolveUid(Object systemUserIdObj) {
            if (!(systemUserIdObj instanceof Integer id) || id <= 0) {
                return 0;
            }
            var model = new SystemUserModel(HohenheimDatabase.datasource());
            Row row = model.findById(id);
            if (row == null) return 0;
            Integer uid = row.get(SystemUserModel.UID);
            return uid != null ? uid : 0;
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
            Map<String, String> result = new LinkedHashMap<>();
            result.put("NODE_ENV", "production");
            return result;
        }

        @Override
        protected int getUid() {
            return resolvedUid;
        }

        @Override
        protected File getWorkingDirectory() {
            if (script == null || script.isEmpty()) return null;
            File scriptFile = new File(script);
            return scriptFile.getParentFile();
        }
    }
}
