package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.hohenheim.server.docker.DockerSiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;

import java.util.Map;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Runs a container image as a managed site, reverse-proxied via a published port.
 */
public class DockerSiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "docker");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("image").label(HohenheimFormCopy.label("image"))
            .help(HohenheimFormCopy.help("image")).build());

    public static final StringField TAG = SETTINGS_SCHEMA.addField(
        StringField.builder().name("tag").label(HohenheimFormCopy.label("image_tag"))
            .help(HohenheimFormCopy.help("image_tag")).build());

    public static final IntegerField CONTAINER_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("container_port").label(HohenheimFormCopy.label("container_port"))
            .help(HohenheimFormCopy.help("container_port")).build());

    public static final StringField COMMAND = SETTINGS_SCHEMA.addField(
        StringField.builder().name("command").label(HohenheimFormCopy.label("container_command"))
            .help(HohenheimFormCopy.help("container_command")).build());

    // Used only for git-sourced sites: path to the Dockerfile within the checkout.
    public static final StringField DOCKERFILE = SETTINGS_SCHEMA.addField(
        PathField.builder().name("dockerfile").label(HohenheimFormCopy.label("dockerfile"))
            .help(HohenheimFormCopy.help("dockerfile")).build());

    // Which builder kind a git-sourced checkout is built with. Nixpacks runs a sandboxed
    // detection phase that emits a Dockerfile, then the same Dockerfile lane runs.
    public static final EnumField BUILDER = SETTINGS_SCHEMA.addField(
        EnumField.builder("builder")
            .value(BuildOperationModel.KIND_DOCKERFILE, "Dockerfile")
            .value(BuildOperationModel.KIND_NIXPACKS, "Nixpacks (buildpack)")
            .defaultValue(BuildOperationModel.KIND_DOCKERFILE)
            .label(HohenheimFormCopy.label("builder"))
            .help(HohenheimFormCopy.help("builder"))
            .build());

    // AIDEV-NOTE: BUILD-time arguments, and deliberately a SEPARATE field from
    // ENVIRONMENT_VARIABLES rather than a reuse of it. The runtime environment carries the
    // workload's secrets (database credentials, API keys) and a sandboxed build never sees
    // it -- the separation is what makes "my build log leaked my database password"
    // structurally impossible. Values here DO reach the build, and a Dockerfile ARG ends up
    // in the image's history, so this field is not a secret channel either.
    public static final StringMapField BUILD_ARGUMENTS = SETTINGS_SCHEMA.addField(
        StringMapField.builder("build_arguments").label(HohenheimFormCopy.label("build_arguments"))
            .help(HohenheimFormCopy.help("build_arguments")).build());

    // Target server (servers.name); blank/local runs on the local daemon, else a remote host over SSH.
    public static final EnumField SERVER = SETTINGS_SCHEMA.addField(
        RegistryEnumField.builder("server").registry(ServerOptions.REGISTRY)
            .label(HohenheimFormCopy.label("server")).help(HohenheimFormCopy.help("server")).build());

    // Environment variables as an ordered name -> value map
    // secret(): redacted on derived surfaces; see NodeSiteType.ENVIRONMENT_VARIABLES.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables").label(HohenheimFormCopy.label("environment_variables"))
            .help(HohenheimFormCopy.help("environment_variables")).secret().build());

    // Persistent named volumes: logical name -> container path. Each entry mounts the
    // SITE's named volume (hohenheim-{token}-site-{siteId}-vol-{name}).
    //
    // AIDEV-NOTE: this comment claimed the volume "survives redeploys", which was false
    // for a health-gated RELEASE until 2026-08-08: the name was derived from the INSTANCE
    // id and SiteReleases.gatedSwap mints a NEW instance row per candidate, so a release
    // mounted a DIFFERENT, EMPTY volume and orphaned the old one (nothing reclaims a
    // volume by design). Volume identity is now keyed to the SITE (SiteVolumes) and a
    // release, a rollback and an in-place replacement all mount the same volume. Keep the
    // history: the reason it hid was that the in-place path reuses the serving row.
    public static final StringMapField VOLUMES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("volumes").label(HohenheimFormCopy.label("volumes"))
            .help(HohenheimFormCopy.help("volumes")).build());

    // Path the release health gate probes on the candidate's published port before it
    // may take traffic (default "/"; any complete HTTP response below 500 passes).
    public static final StringField HEALTH_PATH = SETTINGS_SCHEMA.addField(
        StringField.builder().name("health_path").label(HohenheimFormCopy.label("health_path"))
            .help(HohenheimFormCopy.help("health_path")).build());

    // Optional resource caps (blank = unlimited).
    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb").label(HohenheimFormCopy.label("memory_limit"))
            .help(HohenheimFormCopy.help("memory_limit")).build());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(
        DoubleField.builder().name("cpu_limit").label(HohenheimFormCopy.label("cpu_limit"))
            .help(HohenheimFormCopy.help("cpu_limit")).build());

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() { return "Docker"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("docker").withFilter("scope", "site_type");
    }

    @Override
    public String getDescription() { return "Run a container image as a managed site"; }

    @Override
    public Icon getIcon() { return Icon.of("box"); }

    @Override
    public String getColor() { return "blue"; }

    @Override
    public Schema getSchema() {
        ServerOptions.ensureFresh();
        return SETTINGS_SCHEMA;
    }

    /**
     * Since the isolation wave a Docker site CAN receive database credentials: its
     * release container joins each attached database's link network before start
     * (SiteDatabaseNetworks) and the injected variables carry the container-network
     * address style (DatabaseEnvInjection.Style.CONTAINER_NETWORK), never 127.0.0.1.
     */
    @Override
    public boolean supportsEnvInjection() { return true; }

    @Override
    public boolean containerRuntime() { return true; }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        Map<String, Object> resolved = new java.util.LinkedHashMap<>(settings);
        resolved.put("server", ServerOptions.nameFromKey(settings.get("server")));
        return new DockerSiteRequestHandler(site.get(SiteModel.ID),
            site.get(SiteModel.NAME), resolved);
    }
}
