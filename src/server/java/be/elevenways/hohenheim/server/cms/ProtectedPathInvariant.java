package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * THE protected-path invariant on the model write pipeline: the path stored canonically (the dispatcher's own
 * spelling), a usable prefix, a list, and one row per (site, path), for every writer, not just the CMS form, for the
 * reason {@link SiteDomainRouteInvariant#installRouteInvariant} spells out.
 *
 * AIDEV-NOTE: moved out of the legacy ProtectedPathResource unchanged when the protected paths moved onto parts
 * ({@link ProtectedPathParts}): a schema hook is not a resource part. Installed by HohenheimWriteHooks.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class ProtectedPathInvariant {

    private ProtectedPathInvariant() {
    }

    private static volatile boolean protectionInvariantInstalled;

    /** Installs the invariant once per JVM. */
    public static synchronized void install() {
        if (protectionInvariantInstalled) {
            return;
        }
        protectionInvariantInstalled = true;
        ProtectedPathModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            if (row.has(ProtectedPathModel.PATH.getName())) {
                Object raw = row.get(ProtectedPathModel.PATH);
                String canonical = SiteDispatcher.normalizeRoutePath(
                    raw != null ? String.valueOf(raw) : null);
                // Canonical null means "/" or blank: guarding everything is the site's own
                // access list, and a stored null would silently guard NOTHING here.
                if (canonical == null) {
                    throw Violations.ofField(ProtectedPathModel.PATH.getName(), raw,
                        CmsSupport.violationText("protected_path_required"));
                }
                if (!Objects.equals(raw, canonical)) {
                    row.set(ProtectedPathModel.PATH, canonical);
                }
            }
            refuseIncompleteOrDuplicate(row);
        });
    }

    /** Site and list required, and the (site, path) pair unclaimed. */
    private static void refuseIncompleteOrDuplicate(@NonNull Row row) {
        Model model = Models.get(ProtectedPathModel.class);
        Row stored = row.has(ProtectedPathModel.ID.getName())
            && row.get(ProtectedPathModel.ID) != null
            ? model.findById(row.get(ProtectedPathModel.ID)) : null;

        Object siteIdValue = row.afterWrite(ProtectedPathModel.SITE_ID, stored);
        if (!(siteIdValue instanceof Integer siteId)) {
            throw Violations.ofField(ProtectedPathModel.SITE_ID.getName(), siteIdValue,
                CmsSupport.violationText("site_required"));
        }
        Object listId = row.afterWrite(ProtectedPathModel.ACCESS_LIST_ID, stored);
        if (!(listId instanceof Integer)) {
            throw Violations.ofField(ProtectedPathModel.ACCESS_LIST_ID.getName(), listId,
                CmsSupport.violationText("access_list_required"));
        }
        Object path = row.afterWrite(ProtectedPathModel.PATH, stored);
        if (path == null || String.valueOf(path).isBlank()) {
            throw Violations.ofField(ProtectedPathModel.PATH.getName(), path,
                CmsSupport.violationText("protected_path_required"));
        }
        Object ownId = stored != null ? stored.get(ProtectedPathModel.ID) : null;
        for (Row candidate : model.find()
                .where(ProtectedPathModel.SITE_ID.eq(siteId))
                .and(ProtectedPathModel.PATH.eq(String.valueOf(path))).all()) {
            if (!Objects.equals(candidate.get(ProtectedPathModel.ID), ownId)) {
                throw Violations.ofField(ProtectedPathModel.PATH.getName(), path,
                    CmsSupport.violationText("protected_path_taken"));
            }
        }
    }
}
