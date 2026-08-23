package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
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

    // AIDEV-NOTE: InstanceModel is DELIBERATELY absent, and the absence was re-decided when
    // the non-release-managed stale-address defect was fixed (2026-08-23). Instance rows are
    // written constantly -- a status stamp every couple of minutes per record, deploy
    // progress, install lifecycle -- and every entry here regenerates the WHOLE routing
    // table on write, so adding it would rebuild every site's handler chain on a heartbeat.
    // It is also unnecessary: what a site's instance upstream needs is not a new handler but
    // a fresh ADDRESS, and that is targeted invalidation's job. Every path that moves an
    // instance's address or liveness now bumps its generation -- the InstanceService funnel
    // (deploy/stop/destroy, every kind), the release engine, and the status reconciler on a
    // CORRECTION -- and a handler holding NULL retries on its own short timer. A routing
    // hook here would be a broadcast standing in for facts we already have precisely.
    //
    // AIDEV-NOTE: DatabaseModel is here for the ROUTE, not for convergence. Routing no
    // longer converges anything (phase-0 brief 7: the instance upstream handler resolves an
    // address and nothing else), so a database status flip reaches a workload through the
    // application's next deploy, not through a reload. What stays true is that these models
    // decide which hostnames exist and where they point.
    private static final Set<Identifier> ROUTING_MODELS = Set.of(
        SiteModel.MODEL_ID,
        SiteDomainModel.MODEL_ID,
        CertificateModel.MODEL_ID,
        AccessListModel.MODEL_ID,
        SiteAuthProviderModel.MODEL_ID,
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
