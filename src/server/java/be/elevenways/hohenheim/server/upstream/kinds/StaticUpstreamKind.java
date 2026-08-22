package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimPaths;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.StaticFileHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;

import java.nio.file.Path;
import java.util.Map;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.PathKind;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Serves static files from a directory.
 */
public class StaticUpstreamKind implements UpstreamKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "static");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField ROOT_PATH = SETTINGS_SCHEMA.addField(
        PathField.builder("root_path").browserSource(HohenheimPaths.SERVER_FILES, PathKind.DIRECTORY)
            .label(HohenheimFormCopy.label("root_path"))
            .help(HohenheimFormCopy.help("root_path")).build());

    // Default true matches the Node original (ecstatic showed listings out of the box).
    public static final BooleanField AUTOINDEX = SETTINGS_SCHEMA.addField(
        BooleanField.builder("autoindex").defaultValue(true).label(HohenheimFormCopy.label("autoindex"))
            .help(HohenheimFormCopy.help("autoindex")).build());

    public static final BooleanField INDEXES = SETTINGS_SCHEMA.addField(
        BooleanField.builder("indexes").defaultValue(true).label(HohenheimFormCopy.label("indexes"))
            .help(HohenheimFormCopy.help("indexes")).build());

    public static final BooleanField SHOW_HIDDEN_FILES = SETTINGS_SCHEMA.addField(
        BooleanField.builder("show_hidden_files").defaultValue(false)
            .label(HohenheimFormCopy.label("show_hidden_files"))
            .help(HohenheimFormCopy.help("show_hidden_files")).build());

    public static final IntegerField DELAY = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("delay").suffix("ms").label(HohenheimFormCopy.label("delay"))
            .help(HohenheimFormCopy.help("delay")).build());

    public static final StringField FALLBACK_FILE = SETTINGS_SCHEMA.addField(
        PathField.builder().name("fallback_file").label(HohenheimFormCopy.label("fallback_file"))
            .help(HohenheimFormCopy.help("fallback_file")).build());

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() { return "Static"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("static").withFilter("scope", "upstream_kind");
    }

    @Override
    public @NonNull Microcopy getDescription() {
        return Microcopy.of("static").withFilter("scope", "upstream_kind_description");
    }

    @Override
    public Icon getIcon() { return Icon.of("folder"); }

    @Override
    public String getColor() { return "teal"; }

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
        boolean autoindex = !Boolean.FALSE.equals(settings.get("autoindex"));
        boolean indexes = !Boolean.FALSE.equals(settings.get("indexes"));
        boolean showHidden = Boolean.TRUE.equals(settings.get("show_hidden_files"));

        if (rootPathStr == null || rootPathStr.isEmpty()) {
            // Empty 200 like Node ecstatic with no root: the site exists but serves nothing.
            return (exchange, forwarder) -> {
                exchange.setStatusCode(200);
                exchange.endExchange();
            };
        }

        return new StaticFileHandler(Path.of(rootPathStr), fallbackFile, autoindex,
            indexes, showHidden);
    }
}
