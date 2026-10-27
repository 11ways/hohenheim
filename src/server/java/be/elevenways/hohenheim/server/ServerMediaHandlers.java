package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.cms.ServerMediaTab;
import be.elevenways.hohenheim.server.instance.InstallMedia;
import be.elevenways.hohenheim.server.instance.InstallMediaFetches;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
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

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * Install media on an Incus host: URL fetch (a background job, see InstallMediaFetches), upload
 * and delete forms of the server record's Install media tab. Every endpoint declares the media permission; the
 * service refuses non-Incus hosts by name.
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
                // Every refusal an operator must read now is decided HERE, on the request
                // thread; the download and the import then run as a background job whose
                // stored state the Install media tab renders (and refreshes while it runs).
                // The public-address guard, the ISO cap and the deadline are the fetcher's
                // own and did not move.
                media.requireFetchable(server, name, url);
                InstallMediaFetches.start(server, name, progress ->
                    media.transfer(server, name, url, progress, progress::importing));
            } catch (Violations refused) {
                HohenheimFlash.error(conduit, HandlerSupport.violationMessage(refused));
                return HandlerSupport.redirect(tab);
            }
            // The activity row ("media_fetched") is written by the job when the medium reads
            // back on the host, never for a fetch that is merely accepted.
            HohenheimFlash.success(conduit, HohenheimMicrocopy.SERVER_MEDIA.of("media_fetch_started")
                .withArg("name", name));
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
            name = trimmed(name);
            if (!(conduit instanceof HttpConduit http)) {
                conduit.notFound();
                return null;
            }
            Path temp;
            try {
                temp = Files.createTempFile("hohenheim-media-upload-", ".iso");
            } catch (IOException e) {
                return uploadFailure(conduit, serverId, HohenheimMicrocopy.SERVER_MEDIA.of("upload_failed")
                    .withArg("name", name));
            }
            try {
                long size = http.streamBodyTo(temp, InstallMedia.MAX_ISO_BYTES);
                if (size == 0) {
                    return uploadFailure(conduit, serverId, HohenheimMicrocopy.SERVER_MEDIA.of("upload_empty")
                        .withArg("name", name));
                }
                media.importFrom(server, name, temp);
            } catch (Violations refused) {
                return uploadFailure(conduit, serverId, HandlerSupport.violationMessage(refused));
            } catch (IOException | RuntimeException e) {
                Blast.log("MEDIA: upload of", name, "to",
                    server.get(ServerModel.NAME), "failed -", e.getMessage());
                return uploadFailure(conduit, serverId, HohenheimMicrocopy.SERVER_MEDIA.of("upload_failed")
                    .withArg("name", name));
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // A leftover temp file is worth a log, never a failed upload the
                    // daemon already accepted.
                    Blast.log("MEDIA: could not remove the upload temp file", temp);
                }
            }
            History.event(Models.get(ServerModel.class), serverId, Change.fact(HohenheimActivityAction.MEDIA_UPLOADED,
                Map.of(HohenheimActivityAction.NAME_KEY, name)));
            HohenheimFlash.success(conduit, HohenheimMicrocopy.SERVER_MEDIA.of("media_uploaded").withArg("name", name));
            // The tab's live part hears the new medium; the answer is a bare status rather than a redirect: there is
            // no form post to send back.
            ServerMediaTab.poolChanged(serverId);
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
            History.event(Models.get(ServerModel.class), serverId, Change.fact(HohenheimActivityAction.MEDIA_DELETED,
                Map.of(HohenheimActivityAction.NAME_KEY, name)));
            HohenheimFlash.success(conduit, HohenheimMicrocopy.SERVER_MEDIA.of("media_deleted").withArg("name", name));
            // Another reader's open tab redraws its part without the deleted medium.
            ServerMediaTab.poolChanged(serverId);
            return HandlerSupport.redirect(tab);
        });
    }

    /**
     * A refused upload FLASHES like every other media outcome and answers a bare 422:
     * the uploader reloads the tab on any answer, so the reason reaches the operator
     * through the one message lane this tab already has, in their own language, with
     * no second copy of the resolution machinery here.
     */
    private static ActionResult<Object> uploadFailure(@NonNull Conduit conduit, int serverId,
                                                      @NonNull Microcopy reason) {
        HohenheimFlash.error(conduit, reason);
        // The tab's live part hears the upload end.
        ServerMediaTab.poolChanged(serverId);
        conduit.setResponseStatus(422);
        conduit.endWithContentType("text/plain; charset=utf-8", "");
        return null;
    }

    private static @NonNull RouteTarget mediaTab(@NonNull Integer serverId) {
        return CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.SERVERS, serverId,
            HohenheimSlugs.Tab.INSTALL_MEDIA);
    }
}
