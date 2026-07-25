package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.zenit.common.orm.datasource.Row;

import java.util.Map;

/** Site type capability for raw TLS routes selected before HTTP decoding. */
public interface TlsPassthroughProvider extends SiteTypeHandler {

    TlsPassthroughTarget createTlsPassthroughTarget(Row site, Map<String, Object> settings);

    @Override
    default SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        throw new UnsupportedOperationException("TLS passthrough sites do not handle HTTP requests");
    }
}
