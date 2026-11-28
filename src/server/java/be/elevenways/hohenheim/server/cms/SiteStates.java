package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.KeyedRows;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.peer.SharedRow;
import be.elevenways.zenit.server.peer.SharedStates;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The state kinds of sites and addresses: what this server tells its master of how each of its sites and addresses
 * is doing, as the facts its own Sites and Addresses lists read from this process.
 *
 * AIDEV-NOTE: a site's and an address's own columns travel in the shared record (SiteModel, SiteDomainModel); only the
 * process facts are told here: a site's TLS line and verdict (the working certificates, the proxy's dispatcher), an
 * address's "points here" line (the name lookup's cached answer, never a wait) and HTTPS line. Each entry is keyed as
 * its shared record, so the master joins them by key, and computed without a viewer: no certificate's name or link,
 * which are the reader's (ToldFacts). Two reads per kind whatever the server holds.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SiteStates {

    /** The kind a site's state is offered and kept under. */
    public static final Identifier SITE_KIND = HohenheimIds.id("site_state");

    /** The kind an address's state is offered and kept under. */
    public static final Identifier ADDRESS_KIND = HohenheimIds.id("address_state");

    private static final AtomicBoolean DECLARED = new AtomicBoolean();

    private SiteStates() {
    }

    /** Offers this server's sites' and addresses' state to its master, once however often the handlers install. */
    public static void declare() {
        if (DECLARED.compareAndSet(false, true)) {
            SharedStates.declare(SITE_KIND, SiteStates.class, SiteStates::sites);
            SharedStates.declare(ADDRESS_KIND, SiteStates.class, SiteStates::addresses);
        }
    }

    /** @return one entry per live site: its TLS line (none for a site reached on no address) and its verdict */
    private static @NonNull List<SharedRow> sites() {
        List<Row> sites = Models.get(SiteModel.class).find().noCache().all();
        Map<Integer, List<Row>> domains = domainsOf(sites);
        Set<String> working = AppHealth.workingNames();
        Function<Row, RecordHealth> health = AppHealth.sites(false).read(sites, AccessContext.anonymous());
        List<SharedRow> entries = new ArrayList<>();
        for (Row site : sites) {
            Map<String, Object> told = new LinkedHashMap<>();
            if (SiteParts.reachOf(site).asksAddress()) {
                told.put("tls", ToldFacts.line(StateLineCell.of(SiteParts.tlsOf(domains.getOrDefault(
                    site.get(SiteModel.ID), List.of()), SiteParts.tlsPassthrough(site), working), null)));
            }
            told.put("health", ToldFacts.health(health.apply(site)));
            entries.add(new SharedRow(String.valueOf((Object) site.get(SiteModel.ID)), null,
                String.valueOf((Object) site.get(SiteModel.NAME)), Zenit.DRY.toJson(told)));
        }
        return entries;
    }

    /** @return one entry per address of a live site: whether it points here, and what HTTPS gives it */
    private static @NonNull List<SharedRow> addresses() {
        Map<Integer, Row> sites = new HashMap<>();
        for (Row site : Models.get(SiteModel.class).find().noCache().all()) {
            sites.put(site.get(SiteModel.ID), site);
        }
        Set<String> working = AppHealth.workingNames();
        Supplier<List<Row>> certificates = AppHealth.certificateRows(AccessContext.anonymous());
        List<SharedRow> entries = new ArrayList<>();
        for (List<Row> named : domainsOf(List.copyOf(sites.values())).values()) {
            for (Row domain : named) {
                Map<String, Object> told = new LinkedHashMap<>();
                // The lookup's remembered answer only: a state read never waits on a resolver.
                told.put("reach", ToldFacts.line(DomainParts.reachCell(domain, 0)));
                StateLineCell https = DomainParts.certificateCell(domain, SiteParts.tlsPassthrough(
                    sites.get(domain.get(SiteDomainModel.SITE_ID))), working, certificates, AccessContext.anonymous(),
                    HohenheimSlugs.ADMIN);
                if (https != null) {
                    told.put("https", ToldFacts.line(https));
                }
                entries.add(new SharedRow(String.valueOf((Object) domain.get(SiteDomainModel.ID)), null,
                    String.valueOf((Object) domain.get(SiteDomainModel.HOSTNAME)), Zenit.DRY.toJson(told)));
            }
        }
        return entries;
    }

    /** @return the names of these sites by site, each site's in stored order, read once */
    private static @NonNull Map<Integer, List<Row>> domainsOf(@NonNull List<Row> sites) {
        List<Integer> ids = new ArrayList<>();
        for (Row site : sites) {
            ids.add(site.get(SiteModel.ID));
        }
        return ids.isEmpty() ? Map.of() : KeyedRows.byAsked(Models.get(SiteDomainModel.class).find().noCache()
            .orderBy(SiteDomainModel.ID, SortOrder.ASC), SiteDomainModel.SITE_ID, ids);
    }
}
