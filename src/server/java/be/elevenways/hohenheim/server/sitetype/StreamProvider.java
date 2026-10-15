package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.util.Map;

/** Site type capability for a TCP/UDP stream: a public port of its own on the proxy's host, relayed unread. */
public interface StreamProvider extends UpstreamKindHandler {

    /**
     * @return where the site's public port forwards to
     * @throws IllegalArgumentException naming the setting that cannot be forwarded
     */
    StreamTarget streamTarget(Row site, Map<String, Object> settings);

    @Override
    default SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        throw new UnsupportedOperationException("TCP/UDP streams do not handle HTTP requests");
    }
}
