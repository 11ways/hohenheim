package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The /manage view over schedule steps, scoped through the PARENT schedule: a step is
 * visible exactly when its schedule is.
 *
 * AIDEV-NOTE: the base resource has no read scope at all (ALLOW_ALL), which is why this
 * class exists and why it may not simply be reused in the delegated panel.
 */
public final class ManageInstanceScheduleStepResource extends InstanceScheduleStepResource {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "manage_instance_schedule_step");
    }

    /** Visible exactly when the parent schedule is: {@link TenantScopes#INSTANCE_SCHEDULE_STEPS}. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return AccessFunction.scopedBy(TenantScopes.INSTANCE_SCHEDULE_STEPS);
    }

    /**
     * The contributed pages only. Deliberately NOT frameworkSubpages(): the admin
     * activity/revision history stays off the delegated surface.
     */
    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return this.contributedSubpages();
    }
}
