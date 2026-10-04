package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/** Collects Proteus realm destinations while core owns warning hooks, scans and deadlines. */
public final class ProteusRealmOptInTargets {
    private ProteusRealmOptInTargets() {}

    public static @NonNull List<OutboundUrlGuard.OptInTarget> targetsOf(@Nullable Row provider) {
        if (provider == null
                || !ProteusAuthProviderType.ID.toString().equals(provider.get(SiteAuthProviderModel.PROVIDER_TYPE))
                || !(provider.get(SiteAuthProviderModel.CONFIG) instanceof Map<?, ?> config)) return List.of();
        Object endpoint = config.get(ProteusAuthProviderType.ENDPOINT);
        return endpoint == null ? List.of() : List.of(new OutboundUrlGuard.OptInTarget(
            "site auth provider '" + provider.get(SiteAuthProviderModel.NAME) + "' (Proteus realm)",
            String.valueOf(endpoint)));
    }
}
