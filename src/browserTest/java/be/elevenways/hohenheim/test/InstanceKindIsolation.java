package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.instance.InstanceKindInfo;
import be.elevenways.protoblast.common.registry.RegistrySnapshot;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Restores each class's instance-kind declarations, including replacements and the registry's closed state. */
public final class InstanceKindIsolation implements BeforeAllCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace SCOPE = ExtensionContext.Namespace.create(InstanceKindIsolation.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getStore(SCOPE).put("kinds", InstanceKindRegistry.REGISTRY.snapshot());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterAll(ExtensionContext context) {
        RegistrySnapshot<InstanceKindInfo> before =
            context.getStore(SCOPE).remove("kinds", RegistrySnapshot.class);
        if (before != null) InstanceKindRegistry.REGISTRY.restoreSnapshot(before);
    }
}
