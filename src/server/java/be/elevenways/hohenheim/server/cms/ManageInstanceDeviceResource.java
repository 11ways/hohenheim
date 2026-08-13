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
 * AIDEV-NOTE: what is NOT closed, stated so it is not mistaken for an oversight. A
 * view-only delegate is still SHOWN the synthesized edit/delete row affordances, whose
 * every POST is refused by {@code requireOperationCapability(instanceId, CONFIG)}
 * ({@code InstanceDeviceSurfaceTest.aViewOnlyDelegateIsShownTheDevicesAndCanChangeNoneOfThem}
 * pins the refusals AND the state, so nothing is exploitable -- it is a UX wart, and on
 * the detach button a destructive-looking one). It cannot be closed HERE: zenit-cms gates
 * those affordances on the TYPE-level {@code updatePermission()}/{@code deletePermission()}
 * ({@code ResourceListPageRenderer}), and the record-aware overloads that do exist take a
 * record but no {@code AccessContext}, so neither can express "this principal holds CONFIG
 * on THIS device's instance". Closing it needs a per-record, per-principal write predicate
 * in zenit-cms; it is a framework gap, not a hohenheim one.
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
