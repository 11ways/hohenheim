package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.test.support.EndpointConduit;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A request scope carrying nothing but an identity, so a test can drive a write straight at
 * the MODEL and still be judged as the request it pretends to be.
 *
 * This is the bypass class the write-pipeline invariants exist for: revision restore, the
 * peer API, a zone-file import and any direct {@code model.save} reach the datasource
 * without touching a resource method, a form spec or an endpoint. Testing the freezes only
 * through /manage forms would prove the FORM drops fields, which is not the claim.
 *
 * AIDEV-NOTE: a full-page {@link EndpointConduit}, never a Conduit of its own: everything the invariants read is the
 * principal attribute, and its page-address reads answer "/" as a request outside every panel does.
 */
public final class TenantConduits {

    /** The test admin's sign-in, which every operator caller of the suite is. */
    public static final String OPERATOR_EMAIL = "test@hohenheim.local";

    private TenantConduits() {
    }

    /**
     * The installation operator as a caller: the seeded test admin, for a write that goes through a panel's verb
     * (its authorizers ask who is acting, so the harness's system identity is no caller there).
     */
    public static AccessContext operator() {
        return AccessContext.of(stubFor(operatorPrincipal()));
    }

    /**
     * The installation operator as a caller on a request opened under one parent record ({@code ?parent=}): a
     * programmatic create of a child whose parent is fixed, written under that parent.
     */
    public static AccessContext operatorUnder(Object parentKey) {
        return AccessContext.of(stubUnder(operatorPrincipal(), parentKey));
    }

    private static UserPrincipal operatorPrincipal() {
        return new UserPrincipal(operatorUser().get(UserModel.ID), "Test Admin");
    }

    /**
     * The test admin of the current database, created with the /setup admin's grant-everything shape when absent.
     *
     * AIDEV-NOTE: THE one home of the test admin: HohenheimTestBase's seeded session reuses it, so a class on a
     * fresh database that only needs an operator caller and a class that logs one in never seed a second account.
     */
    public static Row operatorUser() {
        Row existing = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq(OPERATOR_EMAIL)).first();
        if (existing != null) {
            return existing;
        }
        Row user = Models.get(UserModel.class).createEmptyRow();
        user.set(UserModel.EMAIL, OPERATOR_EMAIL);
        user.set(UserModel.DISPLAY_NAME, "Test Admin");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Now.instant());
        user.set(UserModel.UPDATED_AT, Now.instant());
        Models.get(UserModel.class).save(user);
        ZenitAuth.markSeeded();   // a user exists, so the setup gate must not redirect
        // Grant everything (the /setup admin's shape) so the panels' access checks pass.
        Row grant = Models.get(GrantModel.class).createEmptyRow();
        grant.set(GrantModel.SUBJECT_TYPE, GrantSubjectType.USER.key());
        grant.set(GrantModel.SUBJECT_ID, user.get(UserModel.ID));
        grant.set(GrantModel.PERMISSION, "*");
        grant.set(GrantModel.VALUE, true);
        Models.get(GrantModel.class).save(grant);
        return user;
    }

    /** Run {@code body} inside a request scope whose principal is {@code principal}. */
    public static void as(@Nullable Principal principal, Runnable body) {
        RouteScope.run(stub(principal, null, null, null), body);
    }

    /**
     * The same carrier answering as a request that ARRIVED at {@code origin}, for code
     * that reads the hostname the surface is being reached at.
     */
    public static void arrivingAt(String origin, Runnable body) {
        RouteScope.run(stub(null, origin, null, null), body);
    }

    /**
     * The same synthetic carrier, handed out instead of run inside: for a policy that
     * takes an AccessContext rather than a scope ({@code AccessContext.of(conduit)}).
     */
    public static Conduit stubFor(@Nullable Principal principal) {
        return stub(principal, null, null, null);
    }

    /**
     * The same carrier, as a request opened under one parent record ({@code ?parent=}): the way a programmatic create
     * of a child whose parent is fixed names the parent it is written under.
     */
    public static Conduit stubUnder(@Nullable Principal principal, Object parentKey) {
        return stub(principal, null, null, String.valueOf(parentKey));
    }

    /** The same carrier, rendering under one panel: it answers that panel's slug as the routed panel parameter. */
    public static Conduit stubIn(@Nullable Principal principal, String panelSlug) {
        return stub(principal, null, panelSlug, null);
    }

    /** The same carrier, answering as a request that ARRIVED at {@code origin}. */
    public static Conduit stubFor(@Nullable Principal principal, @Nullable String origin) {
        return stub(principal, origin, null, null);
    }

    private static Conduit stub(@Nullable Principal principal, @Nullable String origin, @Nullable String panelSlug,
                                @Nullable String parentKey) {
        EndpointConduit conduit = EndpointConduit.fullPageRequest().setRequestOrigin(origin)
            .withAttribute(ConduitAttributes.PRINCIPAL, principal);
        if (panelSlug != null) {
            conduit.setParameter(CmsEndpoints.PANEL_PARAM, panelSlug);
        }
        if (parentKey != null) {
            conduit.setQueryParam(CmsEndpoints.PARENT_PARAM.getName(), parentKey);
        }
        return conduit;
    }
}
