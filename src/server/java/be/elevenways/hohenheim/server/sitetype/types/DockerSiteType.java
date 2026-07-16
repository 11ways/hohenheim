package be.elevenways.hohenheim.server.sitetype.types;

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
        StringField.builder().name("dockerfile").label(HohenheimFormCopy.label("dockerfile"))
            .help(HohenheimFormCopy.help("dockerfile")).build());

    // Target server (servers.name); blank/local runs on the local daemon, else a remote host over SSH.
    public static final EnumField SERVER = SETTINGS_SCHEMA.addField(
        RegistryEnumField.builder("server").registry(ServerOptions.REGISTRY)
            .label(HohenheimFormCopy.label("server")).help(HohenheimFormCopy.help("server")).build());

    // Environment variables as an ordered name -> value map
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables").label(HohenheimFormCopy.label("environment_variables"))
            .help(HohenheimFormCopy.help("environment_variables")).build());

    // Persistent named volumes: logical name -> container path. Each entry mounts the
    // named volume hohenheim-site-{id}-vol-{name}, which survives redeploys.
    public static final StringMapField VOLUMES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("volumes").label(HohenheimFormCopy.label("volumes"))
            .help(HohenheimFormCopy.help("volumes")).build());

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

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        Map<String, Object> resolved = new java.util.LinkedHashMap<>(settings);
        resolved.put("server", ServerOptions.nameFromKey(settings.get("server")));
        return new DockerSiteRequestHandler(site.get(SiteModel.ID), resolved);
    }
}
