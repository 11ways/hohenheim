package be.elevenways.hohenheim.server.files;

import be.elevenways.domino.common.DominoFile;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.cms.InstanceFilesPage;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteLocation;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.ReturnTarget;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two file-manager transports: the Files tab's HTML lane and the {@code /api/v1}
 * automation lane. Neither makes an authorization decision -- both call
 * {@link InstanceFiles}, which asks for {@code files.read} or {@code files.write} on the
 * SERVICE. A handler that re-implemented either would be a second policy, and a second
 * policy is how an API becomes a wider door than the UI it mirrors.
 *
 * AIDEV-NOTE: the API lane resolves its instance through the same
 * "absent, trashed OR not permitted are ONE answer" 404 the rest of InstanceApi uses, so
 * probing another tenant's id is indistinguishable from probing a nonexistent one. The
 * HTML lane inherits the same property from the /manage resource scope plus the service
 * gate, whose refusal text never names the missing capability.
 */
public final class InstanceFileEndpoints {

    private InstanceFileEndpoints() {
    }

    public static void init() {
        initHtmlLane();
        initApiLane();
    }

    // -- HTML lane ------------------------------------------------------------

    private static void initHtmlLane() {
        HohenheimEndpoints.INSTANCE_FILE_DOWNLOAD.setHandler(conduit -> {
            Integer instanceId = conduit.getParameter(HohenheimEndpoints.INSTANCE_ID);
            String path = conduit.getQueryParam("path");
            if (instanceId == null || path == null || path.isEmpty()) {
                conduit.notFound();
                return null;
            }
            byte[] content;
            try {
                content = new InstanceFiles().read(instanceId, path);
            } catch (Violations refused) {
                return HandlerSupport.redirectUntyped(filesUrl(conduit, instanceId, parentOf(path), refused));
            }
            HandlerSupport.download(conduit, "application/octet-stream", baseName(path), content);
            return null;
        });

        HohenheimEndpoints.INSTANCE_FILE_ACTION.setHandler(conduit -> {
            Integer instanceId = conduit.getParameter(HohenheimEndpoints.INSTANCE_ID);
            if (instanceId == null) {
                conduit.notFound();
                return null;
            }
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            String action = string(form, "action");
            String path = string(form, "path");
            String back = string(form, "directory");
            try {
                perform(instanceId, action, form, path);
            } catch (Violations refused) {
                return HandlerSupport.redirectUntyped(filesUrl(conduit, instanceId,
                    back.isEmpty() ? parentOf(path) : back, refused));
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "files_" + action, path);
            return HandlerSupport.redirectUntyped(filesUrl(conduit, instanceId,
                back.isEmpty() ? parentOf(path) : back, null));
        });
    }

    // -- API lane -------------------------------------------------------------

