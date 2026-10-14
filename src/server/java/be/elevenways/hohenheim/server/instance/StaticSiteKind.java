package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.app.PutOnlineGroup;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.server.build.StaticSiteBuilder;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.PathField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * A static website from a git repository: its build command runs in a build container on the workload host and its
 * output folder is served by the pinned static server, as releases of the application lane ({@link StaticSiteBuilder}).
 *
 * AIDEV-NOTE: release-managed like {@link ApplicationKind}, so the deploy, the health-gated swap, the rollback, the
 * Deploys tab, the scheduled check and the forge webhook are the application's own; what differs is fixed in
 * {@link #releaseSettings}: the builder, the port and the health path are the kind's, never the record's.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class StaticSiteKind implements InstanceKindHandler {

    public static final Identifier ID = HohenheimIds.id("static_site");

    // Its build command runs in a generated build stage that takes no environment, and the preview builder reads an
    // application's own Dockerfile settings, so it offers neither.
    public static final Schema SETTINGS_SCHEMA =
        GitSourceSchema.addTo(new Schema(), EnumSet.of(GitSourceSchema.Offer.BUILD_COMMAND));

    /** The folder served, relative to the repository root; empty serves the root itself. */
    public static final StringField OUTPUT_DIRECTORY = SETTINGS_SCHEMA.addField(
        PathField.builder().name("output_directory")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("output_directory"))
            .help(HohenheimMicrocopy.HELP.of("output_directory")).build());

    /** The image the build command runs in; empty uses the pinned Node.js image. */
    public static final StringField BUILD_IMAGE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("build_image")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("build_image"))
            .help(HohenheimMicrocopy.HELP.of("build_image")).build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(InstanceKindFields.memoryLimit());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(InstanceKindFields.cpuLimit());

    // AIDEV-NOTE: declared after the fields -- Schema.addSection validates membership against the fields so far.
    static {
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.open(HohenheimFormSections.SOURCE, List.of(
            GitSourceSchema.REPOSITORY_URL, GitSourceSchema.PROVIDER_ID, GitSourceSchema.REPOSITORY,
            GitSourceSchema.BRANCH, GitSourceSchema.BUILD_COMMAND, OUTPUT_DIRECTORY.getName())));
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.BUILD,
            HohenheimFormSections.join(List.of(BUILD_IMAGE.getName()), GitSourceSchema.BUILD_DETAIL)));
        SETTINGS_SCHEMA.addSection(
            HohenheimFormSections.collapsed(HohenheimFormSections.DEPLOYMENT, GitSourceSchema.DELIVERY));
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.RUNTIME, List.of(
            MEMORY_LIMIT_MB.getName(), CPU_LIMIT.getName())));
    }

    /** Never runs itself: each deploy generates a release that serves the built output. */
    @Override public boolean releaseManaged() { return true; }

    /** The static builder, the static server's port and its health path, whatever the record stores. */
    @Override public @NonNull Map<String, Object> releaseSettings() {
        return Map.of("builder", BuildOperationModel.KIND_STATIC,
            "container_port", StaticSiteBuilder.PORT,
            "health_path", StaticSiteBuilder.HEALTH_PATH);
    }

    @Override public @NonNull Identifier typeId() { return ID; }

    @Override public @NonNull String getDisplayName() { return "Static site"; }

    @Override public Icon getIcon() { return Icon.of("file-lines"); }

    @Override public PutOnlineGroup putOnlineGroup() { return PutOnlineGroup.OWN_CODE; }

    @Override public BadgeColor color() { return ColorHue.TEAL; }

    @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

    /** What its first release books, so placement picks a host that holds it. */
    @Override public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
        return ReleaseKind.DEFAULT_FOOTPRINT_MB;
    }

    /** The serving release's published port is what its website's {@code instance} upstream serves. */
    @Override public boolean supportsSiteUpstream() { return true; }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("static_site_owns_no_container"));
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("static_site_owns_no_container"));
    }
}
