package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.server.instance.InstancePreStartHook;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The DATABASE side of the instance-database link networks: a recreated database container rejoins every link
 * network of the databases it serves before it starts, whichever lane recreated it.
 *
 * AIDEV-NOTE: takes no DatabaseEngines lock, and must not. A deploy runs under the instance's operation lock, and
 * DatabaseEngines.ensureRunning and redeploy take the engine lock BEFORE that one (they deploy inside it); a hook
 * reaching back for the engine lock would invert the order against them. The work is idempotent network plumbing,
 * which is all the lock-free rejoin needs.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public class DatabaseServingLinkHook implements InstancePreStartHook {

    @Override
    public @NonNull Identifier id() {
        return HohenheimIds.id("database_serving_links");
    }

    /** After the consumer side's 210: the two never apply to the same instance. */
    @Override
    public int weight() {
        return 220;
    }

    @Override
    public void beforeStart(InstanceService.@NonNull Resolved resolved, int instanceId) {
        InstanceDatabaseNetworks.rejoinServedBeforeStart(resolved);
    }
}
