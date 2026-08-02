package be.elevenways.hohenheim.source;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * Schema for git source settings, stored in SiteModel.SOURCE_SETTINGS.
 */
public class GitSourceSchema {

    public static final Schema SCHEMA = new Schema();

    // secret(): a private-repo clone URL routinely embeds https://user:TOKEN@host.
    // Inside a JSON SchemaField it CANNOT be .encrypted() (Schema.refuseEncryptedJsonSubFields);
    // the eventual fix is a separate credential field so the URL itself stays plain.
    public static final StringField REPOSITORY_URL = SCHEMA.addField(
        StringField.builder().name("repository_url").secret()
            .label(HohenheimFormCopy.label("repository_url"))
            .help(HohenheimFormCopy.help("repository_url")).build());

    public static final StringField BRANCH = SCHEMA.addField(
        StringField.builder().name("branch").label(HohenheimFormCopy.label("branch"))
            .help(HohenheimFormCopy.help("branch")).build());

    public static final StringField BUILD_COMMAND = SCHEMA.addField(
        StringField.builder().name("build_command").label(HohenheimFormCopy.label("build_command"))
            .help(HohenheimFormCopy.help("build_command")).build());

    public static final StringField BUILD_DIRECTORY = SCHEMA.addField(
        PathField.builder().name("build_directory").label(HohenheimFormCopy.label("build_directory"))
            .help(HohenheimFormCopy.help("build_directory")).build());

    public static final IntegerField BUILD_TIMEOUT = SCHEMA.addField(
        IntegerField.builder().name("build_timeout").label(HohenheimFormCopy.label("build_timeout"))
            .suffix("s").help(HohenheimFormCopy.help("build_timeout")).build());

    public static final BooleanField AUTO_DEPLOY = SCHEMA.addField(
        BooleanField.builder("auto_deploy").defaultValue(true).label(HohenheimFormCopy.label("auto_deploy"))
            .help(HohenheimFormCopy.help("auto_deploy")).build());

    public static final IntegerField POLL_INTERVAL = SCHEMA.addField(
        IntegerField.builder().name("poll_interval").label(HohenheimFormCopy.label("poll_interval"))
            .suffix("s").help(HohenheimFormCopy.help("poll_interval")).build());

    public static final StringField WEBHOOK_SECRET = SCHEMA.addField(
        StringField.builder().name("webhook_secret").secret().label(HohenheimFormCopy.label("webhook_secret"))
            .help(HohenheimFormCopy.help("webhook_secret")).build());

    public static final BooleanField SHALLOW_CLONE = SCHEMA.addField(
        BooleanField.builder("shallow_clone").defaultValue(true).label(HohenheimFormCopy.label("shallow_clone"))
            .help(HohenheimFormCopy.help("shallow_clone")).build());

    public static final BooleanField SUBMODULES = SCHEMA.addField(
        BooleanField.builder("submodules").defaultValue(false).label(HohenheimFormCopy.label("submodules"))
            .help(HohenheimFormCopy.help("submodules")).build());

    // Build-only environment variables as an ordered name -> value map.
    // secret(): redacted on derived surfaces; see NodeSiteType.ENVIRONMENT_VARIABLES.
    public static final StringMapField BUILD_ENVIRONMENT_VARIABLES = SCHEMA.addField(
        StringMapField.builder("build_environment_variables")
            .label(HohenheimFormCopy.label("build_environment_variables"))
            .help(HohenheimFormCopy.help("build_environment_variables")).secret().build());
}
