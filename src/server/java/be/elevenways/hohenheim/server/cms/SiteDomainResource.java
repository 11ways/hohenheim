package be.elevenways.hohenheim.server.cms;


import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.ListenerAddressMatcher;
import be.elevenways.hohenheim.server.proxy.RouteClaims;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.server.sitetype.types.TlsPassthroughSiteType;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Site domain entries: hostname matching, TLS/HSTS toggles, per-domain
 * headers and certificate pinning. Hidden from the sidebar -- reached
 * through a site's Domains tab.
 */
public class SiteDomainResource extends RowResource {

    static final List<FieldOption<String>> MATCH_OPTIONS = List.of(
        FieldOption.of(SiteDomainModel.MATCH_EXACT,
            Microcopy.of("exact").withFilter("scope", "domain_match")),
        FieldOption.of(SiteDomainModel.MATCH_WILDCARD,
            Microcopy.of("wildcard").withFilter("scope", "domain_match")),
        FieldOption.of(SiteDomainModel.MATCH_REGEX,
            Microcopy.of("regex").withFilter("scope", "domain_match")));

    /** Discovered local addresses (refreshed hourly by UpdateSystemIpAddresses); blank = all interfaces. */
    static List<FieldOption<String>> listenOnOptions() {
        List<FieldOption<String>> options = new ArrayList<>();
        for (String address : UpdateSystemIpAddresses.getLocalAddresses()) {
            options.add(FieldOption.of(address, address));
        }
        return options;
    }

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(SiteDomainModel.SITE_ID, SiteModel.MODEL_ID).build())
        .add(SiteDomainModel.HOSTNAME)
        .add(Select.of(SiteDomainModel.MATCH_TYPE).options(OptionSource.of(MATCH_OPTIONS)).build())
        .add(Select.of(SiteDomainModel.LISTEN_ON)
            .options(OptionSource.dynamic(ctx -> listenOnOptions()))
            .build())
        .add(SiteDomainModel.PATH)
        .add(SiteDomainModel.STRIP_PATH)
        .add(SiteDomainModel.FORCE_SSL)
        .add(RelationPick.of(SiteDomainModel.CERTIFICATE_ID, CertificateModel.MODEL_ID).build())
        .add(SiteDomainModel.HSTS_ENABLED)
        .add(SiteDomainModel.HSTS_SUBDOMAINS)
        .add(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteDomainModel.CUSTOM_HEADERS))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteDomainModel.RESPONSE_HEADERS))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(SiteDomainModel.HOSTNAME).filterable().build())
        .column(ColumnSpec.fromField(SiteDomainModel.MATCH_TYPE).filterable().build())
        .column(ColumnSpec.fromField(SiteDomainModel.FORCE_SSL).filterable().build())
        .column(ColumnSpec.fromField(SiteDomainModel.SITE_ID)
            .relation(RelationPick.of(SiteDomainModel.SITE_ID, SiteModel.MODEL_ID).build()).build())
        .filter(FilterSpec.forField(SiteDomainModel.HOSTNAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(SiteDomainModel.HOSTNAME)).build())
        .filter(FilterSpec.forField(SiteDomainModel.MATCH_TYPE, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(SiteDomainModel.MATCH_TYPE)).build())
        .filter(FilterSpec.forField(SiteDomainModel.FORCE_SSL, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(SiteDomainModel.FORCE_SSL)).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_domain"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "site_domain"); }
    @Override public @NonNull String slug() { return "domains"; }
    @Override public @NonNull Model model() { return Models.get(SiteDomainModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.PROXY_GROUP; }
    @Override public int navOrder() { return 20; }
    @Override public @NonNull Icon icon() { return Icon.of("at"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @org.checkerframework.checker.nullness.qual.Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("sites", row -> row.get(SiteDomainModel.SITE_ID)).tab("domains");
    }


    /** The site's Domains tab links here with ?site_id= so the pick is preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String siteId = conduit.getQueryParam("site_id");
        if (siteId != null && !siteId.isEmpty()) {
            try {
                int parsedSiteId = Integer.parseInt(siteId);
                values.put("site_id", parsedSiteId);
                Row site = Models.get(SiteModel.class).findById(parsedSiteId);
                if (site != null && TlsPassthroughSiteType.ID.toString()
                        .equals(site.get(SiteModel.SITE_TYPE))) {
                    values.put("force_ssl", false);
                    values.put("exclude_from_letsencrypt", true);
                }
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.copyOf(values);
    }

    private static volatile boolean routeInvariantInstalled;

    /**
     * Install THE domain route invariant on the SiteDomainModel write pipeline: path
     * canonicalization, the hostname/route refusal and the live-route claim stamp all run
     * for every writer, not just for a CMS form submit.
     *
     * AIDEV-NOTE: this MUST live in the write pipeline, never in the resource layer --
     * the same reasoning as SiteResource.installEnableInvariant, which this mirrors.
     * These checks used to sit in persistRow/updateRow, where the ONLY thing keeping a
     * bypass hypothetical was that SiteDomainModel happens not to be revisionable today:
     * making it revisionable, or adding any second writer (a seeder, an import, an API
     * writeback, a site-scoped bulk edit, the clone action's direct model.save), would
     * silently reopen hostname takeover. A resource-layer invariant IS the bypass.
     * Do NOT move any of this back into persistRow / updateRow as a per-path check.
     *
     * The refusal lives on the beforeVALIDATE tier and the claim stamp on the beforeWRITE
     * tier of the SAME Schema.beforeWrite pass. That is deliberate: the scan is the
     * advisory diagnosis and must run before the row is judged, the claim is the
     * authoritative write-time backstop and must be the last thing to happen before the
     * row hits the datasource.
     */
    public static synchronized void installRouteInvariant() {
        if (routeInvariantInstalled) {
            return;
        }
        routeInvariantInstalled = true;
        SiteDomainModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null) {
                canonicalizePath(row);
                refuseRouteConflicts(row);
            }
        });
        SiteDomainModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Object siteIdValue = SiteDomainModel.effective(row, SiteDomainModel.SITE_ID);
            Row site = siteIdValue instanceof Integer siteId
                ? Models.get(SiteModel.class).findById(siteId) : null;
            row.set(SiteDomainModel.LIVE_ROUTE_KEY,
                RouteClaims.isLive(site) ? RouteClaims.keyOfPendingWrite(row) : null);
        });
    }

    /**
     * Store the path exactly as the dispatcher will route it, so a stored row cannot
     * spell a route differently than it resolves ("app" -> "/app", "/app/" -> "/app",
     * "/" -> null catch-all). An absent key is untouched, for partial updates.
     */
    private static void canonicalizePath(@NonNull Row row) {
        if (!row.has(SiteDomainModel.PATH.getName())) {
            return;
        }
        Object raw = row.get(SiteDomainModel.PATH);
        String canonical = SiteDispatcher.normalizeRoutePath(raw != null ? String.valueOf(raw) : null);
        if (!Objects.equals(raw, canonical)) {
            row.set(SiteDomainModel.PATH, canonical);
        }
    }

    /** Hostname required, a site required, and the route unclaimed. */
    private static void refuseRouteConflicts(@NonNull Row row) {
        Object hostnameValue = SiteDomainModel.effective(row, SiteDomainModel.HOSTNAME);
        String hostname = hostnameValue != null ? String.valueOf(hostnameValue).trim() : "";
        if (hostname.isEmpty()) {
            throw Violations.ofField("hostname", hostname, CmsSupport.violationText("hostname_required"));
        }
        Object siteIdValue = SiteDomainModel.effective(row, SiteDomainModel.SITE_ID);
        if (!(siteIdValue instanceof Integer siteId)) {
            throw Violations.ofField("site_id", siteIdValue, CmsSupport.violationText("site_required"));
        }
        Row site = Models.get(SiteModel.class).findById(siteId);
        // Uniqueness compares CANONICAL route components: the hostname as the model hook
        // stores it (SiteDomainModel.canonicalHostname -- ONE definition), the path as
        // the dispatcher routes it (normalizeRoutePath), and listener restrictions as
        // ListenerAddressMatcher parses them. Two rows conflict when their listener sets
        // can OVERLAP (an empty set means every address); disjoint sets are genuinely
        // distinct routes. Match type is deliberately NOT part of the identity: an exact
        // and a wildcard row with the same literal hostname shadow each other in the
        // tiered lookup, which is a config mistake worth refusing, not two routes.
        //
        // The check is GLOBAL, not per-site: the dispatcher's route table spans every
        // enabled site and silently drops the loser of a duplicate claim (first-wins by
        // site name), so a same-route row on ANOTHER site is exactly as broken as one on
        // this site. Rows of DISABLED other sites (clones, staged drafts) are exempt --
        // they hold no routes; the conflict is refused again on the site-enable edit.
        String matchType = stringValue(SiteDomainModel.effective(row, SiteDomainModel.MATCH_TYPE));
        String canonicalHostname = SiteDomainModel.canonicalHostname(hostname, matchType);
        String path = normalizedPath(SiteDomainModel.effective(row, SiteDomainModel.PATH));
        List<String> listenOn = ListenerAddressMatcher.parse(
            stringValue(SiteDomainModel.effective(row, SiteDomainModel.LISTEN_ON)));
        Object ownId = row.has(SiteDomainModel.ID.getName()) ? row.get(SiteDomainModel.ID) : null;

        Map<Integer, Row> sitesById = new HashMap<>();
        for (Row candidateSite : Models.get(SiteModel.class).find().all()) {
            sitesById.put(candidateSite.get(SiteModel.ID), candidateSite);
        }
        // A row on a site that does not ROUTE holds no routes, in either direction:
        // staging a duplicate on a draft/clone site is legal, and only the site-enable
        // edit re-judges it against the live table. A soft-deleted site is exactly as
        // routeless as a disabled one -- RouteClaims.isLive is the ONE definition, so a
        // deleted site can never keep a hostname hostage. Same-site duplicates are
        // always a config mistake, live or not.
        boolean ownSiteLive = RouteClaims.isLive(site);

        for (Row candidate : Models.get(SiteDomainModel.class).find().all()) {
            if (ownId != null && ownId.equals(candidate.get(SiteDomainModel.ID))) {
                continue;
            }
            Integer candidateSiteId = candidate.get(SiteDomainModel.SITE_ID);
            boolean sameSite = Objects.equals(candidateSiteId, siteId);
            Row candidateSite = sitesById.get(candidateSiteId);
            if (!sameSite && (!RouteClaims.isLive(candidateSite) || !ownSiteLive)) {
                continue;
            }
            String candidateHostname = SiteDomainModel.canonicalHostname(
                candidate.get(SiteDomainModel.HOSTNAME), candidate.get(SiteDomainModel.MATCH_TYPE));
            if (!Objects.equals(canonicalHostname, candidateHostname)
                || !Objects.equals(path, normalizedPath(candidate.get(SiteDomainModel.PATH)))
                || !listenersOverlap(listenOn,
                    ListenerAddressMatcher.parse(candidate.get(SiteDomainModel.LISTEN_ON)))) {
                continue;
            }
            if (!sameSite) {
                String siteName = candidateSite != null
                    ? String.valueOf(candidateSite.get(SiteModel.NAME)) : "#" + candidateSiteId;
                throw Violations.ofField(path == null ? "hostname" : "path",
                    path == null ? hostname : path,
                    CmsSupport.violationText("route_taken_other_site").withArg("site", siteName));
            }
            if (path == null) {
                throw Violations.ofField("hostname", hostname, CmsSupport.violationText("hostname_taken"));
            }
            throw Violations.ofField("path", path, CmsSupport.violationText("route_taken"));
        }
    }

    /**
     * The site-ENABLE side of the global route check: rows of a disabled site are
     * exempt while it stays disabled, so enabling it must re-run the comparison
     * against every other enabled site's rows.
     *
     * @throws Violations anchored on {@code enabled} naming the conflicting site
     */
    static void refuseEnableRouteConflicts(int siteId) {
        Map<Integer, Row> sitesById = new HashMap<>();
        for (Row site : Models.get(SiteModel.class).find().all()) {
            sitesById.put(site.get(SiteModel.ID), site);
        }
        List<Row> allRows = Models.get(SiteDomainModel.class).find().all();
        for (Row own : allRows) {
            if (!Objects.equals(own.get(SiteDomainModel.SITE_ID), siteId)) {
                continue;
            }
            String ownHostname = SiteDomainModel.canonicalHostname(
                own.get(SiteDomainModel.HOSTNAME), own.get(SiteDomainModel.MATCH_TYPE));
            String ownPath = normalizedPath(own.get(SiteDomainModel.PATH));
            List<String> ownListen = ListenerAddressMatcher.parse(own.get(SiteDomainModel.LISTEN_ON));
            for (Row candidate : allRows) {
                Integer candidateSiteId = candidate.get(SiteDomainModel.SITE_ID);
                if (Objects.equals(candidateSiteId, siteId)) {
                    continue;
                }
                // RouteClaims.isLive, not a bare enabled check: a soft-deleted site keeps
                // enabled=true (deleteRow only stamps deleted_at), so an enabled-only
                // test let a DELETED site hold its hostname hostage forever, refusing
                // every later claimant in the name of a site that appears in no UI.
                Row candidateSite = sitesById.get(candidateSiteId);
                if (!RouteClaims.isLive(candidateSite)) {
                    continue;
                }
                if (!Objects.equals(ownHostname, SiteDomainModel.canonicalHostname(
                        candidate.get(SiteDomainModel.HOSTNAME), candidate.get(SiteDomainModel.MATCH_TYPE)))
                    || !Objects.equals(ownPath, normalizedPath(candidate.get(SiteDomainModel.PATH)))
                    || !listenersOverlap(ownListen,
                        ListenerAddressMatcher.parse(candidate.get(SiteDomainModel.LISTEN_ON)))) {
                    continue;
                }
                throw Violations.ofField("enabled", true,
                    CmsSupport.violationText("enable_route_conflict")
                        .withArg("hostname", String.valueOf(own.get(SiteDomainModel.HOSTNAME)))
                        .withArg("site", String.valueOf(candidateSite.get(SiteModel.NAME))));
            }
        }
    }

    /**
     * An empty restriction listens everywhere, so it overlaps every other set.
     * {@code intersection} answers NULL (not empty) for disjoint sets.
     */
    private static boolean listenersOverlap(@NonNull List<String> first, @NonNull List<String> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return true;
        }
        return ListenerAddressMatcher.intersection(first, second) != null;
    }

    private static @Nullable String stringValue(@Nullable Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    /** Canonical route path for uniqueness, delegated to the routing authority. */
    private static @Nullable String normalizedPath(@Nullable Object value) {
        return SiteDispatcher.normalizeRoutePath(value != null ? String.valueOf(value) : null);
    }

}
