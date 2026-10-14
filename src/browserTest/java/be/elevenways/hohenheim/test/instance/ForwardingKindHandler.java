package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.app.PutOnlineGroup;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.server.instance.GitSourceLane;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE base of every test fake wrapping a real kind: each kind fact is the wrapped kind's answer, so a fake overrides
 * only what it swaps (its runtime, its host prep, a refusal).
 *
 * AIDEV-NOTE: a new method on {@link InstanceKindHandler} (or its supertypes) is forwarded HERE, once;
 * KindHandlerForwardingDriftTest fails until it is, and fails for a wrapping fake that does not extend this.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public abstract class ForwardingKindHandler implements InstanceKindHandler {

    protected final @NonNull InstanceKindHandler real;

    protected ForwardingKindHandler(@NonNull InstanceKindHandler real) {
        this.real = real;
    }

    @Override public @NonNull Identifier typeId() { return this.real.typeId(); }
    @Override public @NonNull String getDisplayName() { return this.real.getDisplayName(); }
    @Override public @NonNull Microcopy getLabel() { return this.real.getLabel(); }
    @Override public @Nullable Microcopy getDescription() { return this.real.getDescription(); }
    @Override public @Nullable Schema getSchema() { return this.real.getSchema(); }
    @Override public @Nullable Icon getIcon() { return this.real.getIcon(); }
    @Override public @Nullable BadgeColor color() { return this.real.color(); }
    @Override public @NonNull HohenheimMicrocopy labelScope() { return this.real.labelScope(); }
    @Override public @NonNull HohenheimMicrocopy descriptionScope() { return this.real.descriptionScope(); }
    @Override public @Nullable PutOnlineGroup putOnlineGroup() { return this.real.putOnlineGroup(); }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        return this.real.runtimeFor(serverName);
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        return this.real.specFor(instanceId, settings);
    }

    @Override public boolean tenantAuthored() { return this.real.tenantAuthored(); }
    @Override public @NonNull WorkloadIsolation isolation() { return this.real.isolation(); }
    @Override public boolean releaseManaged() { return this.real.releaseManaged(); }
    @Override public @NonNull GitSourceLane gitSourceLane() { return this.real.gitSourceLane(); }
    @Override public @NonNull Map<String, Object> releaseSettings() { return this.real.releaseSettings(); }
    @Override public boolean deploysSource(@NonNull Row instance) { return this.real.deploysSource(instance); }
    @Override public boolean generatedOnly() { return this.real.generatedOnly(); }
    @Override public @NonNull Set<String> supportedRuntimes() { return this.real.supportedRuntimes(); }

    @Override
    public @Nullable Class<? extends InstanceRuntime> driverOn(@NonNull String hostRuntime) {
        return this.real.driverOn(hostRuntime);
    }

    @Override public boolean supportsDevices() { return this.real.supportsDevices(); }
    @Override public boolean supportsInstallMedia() { return this.real.supportsInstallMedia(); }
    @Override public boolean supportsTemplateCapture() { return this.real.supportsTemplateCapture(); }

    @Override
    public boolean allowsBlankImage(@NonNull Map<String, Object> settings) {
        return this.real.allowsBlankImage(settings);
    }

    @Override
    public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
        return this.real.defaultFootprintMb(settings);
    }

    @Override
    public void prepareForDeploy(int instanceId, @NonNull String serverName, @NonNull Map<String, Object> settings) {
        this.real.prepareForDeploy(instanceId, serverName, settings);
    }

    @Override
    public @Nullable Integer volumeOwnerUid(int instanceId, @NonNull String serverName) {
        return this.real.volumeOwnerUid(instanceId, serverName);
    }

    @Override public boolean supportsVolumes() { return this.real.supportsVolumes(); }
    @Override public boolean usesRuntimeImage() { return this.real.usesRuntimeImage(); }
    @Override public boolean requiresRuntimeImage() { return this.real.requiresRuntimeImage(); }
    @Override public boolean supportsSiteUpstream() { return this.real.supportsSiteUpstream(); }

    @Override
    public @NonNull PutOnlineReach reachOf(@NonNull Map<String, Object> settings) {
        return this.real.reachOf(settings);
    }

    @Override public @NonNull List<PutOnlineReach> putOnlineReaches() { return this.real.putOnlineReaches(); }
    @Override public @NonNull List<String> portSettings() { return this.real.portSettings(); }

    @Override
    public void requirePlaceableOn(@NonNull String serverName, @NonNull Map<String, Object> settings) {
        this.real.requirePlaceableOn(serverName, settings);
    }

    @Override
    public void requireImageOn(@NonNull String serverName, @NonNull Map<String, Object> settings) {
        this.real.requireImageOn(serverName, settings);
    }
}
