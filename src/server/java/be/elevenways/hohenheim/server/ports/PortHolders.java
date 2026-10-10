package be.elevenways.hohenheim.server.ports;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The presentation boundary of a port claim's holder: who a refusal may name the conflicting owner to.
 *
 * AIDEV-NOTE: {@link PortLedger#describeHolder} loads the owner's title (or its note, or its model and id) with no
 * visibility check, which is right for the log and the operator and a disclosure to a delegate: B's delegate
 * deploying a fixed port A holds learned A's title from the toast. A refusal shown to a caller therefore asks this,
 * and words itself without a holder when it answers null.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class PortHolders {

    private PortHolders() {
    }

    /**
     * @return the holder's description when the work in flight may read the owning record (declared system work, an
     *         operator, or a caller holding view on it), else null; an ownerless claim is named to operators only
     */
    public static @Nullable String describedTo(@NonNull Row claim) {
        String ownerModel = claim.get(PortAllocationModel.OWNER_MODEL);
        Integer ownerId = claim.get(PortAllocationModel.OWNER_ID);
        Identifier model = ownerModel == null ? null : Identifier.tryParse(ownerModel);
        boolean readable = TenantWrites.systemOrCaller(ctx -> HohenheimAccess.isAdmin(ctx)
            || (model != null && ctx.isAccount()
                && HohenheimAccess.reachesRecord(ctx, model, ownerId, HohenheimCapabilities.VIEW)));
        return readable ? PortLedger.describeHolder(claim) : null;
    }
}
