package be.elevenways.hohenheim.server.cms;


import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.proxy.HostnamePatterns;
import be.elevenways.hohenheim.server.proxy.ListenerAddressMatcher;
import be.elevenways.hohenheim.server.proxy.ReleasedClaims;
import be.elevenways.hohenheim.server.proxy.RouteClaims;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.server.sitetype.types.TlsPassthroughSiteType;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
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
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
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

    /** The Domains tab's quick-add entries; the site rides along as a host-supplied preset. */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(SiteDomainModel.HOSTNAME.getName(), SiteDomainModel.FORCE_SSL.getName())
        .presets(SiteDomainModel.SITE_ID.getName());

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
        .column(ColumnSpec.fromField(SiteDomainModel.HOSTNAME).filterable().copyable().build())
        // The path is half of what this route matches; a match type without it is a rule
        // with its subject missing.
        .column(ColumnSpec.fromField(SiteDomainModel.MATCH_TYPE).filterable().subtext("path").build())
        .column(ColumnSpec.fromField(SiteDomainModel.PATH).hidden().build())
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

    /** A route is looked up by the host it answers on and the path it claims. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(SiteDomainModel.HOSTNAME, SiteDomainModel.PATH);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 20; }
    @Override public @NonNull Icon icon() { return Icon.of("at"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @org.checkerframework.checker.nullness.qual.Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("sites", row -> row.get(SiteDomainModel.SITE_ID)).tab("domains");
    }


    /**
     * Writing a domain row demands {@code manage} on the site it binds to.
     *
     * AIDEV-NOTE: declared HERE, on the base, and not left to the delegated peer. Until
     * this wave the mirror was safe only by COINCIDENCE -- no base {@code writableBy} plus
     * a read scope that already excluded foreign sites -- and the moment a surface offers
     * a one-click pencil, "the list only shows yours" stops being the whole answer: the
     * pencil, the cell endpoint and the commit all consult THIS predicate. The write
     * pipeline's own {@code TenantWrites} freeze stays the gate; this decides the
     * affordance, so a view-only delegate is never shown a control that can only refuse.
     */
    @Override
    public boolean writableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        // reachesRecord, never canManageSite: this runs once per RENDERED ROW, and the
        // per-record walk would be a grant-store round trip per row on a page whose own
        // scope criteria already asked the same question set-wise.
        return HohenheimAccess.reachesRecord(accessContext, SiteModel.MODEL_ID,
            record.get(SiteDomainModel.SITE_ID), HohenheimAccess.MANAGE);
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

    /**
     * The Domains tab's quick-add bar: a hostname and whether it is HTTPS-only, with the
     * site riding along as a host-supplied preset.
     *
     * AIDEV-NOTE: MATCH_TYPE is deliberately NOT here even though the admin form and the
     * admin table both carry it -- {@link ManageDomainResource} narrows its formSpec to
     * the delegated subset, which has no match_type entry, and a bar naming an entry the
     * mirror's spec does not declare REFUSES that mirror's registration at boot. The
     * default (exact) is what a hostname typed into a one-line bar means anyway.
     */
    @Override
    public @Nullable QuickCreateSpec quickCreate() {
        return QUICK_CREATE;
    }

    /** The site the bar adds into: the {@code ?site_id=} prefill, else the tab's own record. */
    @Override
    public @NonNull Map<String, Object> quickCreatePresetValues(@NonNull AccessContext accessContext) {
        Conduit conduit = accessContext.conduit();
        if (conduit == null) {
            return Map.of();
        }
        Integer siteId = CmsSupport.scopedParentId(conduit, SiteDomainModel.SITE_ID.getName(),
            "sites");
        return siteId != null ? Map.of(SiteDomainModel.SITE_ID.getName(), siteId) : Map.of();
    }

    /**
     * The TLS switches, which are the everyday domain edits and are read per REQUEST
     * rather than baked into a route.
     *
     * AIDEV-NOTE: every write here is correct on a ONE-ENTRY map by construction, because
     * the route invariant reads its inputs through {@link SiteDomainModel#effective}
     * (submitted value else stored value) rather than off the coerced map -- which is why
     * this resource needs no {@code updateRow} override at all.
     *
     * AIDEV-NOTE: HOSTNAME, PATH, LISTEN_ON and MATCH_TYPE are excluded, and not for
     * tidiness: those four ARE the live route claim. Editing one frees the departing key
     * into the released-claim quarantine ledger (the beforeWrite hook above, release path
     * 2 of 3), so a one-click cell edit would quarantine a hostname the operator still
     * believes they own. That belongs on a form, next to the refusals that explain it.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(SiteDomainModel.FORCE_SSL, SiteDomainModel.HSTS_ENABLED,
            SiteDomainModel.HSTS_SUBDOMAINS, SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT);
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
     * tier of the SAME Schema.beforeWrite pass, and BOTH run inside the one write
     * transaction SiteDomainModel.save declares. That is deliberate: the serialized scan
     * is the authoritative refusal (it alone can judge listener-set OVERLAP, which no
     * unique key can spell -- see RouteClaims), and the claim stamp feeds the unique
     * index that refuses identical keys as the storage-level backstop.
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
            String key = RouteClaims.isLive(site) ? RouteClaims.keyOfPendingWrite(row) : null;
            // RELEASE PATH 2 of 3: editing the hostname, path or listener set of a LIVE row
            // frees the departing key with nothing else observing it -- the site write hook
            // never runs for a domain-only edit. The stored row is still readable here, and
            // (unlike a site delete) its grants are untouched, so the owner set is exact.
            Row stored = storedDomainOf(row);
            if (stored != null && !Objects.equals(key, stored.get(SiteDomainModel.LIVE_ROUTE_KEY))) {
                ReleasedClaims.recordReleaseOf(stored);
            }
            row.set(SiteDomainModel.LIVE_ROUTE_KEY, key);
        });
        // RELEASE PATH 3 of 3: deleting the domain row itself. SiteDomainResource inherits
        // RowResource.deleteRow's plain model.delete(), which runs no write hook at all --
        // and abandoning one hostname while keeping the site is the most ordinary way a
        // tenant releases a name. A ledger written at only SOME release points is worse
        // than none: it would quarantine some hostnames and silently free others.
        SiteDomainModel.SCHEMA.addBeforeRemoveHook(ReleasedClaims::recordReleaseOfDoomedRows);
    }

    /** The stored domain row a write targets, or null for a create. */
    private static @Nullable Row storedDomainOf(@NonNull Row row) {
        if (!row.has(SiteDomainModel.ID.getName()) || row.get(SiteDomainModel.ID) == null) {
            return null;
        }
        return Models.get(SiteDomainModel.class).findById(row.get(SiteDomainModel.ID));
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
        // AIDEV-NOTE: hostname conflict is a question about hostname SETS INTERSECTING
        // (HostnamePatterns), and the rule is OWNER-SCOPED -- never plain string equality.
        // An exact host falling under another row's wildcard is one contested host even
        // though the two spell different claim keys, and the dispatcher consults the exact
        // tier BEFORE the wildcard tier, so the exact row silently seizes traffic the
        // wildcard row was serving. Scoping: serving *.example.com broadly and carving
        // foo.example.com out to a different upstream is a legitimate, desirable
        // configuration -- and it NEEDS two sites, since an upstream is a site-level
        // setting, so the ownership unit here cannot be the site id. It is the site's
        // MANAGE-grant subjects (HohenheimAccess.sameOwner): operator-owned sites all
        // compare equal, and the moment a tenant is involved the same shape becomes a
        // takeover and is refused. IDENTICAL hostnames stay refused in every direction --
        // same-site duplicates are a config mistake and spell the same claim key anyway.
        // Do not "simplify" this back to Objects.equals; that is the defect it closes.
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
            if (!Objects.equals(path, normalizedPath(candidate.get(SiteDomainModel.PATH)))
                || !listenersOverlap(listenOn,
                    ListenerAddressMatcher.parse(candidate.get(SiteDomainModel.LISTEN_ON)))) {
                continue;
            }
            boolean identical = Objects.equals(canonicalHostname, candidateHostname);
            if (!identical) {
                if (sameSite || candidateSiteId == null
                    || !HostnamePatterns.intersect(canonicalHostname, matchType,
                        candidate.get(SiteDomainModel.HOSTNAME),
                        candidate.get(SiteDomainModel.MATCH_TYPE))
                    || HohenheimAccess.sameOwner(siteId, candidateSiteId)) {
                    continue;
                }
                String siteName = candidateSite != null
                    ? String.valueOf(candidateSite.get(SiteModel.NAME)) : "#" + candidateSiteId;
                throw Violations.ofField("hostname", hostname,
                    CmsSupport.violationText("route_overlaps_other_site")
                        .withArg("hostname", String.valueOf(candidateHostname))
                        .withArg("site", siteName));
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

        // The QUARANTINE tier, last: a live holder is the more actionable refusal and keeps
        // naming itself, so this only speaks when the route is genuinely unheld. It answers
        // from an INDEXED lookup on the claim key first, and judges hostname-set overlap
        // against the active ledger rows only if that let the claim through (a wildcard
        // swallows a released exact host while spelling a different key -- see
        // ReleasedClaims). Rows of a site that does not route are exempt here exactly like
        // they are above -- staging is legal, and the enable seam re-judges
        // (refuseEnableRouteConflicts), which is what closes the
        // stage-on-a-disabled-site-then-enable two-step.
        if (ownSiteLive) {
            Row quarantine = ReleasedClaims.refusalFor(RouteClaims.keyOfPendingWrite(row),
                matchType, siteId);
            if (quarantine != null) {
                throw Violations.ofField("hostname", hostname, quarantineViolation(quarantine,
                    "route_quarantined"));
            }
        }
    }

    /**
     * The quarantine refusal text.
     *
     * AIDEV-NOTE: it must NOT name the former owner. The refusals above name the holding
     * SITE because that site is live, visible and actionable for the operator reading the
     * message; a released claim's former owner is typically a DELETED tenant, and naming it
     * would leak one tenant's identity to the next -- a tenancy boundary none of the
     * existing refusals cross. Copying one of those messages here is the single most likely
     * way this leaks.
     */
    private static @NonNull Microcopy quarantineViolation(@NonNull Row quarantine,
                                                          @NonNull String key) {
        return CmsSupport.violationText(key)
            .withArg("hostname", String.valueOf(quarantine.get(ReleasedRouteClaimModel.HOSTNAME)))
            .withArg("days", String.valueOf(ReleasedClaims.remainingDays(quarantine)));
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
                String candidateHostname = SiteDomainModel.canonicalHostname(
                    candidate.get(SiteDomainModel.HOSTNAME), candidate.get(SiteDomainModel.MATCH_TYPE));
                if (!Objects.equals(ownPath, normalizedPath(candidate.get(SiteDomainModel.PATH)))
                    || !listenersOverlap(ownListen,
                        ListenerAddressMatcher.parse(candidate.get(SiteDomainModel.LISTEN_ON)))) {
                    continue;
                }
                // Every candidate here is on ANOTHER site, so a hostname-set overlap is a
                // takeover unless both sites answer to the same owner -- the same
                // owner-scoped rule refuseRouteConflicts documents.
                boolean identical = Objects.equals(ownHostname, candidateHostname);
                if (!identical && (!HostnamePatterns.intersect(
                        own.get(SiteDomainModel.HOSTNAME), own.get(SiteDomainModel.MATCH_TYPE),
                        candidate.get(SiteDomainModel.HOSTNAME),
                        candidate.get(SiteDomainModel.MATCH_TYPE))
                    || candidateSiteId == null
                    || HohenheimAccess.sameOwner(siteId, candidateSiteId))) {
                    continue;
                }
                throw Violations.ofField("enabled", true,
                    CmsSupport.violationText(
                            identical ? "enable_route_conflict" : "enable_route_overlap")
                        .withArg("hostname", String.valueOf(own.get(SiteDomainModel.HOSTNAME)))
                        .withArg("pattern", String.valueOf(candidateHostname))
                        .withArg("site", String.valueOf(candidateSite.get(SiteModel.NAME))));
            }

            // The quarantine tier of the ENABLE seam. Omitting it here would leave the
            // whole mechanism bypassable by a two-step the code above documents as LEGAL:
            // stage the released hostname on a DISABLED site (exempt by design), then
            // enable it. Anchored on 'enabled', like every other refusal on this path.
            Row quarantine = ReleasedClaims.refusalFor(RouteClaims.keyOf(own),
                stringValue(own.get(SiteDomainModel.MATCH_TYPE)), siteId);
            if (quarantine != null) {
                throw Violations.ofField("enabled", true,
                    quarantineViolation(quarantine, "enable_route_quarantined"));
            }
        }
    }

    /** THE overlap rule lives with the matcher, so the quarantine judges it identically. */
    private static boolean listenersOverlap(@NonNull List<String> first, @NonNull List<String> second) {
        return ListenerAddressMatcher.overlap(first, second);
    }

    private static @Nullable String stringValue(@Nullable Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    /** Canonical route path for uniqueness, delegated to the routing authority. */
    private static @Nullable String normalizedPath(@Nullable Object value) {
        return SiteDispatcher.normalizeRoutePath(value != null ? String.valueOf(value) : null);
    }

}
