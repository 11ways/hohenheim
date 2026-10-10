package be.elevenways.hohenheim.server.ports;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.command.Commands;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The server's half of {@link PortLedger#EXCLUSION}: every port claim of one host runs as an ephemeral core command
 * serialized on the host's claim key and fenced on the host's own row, installed at class-load.
 *
 * AIDEV-NOTE: the fence is the exclusion point, the lease only its admission. The pin is a guarded UPDATE of the
 * host row inside the claim's transaction, so on a row-locking engine a rival claim of the host waits on it until
 * the claim's transaction actually ends: a holder stalled past its lease's expiry still blocks its successor, and a
 * claim joined into an outer transaction holds it (and its in-transaction lease) through that outer commit. On a
 * single-writer engine the open write transaction is the exclusion and Commands pins nothing.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
@BlastAutoLoad
public final class PortClaimFence implements PortLedger.ClaimExclusion {

    /** A leaf: a claim names its host only inside whatever operation it runs in, so it ranks after every key. */
    private static final LeaseKeys KEYS = LeaseKeys.declareLeaf(HohenheimIds.id("port_claims"),
        "hohenheim_port_claims_");

    public static final boolean LOADED = install();

    private PortClaimFence() {
    }

    private static boolean install() {
        PortLedger.EXCLUSION.install(new PortClaimFence());
        return true;
    }

    /** The lease key every claim of one host serializes on. */
    public static @NonNull String keyFor(int serverId) {
        return KEYS.key(serverId);
    }

    @Override
    public void exclusively(int serverId, PortLedger.@NonNull ClaimWrite body) {
        Commands.on(Models.get(PortAllocationModel.class).getResolvedDatasource()).ephemeral()
            .serializeOn(keyFor(serverId))
            .fence(Models.get(ServerModel.class), ServerModel.PORT_CLAIM_FENCE, serverId)
            .run(() -> {
                body.write();
                return null;
            });
    }
}
