package be.elevenways.hohenheim.source;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE git-source vocabulary, contributed INTO a host schema rather than owning one.
 *
 * AIDEV-NOTE: it stopped being a standalone {@code SCHEMA} on 2026-08-22, when the source
 * moved off the site and onto the workspace and application instance kinds (phase-0 design
 * section 4). A Field instance belongs to exactly one Schema, so two kinds cannot share one
 * declared set of Field constants -- and a copy-pasted second set is precisely the
 * vocabulary duplication this codebase refuses. {@link #addTo} builds the fields fresh per
 * host schema; the NAMES stay constants here, which is what every reader actually uses.
 */
public final class GitSourceSchema {

    public static final String REPOSITORY_URL = "repository_url";
    public static final String PROVIDER_ID = "provider_id";
    public static final String REPOSITORY = "repository";
    public static final String BRANCH = "branch";
    public static final String BUILD_COMMAND = "build_command";
    public static final String BUILD_DIRECTORY = "build_directory";
    public static final String BUILD_TIMEOUT = "build_timeout";
    public static final String AUTO_DEPLOY = "auto_deploy";
    public static final String POLL_INTERVAL = "poll_interval";
    public static final String WEBHOOK_SECRET = "webhook_secret";
    public static final String SHALLOW_CLONE = "shallow_clone";
    public static final String SUBMODULES = "submodules";
    public static final String BUILD_ENVIRONMENT_VARIABLES = "build_environment_variables";
    public static final String PREVIEWS_ENABLED = "previews_enabled";
    public static final String PREVIEW_BRANCHES = "preview_branches";
    public static final String PREVIEW_ENVIRONMENT_VARIABLES = "preview_environment_variables";

    private GitSourceSchema() {}

    /**
     * Add the git-source fields to a kind's settings schema.
     *
     * @return the same schema, so a kind can chain its own fields after
     */
    public static @NonNull Schema addTo(@NonNull Schema schema) {

        // secret(): a private-repo clone URL routinely embeds https://user:TOKEN@host.
        // Inside a JSON SchemaField it CANNOT be .encrypted() (Schema.refuseEncryptedJsonSubFields);
        // the eventual fix is a separate credential field so the URL itself stays plain.
        schema.addField(StringField.builder().name(REPOSITORY_URL).secret()
            .label(HohenheimFormCopy.label("repository_url"))
            .help(HohenheimFormCopy.help("repository_url")).build());

        // Provider binding: when set, the clone URL derives from the provider + repository
        // and per-operation credentials are minted by GitProviders (never embedded in the
        // URL, which GitRepository refuses). The marker field classes derive the admin
        // picker entries (GitPickerFormEntries): provider select, then a repository picker
        // following it, then a branch picker following both.
        schema.addField(GitProviderRefField.builder(PROVIDER_ID)
            .label(HohenheimFormCopy.label("git_provider"))
            .help(HohenheimFormCopy.help("git_provider")).build());

        schema.addField(GitRepositoryField.builder(REPOSITORY)
            .label(HohenheimFormCopy.label("repository"))
            .help(HohenheimFormCopy.help("repository")).build());

        schema.addField(GitBranchField.builder(BRANCH)
            .label(HohenheimFormCopy.label("branch"))
            .help(HohenheimFormCopy.help("branch")).build());

        schema.addField(StringField.builder().name(BUILD_COMMAND)
            .label(HohenheimFormCopy.label("build_command"))
            .help(HohenheimFormCopy.help("build_command")).build());

        schema.addField(PathField.builder().name(BUILD_DIRECTORY)
            .label(HohenheimFormCopy.label("build_directory"))
            .help(HohenheimFormCopy.help("build_directory")).build());

        schema.addField(IntegerField.builder().name(BUILD_TIMEOUT).suffix("s")
            .label(HohenheimFormCopy.label("build_timeout"))
            .help(HohenheimFormCopy.help("build_timeout")).build());

        schema.addField(BooleanField.builder(AUTO_DEPLOY).defaultValue(true)
            .label(HohenheimFormCopy.label("auto_deploy"))
            .help(HohenheimFormCopy.help("auto_deploy")).build());

        schema.addField(IntegerField.builder().name(POLL_INTERVAL).suffix("s")
            .label(HohenheimFormCopy.label("poll_interval"))
            .help(HohenheimFormCopy.help("poll_interval")).build());

        schema.addField(StringField.builder().name(WEBHOOK_SECRET).secret()
            .label(HohenheimFormCopy.label("webhook_secret"))
            .help(HohenheimFormCopy.help("webhook_secret")).build());

        schema.addField(BooleanField.builder(SHALLOW_CLONE).defaultValue(true)
            .label(HohenheimFormCopy.label("shallow_clone"))
            .help(HohenheimFormCopy.help("shallow_clone")).build());

        schema.addField(BooleanField.builder(SUBMODULES).defaultValue(false)
            .label(HohenheimFormCopy.label("submodules"))
            .help(HohenheimFormCopy.help("submodules")).build());

        // Build-only environment variables as an ordered name -> value map.
        // secret(): redacted on derived surfaces.
        schema.addField(StringMapField.builder(BUILD_ENVIRONMENT_VARIABLES)
            .label(HohenheimFormCopy.label("build_environment_variables"))
            .help(HohenheimFormCopy.help("build_environment_variables")).secret().build());

        /* Opt-in: pull-request webhook events create/update/destroy preview deployments. */
        schema.addField(BooleanField.builder(PREVIEWS_ENABLED).defaultValue(false)
            .label(HohenheimFormCopy.label("previews_enabled"))
            .help(HohenheimFormCopy.help("previews_enabled")).build());

        // Per-BRANCH previews are opt-in PER PATTERN, never on by default: a default that
        // mints a preview per pushed branch is a build + container + hostname the owner
        // never asked for, charged against the same per-owner cap the pull-request lane
        // uses -- three stale branches would silently lock out the PR previews the owner
        // DID opt into. Empty list = pull-request previews only.
        schema.addField(ListField.builder(StringField.builder().name("pattern").build())
            .name(PREVIEW_BRANCHES)
            .label(HohenheimFormCopy.label("preview_branches"))
            .help(HohenheimFormCopy.help("preview_branches")).build());

        // The ONLY runtime environment a preview receives. Previews deliberately inherit
        // NOTHING from the production runtime: not environment_variables, not injected
        // database credentials, not volumes -- a preview builds arbitrary branch code and
        // must never see production secrets or data by default.
        schema.addField(StringMapField.builder(PREVIEW_ENVIRONMENT_VARIABLES)
            .label(HohenheimFormCopy.label("preview_environment_variables"))
            .help(HohenheimFormCopy.help("preview_environment_variables")).secret().build());

        return schema;
    }
}
