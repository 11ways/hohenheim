package be.elevenways.hohenheim.source;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * THE git-source vocabulary, contributed INTO a host schema rather than owning one.
 *
 * AIDEV-NOTE: it stopped being a standalone {@code SCHEMA} on 2026-08-22, when the source
 * moved off the site and onto the workspace and application instance kinds.
 * A Field instance belongs to exactly one Schema, so two kinds cannot share one
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

    /**
     * The checkout-and-build detail a host schema folds away: everything between
     * "which repository" and "what command builds it" that has a working default.
     */
    public static final List<String> BUILD_DETAIL = List.of(
        BUILD_DIRECTORY, BUILD_TIMEOUT, BUILD_ENVIRONMENT_VARIABLES, SHALLOW_CLONE, SUBMODULES);

    /** When a new revision is picked up, and what proves the webhook that says so (poll_interval is retired, see addTo). */
    public static final List<String> DELIVERY = List.of(AUTO_DEPLOY, POLL_INTERVAL, WEBHOOK_SECRET);

    /** The preview lane: off by default, and three fields nobody sets while creating. */
    public static final List<String> PREVIEWS = List.of(
        PREVIEWS_ENABLED, PREVIEW_BRANCHES, PREVIEW_ENVIRONMENT_VARIABLES);

    /** The branch a source without a declared one builds. */
    public static final String DEFAULT_BRANCH = "main";

    /**
     * What auto_deploy is until a source stores it: on, as the form's declared default and as every reader's answer.
     *
     * AIDEV-NOTE: ONE value on purpose. The edit form seeds a stored map's missing keys from the declared default, so
     * a declared true beside an absent-reads-off read showed sources that never stored the flag as auto-deploying and
     * turned it on with the next unrelated save. It is true by decision (new sources deploy on push);
     * M011's "keep every stored git source's auto-deploy off" stored an explicit false on every source written while
     * absent still read off, so flipping this changed no existing source.
     */
    private static final boolean AUTO_DEPLOY_DEFAULT = true;

    private GitSourceSchema() {}

    /** @return whether a push to the source's branch deploys it */
    public static boolean autoDeploys(@Nullable Map<String, ?> settings) {
        return RawValues.isOn(settings, AUTO_DEPLOY, AUTO_DEPLOY_DEFAULT);
    }

    /** @return the source's declared branch trimmed, {@link #DEFAULT_BRANCH} when it declares none */
    public static @NonNull String declaredBranch(@NonNull Map<String, ?> settings) {
        String branch = RawValues.trimmed(settings.get(BRANCH));
        return branch.isEmpty() ? DEFAULT_BRANCH : branch;
    }

    /**
     * Add the git-source fields to a kind's settings schema, the preview lane included.
     *
     * @return the same schema, so a kind can chain its own fields after
     */
    public static @NonNull Schema addTo(@NonNull Schema schema) {
        return addTo(schema, true);
    }

    /**
     * Add the git-source fields to a kind's settings schema.
     *
     * @param offersPreviews false for a kind the preview lane refuses (it only builds applications): its preview fields
     *                       stay DECLARED, so a stored value reads and the closed-world coercion accepts an API request
     *                       sending them, but no edit view offers a switch that cannot work
     * @return the same schema, so a kind can chain its own fields after
     */
    public static @NonNull Schema addTo(@NonNull Schema schema, boolean offersPreviews) {

        // Deliberately NOT secret(): a credentialed https://user:TOKEN@host URL is REFUSED at
        // the write (InstanceDeclarations, GitRepository.embeddedCredential), and provider
        // tokens travel in the exec environment, so a stored value is credential-free by
        // invariant. It never was encrypted at rest (inside a JSON SchemaField it cannot be,
        // Schema.refuseEncryptedJsonSubFields), so the only thing secret() did was hide the
        // value from the form: a stored URL rendered as a mask, and a refused submit threw
        // the just-typed URL away instead of showing it beside its violation.
        schema.addField(StringField.builder().name(REPOSITORY_URL)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("repository_url"))
            .help(HohenheimMicrocopy.HELP.of("repository_url")).build());

        // Provider binding: when set, the clone URL derives from the provider + repository
        // and per-operation credentials are minted by GitProviders (never embedded in the
        // URL, which GitRepository refuses). The marker field classes derive the admin
        // picker entries (GitPickerFormEntries): provider select, then a repository picker
        // following it, then a branch picker following both.
        schema.addField(GitProviderRefField.builder(PROVIDER_ID)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("git_provider"))
            .help(HohenheimMicrocopy.HELP.of("git_provider")).build());

        schema.addField(GitRepositoryField.builder(REPOSITORY)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("repository"))
            .help(HohenheimMicrocopy.HELP.of("repository")).build());

        schema.addField(GitBranchField.builder(BRANCH)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("branch"))
            .help(HohenheimMicrocopy.HELP.of("branch")).build());

        schema.addField(StringField.builder().name(BUILD_COMMAND)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("build_command"))
            .help(HohenheimMicrocopy.HELP.of("build_command")).build());

        schema.addField(PathField.builder().name(BUILD_DIRECTORY)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("build_directory"))
            .help(HohenheimMicrocopy.HELP.of("build_directory")).build());

        schema.addField(IntegerField.builder().name(BUILD_TIMEOUT).suffix("s")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("build_timeout"))
            .help(HohenheimMicrocopy.HELP.of("build_timeout")).build());

        schema.addField(BooleanField.builder(AUTO_DEPLOY).defaultValue(AUTO_DEPLOY_DEFAULT)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("auto_deploy"))
            .help(HohenheimMicrocopy.HELP.of("auto_deploy")).build());

        // AIDEV-NOTE: RETIRED. Nothing ever polled a repository: a new revision arrives by
        // the webhook (auto_deploy + webhook_secret), and inventing a poller was decided
        // against. The field stays DECLARED so a stored value still reads and an
        // API request that still sends settings.poll_interval is still accepted (the schema
        // is closed-world: an undeclared key would become an unknown_field refusal). It is
        // visible in NO edit view, so no form offers a setting that does nothing.
        schema.addField(IntegerField.builder().name(POLL_INTERVAL).suffix("s")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("poll_interval"))
            .help(HohenheimMicrocopy.HELP.of("poll_interval"))
            .attribute(FieldAttributes.VISIBLE_IN, EnumSet.noneOf(EditView.class)).build());

        schema.addField(StringField.builder().name(WEBHOOK_SECRET).secret()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("webhook_secret"))
            .help(HohenheimMicrocopy.HELP.of("webhook_secret")).build());

        schema.addField(BooleanField.builder(SHALLOW_CLONE).defaultValue(true)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("shallow_clone"))
            .help(HohenheimMicrocopy.HELP.of("shallow_clone")).build());

        schema.addField(BooleanField.builder(SUBMODULES).defaultValue(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("submodules"))
            .help(HohenheimMicrocopy.HELP.of("submodules")).build());

        // Build-only environment variables as an ordered name -> value map.
        // secret(): redacted on derived surfaces.
        schema.addField(StringMapField.builder(BUILD_ENVIRONMENT_VARIABLES)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("build_environment_variables"))
            .help(HohenheimMicrocopy.HELP.of("build_environment_variables")).secret().build());

        /* Opt-in: pull-request webhook events create/update/destroy preview deployments. */
        schema.addField(previewLane(BooleanField.builder(PREVIEWS_ENABLED).defaultValue(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("previews_enabled"))
            .help(HohenheimMicrocopy.HELP.of("previews_enabled")), offersPreviews).build());

        // Per-BRANCH previews are opt-in PER PATTERN, never on by default: a default that
        // mints a preview per pushed branch is a build + container + hostname the owner
        // never asked for, charged against the same per-owner cap the pull-request lane
        // uses -- three stale branches would silently lock out the PR previews the owner
        // DID opt into. Empty list = pull-request previews only.
        schema.addField(previewLane(ListField.builder(StringField.builder().name("pattern").build())
            .name(PREVIEW_BRANCHES)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("preview_branches"))
            .help(HohenheimMicrocopy.HELP.of("preview_branches")), offersPreviews).build());

        // The ONLY runtime environment a preview receives. Previews deliberately inherit
        // NOTHING from the production runtime: not environment_variables, not injected
        // database credentials, not volumes -- a preview builds arbitrary branch code and
        // must never see production secrets or data by default.
        schema.addField(previewLane(StringMapField.builder(PREVIEW_ENVIRONMENT_VARIABLES)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("preview_environment_variables"))
            .help(HohenheimMicrocopy.HELP.of("preview_environment_variables")).secret(), offersPreviews).build());

        return schema;
    }

    /** A preview field as declared: offered as is, or visible in no view where the kind has no preview lane. */
    private static <B extends Field.Builder<?, ?, ?, B>> @NonNull B previewLane(@NonNull B builder, boolean offered) {
        return offered ? builder : builder.attribute(FieldAttributes.VISIBLE_IN, EnumSet.noneOf(EditView.class));
    }
}
