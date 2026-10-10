package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.app.PutOnlineGroup;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimPaths;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.sitetype.FaultedSiteHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.StaticFileHandler;
import be.elevenways.hohenheim.server.upstream.TenantUpstreams;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;

import java.nio.file.Path;
import java.util.Map;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.PathKind;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Serves static files from a directory.
 */
public class StaticUpstreamKind implements UpstreamKindHandler {

    public static final Identifier ID = HohenheimIds.id("static");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField ROOT_PATH = SETTINGS_SCHEMA.addField(
        PathField.builder("root_path").browserSource(HohenheimPaths.SERVER_FILES, PathKind.DIRECTORY)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("root_path"))
            .help(HohenheimMicrocopy.HELP.of("root_path")).build());

    // Default true matches the Node original (ecstatic showed listings out of the box).
    public static final BooleanField AUTOINDEX = SETTINGS_SCHEMA.addField(
        BooleanField.builder("autoindex").defaultValue(true).label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("autoindex"))
            .help(HohenheimMicrocopy.HELP.of("autoindex")).build());

    public static final BooleanField INDEXES = SETTINGS_SCHEMA.addField(
        BooleanField.builder("indexes").defaultValue(true).label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("indexes"))
            .help(HohenheimMicrocopy.HELP.of("indexes")).build());

    public static final BooleanField SHOW_HIDDEN_FILES = SETTINGS_SCHEMA.addField(
        BooleanField.builder("show_hidden_files").defaultValue(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("show_hidden_files"))
            .help(HohenheimMicrocopy.HELP.of("show_hidden_files")).build());

    public static final IntegerField DELAY = SETTINGS_SCHEMA.addField(UpstreamSettings.delay());

    public static final StringField FALLBACK_FILE = SETTINGS_SCHEMA.addField(
        PathField.builder().name("fallback_file").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("fallback_file"))
            .help(HohenheimMicrocopy.HELP.of("fallback_file")).build());

    // The folder and the fallback file are the decision (board App-Config-Address); what a
    // folder shows is the next thing changed, and the delay is right almost always so its
    // Connection section folds. AIDEV-NOTE: never the framework's generic "Advanced" here (the site
    // form has its own); after the fields -- membership is validated eagerly.
    static {
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.open(HohenheimFormSections.LISTING,
            List.of(AUTOINDEX.getName(), INDEXES.getName(), SHOW_HIDDEN_FILES.getName())));
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.FORWARDING,
            List.of(DELAY.getName())));
    }

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() { return "Static"; }

    @Override
    public Icon getIcon() { return Icon.of("folder"); }

    /** A folder of the operator's own files: offered beside their code, not among the addresses. */
    @Override
    public PutOnlineGroup putOnlineGroup() { return PutOnlineGroup.OWN_CODE; }

    @Override
    public BadgeColor color() { return ColorHue.TEAL; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    /** The served directory. */
    @Override
    public String upstreamSummary(Map<String, Object> settings) {
        Object root = settings.get(ROOT_PATH.getName());
        return root != null && !String.valueOf(root).isBlank() ? String.valueOf(root) : null;
    }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        String rootPathStr = (String) settings.get("root_path");
        String fallbackFile = (String) settings.get("fallback_file");
        boolean autoindex = RawValues.isOn(settings, AUTOINDEX);
        boolean indexes = RawValues.isOn(settings, INDEXES);
        boolean showHidden = RawValues.isOn(settings, SHOW_HIDDEN_FILES);

        if (rootPathStr == null || rootPathStr.isEmpty()) {
            // Empty 200 like Node ecstatic with no root: the site exists but serves nothing.
            return (exchange, forwarder) -> {
                exchange.setStatusCode(200);
                exchange.endExchange();
            };
        }

        // AIDEV-NOTE: a tenant-owned static site is REFUSED at dial time. No allowed-roots
        // declaration exists that could say which host directories a tenant may publish, and
        // without one root_path serves any directory hohenheim can read (its own database and
        // settings included). Fail closed until the operator owns the site again, or marks
        // its upstream trusted (TenantUpstreams.publicOnly).
        if (TenantUpstreams.publicOnly(site)) {
            Integer siteId = site.get(SiteModel.ID);
            return new FaultedSiteHandler(siteId != null ? siteId : -1,
                HohenheimMicrocopy.SITE_FAULT.of("tenant_host_files"));
        }

        return new StaticFileHandler(Path.of(rootPathStr), fallbackFile, autoindex,
            indexes, showHidden);
    }
}
