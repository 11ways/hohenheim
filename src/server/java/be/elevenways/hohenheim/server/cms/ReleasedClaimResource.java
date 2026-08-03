package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The released-hostname quarantine list, and THE recorded override: an administrator can
 * lift one hostname's quarantine so a different owner may claim it.
 *
 * The rows are evidence, never editable: they are written by the write pipeline
 * ({@code ReleasedClaims}) and the only operator verb is lifting one.
 */
public class ReleasedClaimResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(ReleasedRouteClaimModel.HOSTNAME)
        .add(RelationPick.of(ReleasedRouteClaimModel.FORMER_SITE_ID, SiteModel.MODEL_ID).build())
        .add(ReleasedRouteClaimModel.FORMER_SUBJECTS)
        .add(ReleasedRouteClaimModel.RELEASED_AT)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(ReleasedRouteClaimModel.HOSTNAME).filterable().build())
        .column(ColumnSpec.fromField(ReleasedRouteClaimModel.FORMER_SITE_ID)
            .relation(RelationPick.of(ReleasedRouteClaimModel.FORMER_SITE_ID,
                SiteModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(ReleasedRouteClaimModel.FORMER_SUBJECTS).build())
        .column(ColumnSpec.fromField(ReleasedRouteClaimModel.RELEASED_AT).build())
        .filter(FilterSpec.forField(ReleasedRouteClaimModel.HOSTNAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(ReleasedRouteClaimModel.HOSTNAME)).build())
        .defaultSort(SortSpec.desc(ReleasedRouteClaimModel.RELEASED_AT.getName()))
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "released_claim"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "released_claim"); }
    @Override public @NonNull String slug() { return "released-claims"; }
    @Override public @NonNull Model model() { return Models.get(ReleasedRouteClaimModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.PROXY_GROUP; }
    @Override public int navOrder() { return 25; }
    @Override public @NonNull Icon icon() { return Icon.of("hourglass-half"); }
    @Override public boolean creatable() { return false; }
    @Override public boolean updatable() { return false; }
    @Override public boolean deletable() { return false; }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(this.liftAction());
        return actions;
    }

    /**
     * Lift one quarantine, typed-confirmed on the HOSTNAME itself.
     *
     * AIDEV-NOTE: visibility is NOT authorization -- the typed confirmation is a client-side
     * accident guard and visibleFor only hides the button, so the handler re-checks admin
     * itself. Without that re-check this would be a guard that cannot catch what it exists
     * for: a plain POST to the invoke endpoint would hand any panel-reaching principal the
     * ability to free a hostname another tenant still points a CNAME at.
     */
    private @NonNull RowAction<Row> liftAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "lift_quarantine"))
            .label(Microcopy.of("lift").withFilter("scope", "released_claim"))
            .icon(Icon.of("unlock"))
            .description(Microcopy.of("lift_hint").withFilter("scope", "released_claim"))
            .visibleFor((row, ctx) -> HohenheimAccess.isAdmin(ctx))
            .confirmation(liftConfirmation(null))
            .dynamicConfirmation(row -> liftConfirmation(
                String.valueOf(row.get(ReleasedRouteClaimModel.HOSTNAME))))
            .handler((row, ctx) -> {
                if (!HohenheimAccess.isAdmin(ctx.access())) {
                    return CmsActionResult.errorToast(
                        Microcopy.of("lift_denied").withFilter("scope", "released_claim"));
                }
                ActivityLog.withAction("quarantine_lifted",
                    String.valueOf(row.get(ReleasedRouteClaimModel.HOSTNAME)),
                    () -> this.model().delete(row));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("lifted_toast").withFilter("scope", "released_claim"));
            })
            .build();
    }

    /** The typed confirmation; the phrase is the hostname, so a reflex click cannot lift one. */
    private static @NonNull ConfirmationSpec liftConfirmation(String hostname) {
        ConfirmationSpec.Builder builder = ConfirmationSpec.builder()
            .title(Microcopy.of("lift").withFilter("scope", "released_claim"))
            .body(Microcopy.of("lift_confirm").withFilter("scope", "released_claim"))
            .style(ActionStyle.DESTRUCTIVE);
        if (hostname != null && !hostname.isBlank()) {
            builder.requireTypedConfirmation(hostname);
        }
        return builder.build();
    }
}
