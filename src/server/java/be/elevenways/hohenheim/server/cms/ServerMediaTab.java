package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.instance.InstallMediaFetchState;
import be.elevenways.hohenheim.instance.InstallMediaFetchView;
import be.elevenways.hohenheim.instance.InstallMediaView;
import be.elevenways.hohenheim.instance.InstallMediumView;
import be.elevenways.hohenheim.model.InstallMediaFetchModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstallMedia;
import be.elevenways.hohenheim.server.instance.InstallMediaFetches;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.server.page.RecordTabPart;
import be.elevenways.zenit.common.live.LiveFeed;
import be.elevenways.zenit.common.live.LiveRead;
import be.elevenways.zenit.common.orm.model.ModelChanges;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.server.live.LiveReads;
import be.elevenways.zenit.server.live.LiveServer;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * Install media tab on an Incus host: the ISO volumes of its managed pool (LIVE
 * daemon truth, the store the cdrom device rows reference by name), the stored state of
 * the background fetches (InstallMediaFetches), a fetch-from-URL form and per-medium delete. Hidden and 404d on Docker
 * hosts -- their daemon has no ISO volume to hold (the devices-tab hide-AND-enforce shape).
 */
public final class ServerMediaTab implements RecordTab.Rendered<Row> {

    /**
     * An upload into or a delete from a host's pool ended: the daemon holds the media, so no model write announces
     * it. Announced keyed by the host ({@link #poolChanged}).
     */
    private static final @NonNull Identifier POOL = HohenheimIds.id("install_media_pool");

    /**
     * The tab's media and fetches as one live part, its parameter {@code panel,serverId,page}: heard on its own host's
     * fetch rows and pool changes, never another host's, a piece of the host's Install media subpage, read again
     * through the panel's gate and this tab's own for the reader, so a fetch's progress, its end and an upload redraw
     * the part alone and never the fetch and upload forms beside it.
     */
    public static final LiveRead<String, InstallMediaView> PART =
        LiveRead.of(HohenheimIds.id("install_media"), String.class, InstallMediaView.class)
            .heard(part -> {
                RecordTabPart.At at = ServerMediaTab.TAB_PART.at(part);
                return at == null ? List.of() : List.of(LiveFeed.keyed(
                    access -> access.hasPermission(HohenheimSources.MEDIA_MANAGE), at.key(),
                    InstallMediaFetchModel.MODEL_ID, POOL));
            });

    /** Where {@link #PART} lives: the Install media tab of a server record. */
    private static final RecordTabPart TAB_PART = new RecordTabPart(HohenheimSlugs.SERVERS,
        HohenheimSlugs.Tab.INSTALL_MEDIA);

    /** Serve {@link #PART}; called once by HohenheimHandlers. */
    public static void registerPart() {
        LiveReads.serve(PART, TAB_PART::page, ServerMediaTab::partRead);
    }

    /** Tell the readers of one host's part that its pool changed. */
    public static void poolChanged(int serverId) {
        LiveServer.INVALIDATIONS.changed(POOL, ModelChanges.Keys.of(serverId));
    }

