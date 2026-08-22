package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.server.sitetype.TlsPassthroughProvider;
import be.elevenways.hohenheim.server.sitetype.TlsPassthroughTarget;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.validator.Range;

import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;

/** Passes the original TLS stream to a backend selected by the domain's SNI pattern. */
public final class TlsPassthroughUpstreamKind implements TlsPassthroughProvider {

    public static final Identifier ID = Identifier.of("hohenheim", "tls_passthrough");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField FORWARD_HOST = SETTINGS_SCHEMA.addField(
        StringField.builder().name("forward_host").required()
            .label(HohenheimFormCopy.label("tls_forward_host"))
            .help(HohenheimFormCopy.help("tls_forward_host"))
            .build());

    public static final IntegerField FORWARD_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("forward_port").defaultValue(443).required()
            .validator(Range.of(1, 65535))
            .label(HohenheimFormCopy.label("forward_port"))
            .help(HohenheimFormCopy.help("tls_forward_port"))
            .build());

    public static final BooleanField PROXY_PROTOCOL_V2 = SETTINGS_SCHEMA.addField(
        BooleanField.builder("proxy_protocol_v2").defaultValue(false)
            .label(HohenheimFormCopy.label("proxy_protocol_v2"))
            .help(HohenheimFormCopy.help("proxy_protocol_v2"))
            .build());

    public static final IntegerField CONNECT_TIMEOUT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("connect_timeout").defaultValue(10).required().suffix("s")
            .validator(Range.of(1, 300))
            .label(HohenheimFormCopy.label("connect_timeout"))
            .help(HohenheimFormCopy.help("connect_timeout"))
            .build());

    @Override public Identifier typeId() { return ID; }
    @Override public String getDisplayName() { return "TLS passthrough"; }

    @Override public @NonNull Microcopy getLabel() {
        return Microcopy.of("tls_passthrough").withFilter("scope", "upstream_kind");
    }
    @Override public @NonNull Microcopy getDescription() {
        return Microcopy.of("tls_passthrough").withFilter("scope", "upstream_kind_description");
    }
    @Override public Icon getIcon() { return Icon.of("shuffle"); }
    @Override public String getColor() { return "cyan"; }
    @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public TlsPassthroughTarget createTlsPassthroughTarget(Row site, Map<String, Object> settings) {
        String host = settings.get("forward_host") instanceof String value ? value : null;
        int port = settings.get("forward_port") instanceof Integer value ? value : 443;
        int timeoutSeconds = settings.get("connect_timeout") instanceof Integer value ? value : 10;
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("Connect timeout must be between 1 and 300 seconds");
        }
        return new TlsPassthroughTarget(host, port,
            Boolean.TRUE.equals(settings.get("proxy_protocol_v2")), timeoutSeconds * 1000);
    }
}
