package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.instance.MigrationTargetView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.page.CmsFormBody;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Migrate tab on an instance: the cold move to another host, which had no caller at all
 * outside a whole-host drain until this page shipped.
 *
 * OPERATOR-ONLY, twice over and deliberately: {@link #visibleFor} hides the tab AND 404s
 * its route (render and submit share that gate) for anyone without the installation-wide
 * admin permission, and {@code InstanceMigrations.migrateTo} refuses every
 * tenant-originated call by name. Placement is an operator authority -- the same decision
 * {@code InstancePlacement} records for creates -- so the delegated surface never carries
 * this page ({@link ManageInstanceResource} does not list it).
 */
public final class InstanceMigratePage implements RecordScopedPage<Row> {

    public static final String SLUG = "migrate";

    /** The submitted destination host id. */
    private static final String TARGET_FIELD = "target_server_id";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_migrate"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("migrate").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("truck-fast"); }

    /**
     * Hide AND enforce: an operator authority is not merely an unrendered button, so the
     * route answers 404 for a delegate rather than a form whose submit can only refuse.
     */
    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext accessContext) {
        return HohenheimAccess.isAdmin(accessContext);
    }

    @Override
    public boolean submittable() {
        return true;
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        String name = String.valueOf((Object) instance.get(InstanceModel.NAME));

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "instance_migrate", name));
        vars.put("instanceName", name);
        vars.put("instanceId", instanceId);
        vars.put("sourceHost", ServerModel.nameOf(
            ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID))));
        // The protected-status vocabulary is asked, never re-listed: a fourth in-flight
        // status refuses here the moment it refuses in InstanceOperationGuard.
        vars.put("operable", InstanceModel.isOperable(instance));
        vars.put("status", instance.get(InstanceModel.STATUS));
        vars.put("targets", targetsFor(conduit, instanceId));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-migrate"), vars);
    }

    /**
     * Move the workload. Every refusal -- an ineligible host, a device row, a held
     * publication, an occupied destination, an unreachable daemon -- arrives as
     * {@link Violations} and the subpage-submit lane renders its message as an ERROR
     * toast; there is no path here on which a refused migration reports success.
     */
    @Override
    public @NonNull CmsActionResult submit(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Map<String, Object> body = conduit.getBody(CmsFormBody.BODY);
        Object raw = body == null ? null : body.get(TARGET_FIELD);
        int instanceId = instance.get(InstanceModel.ID);
        int target;
        try {
            target = Integer.parseInt(String.valueOf(raw).strip());
        } catch (NumberFormatException malformed) {
            throw Violations.ofField(TARGET_FIELD, raw,
                Microcopy.of("migrate_target_required").withFilter("scope", "violations"));
        }
        new InstanceMigrations().migrateTo(instanceId, target);
        return CmsActionResult.refreshWithToast(
            Microcopy.of("migrated_toast").withFilter("scope", "instance")
                .withArg("name", instance.get(InstanceModel.NAME))
                .withArg("host", ServerModel.nameOf(target)));
    }

    /** The survey, with every refusal resolved for this reader's locale. */
    private static @NonNull List<MigrationTargetView> targetsFor(@NonNull Conduit conduit,
                                                                 int instanceId) {
        List<MigrationTargetView> views = new ArrayList<>();
        for (InstanceMigrations.Destination destination
                : new InstanceMigrations().destinationsFor(instanceId)) {
            views.add(new MigrationTargetView(
                destination.serverId(),
                destination.name(),
                destination.eligible(),
                destination.refusal() == null ? ""
                    : destination.refusal().resolve(conduit.getLocales(),
                        conduit.getMessageResolver()),
                destination.bookableMb() > 0,
                destination.bookedMb(),
                destination.bookableMb()));
        }
        return views;
    }
}
