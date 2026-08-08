package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.server.instance.InstancePreStartHook;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/**
 * Joins an instance to every attached database's link network before it starts, so the
 * derived connection variables it received name an address it can actually reach.
 */
public class InstanceDatabaseLinkHook implements InstancePreStartHook {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "instance_database_links");
    }

    /** Beside the site lane's 200: the two never apply to the same instance. */
    @Override
    public int weight() {
        return 210;
    }

    @Override
    public void beforeStart(InstanceService.@NonNull Resolved resolved, int instanceId)
            throws IOException {
        InstanceDatabaseNetworks.attachLinksBeforeStart(resolved, instanceId);
    }
}
