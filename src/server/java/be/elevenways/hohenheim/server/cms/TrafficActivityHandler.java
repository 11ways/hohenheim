package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.traffic.ActivityAsk;
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

/**
 * Admits a live traffic link by what it asks and listens it to exactly that: every site for an administrator (the
 * server's Traffic read's gate), one site for whoever manages it (a site's Traffic read's authorizer), each with the
 * visitors' addresses; asked again at every revalidation, so a viewer who lost the right stops hearing at once.
 *
 * AIDEV-NOTE: the frames a link hears are its topics' only (TrafficLive), so a site manager's link never carries
 * another site's paths or visitors: nothing is filtered after the fact. A refusal is typed PERMISSION_DENIED for an
 * unknown, foreign or malformed ask alike, so the link is no site-existence oracle and the client does not retry it.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class TrafficActivityHandler implements ChannelHandler<Object, LiveHits> {

    private final @NonNull ChannelLink<Object, LiveHits> link;
    private volatile @Nullable ActivityAsk ask;

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
        if (asked == null || !this.allowed(asked)) {
            throw new DomainRefusal(ZenitRefusalReason.PERMISSION_DENIED, "Not permitted");
        }
        this.ask = asked;
        // A store opens at its first hit or read: a viewer is a reader, so the quiet seconds tick from the open on.
        HohenheimTraffic.store(HohenheimTraffic.Counted.WEB);
        TrafficLive live = HohenheimTraffic.live();
        live.listen(this.link, asked.kind() == ActivityAsk.Kind.EVERY ? TrafficLive.everySource(true)
            : TrafficLive.topicOf(Objects.requireNonNull(asked.value(), "one site's ask names it"), true));
    }

    @Override
    public boolean revalidate(@NonNull WebSocketSession session) {
        ActivityAsk asked = this.ask;
        return asked != null && this.allowed(asked);
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
            // A TV link's bearer is admitted by the TV page's own handler, never through a viewer's session.
            case TV -> false;
        };
    }
}
