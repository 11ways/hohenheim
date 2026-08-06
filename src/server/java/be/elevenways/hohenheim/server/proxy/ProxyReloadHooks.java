package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.model.GlobalModelHooks;
import be.elevenways.zenit.common.orm.model.Model;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
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

    // SiteDatabaseModel and DatabaseModel are here because Docker sites converge their
    // injected database environment and link-network joins through a RELEASE, and only a
    // reload drives convergence: an attach/detach or a database status flip
    // (provisioning -> active) changes the source fingerprint, and the reload's rebuild
    // is what picks that up. Unchanged sites hit the fingerprint fast lane and reuse.
    private static final Set<Identifier> ROUTING_MODELS = Set.of(
        SiteModel.MODEL_ID,
        SiteDomainModel.MODEL_ID,
        CertificateModel.MODEL_ID,
        AccessListModel.MODEL_ID,
        SiteAuthProviderModel.MODEL_ID,
        SiteDatabaseModel.MODEL_ID,
        DatabaseModel.MODEL_ID);

    private static volatile boolean installed = false;
    private static final ThreadLocal<Set<Datasource>> PENDING = ThreadLocal.withInitial(
        () -> Collections.newSetFromMap(new IdentityHashMap<>()));

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
        if (!ds.hasActiveTransaction()) {
            reload();
            return;
        }
        Set<Datasource> pending = PENDING.get();
        if (!pending.add(ds)) return;
        // One reload per datasource transaction, after every related write is visible.
        ds.afterCommit(ProxyReloadHooks::reload);
        ds.afterTransaction(() -> {
            pending.remove(ds);
            if (pending.isEmpty()) PENDING.remove();
        });
    }

    private static void reload() {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null) {
            proxy.reload();
        }
    }
}
