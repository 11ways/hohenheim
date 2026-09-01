package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.application.InstanceUpstreamHandler;
import be.elevenways.hohenheim.server.sitetype.FaultedSiteHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * Serves a hostname from an INSTANCE this deployment manages: the site names the instance
 * ({@code sites.instance_id}) and the routing build resolves that instance's serving
 * container's published loopback port.
 *
 * AIDEV-NOTE: this kind absorbs the serving half of the deleted {@code docker} site type.
 * Its dispatch is WIRED since phase-0 brief 7: {@link #createHandler} builds an
 * {@link InstanceUpstreamHandler}, which re-resolves through {@code ApplicationUpstreams}
 * whenever the record's generation moves -- a release-managed record resolves its serving
 * release, any other exposable kind resolves its own published port. A record with
 * nothing serving still answers the honest 503.
 */
public final class InstanceUpstreamKind implements UpstreamKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "instance");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /**
     * Which declared port of the instance this site serves from.
     *
     * AIDEV-NOTE: blank means "the instance's single publication", which is the only shape
     * the runtime honours today (InstanceTemplateModel records why there is no port LIST).
     * It is a NAME rather than a number so a site keeps pointing at the same logical port
     * when the instance's settings move it, and so the multi-port day is a resolver change
     * rather than a stored-shape change.
     */
    public static final StringField PORT = SETTINGS_SCHEMA.addField(
        StringField.builder().name("port")
            .label(HohenheimFormCopy.label("instance_port"))
            .help(HohenheimFormCopy.help("instance_port"))
            .build());

    public static final EnumField SCHEME = SETTINGS_SCHEMA.addField(
        EnumField.builder("scheme")
            .value("http", "HTTP", UpstreamCopy.scheme("http"))
            .value("https", "HTTPS", UpstreamCopy.scheme("https"))
            .defaultValue("http")
            .label(HohenheimFormCopy.label("forward_scheme"))
            .help(HohenheimFormCopy.help("forward_scheme"))
            .build());

    public static final BooleanField WEBSOCKET_UPGRADE = SETTINGS_SCHEMA.addField(
        BooleanField.builder("websocket_upgrade").defaultValue(true)
            .label(HohenheimFormCopy.label("websocket_upgrade"))
            .help(HohenheimFormCopy.help("websocket_upgrade")).build());

    public static final IntegerField REQUEST_TIMEOUT = SETTINGS_SCHEMA.addField(
        UpstreamSettings.requestTimeout());

    @Override public Identifier typeId() { return ID; }

    @Override public String getDisplayName() { return "Instance"; }

    @Override public @NonNull Microcopy getLabel() {
        return Microcopy.of("instance").withFilter("scope", "upstream_kind");
    }

    @Override public @NonNull Microcopy getDescription() {
        return Microcopy.of("instance").withFilter("scope", "upstream_kind_description");
    }

    @Override public Icon getIcon() { return Icon.of("box"); }

    @Override public String getColor() { return "blue"; }

    @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override public boolean requiresInstance() { return true; }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {

        Integer instanceId = site.get(SiteModel.INSTANCE_ID);

        if (instanceId == null) {
            return new FaultedSiteHandler(site.get(SiteModel.ID),
                "this site names no instance to serve from");
        }

        Object scheme = settings.get(SCHEME.getName());

        return new InstanceUpstreamHandler(site.get(SiteModel.ID), instanceId,
            !Boolean.FALSE.equals(settings.get(WEBSOCKET_UPGRADE.getName())),
            scheme == null || scheme.toString().isBlank() ? "http" : scheme.toString());
    }
}
