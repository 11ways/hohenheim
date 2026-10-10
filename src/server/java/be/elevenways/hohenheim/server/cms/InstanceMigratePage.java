package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.MigrationTargetView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.render.action.PageFormState;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.server.page.PageActions;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Migrate tab on an instance: the cold move to another host, one placed migrate operation offered once per eligible
 * destination row, its form preset with that host and its dialog naming both the source and the destination.
 *
 * OPERATOR-ONLY, twice over and deliberately: {@link #visibleFor} hides the tab AND 404s its route for anyone without
 * the installation-wide admin permission, and the migrate operation's authorizer and
 * {@code InstanceMigrations.migrateTo} refuse everyone else by name. Placement is an operator authority, so the
 * delegated surface never carries this page ({@link InstanceParts#manage()} does not list it).
 */
public final class InstanceMigratePage implements RecordTab.Rendered<Row> {

    /** The migrate operation over this tab's own record, its destination carried hidden per row. */
    private static final PanelAction<Row> MIGRATE = PanelAction.<Row, Integer>places(InstanceOperations.MIGRATE,
            ActionPlacement.PAGE, (request, result) -> CmsActionResult.refreshWithToast(
                HohenheimMicrocopy.INSTANCE.of("migrated_toast")
                    .withArg("name", request.subject().get(InstanceModel.NAME))
                    .withArg("host", ServerModel.nameOf(result.value()))))
        .confirmation(ConfirmationSpec.of(HohenheimMicrocopy.INSTANCE_MIGRATE.of("migrate"),
            HohenheimMicrocopy.INSTANCE_MIGRATE.of("migrate_here"), HohenheimMicrocopy.INSTANCE_MIGRATE.of("cold_note"),
            ActionStyle.DESTRUCTIVE))
        // The destination is the row's own; the dialog names it beside the source, so a move is confirmed for
        // exactly the two hosts it involves.
        .confirmationBody((instance, input) -> HohenheimMicrocopy.INSTANCE_MIGRATE.of("migrate_confirm")
            .withArg("name", instance.get(InstanceModel.NAME))
            .withArg("from", ServerModel.canonicalNameOf(instance.get(InstanceModel.SERVER_ID)))
            .withArg("to", ServerModel.nameOf(destination(input))))
        .transport(InstanceOperations.TARGET_SERVER.getName())
        .selectedByRoute(instance -> String.valueOf((Object) instance.get(InstanceModel.ID)))
        .build();

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_migrate"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.INSTANCE.of("migrate"); }
    /**
     * Housekeeping, not an everyday destination: the tab lives in the strip's "More"
     * menu so the visible strip stays the handful of tabs an operator opens daily.
     */
    @Override public boolean secondaryTab() { return true; }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.MIGRATE; }
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
    public @NonNull List<PanelAction<Row>> actions() {
        return List.of(MIGRATE);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        Conduit conduit = request.conduit();
        Integer instanceId = instance.get(InstanceModel.ID);
        String name = String.valueOf((Object) instance.get(InstanceModel.NAME));
        // The protected-status vocabulary is asked, never re-listed: a fourth in-flight
        // status refuses here the moment it refuses in InstanceOperationGuard.
        boolean operable = InstanceModel.isOperable(instance);

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, HohenheimMicrocopy.INSTANCE_MIGRATE, name));
        vars.put("instanceName", name);
        vars.put("instanceId", instanceId);
        vars.put("sourceHost", ServerModel.canonicalNameOf(instance.get(InstanceModel.SERVER_ID)));
        vars.put("operable", operable);
        vars.put("status", instance.get(InstanceModel.STATUS));
        vars.put("targets", this.targetsFor(request, instance, operable));
        vars.put("head", recordHead(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_MIGRATE, vars);
    }

    /** The survey, every refusal resolved for this reader's locale, an eligible row carrying its preset form. */
    private @NonNull List<MigrationTargetView> targetsFor(@NonNull PanelRequest request, @NonNull Row instance,
                                                          boolean operable) {
        Conduit conduit = request.conduit();
        List<MigrationTargetView> views = new ArrayList<>();
        for (InstanceMigrations.Destination destination
                : new InstanceMigrations().destinationsFor(instance.get(InstanceModel.ID))) {
            boolean offered = destination.eligible() && operable;
            views.add(new MigrationTargetView(
                destination.serverId(),
                destination.name(),
                destination.eligible(),
                destination.refusal() == null ? ""
                    : destination.refusal().asString(conduit),
                destination.bookableMb() > 0,
                destination.bookedMb(),
                destination.bookableMb(),
                offered ? this.formFor(request, instance, destination.serverId()) : null));
        }
        return views;
    }

    /** The migrate form preset with one destination; null when the operation withholds a form. */
    private @Nullable PageFormState formFor(@NonNull PanelRequest request, @NonNull Row instance, int serverId) {
        PageActions.Opened opened = PageActions.open(request, this, instance, MIGRATE.id(),
            Map.of(InstanceOperations.TARGET_SERVER.getName(), serverId));
        return opened instanceof PageActions.Form form ? form.state() : null;
    }

    /** @return the destination the form opens with, -1 when it names none */
    private static int destination(@NonNull Map<String, Object> input) {
        Object value = input.get(InstanceOperations.TARGET_SERVER.getName());
        return RawValues.intOr(value, -1);
    }

}
