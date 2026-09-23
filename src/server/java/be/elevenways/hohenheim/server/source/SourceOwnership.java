package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Set;

/**
 * THE reach of a source or provider record: an OPERATOR-owned one may name the controller's
 * own filesystem and private networks, a TENANT-owned one only remote, public places.
 *
 * AIDEV-NOTE: "operator-owned" is {@link HohenheimAccess#manageSubjectsOf} answering an EMPTY
 * set -- the one ownership derivation of every tier (there is no owner column). An
 * unreadable grant set answers null and fails CLOSED to tenant reach. It is asked at USE
 * time, never trusted from write time: a record an operator filled in and later granted to
 * a tenant loses the wider reach at that moment, and a value a tenant stored before the
 * write gate existed is refused where it would be acted on.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SourceOwnership {

    private SourceOwnership() {
    }

    /**
     * Whether a checkout for this owner may clone a LOCAL source (an absolute path or a
     * {@code file://} URL on the controller).
     *
     * @param ownerModel the record the checkout directory belongs to: an application or
     *                   workspace instance, or a preview of an application
     */
    public static boolean localSourcesAllowed(@NonNull Identifier ownerModel, int ownerId) {
        Integer instanceId = sourceInstanceOf(ownerModel, ownerId);
        return instanceId != null && operatorOwned(InstanceModel.MODEL_ID, instanceId);
    }

    /**
     * The outbound guard a provider's API calls ride: any address for an operator-owned
     * provider (a forge on the operator's own network is legitimate), the public internet for
     * every other one.
     */
    public static @NonNull OutboundUrlGuard providerGuard(@NonNull Row provider) {
        Integer providerId = provider.get(GitProviderModel.ID);
        return providerId != null && operatorOwned(GitProviderModel.MODEL_ID, providerId)
            ? OutboundUrlGuard.ANY_ADDRESS : OutboundUrlGuard.PUBLIC_INTERNET;
    }

    /** Whether nobody holds a manage grant on the record; unreadable grants answer false. */
    private static boolean operatorOwned(@NonNull Identifier model, int recordId) {
        Set<String> subjects = HohenheimAccess.manageSubjectsOf(model, recordId);
        return subjects != null && subjects.isEmpty();
    }

    /**
     * The instance whose settings carry the source a checkout of this owner clones: the
     * instance itself, or the application a preview builds. Null for any other owner, which
     * fails closed.
     */
    private static @Nullable Integer sourceInstanceOf(@NonNull Identifier ownerModel, int ownerId) {
        if (InstanceModel.MODEL_ID.equals(ownerModel)) {
            return ownerId;
        }
        if (PreviewDeploymentModel.MODEL_ID.equals(ownerModel)) {
            Row preview = Models.get(PreviewDeploymentModel.class).findById(ownerId);
            return preview == null ? null : preview.get(PreviewDeploymentModel.APPLICATION_ID);
        }
        return null;
    }
}