    /** The part's read: the panel's gate admits the reader to the servers, then this tab's gate to the host. */
    private static @NonNull InstallMediaView partRead(@NonNull AccessContext access, @NonNull String part) {
        if (!(TAB_PART.open(access, part).record() instanceof Row server)) {
            throw new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "No such install media tab");
        }
        return view(server);
    }

    @Override public @NonNull Identifier id() { return HohenheimIds.id("server_media"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.SERVER_MEDIA.of("tab"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.INSTALL_MEDIA; }
    @Override public @NonNull Icon icon() { return Icon.of("compact-disc"); }

    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext access) {
        return ServerModel.isIncus(record);
    }

    /**
     * Managing install media is its own authority, not a use of the admin permission:
     * this gate hides the tab AND 404s the route for an admin without it, and the same
     * permission is what the three media endpoints declare.
     */
    @Override
    public @Nullable Permission requiredPermission() {
        return HohenheimSources.MEDIA_MANAGE;
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row server) {
        Conduit conduit = request.conduit();
        Integer serverId = server.get(ServerModel.ID);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, HohenheimMicrocopy.SERVER_MEDIA,
            server.get(ServerModel.NAME)));
        vars.put("serverName", server.get(ServerModel.NAME));
        // The live part's first paint; the same view is what its re-read answers.
        vars.put("part", LiveReads.at(PART, TAB_PART.parameter(request.panelSlug(), serverId, 1),
            () -> view(server)));
        vars.put("fetchTarget", HohenheimEndpoints.SERVERS_MEDIA_FETCH
            .with(HohenheimEndpoints.SERVER_ID, serverId));
        vars.put("deleteTarget", HohenheimEndpoints.SERVERS_MEDIA_DELETE
            .with(HohenheimEndpoints.SERVER_ID, serverId));
        // The uploader is a fetch() call, not a form post, so the template needs the URL
        // itself rather than a RouteTarget to render into an action attribute.
        vars.put("uploadUrl", HohenheimEndpoints.SERVERS_MEDIA_UPLOAD
            .with(HohenheimEndpoints.SERVER_ID, serverId).toUrl());
        vars.put("maxIsoBytes", InstallMedia.MAX_ISO_BYTES);
        vars.put("head", recordHead(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.SERVER_MEDIA, vars);
    }

    /**
     * THE install-media view of one Incus host, rendered by the tab and answered by its part's re-read
     * ({@link #PART}).
     *
     * AIDEV-NOTE: the fetch lane runs in the background, so its stored rows are the only place a running
     * download, its progress or its failure reason can be read. They render here, and the tab's part
     * re-reads this view whenever one of its host's fetch rows changes instead of reloading the whole tab on a timer.
     */
    public static @NonNull InstallMediaView view(@NonNull Row server) {
        List<InstallMediumView> media = new ArrayList<>();
        String loadError = null;
        try {
            for (InstallMedia.Medium medium : new InstallMedia().listFor(server)) {
                media.add(new InstallMediumView(medium.name(),
                    medium.description() == null ? "" : medium.description()));
            }
        } catch (IOException unreachable) {
            // An unreachable daemon renders the tab with a named error instead of a 500:
            // the operator came here to manage media, and "the host did not answer" is
            // an answer about the host, not a page failure.
            Blast.log("MEDIA: listing install media of", server.get(ServerModel.NAME),
                "failed -", unreachable.getMessage());
            loadError = unreachable.getMessage();
        }

        List<InstallMediaFetchView> fetches = new ArrayList<>();
        Integer serverId = server.get(ServerModel.ID);
        Set<String> listed = new HashSet<>();
        for (InstallMediumView medium : media) {
            listed.add(medium.name());
        }
        for (Row fetch : InstallMediaFetches.shownFor(serverId)) {
            InstallMediaFetchState state = InstallMediaFetches.stateOf(fetch);
            // A READY ending is about its volume: once the host no longer lists it (deleted outside this tab, or
            // before the delete forgot it), "Available" would be false, so it follows the listing. An unreadable
            // listing proves nothing either way.
            if (state == InstallMediaFetchState.READY && loadError == null
                    && !listed.contains(fetch.get(InstallMediaFetchModel.NAME))) {
                continue;
            }
            Double progress = fetch.get(InstallMediaFetchModel.PROGRESS);
            fetches.add(new InstallMediaFetchView(
                fetch.get(InstallMediaFetchModel.NAME),
                state.label(),
                state.variant(),
                state.active(),
                state.active() && progress != null ? Integer.valueOf((int) Math.floor(progress * 100)) : null,
                fetch.get(InstallMediaFetchModel.ERROR)));
        }
        return new InstallMediaView(media, fetches, loadError);
    }
}