    private static void initApiLane() {
        HohenheimEndpoints.API_INSTANCE_FILES.setHandler(conduit -> {
            Row instance = apiInstance(conduit);
            if (instance == null) {
                return null;
            }
            int instanceId = instance.get(InstanceModel.ID);
            try {
                InstanceFiles.Listing listing = new InstanceFiles()
                    .list(instanceId, conduit.getQueryParam("path"));
                List<Map<String, Object>> entries = new ArrayList<>();
                for (InstanceFiles.Entry entry : listing.entries()) {
                    Map<String, Object> projection = new LinkedHashMap<>();
                    projection.put("name", entry.name());
                    projection.put("path", entry.path());
                    projection.put("kind", entry.kind());
                    projection.put("size", entry.size());
                    projection.put("modified", entry.modified());
                    projection.put("mode", entry.mode());
                    projection.put("managed", entry.managed());
                    entries.add(projection);
                }
                return ApiConduits.json(Map.of("id", instanceId, "path", listing.path(),
                    "volumes", listing.volumeRoots(), "entries", entries));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
        });

        HohenheimEndpoints.API_INSTANCE_FILE_CONTENT.setHandler(conduit -> {
            Row instance = apiInstance(conduit);
            if (instance == null) {
                return null;
            }
            int instanceId = instance.get(InstanceModel.ID);
            String path = conduit.getQueryParam("path");
            if (path == null || path.isEmpty()) {
                return ApiConduits.refusal(conduit, InstanceFilePath.refused());
            }
            try {
                HandlerSupport.download(conduit, "application/octet-stream", baseName(path),
                    new InstanceFiles().read(instanceId, path));
                return null;
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
        });

        HohenheimEndpoints.API_INSTANCE_FILE_WRITE.setHandler(conduit -> {
            Row instance = apiInstance(conduit);
            if (instance == null) {
                return null;
            }
            int instanceId = instance.get(InstanceModel.ID);
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            String path = string(form, "path");
            try {
                new InstanceFiles().write(instanceId, path, contentOf(form));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "files_write", path);
            return ApiConduits.json(Map.of("id", instanceId, "path", path, "status", "written"));
        });

        HohenheimEndpoints.API_INSTANCE_FILE_ACTION.setHandler(conduit -> {
            Row instance = apiInstance(conduit);
            if (instance == null) {
                return null;
            }
            int instanceId = instance.get(InstanceModel.ID);
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            String action = string(form, "action");
            String path = string(form, "path");
            try {
                perform(instanceId, action, form, path);
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId,
                "files_" + action, path);
            return ApiConduits.json(Map.of("id", instanceId, "path", path, "action", action));
        });
    }

    // -- the ONE dispatch both lanes share ------------------------------------

    /**
     * Every mutating verb, in one place, so the HTML form and the API call cannot drift on
     * what an action means.
     *
     * @throws Violations {@code files_unknown_action} for anything not named here
     */
    private static void perform(int instanceId, @NonNull String action,
                                @NonNull Map<String, Object> form, @NonNull String path) {
        InstanceFiles files = new InstanceFiles();
        switch (action) {
            case "save" -> files.write(instanceId, path, contentOf(form));
            case "upload" -> files.write(instanceId, path, uploadOf(form));
            case "mkdir" -> files.makeDirectory(instanceId, path);
            case "rename" -> files.rename(instanceId, path, string(form, "target"));
            case "delete" -> files.delete(instanceId, path);
            default -> throw Violations.ofForm(
                Microcopy.of("files_unknown_action").withFilter("scope", "violations"));
        }
    }

    /**
     * The bytes a write carries: an uploaded multipart part when there is one, else the
     * submitted text -- the "save" action and the automation API's write route, which
     * accept both shapes.
     *
     * AIDEV-NOTE: the SIZE cap is NOT applied here. The framework's HTTP layer already
     * refuses an over-limit body during the read (readNBytes + an explicit over-read probe,
     * never a truncation), and InstanceFiles re-checks the decoded length against
     * hohenheim.files.max_file_kb BEFORE a single byte is handed to a driver. Adding a
     * third check here that ran AFTER the body was buffered would be the shape this
     * codebase hunts: a bound that looks enforced and is actually a post-hoc measurement.
     */
    static byte @NonNull [] contentOf(@NonNull Map<String, Object> form) {
        DominoFile uploaded = uploadedFile(form);
        if (uploaded != null && uploaded.getSize() > 0) {
            return uploaded.getBytes();
        }
        return string(form, "content").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The "upload" lane: the selected file's bytes, or a REFUSAL when no file was chosen.
     *
     * AIDEV-NOTE: the upload form never sends a {@code content} field, so the old shared
     * fallback turned "Upload" pressed with no file selected into a write of ZERO bytes
     * over whatever lived at that path. A browser submits an unselected file input as a
     * part with an empty filename; a chosen empty file keeps its name and is a legitimate
     * (empty) upload.
     *
     * Package-private so the lane's own test can drive it without an HTTP stack.
     *
     * @throws Violations {@code files_upload_missing} when no file part was chosen
     */
    static byte @NonNull [] uploadOf(@NonNull Map<String, Object> form) {
        DominoFile uploaded = uploadedFile(form);
        if (uploaded == null) {
            throw Violations.ofForm(
                Microcopy.of("files_upload_missing").withFilter("scope", "violations"));
        }
        return uploaded.getBytes();
    }

    /** The submitted {@code file} part, or null when none was chosen. */
    private static @Nullable DominoFile uploadedFile(@NonNull Map<String, Object> form) {
        Object file = form.get("file");
        if (file instanceof List<?> list && !list.isEmpty()) {
            file = list.get(0);
        }
        if (!(file instanceof DominoFile uploaded)) {
            return null;
        }
        String name = uploaded.getName();
        boolean chosen = (name != null && !name.isBlank()) || uploaded.getSize() > 0;
        return chosen ? uploaded : null;
    }

    // -- plumbing -------------------------------------------------------------

    /**
     * Resolve the route's instance for an API caller: absent, trashed and not-permitted are
     * ONE 404, and a session principal is refused outright (the HTML routes are not the
     * automation API -- that is what makes csrfExempt safe here).
     *
     * AIDEV-NOTE: the {@link InstanceModel#liveAuthored} clause is the SAME scope
     * {@code InstanceApi.visibleInstances}/{@code visibleInstance} and TenantScopes.INSTANCES
     * apply, read from its one home so the lanes cannot drift. docs/paas-api.md says the automation API "never lists or drives"
     * a product-tier-generated instance; without this clause the file lane was the one
     * v1 route that could address one (latent -- files.read has no impliedBy and nothing
     * plants it on a generated row -- but an operator can hand-grant it).
     *
     * @return the row, or null when the response has already been ended
     */
    private static @Nullable Row apiInstance(@NonNull Conduit conduit) {
        AccessContext ctx = ApiConduits.requireKey(conduit);
        if (ctx == null) {
            return null;
        }
        Integer instanceId = conduit.getParameter(HohenheimEndpoints.INSTANCE_ID);
        Row row = instanceId == null ? null : Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.liveAuthored())
            .first();
        // Visibility rides files.read: an id whose files the caller may not even LIST must
        // read as nonexistent, not as forbidden.
        if (row == null || !HohenheimAccess.hasInstanceCapability(ctx, instanceId,
                HohenheimAccess.FILES_READ)) {
            conduit.notFound();
            return null;
        }
        return row;
    }

    /**
     * The Files tab URL for a directory, stashing any refusal as a flash toast first.
     *
     * AIDEV-NOTE: the destination is USUALLY the submitted _return, which ReturnTarget
     * hands back as an already-sanitized String; the FALLBACK is built from the typed
     * route. Either way the directory is set through {@link RouteLocation#with} on the
     * declared {@link HohenheimParams#FILES_PATH}, which REPLACES a {@code path=} the
     * captured page already carried -- appending used to put the parameter in the URL
     * twice, and which one a reader honoured was up to the reader. The refusal does NOT
     * ride the URL: it is a notification, so it rides the session flash.
     */
    private static @NonNull String filesUrl(@NonNull Conduit conduit, int instanceId,
                                            @NonNull String directory,
                                            @Nullable Violations refused) {
        if (refused != null) {
            HohenheimFlash.error(conduit, HandlerSupport.violationMessage(refused));
        }
        String base = ReturnTarget.or(ReturnTarget.read(conduit),
            CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES, instanceId,
                InstanceFilesPage.SLUG).toUrl());
        return RouteLocation.with(base, HohenheimParams.FILES_PATH,
            directory.isEmpty() ? null : directory);
    }

    private static @NonNull String parentOf(@NonNull String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    private static @NonNull String baseName(@NonNull String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 || slash == path.length() - 1 ? path : path.substring(slash + 1);
    }

    /**
     * The submitted value as is.
     *
     * AIDEV-NOTE: deliberately NOT {@code HandlerSupport.submittedString}, which trims: a
     * file path or file content with leading or trailing whitespace is a different path or
     * a different file.
     */
    private static @NonNull String string(@NonNull Map<String, Object> form, @NonNull String name) {
        Object value = form.get(name);
        if (value instanceof List<?> list) {
            value = list.isEmpty() ? null : list.get(0);
        }
        return value == null ? "" : String.valueOf(value);
    }
}
