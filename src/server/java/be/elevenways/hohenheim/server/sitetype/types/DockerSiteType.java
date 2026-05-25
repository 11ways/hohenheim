package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.docker.DockerSiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;

import java.util.Map;

/**
 * Runs a container image as a managed site, reverse-proxied via a published port.
 */
public class DockerSiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "docker");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("image").build());

    public static final StringField TAG = SETTINGS_SCHEMA.addField(
        StringField.builder().name("tag").build());

    public static final IntegerField CONTAINER_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("container_port").build());

    public static final StringField COMMAND = SETTINGS_SCHEMA.addField(
        StringField.builder().name("command").build());

    // Used only for git-sourced sites: path to the Dockerfile within the checkout.
    public static final StringField DOCKERFILE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("dockerfile").build());

    // Target server (servers.name); blank/local runs on the local daemon, else a remote host over SSH.
    public static final StringField SERVER = SETTINGS_SCHEMA.addField(
        StringField.builder().name("server").build());

    // Reuse the same env-var sub-schema shape as the node/command site types.
    public static final Schema ENV_VAR_SCHEMA = new Schema();
    static {
        ENV_VAR_SCHEMA.addField(StringField.builder().name("name").build());
        ENV_VAR_SCHEMA.addField(StringField.builder().name("value").build());
    }

    public static final SchemaField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        SchemaField.builder("environment_variables").subSchema(ENV_VAR_SCHEMA).build());

    @Override
    public String getDisplayName() { return "Docker"; }

    @Override
    public String getDescription() { return "Run a container image as a managed site"; }

    @Override
    public String getIcon() { return "box"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        return new DockerSiteRequestHandler(site.get(SiteModel.ID), settings);
    }
}
