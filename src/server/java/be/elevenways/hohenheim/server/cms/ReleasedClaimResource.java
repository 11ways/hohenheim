package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
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
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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

    /** Virtual column: the former site's stored NAME, which outlives its visibility. */
    static final String FORMER_SITE_COLUMN = "former_site";

    /** Virtual column: the former owner's subjects rendered as the people they name. */
    static final String FORMER_OWNER_COLUMN = "former_owner";

    private final FormSpec formSpec = FormSpec.builder()
        .add(ReleasedRouteClaimModel.HOSTNAME)
        .add(RelationPick.of(ReleasedRouteClaimModel.FORMER_SITE_ID, SiteModel.MODEL_ID).build())
        .add(ReleasedRouteClaimModel.FORMER_SUBJECTS)
        .add(ReleasedRouteClaimModel.RELEASED_AT)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        // The lift action demands the hostname TYPED, so the chip feeds the next click.
        .column(ColumnSpec.fromField(ReleasedRouteClaimModel.HOSTNAME).filterable().copyable().build())
        // A released hostname is unreadable without its kind: "^(shop|www)\.x\.test$" is a
        // pattern, not a host, and the quarantine judges the two differently.
        .column(ColumnSpec.fromField(ReleasedRouteClaimModel.MATCH_TYPE).build())
        // NOT a relation column: the site a claim names is soft-deleted by the time the row
        // matters, so the relation resolved through the site resource's own "live sites
        // only" scope and rendered the bare id. The name is read off the stored row here.
        .column(ColumnSpec.virtual(FORMER_SITE_COLUMN,
            FieldLabels.labelFor(ReleasedRouteClaimModel.FORMER_SITE_ID)).build())
        // NOT the stored column: it holds the packed "user:5" grant subjects, which is a
        // storage key and not a name anyone reads. The people behind it are resolved here.
        .column(ColumnSpec.virtual(FORMER_OWNER_COLUMN,
            FieldLabels.labelFor(ReleasedRouteClaimModel.FORMER_SUBJECTS)).build())
        .column(ColumnSpec.fromField(ReleasedRouteClaimModel.RELEASED_AT).build())
        .filter(FilterSpec.forField(ReleasedRouteClaimModel.HOSTNAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(ReleasedRouteClaimModel.HOSTNAME)).build())
        .defaultSort(SortSpec.desc(ReleasedRouteClaimModel.RELEASED_AT.getName()))
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "released_claim"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "released_claim"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "released_claim"); }
    @Override public @NonNull String slug() { return "released-claims"; }
    @Override public @NonNull Model model() { return Models.get(ReleasedRouteClaimModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /**
     * The former site's name, read off the stored row so a soft-deleted site still reads as
     * itself, and the former owner's subjects rendered as people. Both are empty when the
     * thing they name is gone, which says more than its id would; a subject that no longer
     * resolves keeps its raw token ({@link HohenheimAccess#subjectLabel}), because a
     * deleted tenant is exactly what a released claim usually records.
     */
    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if (FORMER_OWNER_COLUMN.equals(column.name())) {
            return HohenheimAccess.labelSubjects(row.get(ReleasedRouteClaimModel.FORMER_SUBJECTS));
        }
        if (!FORMER_SITE_COLUMN.equals(column.name())) {
            return super.cellValue(row, column);
        }
        Integer siteId = row.get(ReleasedRouteClaimModel.FORMER_SITE_ID);
        if (siteId == null) {
            return null;
        }
        Model sites = Models.get(SiteModel.class);
        Row site = sites.findById(siteId);
        return site != null ? sites.getDisplayTitle(site) : null;
    }

    /** A quarantined name is looked up by the hostname, or by who used to hold it. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(ReleasedRouteClaimModel.HOSTNAME, ReleasedRouteClaimModel.FORMER_SUBJECTS);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
    @Override public int navOrder() { return 40; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "released_claim");
    }
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
