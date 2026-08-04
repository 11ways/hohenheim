package be.elevenways.hohenheim.server.files;

import be.elevenways.domino.common.DominoFile;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.ErrorResponse;
import be.elevenways.zenit.common.result.ErrorResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.server.http.RedirectResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.HttpConduit;
import be.elevenways.zenit.server.http.ReturnTarget;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.URLEncoder;
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

    /** Accountability origin of everything the automation lane does. */
    private static final String API_ORIGIN = "api";

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
                return redirect(filesUrl(conduit, instanceId, parentOf(path), refused));
            }
            download(conduit, "application/octet-stream", baseName(path), content);
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
                return redirect(filesUrl(conduit, instanceId,
                    back.isEmpty() ? parentOf(path) : back, refused));
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "files_" + action, path);
            return redirect(filesUrl(conduit, instanceId,
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
                return json(Map.of("id", instanceId, "path", listing.path(),
                    "volumes", listing.volumeRoots(), "entries", entries));
            } catch (Violations refused) {
                return refusal(conduit, refused);
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
                return refusal(conduit, InstanceFilePath.refused());
            }
            try {
                download(conduit, "application/octet-stream", baseName(path),
                    new InstanceFiles().read(instanceId, path));
                return null;
            } catch (Violations refused) {
                return refusal(conduit, refused);
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
                return refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "files_write", API_ORIGIN);
            return json(Map.of("id", instanceId, "path", path, "status", "written"));
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
                return refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId,
                "files_" + action, API_ORIGIN);
            return json(Map.of("id", instanceId, "path", path, "action", action));
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
            case "save", "upload" -> files.write(instanceId, path, contentOf(form));
            case "mkdir" -> files.makeDirectory(instanceId, path);
            case "rename" -> files.rename(instanceId, path, string(form, "target"));
            case "delete" -> files.delete(instanceId, path);
            default -> throw Violations.ofForm(
                Microcopy.of("files_unknown_action").withFilter("scope", "violations"));
        }
    }

    /**
     * The bytes a write carries: an uploaded multipart part when there is one, else the
     * submitted text.
     *
     * AIDEV-NOTE: the SIZE cap is NOT applied here. The framework's HTTP layer already
     * refuses an over-limit body during the read (readNBytes + an explicit over-read probe,
     * never a truncation), and InstanceFiles re-checks the decoded length against
     * hohenheim.files.max_file_kb BEFORE a single byte is handed to a driver. Adding a
     * third check here that ran AFTER the body was buffered would be the shape this
     * codebase hunts: a bound that looks enforced and is actually a post-hoc measurement.
     */
    private static byte @NonNull [] contentOf(@NonNull Map<String, Object> form) {
        Object file = form.get("file");
        if (file instanceof List<?> list && !list.isEmpty()) {
            file = list.get(0);
        }
        if (file instanceof DominoFile uploaded && uploaded.getSize() > 0) {
            return uploaded.getBytes();
        }
        return string(form, "content").getBytes(StandardCharsets.UTF_8);
    }

    // -- plumbing -------------------------------------------------------------

    /**
     * Resolve the route's instance for an API caller: absent, trashed and not-permitted are
     * ONE 404, and a session principal is refused outright (the HTML routes are not the
     * automation API -- that is what makes csrfExempt safe here).
     *
     * @return the row, or null when the response has already been ended
     */
    private static @Nullable Row apiInstance(@NonNull Conduit conduit) {
        if (!(conduit.getAttribute(ConduitAttributes.PRINCIPAL) instanceof ApiKeyPrincipal)) {
            conduit.forbidden();
            return null;
        }
        AccessContext ctx = AccessContext.of(conduit);
        Integer instanceId = conduit.getParameter(HohenheimEndpoints.INSTANCE_ID);
        Row row = instanceId == null ? null : Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
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

    /** The Files tab URL, carrying the directory and (optionally) a refusal to render. */
    private static @NonNull String filesUrl(@NonNull Conduit conduit, int instanceId,
                                            @NonNull String directory,
                                            @Nullable Violations refused) {
        String base = ReturnTarget.or(ReturnTarget.read(conduit),
            "/admin/instances/" + instanceId + "/page/files");
        StringBuilder url = new StringBuilder(base);
        url.append(base.contains("?") ? '&' : '?').append("path=").append(encode(directory));
        if (refused != null) {
            url.append("&error=").append(encode(messageOf(conduit, refused)));
        }
        return url.toString();
    }

    /** The refusal text as the caller's locale renders it -- never a raw exception string. */
    private static @NonNull String messageOf(@NonNull Conduit conduit,
                                             @NonNull Violations violations) {
        List<Violation> all = violations.all();
        if (all.isEmpty()) {
            return String.valueOf(violations.getMessage());
        }
        return all.get(0).message().resolve(conduit.getLocales(), conduit.getMessageResolver());
    }

    /** 422 carrying the violation's MACHINE KEY, matching the rest of the v1 surface. */
    @SuppressWarnings("unchecked")
    private static @NonNull ActionResult<Object> refusal(@NonNull Conduit conduit,
                                                        @NonNull Violations violations) {
        List<Violation> all = violations.all();
        String code = all.isEmpty() ? "REFUSED" : all.get(0).message().key();
        return (ActionResult<Object>) (ActionResult<?>) new ErrorResult(
            ErrorResponse.of(422, code, messageOf(conduit, violations)));
    }

    private static @NonNull String parentOf(@NonNull String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    private static @NonNull String baseName(@NonNull String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 || slash == path.length() - 1 ? path : path.substring(slash + 1);
    }

    private static @NonNull String string(@NonNull Map<String, Object> form, @NonNull String name) {
        Object value = form.get(name);
        if (value instanceof List<?> list) {
            value = list.isEmpty() ? null : list.get(0);
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static @NonNull String encode(@NonNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Stream a binary body as a downloadable attachment with a sanitized filename. */
    private static void download(@NonNull Conduit conduit, @NonNull String contentType,
                                 @NonNull String filename, byte @NonNull [] body) {
        if (conduit instanceof HttpConduit http) {
            String safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            http.setResponseHeader("Content-Type", contentType);
            http.setResponseHeader("Content-Disposition",
                "attachment; filename=\"" + safeName + "\"");
        }
        conduit.endWithBytes(contentType, body);
    }

    @SuppressWarnings("unchecked")
    private static @NonNull ActionResult<Object> json(@NonNull Map<String, Object> body) {
        return (ActionResult<Object>) (ActionResult<?>) new JsonResult<>(body);
    }

    @SuppressWarnings("unchecked")
    private static @NonNull ActionResult<Object> redirect(@NonNull String url) {
        return (ActionResult<Object>) (ActionResult<?>) new RedirectResult(url);
    }
}
