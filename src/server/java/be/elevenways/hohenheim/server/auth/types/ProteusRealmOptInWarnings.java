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
 * admits it. The proxy boot never waits on DNS: the startup scan is a background {@link JobRunner} job, and the
 * save-time check waits at most {@link OutboundUrlGuard#OPT_IN_CHECK_TIMEOUT} before logging "could not check".
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

    /** @return the warning logged for each stored provider whose realm waits on the opt-in; waits on DNS */
    public static @NonNull List<String> scan() {
        List<String> warnings = new ArrayList<>();
        for (Row provider : Models.get(SiteAuthProviderModel.class).findAllOrdered()) {
            String warning = warnFor(provider, null);
            if (warning != null) {
                warnings.add(warning);
            }
        }
        return warnings;
    }

    /**
     * @param timeout how long to wait for DNS before logging "could not check" instead, or null to wait for it
     * @return the line logged for this provider, or null when it is no Proteus provider or nothing waits
     */
    public static @Nullable String warnFor(@Nullable Row provider, @Nullable Duration timeout) {
        if (provider == null
            || !ProteusAuthProviderType.ID.toString().equals(provider.get(SiteAuthProviderModel.PROVIDER_TYPE))
            || !(provider.get(SiteAuthProviderModel.CONFIG) instanceof Map<?, ?> config)) {
            return null;
        }
        Object endpoint = config.get(ProteusAuthProviderType.ENDPOINT);
        if (endpoint == null) {
            return null;
        }
        String subject = "site auth provider '" + provider.get(SiteAuthProviderModel.NAME) + "' (Proteus realm)";
        return timeout == null
            ? OutboundUrlGuard.warnIfAwaitingOptIn(HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS, subject,
                String.valueOf(endpoint))
            : OutboundUrlGuard.warnIfAwaitingOptIn(HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS, subject,
                String.valueOf(endpoint), timeout);
    }
}
