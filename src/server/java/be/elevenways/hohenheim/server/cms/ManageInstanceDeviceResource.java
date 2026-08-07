package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Set;

/**
 * The /manage view over instance devices: the same editor, narrowed to the devices of
 * instances the principal manages.
 *
 * AIDEV-NOTE: the base resource's accessFunction does not scope by owner, which is
 * correct in an admin-gated panel and a cross-tenant leak in a delegated one. Every
 * WRITE already re-asks InstanceDevices (which demands manage on the instance), but a
 * READ would have listed every tenant's disk names and sizes -- the same half-gate
 * ManageInstanceScheduleResource was created to close.
 */
public final class ManageInstanceDeviceResource extends InstanceDeviceResource {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "manage_instance_device");
    }

    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ManageInstanceDeviceResource::decide;
    }

    static @NonNull AccessDecision decide(@NonNull AccessContext ctx) {
        if (HohenheimAccess.isAdmin(ctx)) {
            return AccessDecision.allow(QueryPredicate.of(
                InstanceDeviceModel.INSTANCE_ID.isNotNull()));
        }
        Set<Integer> managed = HohenheimAccess.instanceIdsWith(ctx, HohenheimAccess.VIEW);
        if (managed.isEmpty()) {
            return AccessDecision.allow(QueryPredicate.of(
                Models.get(InstanceDeviceModel.class).matchNone()));
        }
        return AccessDecision.allow(QueryPredicate.of(
            InstanceDeviceModel.INSTANCE_ID.in(managed)));
    }
}
