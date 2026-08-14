package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstallMedia;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Install media tab on an Incus host: the ISO volumes of its managed pool (LIVE
 * daemon truth, the store the cdrom device rows reference by name), a fetch-from-URL
 * form and per-medium delete. Hidden and 404d on Docker hosts -- their daemon has no
 * ISO volume to hold (the devices-tab hide-AND-enforce shape).
 */
public final class ServerMediaPage implements RecordScopedPage<Row> {

    public static final String SLUG = "install-media";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "server_media"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("tab").withFilter("scope", "server_media"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("compact-disc"); }

    @Override
    public boolean visibleFor(@NonNull Row record) {
        return ServerModel.isIncus(record);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row server) {
        Integer serverId = server.get(ServerModel.ID);
        List<Map<String, Object>> media = new ArrayList<>();
        String loadError = null;
        try {
            for (InstallMedia.Medium medium : new InstallMedia().listFor(server)) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("name", medium.name());
                entry.put("description", medium.description());
                media.add(entry);
            }
        } catch (IOException unreachable) {
            // An unreachable daemon renders the tab with a named error instead of a 500:
            // the operator came here to manage media, and "the host did not answer" is
            // an answer about the host, not a page failure.
            Blast.log("MEDIA: listing install media of", server.get(ServerModel.NAME),
                "failed -", unreachable.getMessage());
            loadError = unreachable.getMessage();
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "server_media",
            server.get(ServerModel.NAME)));
        vars.put("serverName", server.get(ServerModel.NAME));
        vars.put("media", media);
        vars.put("loadError", loadError);
        vars.put("fetchTarget", HohenheimEndpoints.SERVERS_MEDIA_FETCH
            .with(HohenheimEndpoints.SERVER_ID, serverId));
        vars.put("deleteTarget", HohenheimEndpoints.SERVERS_MEDIA_DELETE
            .with(HohenheimEndpoints.SERVER_ID, serverId));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/server-media"), vars);
    }
}
