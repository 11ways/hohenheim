package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.GlobalModelHooks;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Names, in the log, every Proteus site auth provider whose realm is on a private network while
 * {@link HohenheimSettings.ProxyAuth#PROTEUS_ALLOW_PRIVATE_NETWORKS} is off: at startup and whenever a provider is
 * saved.
 *
 * AIDEV-NOTE: since provider realms ride the public-internet guard, such a provider fails closed (its gate cannot log
 * anyone in); on a live upgrade that must never be silent. The wording is core's
 * {@link OutboundUrlGuard#warnIfAwaitingOptIn}; a loopback or link-local realm gets no warning because no setting
 * admits it.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ProteusRealmOptInWarnings {

    private static volatile boolean installed = false;

    private ProteusRealmOptInWarnings() {
    }

    /** Scans every stored provider once and warns from now on whenever a provider is saved. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        GlobalModelHooks.addAfterSaveHook(context -> {
            Model model = context.getModel();
            if (model != null && SiteAuthProviderModel.MODEL_ID.equals(model.getModelId())) {
                warnFor(context.getRow());
            }
        });
        installed = true;
        scan();
    }

    /** @return the warning logged for each stored provider whose realm waits on the opt-in */
    public static @NonNull List<String> scan() {
        List<String> warnings = new ArrayList<>();
        for (Row provider : Models.get(SiteAuthProviderModel.class).findAllOrdered()) {
            String warning = warnFor(provider);
            if (warning != null) {
                warnings.add(warning);
            }
        }
        return warnings;
    }

    /** @return the warning logged for this provider, or null when it is no Proteus provider or nothing waits */
    public static @Nullable String warnFor(@Nullable Row provider) {
        if (provider == null
            || !ProteusAuthProviderType.ID.toString().equals(provider.get(SiteAuthProviderModel.PROVIDER_TYPE))
            || !(provider.get(SiteAuthProviderModel.CONFIG) instanceof Map<?, ?> config)) {
            return null;
        }
        Object endpoint = config.get(ProteusAuthProviderType.ENDPOINT);
        if (endpoint == null) {
            return null;
        }
        return OutboundUrlGuard.warnIfAwaitingOptIn(HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS,
            "site auth provider '" + provider.get(SiteAuthProviderModel.NAME) + "' (Proteus realm)",
            String.valueOf(endpoint));
    }
}
