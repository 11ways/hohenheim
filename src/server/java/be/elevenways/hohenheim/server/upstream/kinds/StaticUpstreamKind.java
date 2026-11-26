package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.app.PutOnlineGroup;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimPaths;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.files.SiteFiles;
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
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Serves static files from a directory.
 */
public class StaticUpstreamKind implements UpstreamKindHandler {

    public static final Identifier ID = HohenheimIds.id("static");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /**
     * Whether the site serves its OWN files, kept in the storage it names under its own folder of keys, instead of a
     * folder of the host ({@link #ROOT_PATH}). Who manages the site puts them there (SFTP, the Files tab, the tools).
     *
     * AIDEV-NOTE: a settings key, not a column: the setting lives in the kind's own schema like every other, so no
     * migration carries it. The served place is derived from the site's id ({@code SiteFiles.prefixOf}), never typed,
     * so it names no host path and a site managed by a non-administrator needs no trusted upstream for it.
     */
    public static final BooleanField OWN_FILES = SETTINGS_SCHEMA.addField(
        BooleanField.builder("own_files").defaultValue(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("own_files"))
            .help(HohenheimMicrocopy.HELP.of("own_files")).build());

    /**
     * The storage mount a site's own files live in; blank is the installation's own site-files folder. An
     * administrator's choice (TenantWrites freezes the settings a delegate may not author).
     */
    public static final StringField STORAGE = SETTINGS_SCHEMA.addField(
        StringField.builder("storage")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("storage"))
            .help(HohenheimMicrocopy.HELP.of("site_storage")).build());

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

    public static final StringField NOT_FOUND_PAGE = SETTINGS_SCHEMA.addField(
        PathField.builder().name("not_found_page").inputName("missing_page")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("not_found_page"))
            .help(HohenheimMicrocopy.HELP.of("not_found_page")).placeholder("404.html").build());

    // The folder and the fallback file are the decision; what a
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

    /** The served directory, or that the site serves its own files. */
    @Override
    public String upstreamSummary(Map<String, Object> settings) {
        if (RawValues.isOn(settings, OWN_FILES)) {
            return SiteFiles.storageNameOf(settings);
        }
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
        String notFound = (String) settings.get(NOT_FOUND_PAGE.getName());
        Integer siteId = site.get(SiteModel.ID);

        // A site's own files are a place derived from its id in a storage Hohenheim mounts: no host path anyone
        // typed, so no trust is asked, whoever manages the site.
        if (RawValues.isOn(settings, OWN_FILES) && siteId != null) {
            try {
                return StaticFileHandler.stored(SiteFiles.servedTree(siteId, settings),
                    HohenheimSettings.positiveOrDefault(HohenheimSettings.Files.MAX_ENTRIES), fallbackFile, notFound,
                    autoindex, indexes, showHidden);
            } catch (Violations unmounted) {
                return new FaultedSiteHandler(siteId, HohenheimMicrocopy.SITE_FAULT.of("site_files_storage"));
            }
        }

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
            return new FaultedSiteHandler(siteId != null ? siteId : -1,
                HohenheimMicrocopy.SITE_FAULT.of("tenant_host_files"));
        }

        return new StaticFileHandler(Path.of(rootPathStr), fallbackFile, notFound, autoindex, indexes, showHidden);
    }
}
