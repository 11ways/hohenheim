package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * The released route claims' parts: the read-only quarantine ledger of hostnames a deleted or edited site gave up,
 * and the one verb on it, lifting a quarantine.
 *
 * AIDEV-NOTE: nobody creates, edits or deletes a ledger row by hand: the route invariant writes it when a claim is
 * released, and {@link #LIFT} (installation administration) is the only removal, recorded as the lift it is. The lift
 * demands the hostname TYPED, which is why the hostname column is copyable.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class ReleasedClaimParts {

    /** The released hostname entry's slug, which the panel's clusters name. */
    public static final String SLUG = "released-claims";

    /** Virtual column names (computed cells). */
    static final String FORMER_SITE_COLUMN = "former_site";
    static final String FORMER_OWNER_COLUMN = "former_owner";

    private static final SubjectType<Row> SUBJECT = SubjectType.record(ReleasedRouteClaimModel.MODEL_ID);

    /** Removes a quarantine before its window ends, freeing the hostname for any owner. */
    public static final Operation<Row, Void, Void> LIFT = Operation.declare(HohenheimIds.id("lift_quarantine"))
        .happened(OperationSentences.of("lift_quarantine"))
        .label(Microcopy.of("lift").withFilter("scope", "released_claim"))
        .icon(Icon.of("unlock"))
        .one(SUBJECT)
        .gate(OperationGate.open())
        .facts(OperationFact.DESTRUCTIVE)
        // Placed as a row action: a resubmitted click answers from the receipt instead of lifting twice.
        .command()
        .register();

    static {
        OperationHandlers.attach(LIFT)
            .authorize((claim, input, access) -> HohenheimAccess.isAdmin(access) ? null
                : new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "claim " + claim.get(ReleasedRouteClaimModel.ID)
                    + " is not reachable"))
            .handle(call -> {
                Row claim = call.subject();
                ActivityLog.withAction(HohenheimActivityAction.QUARANTINE_LIFTED,
                    String.valueOf((Object) claim.get(ReleasedRouteClaimModel.HOSTNAME)),
                    () -> Models.get(ReleasedRouteClaimModel.class).delete(claim));
                return null;
            });
    }

    private ReleasedClaimParts() {
    }

    /** @return the admin quarantine ledger */
    public static @NonNull PanelResource<Row> admin() {
        TableSpec<Row> table = TableSpec.<Row>builder()
            // The lift demands the hostname TYPED, so the chip feeds the next click.
            .column(ColumnSpec.fromField(ReleasedRouteClaimModel.HOSTNAME).filterable().copyable().build())
            // A released hostname is unreadable without its kind: "^(shop|www)\.x\.test$" is a pattern, not a host,
            // and the quarantine judges the two differently.
            .column(ColumnSpec.fromField(ReleasedRouteClaimModel.MATCH_TYPE).build())
            // NOT a relation column: the site a claim names is soft-deleted by the time the row matters, so the
            // relation would resolve through the live-sites scope and render a bare id. The name is read off the
            // stored row instead.
            .column(ColumnSpec.virtual(FORMER_SITE_COLUMN,
                FieldLabels.labelFor(ReleasedRouteClaimModel.FORMER_SITE_ID)).build())
            // NOT the stored column: it holds the packed "user:5" grant subjects, a storage key nobody reads.
            .column(ColumnSpec.virtual(FORMER_OWNER_COLUMN,
                FieldLabels.labelFor(ReleasedRouteClaimModel.FORMER_SUBJECTS)).build())
            .column(ColumnSpec.fromField(ReleasedRouteClaimModel.RELEASED_AT).build())
            .filter(FilterSpec.leaf(ReleasedRouteClaimModel.HOSTNAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(ReleasedRouteClaimModel.HOSTNAME)).build())
            .defaultSort(SortSpec.desc(ReleasedRouteClaimModel.RELEASED_AT.getName()))
            .build();
        FormSpec form = FormSpec.builder()
            .add(ReleasedRouteClaimModel.HOSTNAME)
            .add(RelationPick.of(ReleasedRouteClaimModel.FORMER_SITE_ID, SiteModel.MODEL_ID).build())
            .add(ReleasedRouteClaimModel.FORMER_SUBJECTS)
            .add(ReleasedRouteClaimModel.RELEASED_AT)
            .build();
        return PanelResource.builder(HohenheimIds.id("released_claim"), SLUG, SUBJECT)
            .label(Microcopy.of("plural").withFilter("scope", "released_claim"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "released_claim"))
            .description(Microcopy.of("nav_hint").withFilter("scope", "released_claim"))
            .icon(Icon.of("hourglass-half"))
            .navGroup(HohenheimPanel.NETWORK_GROUP)
            .navOrder(40)
            .reads(ResourceReads.rows())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(ReleasedRouteClaimModel.HOSTNAME, ReleasedRouteClaimModel.FORMER_SUBJECTS)
                .computed(Objects.requireNonNull(table.column(FORMER_SITE_COLUMN)),
                    (claim, request) -> formerSiteName(claim))
                .computed(Objects.requireNonNull(table.column(FORMER_OWNER_COLUMN)),
                    (claim, request) -> HohenheimAccess.labelSubjects(
                        claim.get(ReleasedRouteClaimModel.FORMER_SUBJECTS)))
                .build())
            .form(ResourceForm.<Row>of(form).build())
            .actions(List.of(liftAction()))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** The former site's name, read off the stored row: trashed included, a released claim's usual site state. */
    private static @Nullable String formerSiteName(@NonNull Row claim) {
        Integer siteId = claim.get(ReleasedRouteClaimModel.FORMER_SITE_ID);
        if (siteId == null) {
            return null;
        }
        Model sites = Models.get(SiteModel.class);
        Row site = StoredRows.byId(sites, siteId);
        return site != null ? sites.getDisplayTitle(site) : null;
    }

    private static @NonNull PanelAction<Row> liftAction() {
        return PanelAction.<Row, Void>places(LIFT, ActionPlacement.ROW, (request, result) ->
                CmsActionResult.refreshWithToast(Microcopy.of("lifted_toast").withFilter("scope", "released_claim")))
            .description(Microcopy.of("lift_hint").withFilter("scope", "released_claim"))
            .confirmation(liftConfirmation(null))
            .dynamicConfirmation(claim -> liftConfirmation(
                String.valueOf((Object) claim.get(ReleasedRouteClaimModel.HOSTNAME))))
            .build();
    }

    /** @param hostname the hostname the operator must type, null for the record-less fallback */
    private static @NonNull ConfirmationSpec liftConfirmation(@Nullable String hostname) {
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
