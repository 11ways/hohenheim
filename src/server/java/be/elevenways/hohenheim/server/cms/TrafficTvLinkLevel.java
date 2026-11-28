package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.traffic.ActivityAsk;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.access.ShareLinkScope;
import be.elevenways.zenit.auth.model.LinkBearerPrincipal;
import be.elevenways.zenit.auth.server.GrantableModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ShareLink;
import be.elevenways.zenit.auth.server.ShareLinkLevel;
import be.elevenways.zenit.auth.server.ShareLinks;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.RecordCapabilityRules;
import be.elevenways.zenit.server.http.ExternalUrls;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The TV page's links as zenit-auth share links: a level of their own on this server's controller identity row, its
 * scope the {@link ActivityAsk} spelling of what a link shows (every site, or one), its address {@code /tv/<token>}.
 *
 * AIDEV-NOTE: no table of ours. The level's capability is one no grant holds (the model registers no
 * KnownCapabilities, so no access page is offered for it), minted only by the model's admin (an administrator,
 * {@link RecordCapabilityRules#admin}). The level is NOT reshown: no token is kept, so an address exists only in the
 * answer of its make (TrafficTvLinks parks it, shown once). Revoking announces the record (AuthAccessChanges), so an
 * open TV page's live link is asked again at once (TrafficActivityHandler).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class TrafficTvLinkLevel {

    /** The one capability a TV link confers: watching the live traffic picture it names. */
    public static final String CAPABILITY = "watch_traffic";

    /** One instance: a grantable declaration is add-or-throw. */
    private static final GrantableModel GRANTABLE = GrantableModel.of(ControllerIdentityModel.MODEL_ID);

    public static final ShareLinkLevel LEVEL = ShareLinkLevel.of(HohenheimIds.id("traffic_tv_link"),
            ControllerIdentityModel.MODEL_ID, CAPABILITY, (conduit, link, token) -> urlFor(conduit, token))
        .scopedBy((actor, record) -> scopes());

    private static boolean declared;

    private TrafficTvLinkLevel() {
    }

    /** Declares the controller identity shared by the TV level alone, its admin an administrator; boot, idempotent. */
    public static synchronized void declare() {
        if (declared) return;
        declared = true;
        RecordGrants.declareGrantable(GRANTABLE);
        RecordCapabilityRules.declare(ControllerIdentityModel.MODEL_ID,
            RecordCapabilityRules.create().admin(HohenheimSources.ADMIN_ACCESS));
        ShareLinks.declareLevel(LEVEL);
    }

    /** @return the address a TV link's holder opens, under the base the request reached this server at */
    public static @NonNull String urlFor(@Nullable Conduit conduit, @NonNull String token) {
        String base = ExternalUrls.requesterBaseOrNull(conduit);
        return (base == null ? "" : base) + HohenheimEndpoints.TV_PAGE.with(HohenheimEndpoints.TV_TOKEN, token)
            .toUrl();
    }

    /** @return what a link may show: every site, then each site by name */
    private static @NonNull List<ShareLinkScope> scopes() {
        List<ShareLinkScope> scopes = new ArrayList<>();
        scopes.add(new ShareLinkScope(ActivityAsk.every().spelled(),
            HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_link_every_site")));
        for (Row site : Models.get(SiteModel.class).find().orderBy(SiteModel.NAME, SortOrder.ASC).all()) {
            scopes.add(new ShareLinkScope(ActivityAsk.site(HohenheimTraffic.sourceOf(site.get(SiteModel.ID)))
                .spelled(), Microcopy.literal(site.get(SiteModel.NAME))));
        }
        return scopes;
    }

    /** @return the live TV link a token opens, null for a wrong, ended or expired token, or another level's */
    public static @Nullable ShareLink open(@Nullable String token) {
        return tvLinkOf(ShareLinks.resolve(token));
    }

    /** @return whether the stored link is still a live TV link */
    public static boolean live(@NonNull UUID linkId) {
        return tvLinkOf(ShareLinks.bearer(linkId)) != null;
    }

    /**
     * @return what the TV link a reader reads as shows, null for any other reader (an account, another link's bearer,
     *         a link ended or expired since)
     */
    public static @Nullable ActivityAsk showsTo(@NonNull AccessContext reader) {
        ShareLink link = reader.principal() instanceof LinkBearerPrincipal bearer ? tvLinkOf(bearer) : null;
        return link == null ? null : shows(link);
    }

    /** @return the reader a token's live TV link reads as, detached; null for a wrong, ended or expired token */
    public static @Nullable AccessContext holderOf(@Nullable String token) {
        LinkBearerPrincipal bearer = ShareLinks.resolve(token);
        return tvLinkOf(bearer) == null ? null : AccessContext.detached(Objects.requireNonNull(bearer));
    }

    /**
     * @return the live TV link a bearer holds, null for none, another level's, one ended or expired, or one of a site
     *         in the trash
     */
    private static @Nullable ShareLink tvLinkOf(@Nullable LinkBearerPrincipal bearer) {
        ShareLink link = bearer == null ? null : ShareLinks.linkOf(bearer);
        return link != null && isTv(link) && !ofLiveSites(List.of(link)).isEmpty() ? link : null;
    }

    /**
     * THE "is its site in the trash" fact of TV links, for the list and every token read alike: a link of one site
     * in the trash is left out, and comes back with its site.
     *
     * @param links TV links
     * @return those showing every site, or a site out of the trash, in order; one read of the sites they name
     */
    static @NonNull List<ShareLink> ofLiveSites(@NonNull List<ShareLink> links) {
        Set<Integer> named = new HashSet<>();
        for (ShareLink link : links) {
            Integer site = siteShown(link);
            if (site != null) named.add(site);
        }
        Set<Integer> live = new HashSet<>();
        if (!named.isEmpty()) {
            // The default find hides the trash, as SiteModel.ofLiveSite does for a site's other children.
            for (Row site : Models.get(SiteModel.class).find().where(SiteModel.ID.in(named)).all()) {
                live.add(site.get(SiteModel.ID));
            }
        }
        List<ShareLink> kept = new ArrayList<>();
        for (ShareLink link : links) {
            ActivityAsk shows = shows(link);
            if (shows != null && shows.kind() == ActivityAsk.Kind.SITE && !live.contains(siteShown(link))) continue;
            kept.add(link);
        }
        return kept;
    }

    /** @return the site a one-site link shows, null for every other link or a source naming no site */
    private static @Nullable Integer siteShown(@NonNull ShareLink link) {
        ActivityAsk shows = shows(link);
        return shows == null || shows.kind() != ActivityAsk.Kind.SITE ? null
            : HohenheimTraffic.siteOf(Objects.requireNonNull(shows.value()));
    }

    /** @return what a TV link shows: every site or one, null for a scope no TV link is made with */
    public static @Nullable ActivityAsk shows(@NonNull ShareLink link) {
        return shows(link.scope());
    }

    /** @return what a TV link of this stored scope shows: every site or one, null for one no TV link is made with */
    public static @Nullable ActivityAsk shows(@Nullable String scope) {
        ActivityAsk shows = scope == null ? null : ActivityAsk.parse(scope);
        return shows == null || shows.kind() == ActivityAsk.Kind.TV ? null : shows;
    }

    private static boolean isTv(@NonNull ShareLink link) {
        return CAPABILITY.equals(link.capability())
            && ControllerIdentityModel.MODEL_ID.equals(link.record().model());
    }
}
