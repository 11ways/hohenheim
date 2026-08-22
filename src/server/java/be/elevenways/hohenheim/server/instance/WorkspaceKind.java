package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;
import java.util.Set;

/**
 * A persistent development box: one container per workspace, on Docker or Incus, started
 * from a RUNTIME IMAGE, with a Hohenheim-owned host directory as its {@code /home/site}
 * and everything outside it disposable.
 *
 * AIDEV-NOTE: this is NOT {@link SystemContainerKind} with a mount. A system container is a
 * rootfs-persistent pet box (Proxmox-style) and stays its own kind; a workspace's rootfs is
 * DISPOSABLE and only its data volume survives, which is a different promise to the person
 * using it and a different reclaim story for the operator.
 *
 * AIDEV-NOTE: model-level only in phase 0 brief 5 -- the settings, the runtime set and the
 * placement refusal are real and enforced, {@link #runtimeFor} and {@link #specFor} are
 * not wired until brief 8 (uid mapping, bind-source hardening, GitCheckout/WorkspaceBuilds,
 * console/exec). They REFUSE by name rather than half-deploying.
 */
public final class WorkspaceKind implements InstanceKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "workspace");
    public static final Schema SETTINGS_SCHEMA = GitSourceSchema.addTo(new Schema());

    /** Overrides the runtime image's default command; blank = the image decides. */
    public static final StringField START_COMMAND = SETTINGS_SCHEMA.addField(
        StringField.builder().name("start_command")
            .label(HohenheimFormCopy.label("start_command"))
            .help(HohenheimFormCopy.help("start_command")).build());

    /** The port the workspace's process listens on, for readiness and for exposure. */
    public static final IntegerField CONTAINER_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("container_port")
            .label(HohenheimFormCopy.label("container_port"))
            .help(HohenheimFormCopy.help("instance_container_port")).build());

    // secret(): redacted on derived surfaces, masked in forms, kept on blank submit.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables")
            .label(HohenheimFormCopy.label("environment_variables"))
            .help(HohenheimFormCopy.help("environment_variables")).secret().build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb")
            .label(HohenheimFormCopy.label("memory_limit"))
            .help(HohenheimFormCopy.help("memory_limit")).build());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(
        DoubleField.builder().name("cpu_limit")
            .label(HohenheimFormCopy.label("cpu_limit"))
            .help(HohenheimFormCopy.help("cpu_limit")).build());

    @Override public @NonNull Identifier typeId() { return ID; }

    @Override public @NonNull String getDisplayName() { return "Workspace"; }

    @Override public @NonNull Microcopy getLabel() {
        return Microcopy.of("workspace").withFilter("scope", "instance_kind");
    }

    @Override public @NonNull Microcopy getDescription() {
        return Microcopy.of("workspace").withFilter("scope", "instance_kind_description");
    }

    @Override public Icon getIcon() { return Icon.of("code"); }

    @Override public String getColor() { return "violet"; }

    @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

    /** The operator picks the runtime; the kind runs on both (phase-0 design section 4.3). */
    @Override public @NonNull Set<String> supportedRuntimes() {
        return Set.of(ServerModel.RUNTIME_DOCKER, ServerModel.RUNTIME_INCUS);
    }

    /** A userland plus an editor's language server: measurably more than a bare service. */
    @Override public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
        return 1024;
    }

    @Override
    public void requirePlaceableOn(@NonNull String serverName,
                                   @NonNull Map<String, Object> settings) {
        VolumeBackends.requireQuotaCapableHost(serverName, getLabel());
    }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        throw notWired();
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        throw notWired();
    }

    private static Violations notWired() {
        return Violations.ofForm(Microcopy.of("workspace_runtime_not_wired")
            .withFilter("scope", "violations"));
    }
}
