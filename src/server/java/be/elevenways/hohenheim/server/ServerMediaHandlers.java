package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.cms.ServerMediaPage;
import be.elevenways.hohenheim.server.instance.InstallMedia;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

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

    private static @NonNull RouteTarget mediaTab(@NonNull Integer serverId) {
        return CmsRoutes.subpage(HandlerSupport.ADMIN, "servers", serverId,
            ServerMediaPage.SLUG);
    }

    private static Microcopy mediaMessage(String key, String name) {
        return Microcopy.of(key).withFilter("scope", "server_media").withArg("name", name);
    }
}
