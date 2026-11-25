package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The enabled stream sites the proxy serves, each with its public ports: what the proxy binds (StreamRelays) and what
 * an app on the proxy's own host may reach it on (PublicPorts) read this one answer.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class ServedStreams {

    /**
     * One enabled stream site and where its public ports forward.
     *
     * @param name    the site's name, as the relay logs name it
     * @param targets its public ports, in the order the site declares them
     */
    public record Served(@NonNull Row site, @NonNull String name, @NonNull List<StreamTarget> targets) {
    }

    private ServedStreams() {
    }

    /**
     * @param unserved told the name of each enabled stream site whose settings forward no port, and why
     * @return every enabled stream site that forwards at least one port
     */
    public static @NonNull List<Served> enabled(@NonNull BiConsumer<String, RuntimeException> unserved) {
        List<Served> served = new ArrayList<>();
        for (Row site : Models.get(SiteModel.class).findEnabled()) {
            UpstreamKindHandler handler = UpstreamKindHandlers.getHandler(site.get(SiteModel.UPSTREAM_KIND));
            if (!(handler instanceof StreamProvider provider)) continue;
            String name = String.valueOf((Object) site.get(SiteModel.NAME));
            try {
                Map<String, Object> settings = RawValues.mapOrNull(site.get(SiteModel.SETTINGS));
                served.add(new Served(site, name, List.copyOf(provider.streamTargets(site,
                    settings == null ? Map.of() : settings))));
            } catch (RuntimeException refused) {
                unserved.accept(name, refused);
            }
        }
        return List.copyOf(served);
    }
}
