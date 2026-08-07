package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.server.instance.InstancePreStartHook;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/**
 * Joins a stack service to its stack's shared link network under its compose service
 * name, so a sibling that dials that name resolves it -- and only inside that stack.
 */
public class StackServiceLinkHook implements InstancePreStartHook {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "stack_service_links");
    }

    @Override
    public int weight() {
        return 300;
    }

    @Override
    public void beforeStart(InstanceService.@NonNull Resolved resolved, int instanceId)
            throws IOException {
        StackInstances.attachLinksBeforeStart(resolved, instanceId);
    }
}
