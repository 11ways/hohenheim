package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The /manage view over snapshots: the snapshots of instances the principal holds
 * {@code snapshots} on, and no others. Restore and delete stay available (both are acts
 * on the tenant's OWN instance, and both re-ask the same capability inside
 * {@link be.elevenways.hohenheim.server.instance.InstanceSnapshots}).
 *
 * AIDEV-NOTE: scoped by the {@code snapshots} capability rather than by {@code manage},
 * because that is the capability the operations on this row demand. Scoping by manage
 * would list rows whose every action then refuses -- a surface that shows what it
 * cannot do.
 */
public final class ManageInstanceSnapshotResource extends InstanceSnapshotResource {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "manage_instance_snapshot");
    }

    /**
     * The walk's tri-state through {@code grantScope}, never a hand-rolled
     * isAdmin-plus-id-set prefix: an id set cannot express a whole-model row, and the
     * enumeration THROWS on one the moment instances grow a type-level permission.
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            Criteria scope = HohenheimAccess.grantScope(ctx,
                Models.get(InstanceSnapshotModel.class), InstanceModel.MODEL_ID,
                HohenheimAccess.SNAPSHOTS, InstanceSnapshotModel.INSTANCE_ID::in);
            return scope == null ? AccessDecision.allowAll()
                : AccessDecision.allow(QueryPredicate.of(scope));
        };
    }

    /** NAV-ONLY; reachesAny, because an id set cannot express every-record authority. */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return HohenheimAccess.reachesAny(access, InstanceModel.MODEL_ID,
            HohenheimAccess.SNAPSHOTS);
    }
}
