package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The /manage view over backups: the backups of instances the principal holds
 * {@code backups} on.
 *
 * Restore-to-NEW is deliberately absent here. It creates an instance outside the
 * creation funnel (no create authority, no placement, no creator grant, image from the
 * archive manifest instead of an approved template), so
 * {@link be.elevenways.hohenheim.server.instance.InstanceBackups} refuses it for any
 * tenant-originated call. Rendering the action anyway would be a button that cannot
 * work -- the surface offers exactly what the funnel allows.
 */
public final class ManageInstanceBackupResource extends InstanceBackupResource {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "manage_instance_backup");
    }

    /**
     * The walk's tri-state through {@code grantScope}, never a hand-rolled
     * isAdmin-plus-id-set prefix: an id set cannot express a whole-model row, and the
     * enumeration THROWS on one the moment instances grow a type-level permission.
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return TenantScopes.INSTANCE_BACKUPS.accessFunction();
    }

    /** Only the synthesized view/delete affordances; no restore-to-new. */
    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        return List.of();
    }

    /** NAV-ONLY; reachesAny, because an id set cannot express every-record authority. */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return HohenheimAccess.reachesAny(access, InstanceModel.MODEL_ID,
            HohenheimAccess.BACKUPS);
    }

    /**
     * The contributed pages only (the generic access matrix, which gates itself per record).
     * Deliberately NOT frameworkSubpages(): the admin activity/revision history stays off the
     * delegated surface, and dropping the page here also 404s its routes.
     */
    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return this.contributedSubpages();
    }
}
