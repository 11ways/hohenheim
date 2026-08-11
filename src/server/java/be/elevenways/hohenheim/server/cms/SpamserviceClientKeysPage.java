package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.ManagedClient;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

/** Client tab that opens the generic client-scoped key resource. */
final class SpamserviceClientKeysPage implements RecordScopedPage<ManagedClient> {

    static final String SLUG = "keys";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "spamservice_client_keys_page"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("keys").withFilter("scope", "spamservice_client"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("key"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit, @NonNull AccessContext context,
                                           @NonNull ManagedClient client) {
        // AIDEV-NOTE: the client scoping rides a bound ParameterDefinition (an extra
        // parameter renders as a query param), never "?client_id=" concatenation. Built
        // off CmsEndpoints rather than CmsRoutes.list because CmsRoutes returns the
        // RouteTarget interface, which has no with(...) to chain the query onto.
        return conduit.softRedirect(CmsEndpoints.LIST
            .with(CmsEndpoints.PANEL_PARAM, "admin")
            .with(CmsEndpoints.RESOURCE_PARAM, SpamserviceClientKeysResource.SLUG)
            .with(SpamserviceClientKeysResource.CLIENT_ID_QUERY, String.valueOf(client.id())));
    }
}
