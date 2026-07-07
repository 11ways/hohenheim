package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.model.GlobalModelHooks;
import be.elevenways.zenit.common.orm.model.Model;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Set;

/**
 * Rebuilds the proxy routing table whenever a routing-relevant model is
 * written or deleted, from ANY surface (admin forms, row actions, ACME
 * renewals, tasks) -- no per-call reload bookkeeping. Rides the global model
 * hook tier; the reload runs after the surrounding transaction commits.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
public final class ProxyReloadHooks {

    private static final Set<Identifier> ROUTING_MODELS = Set.of(
        SiteModel.MODEL_ID,
        SiteDomainModel.MODEL_ID,
        CertificateModel.MODEL_ID,
        AccessListModel.MODEL_ID,
        SiteAuthProviderModel.MODEL_ID);

    private static volatile boolean installed = false;

    private ProxyReloadHooks() {}

    public static synchronized void install() {
        if (installed) {
            return;
        }
        GlobalModelHooks.addAfterSaveHook(context ->
            maybeReload(context.getModel(), context.getDatasource()));
        GlobalModelHooks.addAfterRemoveHook(context ->
            maybeReload(context.getModel(), context.getDatasource()));
        installed = true;
    }

    private static void maybeReload(@Nullable Model model, @Nullable Datasource datasource) {
        if (model == null || !ROUTING_MODELS.contains(model.getModelId())) {
            return;
        }
        if (ServerMain.getProxyServer() == null) {
            return;
        }
        Datasource ds = datasource != null ? datasource : model.getResolvedDatasource();
        // After the surrounding transaction commits (immediately when there is none),
        // so the reload reads the state it was triggered by.
        ds.afterCommit(ProxyReloadHooks::reload);
    }

    private static void reload() {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null) {
            proxy.reload();
        }
    }
}
