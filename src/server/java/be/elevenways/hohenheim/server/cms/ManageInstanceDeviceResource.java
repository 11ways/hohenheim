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
 * WRITE already re-asks InstanceDevices (which demands CONFIG on the instance -- the
 * enforced verb, manage merely implies it; this note said "manage" until the capability
 * split's prose drift was swept), but a READ would have listed every tenant's disk names
 * and sizes -- the same half-gate ManageInstanceScheduleResource was created to close.
 * The read scope below is deliberately the WIDER {@code view}: seeing that a device
 * exists on an instance you may view is not the authority to change it.
 *
 * AIDEV-NOTE: re-examined 2026-08-13 against the "affordance that can only refuse" fix
 * applied to the Docker devices TAB, and the read scope above is KEPT. The two are not the
 * same shape. That tab was KIND-absolute: no principal could ever attach a device to a
 * Docker instance, so every click could only answer {@code devices_unsupported} and hiding
 * it removed a permanently dead control. This one is PRINCIPAL-relative: the edit route
 * works perfectly for a {@code config} holder, and what a {@code view}-only delegate sees
 * is ordinary authorization, not a missing product capability. Narrowing the read to
 * {@code config} to make the two agree would DELETE the deliberate reading above -- a
 * delegate would stop being able to see that a disk exists on an instance it may view.
 *
 * AIDEV-NOTE: CLOSED 2026-08-13, and the note is kept because the SHAPE is worth
 * recognising again. A view-only delegate used to be shown the synthesized edit/delete row
 * affordances -- every POST refused by {@code requireOperationCapability(instanceId,
 * CONFIG)}, so nothing was exploitable, but the destructive detach button lied about what
 * the viewer could do. It could not be closed here: zenit-cms gated those affordances on
 * the TYPE-level {@code updatePermission()}/{@code deletePermission()}, and a Permission is
 * a NAME, so no overload of it could ever say "this principal holds CONFIG on THIS device's
 * instance". The framework gained the missing shape ({@code Resource.updatableBy} /
 * {@code deletableBy}, a boolean over record + AccessContext), and
 * {@code InstanceDeviceResource} answers it with the same capability the mutators demand.
 * The lesson: when an affordance can only refuse, check whether the gate can even be
 * EXPRESSED before assuming the surface is at fault.
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
