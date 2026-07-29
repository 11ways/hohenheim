package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimPaths;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.WorkloadIdentity;
import be.elevenways.hohenheim.server.options.NodeVersionOptions;
import be.elevenways.hohenheim.server.options.SystemUserOptions;
import be.elevenways.hohenheim.server.process.ChildWrapper;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.PortAllocator;
import be.elevenways.hohenheim.server.process.ProcessMonitor;
import be.elevenways.hohenheim.server.process.SocketAllocator;
import be.elevenways.hohenheim.server.sitetype.FaultedSiteHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.PathKind;

import java.io.File;
import java.util.*;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Manages Node.js child processes.
 */
public class NodeSiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "node");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField SCRIPT = SETTINGS_SCHEMA.addField(
        PathField.builder("script").browserSource(HohenheimPaths.SERVER_FILES, PathKind.FILE)
            .label(HohenheimFormCopy.label("script"))
            .help(HohenheimFormCopy.help("script")).build());

    // Discovered node version ("hohenheim:<version>" registry key); null means
    // "node" from PATH. The registry drives the admin dropdown live.
    public static final EnumField NODE = SETTINGS_SCHEMA.addField(
        RegistryEnumField.builder("node")
            .registry(NodeVersionOptions.REGISTRY)
            .label(HohenheimFormCopy.label("node_runtime"))
            .help(HohenheimFormCopy.help("node_runtime"))
            .build());

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

    // Environment variables as an ordered name -> value map.
    // AIDEV-NOTE: secret() is the chosen redaction shape (plan 0.6b, option "a"):
    // the map is the largest plaintext-secret surface in hohenheim (operators put
    // DATABASE_PASSWORD-grade values in it) and it lives in a JSON SchemaField, so
    // encryption is impossible and derived-surface redaction is its ONLY control.
    // Consequences: revision snapshots OMIT the map, activity deltas collapse it to
    // one [redacted] pair (other settings keys still diff per key, and the fact that
    // env changed stays visible), restore keeps the CURRENT values (legacy plaintext
    // revisions can never reactivate), and the admin form still shows the KEYS with
    // per-key keep-if-blank password values. The rejected shape (typed rows with a
    // plain/secret kind, the Phase 3 instance_variables table) would keep per-key
    // diffs for plain vars but needs a storage migration plus an unanswerable
    // which-existing-values-are-secret classification. Same flag on Java/Command/
    // Docker site types and GitSourceSchema.BUILD_ENVIRONMENT_VARIABLES.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables").label(HohenheimFormCopy.label("environment_variables"))
            .help(HohenheimFormCopy.help("environment_variables")).secret().build());

    // API keys for X-Hohenheim-Key header validation; SiteApiKeys stores only
    // sha256 digests, so a typed key is adopted (hashed) and never echoed back.
    public static final ListField<String> API_KEYS = SETTINGS_SCHEMA.addField(
        ListField.<String>builder(StringField.builder().name("api_key").build()).name("api_keys")
            .label(HohenheimFormCopy.label("api_keys")).help(HohenheimFormCopy.help("api_keys"))
            .secret().build());

    // Discovered system user ("hohenheim:<username>" registry key); null means
    // "run as the current user". The registry drives the admin dropdown live.
    public static final EnumField USER = SETTINGS_SCHEMA.addField(
        RegistryEnumField.builder("user")
            .registry(SystemUserOptions.REGISTRY)
            .label(HohenheimFormCopy.label("run_as_user"))
            .help(HohenheimFormCopy.help("run_as_user"))
            .build());

    public static final BooleanField USE_PORTS = SETTINGS_SCHEMA.addField(
        BooleanField.builder("use_ports").defaultValue(false)
            .label(HohenheimFormCopy.label("use_ports")).help(HohenheimFormCopy.help("use_ports")).build());

    // Shared infrastructure (singleton per server)
    private static PortAllocator portAllocator;
    private static SocketAllocator socketAllocator;
    private static ProcessMonitor processMonitor;

    public static void initSharedInfrastructure() {
        portAllocator = new PortAllocator();
        socketAllocator = new SocketAllocator();
        processMonitor = new ProcessMonitor();
        processMonitor.start();
    }

    public static void shutdownSharedInfrastructure() {
        if (processMonitor != null) processMonitor.stop();
    }

    public static PortAllocator getPortAllocator() { return portAllocator; }
    public static SocketAllocator getSocketAllocator() { return socketAllocator; }
    public static ProcessMonitor getProcessMonitor() { return processMonitor; }

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() { return "Node.js"; }

    @Override
    public String getDescription() { return "Manage Node.js child processes"; }

    @Override
    public Icon getIcon() { return Icon.of("terminal"); }

    @Override
    public String getColor() { return "green"; }

    @Override
    public boolean supportsEnvInjection() { return true; }

    @Override
    public boolean managedProcessEnvironment() { return true; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        int siteId = site.get(SiteModel.ID);
        String siteName = site.get(SiteModel.NAME);

        try {
            return new NodeProcessHandler(siteId, siteName, settings, getDefaultArgs(), useChildWrapper());
        } catch (IllegalArgumentException e) {
            // Fail fast: a misconfigured site serves an explicit 503 instead of half-starting.
            return new FaultedSiteHandler(siteId, e.getMessage());
        }
    }

    /**
     * Override in subclasses (AlchemySiteType) to add default args.
     */
    protected List<String> getDefaultArgs() {
        return List.of();
    }

    /**
     * Whether to launch the site through the hohenheim-child-wrapper fork shim.
     * Only needed for frameworks (alchemy) that expect a native Node IPC channel
     * via process.on('message'). Plain Node sites talk to HOHENHEIM_IPC_PORT
     * directly if they care, sending {@code {"type":"auth","token":...}} from
     * HOHENHEIM_IPC_TOKEN as their first line.
     */
    protected boolean useChildWrapper() {
        return false;
    }

    // -----------------------------------------------------------------------
    // The actual handler
    // -----------------------------------------------------------------------

    protected static class NodeProcessHandler extends ManagedProcessSiteHandler {

        private final String script;
        private final String nodePath;
        private final SystemUsers.@Nullable RunAsUser runAs;
        private final List<String> defaultArgs;
        private final boolean useChildWrapper;

        NodeProcessHandler(int siteId, String siteName, Map<String, Object> settings,
                           List<String> defaultArgs, boolean useChildWrapper) {
            super(siteId, siteName, settings, portAllocator, processMonitor);
            this.script = (String) settings.getOrDefault("script", "");
            this.nodePath = NodeVersionOptions.resolvePath(settings.get("node"));
            this.defaultArgs = defaultArgs;
            this.useChildWrapper = useChildWrapper;

            validatePreconditions();

            // After the cheap checks so a blank script still reports as such; refusal
            // (missing/shared/daemon identity) throws IllegalArgumentException here and
            // becomes a FaultedSiteHandler BEFORE any spawn attempt.
            this.runAs = WorkloadIdentity.forSite(siteId, settings.get("user"));

            startMinimumServers();
        }

        /**
         * Fail-fast checks before any spawn attempt.
         *
         * @throws IllegalArgumentException naming the problem; the site type turns it into a
         *                                  FaultedSiteHandler (explicit 503)
         */
        private void validatePreconditions() {
            if (script.isBlank()) {
                throw new IllegalArgumentException("no script configured");
            }
            File scriptFile = new File(script);
            if (!scriptFile.isFile()) {
                throw new IllegalArgumentException("script does not exist: " + script);
            }
            File workDir = scriptFile.getParentFile();
            if (workDir == null || !workDir.isDirectory()) {
                throw new IllegalArgumentException("working directory does not exist for: " + script);
            }
        }

        @Override
        protected List<String> buildCommand(String listenTarget) {
            List<String> cmd = new ArrayList<>();
            cmd.add(nodePath != null && !nodePath.isEmpty() ? nodePath : "node");
            if (useChildWrapper) {
                // Framework (alchemy) expects a native Node IPC channel via
                // process.on('message'). The wrapper forks the real script
                // and bridges HOHENHEIM_IPC_PORT into that channel.
                cmd.add(ChildWrapper.path().toString());
                cmd.add("--hh-exec");
            }
            cmd.add(script);
            // listenTarget is a TCP port number or, when use_ports=false, the unix socket path.
            cmd.add("--port=" + listenTarget);

            // Add a marker arg so processes can identify themselves
            cmd.add("hohenchild");

            cmd.addAll(defaultArgs);
            return cmd;
        }

        @Override
        protected String allocateSocketPath() {
            return socketAllocator.allocate(siteId);
        }

        @Override
        protected void releaseSocketPath(String socketPath) {
            socketAllocator.release(socketPath);
        }

        @Override
        protected Map<String, String> buildRuntimeEnvironment(int port) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("NODE_ENV", "production");
            return result;
        }

        @Override
        protected SystemUsers.@Nullable RunAsUser getRunAsUser() {
            return runAs;
        }

        @Override
        protected File getWorkingDirectory() {
            if (script == null || script.isEmpty()) return null;
            File scriptFile = new File(script);
            return scriptFile.getParentFile();
        }
    }
}
