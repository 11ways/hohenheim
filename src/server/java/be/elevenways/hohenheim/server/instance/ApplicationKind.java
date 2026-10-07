package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.app.PutOnlineGroup;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.instance.ConsoleKind;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.PathField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.validation.validator.Range;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * The AUTHORED half of a deployed app: source, build, runtime image, variables, volumes,
 * declared port and retention. It is never itself a container -- each deploy generates a
 * {@code hohenheim:release} instance, and this record's status reflects its serving one.
 *
 * AIDEV-NOTE: two records, not one (phase-0 design section 4.2). The thing an operator
 * EDITS must stay editable while an immutable per-deploy container keeps serving and one
 * retired container stays available for rollback; folding them into one record is what
 * would make "edit the app" and "the release that is running" the same row, and there is
 * no shape in which that is true during a gated swap.
 *
 * AIDEV-NOTE: {@link #runtimeFor}/{@link #specFor} refuse by name PERMANENTLY -- the
 * release engine ({@code ApplicationReleases}/{@code ReleaseEngine}, re-keyed here in
 * phase 0 brief 7) builds specs for the generated release instances; this record never
 * has a driver of its own.
 */
public final class ApplicationKind implements InstanceKindHandler {

    public static final Identifier ID = HohenheimIds.id("application");
    public static final Schema SETTINGS_SCHEMA = GitSourceSchema.addTo(new Schema());

    /**
     * A prebuilt image to release instead of building one.
     *
     * AIDEV-NOTE: an application does not have to be git-sourced. Naming an image here gets
     * the SAME health-gated swap, digest pin and rollback as a built one -- the difference is
     * only where the artifact comes from. {@code docker_container} stays the kind for a
     * one-off container that wants none of that; this is for a released one.
     */
    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("image").label(HohenheimFormCopy.label("image"))
            .help(HohenheimFormCopy.help("image")).build());

    public static final StringField TAG = SETTINGS_SCHEMA.addField(
        StringField.builder().name("tag").label(HohenheimFormCopy.label("image_tag"))
            .help(HohenheimFormCopy.help("image_tag")).build());

    /** Which builder a checkout is built with; Nixpacks emits a Dockerfile, then one lane. */
    public static final EnumField BUILDER = SETTINGS_SCHEMA.addField(
        EnumField.builder("builder")
            .value(BuildOperationModel.KIND_DOCKERFILE, "Dockerfile",
                BuildOperationModel.kindLabel(BuildOperationModel.KIND_DOCKERFILE))
            .value(BuildOperationModel.KIND_NIXPACKS, "Nixpacks (buildpack)",
                BuildOperationModel.kindLabel(BuildOperationModel.KIND_NIXPACKS))
            .defaultValue(BuildOperationModel.KIND_DOCKERFILE)
            .label(HohenheimFormCopy.label("builder"))
            .help(HohenheimFormCopy.help("builder"))
            .build());

    /** Path to the Dockerfile within the checkout. */
    public static final StringField DOCKERFILE = SETTINGS_SCHEMA.addField(
        PathField.builder().name("dockerfile")
            .label(HohenheimFormCopy.label("dockerfile"))
            .help(HohenheimFormCopy.help("dockerfile")).build());

    // AIDEV-NOTE: BUILD-time arguments, and deliberately a SEPARATE field from
    // ENVIRONMENT_VARIABLES rather than a reuse of it. The runtime environment carries the
    // workload's secrets (database credentials, API keys) and a sandboxed build never sees
    // it -- the separation is what makes "my build log leaked my database password"
    // structurally impossible. Values here DO reach the build, and a Dockerfile ARG ends up
    // in the image's history, so this field is not a secret channel either.
    public static final StringMapField BUILD_ARGUMENTS = SETTINGS_SCHEMA.addField(
        StringMapField.builder("build_arguments")
            .label(HohenheimFormCopy.label("build_arguments"))
            .help(HohenheimFormCopy.help("build_arguments")).build());

    public static final IntegerField CONTAINER_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("container_port")
            .label(HohenheimFormCopy.label("container_port"))
            .help(HohenheimFormCopy.help("instance_container_port")).build());

    public static final StringField COMMAND = SETTINGS_SCHEMA.addField(
        StringField.builder().name("command")
            .label(HohenheimFormCopy.label("container_command"))
            .help(HohenheimFormCopy.help("container_command")).build());

    public static final StringField WORKDIR = SETTINGS_SCHEMA.addField(
        PathField.builder().name("workdir")
            .label(HohenheimFormCopy.label("workdir")).build());

    /** Path the release health gate probes before a candidate may take traffic. */
    public static final StringField HEALTH_PATH = SETTINGS_SCHEMA.addField(
        StringField.builder().name("health_path")
            .label(HohenheimFormCopy.label("health_path"))
            .help(HohenheimFormCopy.help("health_path")).build());

    // secret(): redacted on derived surfaces, masked in forms, kept on blank submit.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables")
            .label(HohenheimFormCopy.label("environment_variables"))
            .help(HohenheimFormCopy.help("environment_variables")).secret().build());

    /**
     * RETIRED: the release engine keeps exactly one release besides the serving one (its one-step rollback target), so
     * this number never chose anything.
     *
     * AIDEV-NOTE: kept DECLARED, the poll_interval way (GitSourceSchema), so a stored value still reads and an API
     * request still sending settings.keep_releases is still accepted (the settings schema is closed-world); visible in
     * NO edit view, so no form offers a setting that does nothing.
     */
    public static final IntegerField KEEP_RELEASES = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("keep_releases").defaultValue(2)
            .validator(Range.of(1, 10))
            .label(HohenheimFormCopy.label("keep_releases"))
            .help(HohenheimFormCopy.help("keep_releases"))
            .attribute(FieldAttributes.VISIBLE_IN, EnumSet.noneOf(EditView.class)).build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb")
            .label(HohenheimFormCopy.label("memory_limit"))
            .help(HohenheimFormCopy.help("memory_limit")).build());

    /**
     * Which console every release's primary process gets; copied onto each release's
     * settings by {@code ApplicationReleases}. {@link ConsoleKind} is the vocabulary's home.
     */
    public static final EnumField CONSOLE_KIND = SETTINGS_SCHEMA.addField(
        ConsoleKind.fieldBuilder(ConsoleKind.SETTING)
            .label(HohenheimFormCopy.label("console_kind"))
            .help(HohenheimFormCopy.help("console_kind")).build());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(
        DoubleField.builder().name("cpu_limit")
            .label(HohenheimFormCopy.label("cpu_limit"))
            .help(HohenheimFormCopy.help("cpu_limit")).build());

    // What a reader of a running app comes to change -- its variables per lane, and where the
    // code comes from with how it builds, deploys and previews -- stays open; the build and
    // deploy details and the runtime ceilings all have defaults, so they fold under headers
    // that say what is inside. AIDEV-NOTE: declared after the fields -- Schema.addSection
    // validates membership against the fields declared so far.
    static {
        // The record's Configuration reads as the board's two decisions first: where the code comes from with how it
        // builds, deploys and previews, then the variables per lane. AIDEV-NOTE: source before variables (the board
        // draws variables first) because this one schema also renders the CREATE form, where the repository is the
        // first decision and no variable can be meaningful yet.
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.open(HohenheimFormSections.SOURCE, List.of(
            GitSourceSchema.REPOSITORY_URL, GitSourceSchema.PROVIDER_ID, GitSourceSchema.REPOSITORY,
            GitSourceSchema.BRANCH, BUILDER.getName(), GitSourceSchema.BUILD_COMMAND, GitSourceSchema.BUILD_DIRECTORY,
            IMAGE.getName(), TAG.getName(), CONTAINER_PORT.getName(),
            GitSourceSchema.AUTO_DEPLOY, GitSourceSchema.PREVIEWS_ENABLED, GitSourceSchema.PREVIEW_BRANCHES)));
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.open(HohenheimFormSections.VARIABLES,
            HohenheimFormCopy.section("variables_description"), List.of(
            ENVIRONMENT_VARIABLES.getName(), GitSourceSchema.BUILD_ENVIRONMENT_VARIABLES,
            GitSourceSchema.PREVIEW_ENVIRONMENT_VARIABLES)));
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.BUILD, List.of(
            DOCKERFILE.getName(), BUILD_ARGUMENTS.getName(), GitSourceSchema.BUILD_TIMEOUT,
            GitSourceSchema.SHALLOW_CLONE, GitSourceSchema.SUBMODULES)));
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.DEPLOYMENT, List.of(
            GitSourceSchema.WEBHOOK_SECRET, GitSourceSchema.POLL_INTERVAL, HEALTH_PATH.getName(),
            KEEP_RELEASES.getName())));
        SETTINGS_SCHEMA.addSection(
            HohenheimFormSections.collapsed(HohenheimFormSections.RUNTIME, List.of(
                MEMORY_LIMIT_MB.getName(), CPU_LIMIT.getName(),
                CONSOLE_KIND.getName(), COMMAND.getName(), WORKDIR.getName())));
    }

    /**
     * An application never runs: each deploy generates a {@code hohenheim:release} instance
     * and this record's status reflects the one that serves.
     */
    @Override public boolean releaseManaged() { return true; }

    @Override public @NonNull Identifier typeId() { return ID; }

    @Override public @NonNull String getDisplayName() { return "Application"; }

    @Override public @NonNull Microcopy getLabel() {
        return Microcopy.of("application").withFilter("scope", "instance_kind");
    }

    @Override public @NonNull Microcopy getDescription() {
        return Microcopy.of("application").withFilter("scope", "instance_kind_description");
    }

    @Override public Icon getIcon() { return Icon.of("rocket"); }

    @Override public PutOnlineGroup putOnlineGroup() { return PutOnlineGroup.OWN_CODE; }

    @Override public BadgeColor color() { return ColorHue.INDIGO; }

    @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

    /**
     * A gated swap runs the candidate BESIDE the serving release, so the authored record is
     * charged for both -- the same number DockerContainerKind charges, doubled.
     */
    @Override public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
        return 1024;
    }

    /** Its declared volumes mount into every release; placement demands quota via the default. */
    @Override public boolean supportsVolumes() { return true; }

    /** May name a runtime image as its build base; optional, unlike the workspace. */
    @Override public boolean usesRuntimeImage() { return true; }

    /** The serving release's published port is what a site's {@code instance} upstream serves. */
    @Override public boolean supportsSiteUpstream() { return true; }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        throw notWired();
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        throw notWired();
    }

    private static Violations notWired() {
        return Violations.ofForm(HohenheimViolations.text("application_owns_no_container"));
    }
}
