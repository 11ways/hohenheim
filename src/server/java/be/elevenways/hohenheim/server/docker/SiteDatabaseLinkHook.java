package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.server.instance.InstancePreStartHook;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/**
 * Joins a Docker site's release container to every attached database's link network
 * before it starts, so the health gate probes the release WITH its database reachable.
 */
public class SiteDatabaseLinkHook implements InstancePreStartHook {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "site_database_links");
    }

    @Override
    public int weight() {
        return 200;
    }

    @Override
    public void beforeStart(InstanceService.@NonNull Resolved resolved, int instanceId)
            throws IOException {
        SiteDatabaseNetworks.attachLinksBeforeStart(resolved, instanceId);
    }
}
