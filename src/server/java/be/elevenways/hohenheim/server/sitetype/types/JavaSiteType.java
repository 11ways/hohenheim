package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.options.SystemUserOptions;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.sitetype.FaultedSiteHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;

import java.io.File;
import java.util.*;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Runs a JVM application (a fat/shadow jar) as a managed process; a Zenit app
 * picks up its listen port from the injected {@code ZENIT__NETWORK__PORT}
 * environment override, so no port argument is threaded onto the command line.
 */
public class JavaSiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "java");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField JAR_PATH = SETTINGS_SCHEMA.addField(
        StringField.builder().name("jar_path").placeholder("/opt/app/app.jar")
            .label(HohenheimFormCopy.label("jar_path")).help(HohenheimFormCopy.help("jar_path")).build());

    public static final StringField JAVA_BINARY = SETTINGS_SCHEMA.addField(
        StringField.builder().name("java_binary").placeholder("java")
            .label(HohenheimFormCopy.label("java_binary")).help(HohenheimFormCopy.help("java_binary")).build());

    public static final StringField JVM_ARGS = SETTINGS_SCHEMA.addField(
        StringField.builder().name("jvm_args").placeholder("-Xmx512m")
            .label(HohenheimFormCopy.label("jvm_args")).help(HohenheimFormCopy.help("jvm_args")).build());

    public static final StringField APP_ARGS = SETTINGS_SCHEMA.addField(
        StringField.builder().name("app_args")
            .label(HohenheimFormCopy.label("app_args")).help(HohenheimFormCopy.help("app_args")).build());

    public static final StringField WORKING_DIRECTORY = SETTINGS_SCHEMA.addField(
        StringField.builder().name("working_directory")
            .label(HohenheimFormCopy.label("working_directory")).help(HohenheimFormCopy.help("working_directory")).build());

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
        IntegerField.builder().name("delay").label(HohenheimFormCopy.label("delay"))
            .help(HohenheimFormCopy.help("delay")).build());

    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables").label(HohenheimFormCopy.label("environment_variables"))
            .help(HohenheimFormCopy.help("environment_variables")).build());

    public static final ListField<String> API_KEYS = SETTINGS_SCHEMA.addField(
        ListField.<String>builder(StringField.builder().name("api_key").build()).name("api_keys")
            .label(HohenheimFormCopy.label("api_keys")).help(HohenheimFormCopy.help("api_keys")).build());

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
    public String getDisplayName() { return "Java / Zenit"; }

    @Override
    public String getDescription() { return "Run a JVM application (fat jar); Zenit apps get their port injected"; }

    @Override
    public Icon getIcon() { return Icon.of("box"); }

    @Override
    public String getColor() { return "red"; }

    @Override
    public boolean supportsEnvInjection() { return true; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        int siteId = site.get(SiteModel.ID);
        String siteName = site.get(SiteModel.NAME);
        try {
            return new JavaProcessHandler(siteId, siteName, settings);
        } catch (IllegalArgumentException e) {
            return new FaultedSiteHandler(siteId, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------

    static class JavaProcessHandler extends ManagedProcessSiteHandler {

        private final String jarPath;
        private final String javaBinary;
        private final String jvmArgs;
        private final String appArgs;
        private final String workingDirectory;
        private final SystemUsers.@Nullable RunAsUser runAs;

        JavaProcessHandler(int siteId, String siteName, Map<String, Object> settings) {
            super(siteId, siteName, settings,
                NodeSiteType.getPortAllocator(), NodeSiteType.getProcessMonitor());

            this.jarPath = (String) settings.getOrDefault("jar_path", "");
            this.javaBinary = (String) settings.getOrDefault("java_binary", "");
            this.jvmArgs = (String) settings.getOrDefault("jvm_args", "");
            this.appArgs = (String) settings.getOrDefault("app_args", "");
            this.workingDirectory = (String) settings.get("working_directory");
            this.runAs = SystemUsers.resolve(settings.get("user"));

            validatePreconditions();
            startMinimumServers();
        }

        private void validatePreconditions() {
            if (jarPath.isBlank()) {
                throw new IllegalArgumentException("no jar configured");
            }
            if (!new File(jarPath).isFile()) {
                throw new IllegalArgumentException("jar does not exist: " + jarPath);
            }
        }

        @Override
        protected List<String> buildCommand(String listenTarget) {
            // The port reaches the app via ZENIT__NETWORK__PORT, so listenTarget
            // is intentionally not on the command line.
            List<String> cmd = new ArrayList<>();
            cmd.add(!javaBinary.isBlank() ? javaBinary : "java");
            addTokens(cmd, jvmArgs);
            cmd.add("-jar");
            cmd.add(jarPath);
            addTokens(cmd, appArgs);
            return cmd;
        }

        private static void addTokens(List<String> cmd, String raw) {
            if (raw != null && !raw.isBlank()) {
                Collections.addAll(cmd, raw.trim().split("\\s+"));
            }
        }

        @Override
        protected Map<String, String> buildRuntimeEnvironment(int port) {
            Map<String, String> result = new LinkedHashMap<>();
            // Zenit reads these overrides (EnvSettingsSource); a plain JVM app
            // that ignores them can still read PORT from its own env.
            result.put("ZENIT__NETWORK__PORT", String.valueOf(port));
            result.put("ZENIT__NETWORK__BIND_ADDRESS", "127.0.0.1");
            result.put("PORT", String.valueOf(port));
            return result;
        }

        @Override
        protected SystemUsers.@Nullable RunAsUser getRunAsUser() {
            return runAs;
        }

        @Override
        protected File getWorkingDirectory() {
            if (workingDirectory != null && !workingDirectory.isBlank()) {
                File dir = new File(workingDirectory);
                if (!dir.isDirectory()) {
                    // Fault instead of silently running elsewhere (the Node posture).
                    throw new IllegalArgumentException(
                        "working directory does not exist: " + workingDirectory);
                }
                return dir;
            }
            File parent = new File(jarPath).getAbsoluteFile().getParentFile();
            if (parent == null || !parent.isDirectory()) {
                throw new IllegalArgumentException("working directory does not exist for: " + jarPath);
            }
            return parent;
        }
    }
}
