package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/** Site type capability for a TCP/UDP stream: public ports of its own on the proxy's host, relayed unread. */
public interface StreamProvider extends UpstreamKindHandler {

    /**
     * @return where each of the site's public ports forwards to, in the order the site declares them
     * @throws IllegalArgumentException naming the setting that cannot be forwarded; the site then forwards no port
     */
    @NonNull List<StreamTarget> streamTargets(Row site, Map<String, Object> settings);

    @Override
    default SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        throw new UnsupportedOperationException("TCP/UDP streams do not handle HTTP requests");
    }
}
