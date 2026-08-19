package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * The /manage view over preview deployments: a tenant sees, creates and destroys
 * previews of exactly the sites they hold {@code manage} on. Authority is the
 * existing site-level MANAGE capability -- a preview is a projection of its site, so
 * no new verb exists for it -- and the quota charge stays the SITE owner's bucket
 * regardless of who clicks (see PreviewDeployments.queue).
 */
public final class ManagePreviewDeploymentResource extends PreviewDeploymentResource {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_preview_deployment"); }

    /**
     * Visible here although the operator resource is not: /admin demoted previews behind a
     * header action on the Sites list, and this panel has no such list to hang one on.
     */
    @Override public boolean showInNav() { return true; }

    // AIDEV-NOTE: 25, not 30 -- the admin regrouping folded sites and instances into ONE
    // Deploy group, and instances already hold 30 there. 25 keeps previews directly under
    // the Sites entry they are a projection of, and keeps every navOrder in this panel's
    // Deploy section unique (AdminNavigationJourneyTest step 7).
    @Override public int navOrder() { return 25; }

    /** Admins see everything; everyone else only previews of their granted sites. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            Criteria scope = ManagePanel.previewScope(ctx);
            return scope == null
                ? AccessDecision.allowAll()
                : AccessDecision.allow(QueryPredicate.of(scope));
        };
    }

    /**
     * The FIRST statement of the create path (verifiesScopeBeforeMutating contract):
     * a tenant may only aim a preview at a site the capability walk confirms they
     * manage. Same uniform refusal as an unknown site -- naming the difference would
     * be a site-existence oracle.
     */
    @Override
    protected void requireCreateAuthority(@NonNull Map<String, Object> coerced,
                                          @NonNull AccessContext accessContext) {
        Object siteId = coerced.get(PreviewDeploymentModel.SITE_ID.getName());
        if (!(siteId instanceof Number number)
                || !HohenheimAccess.canManageSite(accessContext, number.intValue())) {
            throw Violations.ofField(PreviewDeploymentModel.SITE_ID.getName(), siteId,
                CmsSupport.violationText("preview_site_required"));
        }
    }

    /** NAV-ONLY (zero granted sites hide the empty list); the route itself stays scoped by accessFunction. */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return ManagePanel.hasManageScope(access);
    }
}
