package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.cms.ServerMediaPage;
import be.elevenways.hohenheim.server.instance.InstallMedia;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.HttpConduit;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Install media on an Incus host: URL fetch + delete forms of the server record's
 * Install media tab. Both endpoints declare the admin permission; the service refuses
 * non-Incus hosts by name.
 */
final class ServerMediaHandlers {

    private ServerMediaHandlers() {
    }

    static void init() {
        InstallMedia media = new InstallMedia();

        HohenheimEndpoints.SERVERS_MEDIA_FETCH.setHandler(conduit -> {
            Integer serverId = conduit.getParameter(HohenheimEndpoints.SERVER_ID);
            Row server = Models.get(ServerModel.class).findById(serverId);
            if (server == null) {
                conduit.notFound();
                return null;
            }
            RouteTarget tab = mediaTab(serverId);
            Map<String, String> form = HandlerSupport.formMap(conduit);
            String name = form.getOrDefault("name", "").trim();
            String url = form.getOrDefault("url", "").trim();
            try {
                // The fetch downloads and re-uploads a multi-GB ISO synchronously (the
                // backup lane's contract); the endpoint's rate limit bounds abuse.
                media.fetch(server, name, url);
            } catch (Violations refused) {
                HohenheimFlash.error(conduit, HandlerSupport.violationMessage(refused));
                return HandlerSupport.redirect(tab);
            }
            ActivityLog.record(Models.get(ServerModel.class), serverId, "media_fetched", name);
            HohenheimFlash.success(conduit, mediaMessage("media_fetched", name));
            return HandlerSupport.redirect(tab);
        });

        HohenheimEndpoints.SERVERS_MEDIA_UPLOAD.setHandler(conduit -> {
            Integer serverId = conduit.getParameter(HohenheimEndpoints.SERVER_ID);
            Row server = Models.get(ServerModel.class).findById(serverId);
            if (server == null) {
                conduit.notFound();
                return null;
            }
            // The BODY is the ISO, so the name cannot travel in a form: parsing one
            // would buffer the whole image first, which is the thing this lane exists
            // to avoid. It rides the query string instead.
            String name = conduit.getQueryParam("name");
            name = name == null ? "" : name.trim();
            if (!(conduit instanceof HttpConduit http)) {
                conduit.notFound();
                return null;
            }
            Path temp;
            try {
                temp = Files.createTempFile("hohenheim-media-upload-", ".iso");
            } catch (IOException e) {
                return uploadFailure(conduit, mediaMessage("upload_failed", name));
            }
            try {
                long size = http.streamBodyTo(temp, InstallMedia.MAX_ISO_BYTES);
                if (size == 0) {
                    return uploadFailure(conduit, mediaMessage("upload_empty", name));
                }
                media.importFrom(server, name, temp);
            } catch (Violations refused) {
                return uploadFailure(conduit, HandlerSupport.violationMessage(refused));
            } catch (IOException | RuntimeException e) {
                Blast.log("MEDIA: upload of", name, "to",
                    server.get(ServerModel.NAME), "failed -", e.getMessage());
                return uploadFailure(conduit, mediaMessage("upload_failed", name));
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // A leftover temp file is worth a log, never a failed upload the
                    // daemon already accepted.
                    Blast.log("MEDIA: could not remove the upload temp file", temp);
                }
            }
            ActivityLog.record(Models.get(ServerModel.class), serverId, "media_uploaded", name);
            HohenheimFlash.success(conduit, mediaMessage("media_uploaded", name));
            // The uploader reloads the tab itself, so the answer is a bare status rather
            // than a redirect: there is no form post to send back.
            conduit.setResponseStatus(200);
            conduit.endWithContentType("text/plain; charset=utf-8", "");
            return null;
        });

        HohenheimEndpoints.SERVERS_MEDIA_DELETE.setHandler(conduit -> {
            Integer serverId = conduit.getParameter(HohenheimEndpoints.SERVER_ID);
            Row server = Models.get(ServerModel.class).findById(serverId);
            if (server == null) {
                conduit.notFound();
                return null;
            }
            RouteTarget tab = mediaTab(serverId);
            String name = HandlerSupport.formMap(conduit).getOrDefault("name", "").trim();
            try {
                media.delete(server, name);
            } catch (Violations refused) {
                HohenheimFlash.error(conduit, HandlerSupport.violationMessage(refused));
                return HandlerSupport.redirect(tab);
            }
            ActivityLog.record(Models.get(ServerModel.class), serverId, "media_deleted", name);
            HohenheimFlash.success(conduit, mediaMessage("media_deleted", name));
            return HandlerSupport.redirect(tab);
        });
    }

    /**
     * A refused upload FLASHES like every other media outcome and answers a bare 422:
     * the uploader reloads the tab on any answer, so the reason reaches the operator
     * through the one message lane this tab already has, in their own language, with
     * no second copy of the resolution machinery here.
     */
    private static ActionResult<Object> uploadFailure(@NonNull Conduit conduit,
                                                      @NonNull Microcopy reason) {
        HohenheimFlash.error(conduit, reason);
        conduit.setResponseStatus(422);
        conduit.endWithContentType("text/plain; charset=utf-8", "");
        return null;
    }

    private static @NonNull RouteTarget mediaTab(@NonNull Integer serverId) {
        return CmsRoutes.subpage(HandlerSupport.ADMIN, "servers", serverId,
            ServerMediaPage.SLUG);
    }

    private static Microcopy mediaMessage(String key, String name) {
        return Microcopy.of(key).withFilter("scope", "server_media").withArg("name", name);
    }
}
