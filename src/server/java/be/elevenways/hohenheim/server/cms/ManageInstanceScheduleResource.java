package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The /manage view over instance schedules: the same editor as the admin one, narrowed
 * to the schedules of instances the principal manages.
 *
 * AIDEV-NOTE: the base resource's accessFunction scopes only by MODEL ("this surface
 * manages instance schedules"), which is correct in an admin-gated panel and a
 * cross-tenant list in a delegated one -- every write already demanded manage, but the
 * READ did not. This override is that missing half; the scope itself is
 * {@link TenantScopes#INSTANCE_SCHEDULES}, the SAME declaration the record-schedule
 * picker source applies, and it is a criteria over the record_id STRINGS because record
 * schedules key their target polymorphically.
 */
public final class ManageInstanceScheduleResource extends InstanceScheduleResource {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "manage_instance_schedule");
    }

    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return TenantScopes.INSTANCE_SCHEDULES.accessFunction();
    }

    /**
     * The steps tab plus the CONTRIBUTED pages. Deliberately NOT frameworkSubpages(): the
     * admin activity/revision history stays off the delegated surface.
     */
    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>(List.of(new InstanceScheduleStepsPage()));
        pages.addAll(this.contributedSubpages());
        return pages;
    }
}
