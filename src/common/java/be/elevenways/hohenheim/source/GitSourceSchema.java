package be.elevenways.hohenheim.source;

import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * Schema for git source settings, stored in SiteModel.SOURCE_SETTINGS.
 */
public class GitSourceSchema {

    public static final Schema SCHEMA = new Schema();

    public static final StringField REPOSITORY_URL = SCHEMA.addField(
        StringField.builder().name("repository_url").build());

    public static final StringField BRANCH = SCHEMA.addField(
        StringField.builder().name("branch").build());

    public static final StringField BUILD_COMMAND = SCHEMA.addField(
        StringField.builder().name("build_command").build());

    public static final StringField BUILD_DIRECTORY = SCHEMA.addField(
        StringField.builder().name("build_directory").build());

    public static final IntegerField BUILD_TIMEOUT = SCHEMA.addField(
        IntegerField.builder().name("build_timeout").build());

    public static final BooleanField AUTO_DEPLOY = SCHEMA.addField(
        BooleanField.builder("auto_deploy").defaultValue(true).build());

    public static final IntegerField POLL_INTERVAL = SCHEMA.addField(
        IntegerField.builder().name("poll_interval").build());

    public static final StringField WEBHOOK_SECRET = SCHEMA.addField(
        StringField.builder().name("webhook_secret").build());

    public static final BooleanField SHALLOW_CLONE = SCHEMA.addField(
        BooleanField.builder("shallow_clone").defaultValue(true).build());

    public static final BooleanField SUBMODULES = SCHEMA.addField(
        BooleanField.builder("submodules").defaultValue(false).build());

    // Sub-schema for build-only environment variables
    public static final Schema BUILD_ENV_VAR_SCHEMA = new Schema();
    static {
        BUILD_ENV_VAR_SCHEMA.addField(StringField.builder().name("name").build());
        BUILD_ENV_VAR_SCHEMA.addField(StringField.builder().name("value").build());
    }

    public static final SchemaField BUILD_ENVIRONMENT_VARIABLES = SCHEMA.addField(
        SchemaField.builder("build_environment_variables")
            .subSchema(BUILD_ENV_VAR_SCHEMA)
            .build());
}
