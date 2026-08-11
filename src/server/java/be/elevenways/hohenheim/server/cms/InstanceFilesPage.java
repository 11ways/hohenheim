package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.files.InstanceFiles;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Files tab on an instance: a browser over the instance's own volumes, plus an inline
 * editor for text files.
 *
 * The page makes NO authorization decision. It asks {@link InstanceFiles}, which gates on
 * {@code files.read}, and renders the write controls only when the context also holds
 * {@code files.write} -- an affordance on top of a gate, never instead of one: the action
 * endpoint asks the service for write authority regardless of what this page drew.
 */
public final class InstanceFilesPage implements RecordScopedPage<Row> {

    public static final String SLUG = "files";

    /** Above this, the inline editor is not offered -- a browser is not a hex editor. */
    private static final long INLINE_EDIT_LIMIT = 512 * 1024;

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_files_browser"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("files").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("folder-tree"); }

    /**
     * The tab exists only for a principal that may actually READ this record's files.
     *
     * AIDEV-NOTE: it had NO gate at all -- the tab rendered for every viewer and the
     * refusal arrived as an error banner from {@code InstanceFiles.list}, which is a
     * broken-looking tab where an absent one is the truth. Hide AND enforce, exactly like
     * the exec tab: zenit-cms 404s an unoffered slug, so this gates the route too, and
     * the service still asks the same capability on every call.
     */
    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext accessContext) {
        return HohenheimAccess.hasInstanceCapability(
            accessContext, record.get(InstanceModel.ID), HohenheimAccess.FILES_READ);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        InstanceFiles files = new InstanceFiles();

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME));
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("instanceId", instanceId);
        vars.put("recordTabs", recordTabs(conduit));
        vars.put("returnUrl", ReturnTarget.capture(conduit));
        vars.put("actionUrl", "/instances/" + instanceId + "/files/action");
        vars.put("downloadBase", "/instances/" + instanceId + "/files/download?path=");
        vars.put("browseBase", CmsSupport.panelBase(conduit) + "/instances/" + instanceId
            + "/page/" + SLUG + "?path=");
        vars.put("canWrite", HohenheimAccess.hasInstanceCapability(accessContext, instanceId,
            HohenheimAccess.FILES_WRITE));
        vars.put("maxFileBytes", InstanceFiles.maxFileBytes());

        String error = conduit.getQueryParam("error");
        vars.put("error", error == null ? "" : error);
        vars.put("entries", List.of());
        vars.put("crumbs", List.of());
        vars.put("volumes", List.of());
        vars.put("path", "");
        vars.put("parentPath", "");
        vars.put("editPath", "");
        vars.put("editContent", "");
        vars.put("editTooLarge", false);

        // A runtime that has no file lane at all is a NAMED state, not an error banner:
        // only the Docker driver implements InstanceFileSupport, so an Incus workload
        // renders "not available for this runtime yet" and no browser chrome.
        boolean supported = files.isSupported(instanceId);
        vars.put("supported", supported);
        if (!supported) {
            return new RenderTemplateResult(
                Identifier.of("hohenheim", "cms/instance-files"), vars);
        }

        String requested = conduit.getQueryParam("path");
        String editing = conduit.getQueryParam("edit");
        try {
            InstanceFiles.Listing listing = files.list(instanceId, requested);
            vars.put("path", listing.path());
            vars.put("volumes", listing.volumeRoots());
            vars.put("crumbs", crumbsOf(listing));
            vars.put("parentPath", parentWithin(listing));
            List<Map<String, Object>> entries = new ArrayList<>();
            for (InstanceFiles.Entry entry : listing.entries()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", entry.name());
                row.put("path", entry.path());
                row.put("kind", entry.kind());
                row.put("directory", "DIRECTORY".equals(entry.kind()));
                // A symlink is SHOWN (hiding it would be a lie about what is on the volume)
                // but never followed, and every action on it is refused by the containment
                // walk -- so it renders as an inert row.
                row.put("symlink", "SYMLINK".equals(entry.kind()));
                row.put("size", entry.size());
                row.put("mode", entry.mode());
                row.put("managed", entry.managed());
                row.put("editable", "FILE".equals(entry.kind()) && !entry.managed()
                    && entry.size() <= INLINE_EDIT_LIMIT);
                entries.add(row);
            }
            vars.put("entries", entries);

            if (editing != null && !editing.isEmpty()) {
                vars.put("editPath", editing);
                vars.put("editContent", files.readText(instanceId, editing));
            }
        } catch (Violations refused) {
            vars.put("error", messageOf(conduit, refused));
        }
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-files"), vars);
    }

    /** One crumb per path segment from the volume root down, each a browsable path. */
    private static @NonNull List<Map<String, Object>> crumbsOf(InstanceFiles.@NonNull Listing listing) {
        List<Map<String, Object>> crumbs = new ArrayList<>();
        String root = rootOf(listing);
        StringBuilder current = new StringBuilder(root);
        crumbs.add(crumb(root, root));
        String remainder = listing.path().substring(root.length());
        for (String segment : remainder.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            current.append('/').append(segment);
            crumbs.add(crumb(segment, current.toString()));
        }
        return crumbs;
    }

    private static @NonNull Map<String, Object> crumb(@NonNull String label, @NonNull String path) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("label", label);
        entry.put("path", path);
        return entry;
    }

    /** The parent path, clamped at the volume root (never "" and never outside it). */
    private static @NonNull String parentWithin(InstanceFiles.@NonNull Listing listing) {
        String root = rootOf(listing);
        if (listing.path().equals(root)) {
            return "";
        }
        int slash = listing.path().lastIndexOf('/');
        return slash <= 0 ? root : listing.path().substring(0, slash);
    }

    private static @NonNull String rootOf(InstanceFiles.@NonNull Listing listing) {
        for (String root : listing.volumeRoots()) {
            if (listing.path().equals(root) || listing.path().startsWith(root + "/")) {
                return root;
            }
        }
        return listing.path();
    }

    private static @NonNull String messageOf(@NonNull Conduit conduit,
                                             @NonNull Violations violations) {
        List<Violation> all = violations.all();
        if (all.isEmpty()) {
            return String.valueOf(violations.getMessage());
        }
        return all.get(0).message().resolve(conduit.getLocales(), conduit.getMessageResolver());
    }
}
