package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.traffic.ActivityAsk;
import be.elevenways.zenit.auth.server.ShareLink;
import be.elevenways.zenit.common.channel.ChannelHandler;
import be.elevenways.zenit.common.channel.ChannelLink;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import be.elevenways.zenit.traffic.common.LiveHits;
import be.elevenways.zenit.traffic.server.TrafficLive;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Admits a live traffic link by what it asks and listens it to exactly that: every site for an administrator (the
 * server's Traffic read's gate), one site for whoever manages it (a site's Traffic read's authorizer), each with the
 * visitors' addresses; a TV link's holder hears what the link shows, never an address. Asked again at every
 * revalidation, so a viewer who lost the right, or a TV link ended, stops hearing at once.
 *
 * AIDEV-NOTE: the frames a link hears are its topics' only (TrafficLive), so a site manager's link never carries
 * another site's paths or visitors and a TV link's no address at all: nothing is filtered after the fact. A refusal is
 * typed PERMISSION_DENIED for an unknown, foreign, ended or malformed ask alike, so the link is no site-existence or
 * token oracle and the client does not retry it.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class TrafficActivityHandler implements ChannelHandler<Object, LiveHits> {

    private final @NonNull ChannelLink<Object, LiveHits> link;
    private volatile @Nullable ActivityAsk ask;
    /** The TV link a TV ask was admitted by, null for any other ask. */
    private volatile @Nullable UUID tvLink;

    public TrafficActivityHandler(@NonNull ChannelLink<Object, LiveHits> link) {
        this.link = link;
    }

    /** Installs the factory; called at the MODULES boot stage. */
    public static void init() {
        HohenheimChannels.TRAFFIC_LIVE.setHandlerFactory(TrafficActivityHandler::new);
    }

    @Override
    public void onOpen(@Nullable Object openData) {
        ActivityAsk asked = ActivityAsk.parse(openData);
        boolean tvAsk = asked != null && asked.kind() == ActivityAsk.Kind.TV;
        if (tvAsk) {
            // Ending a link announces its record: heard before the link is judged, so an end in between still cuts.
            this.link.revalidateOnChangeOf(ControllerIdentityModel.MODEL_ID, ControllerIdentity.recordId());
        }
        ShareLink tv = tvAsk ? TrafficTvLinkLevel.open(asked.value()) : null;
        // A TV link hears what it shows, never the visitors' addresses; any other ask is its viewer's.
        ActivityAsk heard = tv != null ? TrafficTvLinkLevel.shows(tv) : !tvAsk && asked != null && this.allowed(asked)
            ? asked : null;
        if (heard == null) {
            throw new DomainRefusal(ZenitRefusalReason.PERMISSION_DENIED, "Not permitted");
        }
        this.ask = asked;
        if (tv != null) {
            this.tvLink = tv.id();
        }
        // A store opens at its first hit or read: a viewer is a reader, so the quiet seconds tick from the open on.
        HohenheimTraffic.store(HohenheimTraffic.Counted.WEB);
        TrafficLive live = HohenheimTraffic.live();
        boolean visitors = tv == null;
        live.listen(this.link, heard.kind() == ActivityAsk.Kind.EVERY ? TrafficLive.everySource(visitors)
            : TrafficLive.topicOf(Objects.requireNonNull(heard.value(), "one site's ask names it"), visitors));
    }

    @Override
    public boolean revalidate(@NonNull WebSocketSession session) {
        ActivityAsk asked = this.ask;
        UUID tv = this.tvLink;
        return asked != null && (tv != null ? TrafficTvLinkLevel.live(tv) : this.allowed(asked));
    }

    /** @return whether the link's viewer may hear what it asks: by the rule of the read of the same traffic */
    boolean allowed(@NonNull ActivityAsk asked) {
        Principal principal = this.link.getPrincipal();
        if (principal == null) {
            return false;
        }
        AccessContext viewer = AccessContext.detached(principal);
        return switch (asked.kind()) {
            case EVERY -> viewer.hasPermission(HohenheimSources.ADMIN_ACCESS);
            case SITE -> {
                Integer siteId = HohenheimTraffic.siteOf(asked.value());
                Row site = siteId == null ? null
                    : Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId)).noCache().first();
                yield site != null && SiteOperationHandlers.reaches(site, viewer);
            }
            // A TV link's holder is admitted by the link itself (onOpen), never through a viewer's session.
            case TV -> false;
        };
    }
}
