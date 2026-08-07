package be.elevenways.hohenheim.server.game;

import be.elevenways.hohenheim.server.instance.InstancePreStartHook;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/** Re-attaches a game backend's proxy link networks before the container starts. */
public class GameDomainLinkHook implements InstancePreStartHook {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "game_domain_links");
    }

    @Override
    public int weight() {
        return 100;
    }

    @Override
    public void beforeStart(InstanceService.@NonNull Resolved resolved, int instanceId)
            throws IOException {
        GameDomains.attachLinksBeforeStart(resolved, instanceId);
    }
}
