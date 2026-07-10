package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;

import java.nio.file.Path;
import java.util.Map;
import be.elevenways.zenit.common.ui.Icon;

/**
 * Serves static files from a directory.
 */
public class StaticSiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "static");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField ROOT_PATH = SETTINGS_SCHEMA.addField(
        StringField.builder().name("root_path").build());

    // Default true matches the Node original (ecstatic showed listings out of the box).
    public static final BooleanField AUTOINDEX = SETTINGS_SCHEMA.addField(
        BooleanField.builder("autoindex").defaultValue(true).build());

    public static final BooleanField INDEXES = SETTINGS_SCHEMA.addField(
        BooleanField.builder("indexes").defaultValue(true).build());

    public static final BooleanField SHOW_HIDDEN_FILES = SETTINGS_SCHEMA.addField(
        BooleanField.builder("show_hidden_files").defaultValue(false).build());

    public static final IntegerField DELAY = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("delay").build());

    public static final StringField FALLBACK_FILE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("fallback_file").build());

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() { return "Static"; }

    @Override
    public String getDescription() { return "Serve static files from a directory"; }

    @Override
    public Icon getIcon() { return Icon.of("folder"); }

    @Override
    public String getColor() { return "teal"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

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
