package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.GlobalModelHooks;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Names, in the log, every Proteus site auth provider whose realm is on a private network while
 * {@link HohenheimSettings.ProxyAuth#PROTEUS_ALLOW_PRIVATE_NETWORKS} is off: at startup and whenever a provider is
 * saved.
 *
 * AIDEV-NOTE: since provider realms ride the public-internet guard, such a provider fails closed (its gate cannot log
 * anyone in); on a live upgrade that must never be silent. The wording is core's
 * {@link OutboundUrlGuard#warnIfAwaitingOptIn}; a loopback or link-local realm gets no warning because no setting
 * admits it. The proxy boot never waits on DNS: the startup scan is a background {@link JobRunner} job, and the scan
 * and a save check every realm at once under ONE {@link OutboundUrlGuard#OPT_IN_CHECK_TIMEOUT}, so a hung lookup is
 * reported "could not check" and never delays the others.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ProteusRealmOptInWarnings {

    private static volatile boolean installed = false;

    private ProteusRealmOptInWarnings() {
    }

    /** Warns from now on whenever a provider is saved, and starts the background scan of the stored ones. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        GlobalModelHooks.addAfterSaveHook(context -> {
            Model model = context.getModel();
            if (model != null && SiteAuthProviderModel.MODEL_ID.equals(model.getModelId())) {
                warnFor(context.getRow(), OutboundUrlGuard.OPT_IN_CHECK_TIMEOUT);
            }
        });
        installed = true;
        startScan();
    }

    /**
     * Starts {@link #scan()} as a background job, so nothing waits on DNS for it.
     *
     * @return the scan's warnings once every provider was checked
     */
    public static @NonNull CompletableFuture<List<String>> startScan() {
        Datasource datasource = Db.currentOrDefault();
        CompletableFuture<List<String>> scanned = new CompletableFuture<>();
        JobRunner.startVirtualThread(() -> ExecutionIdentity.runAsSystem("proteus-realm-opt-in-scan",
            () -> Db.run(datasource, () -> {
                try {
                    scanned.complete(scan());
                } catch (RuntimeException failure) {
                    scanned.completeExceptionally(failure);
                }
            })));
        return scanned;
    }

    /**
     * @return the lines logged for every stored provider: every realm checked at once, under one
     *     {@link OutboundUrlGuard#OPT_IN_CHECK_TIMEOUT}, so a hung lookup never delays the others
     */
    public static @NonNull List<String> scan() {
        List<OutboundUrlGuard.OptInTarget> targets = new ArrayList<>();
        for (Row provider : Models.get(SiteAuthProviderModel.class).findAllOrdered()) {
            OutboundUrlGuard.OptInTarget target = targetOf(provider);
            if (target != null) {
                targets.add(target);
            }
        }
        return OutboundUrlGuard.checkEachAwaitingOptIn(HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS,
            targets, OutboundUrlGuard.OPT_IN_CHECK_TIMEOUT).lines();
    }

    /**
     * @param timeout how long to wait for DNS before logging "could not check" instead
     * @return the line logged for this provider, or null when it is no Proteus provider or nothing waits
     */
    public static @Nullable String warnFor(@Nullable Row provider, @NonNull Duration timeout) {
        OutboundUrlGuard.OptInTarget target = targetOf(provider);
        if (target == null) {
            return null;
        }
        List<String> lines = OutboundUrlGuard.checkEachAwaitingOptIn(
            HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS, List.of(target), timeout).lines();
        return lines.isEmpty() ? null : lines.getFirst();
    }

    /** @return the provider's realm endpoint, named for the operator; null when it is no Proteus provider */
    private static OutboundUrlGuard.@Nullable OptInTarget targetOf(@Nullable Row provider) {
        if (provider == null
            || !ProteusAuthProviderType.ID.toString().equals(provider.get(SiteAuthProviderModel.PROVIDER_TYPE))
            || !(provider.get(SiteAuthProviderModel.CONFIG) instanceof Map<?, ?> config)) {
            return null;
        }
        Object endpoint = config.get(ProteusAuthProviderType.ENDPOINT);
        if (endpoint == null) {
            return null;
        }
        return new OutboundUrlGuard.OptInTarget(
            "site auth provider '" + provider.get(SiteAuthProviderModel.NAME) + "' (Proteus realm)",
            String.valueOf(endpoint));
    }
}
