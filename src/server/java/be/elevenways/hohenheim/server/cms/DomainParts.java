package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.server.tls.HostnameReach;
import be.elevenways.hohenheim.server.upstream.kinds.TlsPassthroughUpstreamKind;
import be.elevenways.hohenheim.site.DomainCertCell;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.render.CmsTemplateIds;
import be.elevenways.zenit.cms.common.render.table.RecordLink;
import be.elevenways.zenit.cms.common.render.table.RecordLinksCell;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RecordLead;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The site domain entries' shared parts, and the admin domain resource and its /manage twin built from them.
 *
 * AIDEV-NOTE: a domain is a hostname row of a site, hidden from the sidebar and reached through a site's Domains tab.
 * The route claim, hostname canonicalization and tenant column freeze are the model's write hooks
 * ({@link SiteDomainRouteInvariant}, {@code TenantWrites}), so both twins write plain rows; the /manage form is a UX
 * narrowing only, never the gate.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class DomainParts {

    /** Domains of live sites; the /manage scope narrows this same base per principal. */
    public static final RowScope ROWS = RowScope.within(DomainParts::liveSiteScope);

    /** The entry slug both twins share, which a site's Domains tab and the parent links name. */
    public static final String SLUG = "domains";

    /** The "Points here" column: whether the name resolves to this proxy. */
    static final String REACH_COLUMN = "points_here";

    /** The HTTPS column: what HTTPS gives the name, and the certificate behind it. */
    static final String CERTIFICATE_COLUMN = "certificate";

    /** The App column: the app the address serves, linked to its front door. */
    static final String APP_COLUMN = "app";

    /** Request memo of every site by id: the App cells of one rendered page read the sites once. */
    private static final IdentifierKey<Map<Integer, Row>> SITES_BY_ID = IdentifierKey.of("hohenheim", "domain_sites");

    /**
     * The Domains tab's quick-add entries; the site rides along as a host-supplied preset.
     *
     * AIDEV-NOTE: MATCH_TYPE is deliberately NOT here even though the admin form carries it: the /manage form has
     * no match_type entry, and a bar naming an entry a twin's form does not declare refuses that twin at boot. The
     * default (exact) is what a hostname typed into a one-line bar means anyway.
     */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(SiteDomainModel.HOSTNAME.getName(), SiteDomainModel.FORCE_SSL.getName())
        .presets(SiteDomainModel.SITE_ID.getName());

    private static final SubjectType<Row> SUBJECT = SubjectType.record(SiteDomainModel.MODEL_ID);

    /** The create verb in the Domains board's words: the list's button and the form's heading ("Add address"). */
    private static final Microcopy CREATE_TITLE = Microcopy.of("create_title").withFilter("scope", "site_domain");

    private DomainParts() {
    }

    /** @return the admin domain resource: every domain of a live site */
    public static @NonNull PanelResource<Row> admin() {
        return entry("site_domain")
            .scope(ROWS)
            .form(form(adminFormSpec()))
            // Requesting a certificate stays installation administration (an issued certificate is authority over a
            // name), so only the admin twin offers it.
            .actions(List.of(CertificateOperations.requestForDomainAction()))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return the /manage twin: the domains of the sites the caller manages, through the delegated form */
    public static @NonNull PanelResource<Row> manage() {
        return entry("manage_site_domain")
            .scope(TenantScopes.DOMAINS)
            .form(form(manageFormSpec()))
            // NAV-ONLY (zero granted sites hide the empty list); the route itself stays scoped.
            .hasInScopeRecords(ManagePanel::hasManageScope)
            .build();
    }

    /** The entry, list, reads, writes, authority and parent both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull String id) {
        return PanelResource.builder(HohenheimIds.id(id), SLUG, SUBJECT)
            .label(Microcopy.of("plural").withFilter("scope", "site_domain"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "site_domain"))
            .description(Microcopy.of("nav_hint").withFilter("scope", "site_domain"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(20)
            .icon(Icon.of("at"))
            .parent(ResourceParent.of(HohenheimSlugs.SITES, SiteDomainModel.SITE_ID).tab(SLUG))
            .list(list())
            .reads(ResourceReads.rows())
            .writes(ResourceMutations.rows().create().update().delete().build())
            .authority(authority());
    }

    /**
     * Writing a domain row demands {@code manage} on the site it binds to.
     *
     * AIDEV-NOTE: reachesRecord, never canManageSite: this runs once per RENDERED ROW, and the per-record walk would
     * be a grant-store round trip per row on a page whose own scope criteria already asked the same question
     * set-wise. The write pipeline's {@code TenantWrites} freeze stays the gate; this decides the affordance, so a
     * view-only delegate is never shown a control that can only refuse.
     */
    private static @NonNull ResourceAuthority<Row> authority() {
        return ResourceAuthority.<Row>builder()
            .write(null, (record, access) -> HohenheimAccess.reachesRecord(access, SiteModel.MODEL_ID,
                record.get(SiteDomainModel.SITE_ID), HohenheimAccess.MANAGE))
            // A create under a site (its Domains tab's add, the create form, the submit) asks the same of that site.
            .createUnder((site, access) -> site instanceof Integer id
                && HohenheimAccess.reachesRecord(access, SiteModel.MODEL_ID, id, HohenheimAccess.MANAGE))
            .build();
    }

    /**
     * An address is read by what visitors type and what they get: the name (its path under it), the app it serves,
     * whether the name points at this proxy, and what HTTPS gives it.
     *
     * AIDEV-NOTE: "Points here" reads {@link HostnameReach#recent}, so a list render resolves each name at most once a
     * minute; a pattern has no single name and gets no answer. The HTTPS cell is {@link AppHealth#httpsOf}, the one
     * per-name rule the app overview and the app's verdict read, so the list never calls a forced name without a
     * working certificate anything but broken.
     */
    private static @NonNull ResourceList<Row> list() {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(SiteDomainModel.HOSTNAME)
                .label(Microcopy.of("address_column").withFilter("scope", "site_domains"))
                .filterable().copyable().subtext("path").build())
            // How the name matches stays in the picker and the filter strip: the Domains board reads addresses by
            // the name visitors type, and a pattern says so in its empty "Points here" cell.
            .column(ColumnSpec.fromField(SiteDomainModel.MATCH_TYPE).filterable().hidden().build())
            .column(ColumnSpec.fromField(SiteDomainModel.PATH).hidden().build())
            // The app this address serves, by name, linked to the app's front door (appCell).
            .column(ColumnSpec.virtual(APP_COLUMN, Microcopy.of("app_column").withFilter("scope", "site_domains"))
                .renderer(CmsTemplateIds.CELL_RECORD_LINKS).build())
            .column(ColumnSpec.virtual(REACH_COLUMN,
                    Microcopy.of("points_here_column").withFilter("scope", "site_domains"))
                .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build())
            .column(ColumnSpec.virtual(CERTIFICATE_COLUMN,
                    Microcopy.of("https_column").withFilter("scope", "site_domains"))
                .renderer(HohenheimTemplateIds.CELL_DOMAIN_CERTIFICATE).build())
            .column(ColumnSpec.fromField(SiteDomainModel.FORCE_SSL).filterable().hidden().build())
            .filter(FilterSpec.leaf(SiteDomainModel.HOSTNAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(SiteDomainModel.HOSTNAME)).build())
            .filter(FilterSpec.leaf(SiteDomainModel.MATCH_TYPE, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(SiteDomainModel.MATCH_TYPE)).build())
            .filter(FilterSpec.leaf(SiteDomainModel.FORCE_SSL, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE)
                .label(FieldLabels.labelFor(SiteDomainModel.FORCE_SSL)).build())
            .build();
        return ResourceList.rows(table)
            .chrome(ListChrome.MINIMAL)
            .search(SiteDomainModel.HOSTNAME, SiteDomainModel.PATH)
            // What an empty list (a new site's Domains tab above all: it routes nothing yet) tells the reader to do.
            .emptyDescription(Microcopy.of("empty_description").withFilter("scope", "site_domains"))
            .computed(Objects.requireNonNull(table.column(APP_COLUMN)), DomainParts::appCell)
            .computed(Objects.requireNonNull(table.column(REACH_COLUMN)), DomainParts::reachCell)
            .computed(Objects.requireNonNull(table.column(CERTIFICATE_COLUMN)), DomainParts::certificateCell)
            .build();
    }

    /**
     * The app an address serves, by name, linked to that app's front door (its overview, through the framework's
     * {@code /open}) for a reader who may open it.
     *
     * AIDEV-NOTE: the site IS the app's page here (decision J1: a site's overview and its workload's are one
     * composition), so the link opens the site the row names, never a workload the reader may not reach.
     */
    static @Nullable RecordLinksCell appCell(@NonNull Row domain, @NonNull PanelRequest request) {
        Integer siteId = domain.get(SiteDomainModel.SITE_ID);
        Row site = siteId == null ? null
            : CmsSupport.memo(request.conduit(), SITES_BY_ID, DomainParts::sitesById).get(siteId);
        if (site == null) {
            return null;
        }
        boolean opens = AppDirectory.offers(request.panel(), HohenheimSlugs.SITES, request.access())
            && HohenheimAccess.reachesRecord(request.access(), SiteModel.MODEL_ID, siteId, HohenheimAccess.VIEW);
        return RecordLinksCell.of(new RecordLink(String.valueOf((Object) site.get(SiteModel.NAME)),
            opens ? CmsRoutes.open(request.panelSlug(), HohenheimSlugs.SITES, siteId).toUrl() : null));
    }

    /** @return every site by id, read once for a rendered page */
    private static @NonNull Map<Integer, Row> sitesById() {
        Map<Integer, Row> sites = new LinkedHashMap<>();
        for (Row site : Models.get(SiteModel.class).find().all()) {
            sites.put(site.get(SiteModel.ID), site);
        }
        return sites;
    }

    /**
     * Whether an exact name points at this proxy, in a word, and what it does instead; null for a pattern.
     *
     * AIDEV-NOTE: the rows of one page share ONE wait ({@link #REACH_RENDER_BUDGET_MS}, from the first cell drawn):
     * cells are computed row after row, and a name the resolver does not answer in time reads "Checking" while its
     * lookup keeps running into the cache for the next view. Without the shared budget a page of unanswered names
     * waited one lookup timeout per row.
     */
    static @Nullable StateLineCell reachCell(@NonNull Row domain, @NonNull PanelRequest request) {
        return reachCell(domain, reachWaitMs(request));
    }

    /** Whether an exact name points at this proxy, waiting at most {@code waitMs} for its lookup; null for a pattern. */
    static @Nullable StateLineCell reachCell(@NonNull Row domain, long waitMs) {
        if (!AppHealth.exact(domain)) {
            return null;
        }
        String hostname = String.valueOf((Object) domain.get(SiteDomainModel.HOSTNAME));
        HostnameReach.Reach reach = HostnameReach.recent(hostname, waitMs);
        return switch (reach.verdict()) {
            case CHECKING -> new StateLineCell("checking", BadgeVariant.OUTLINE,
                Microcopy.of("points_here_checking").withFilter("scope", "site_domains"),
                Microcopy.of("points_checking_detail").withFilter("scope", "site_domains"), null);
            case POINTS_HERE -> new StateLineCell("points_here", BadgeVariant.SUCCESS,
                Microcopy.of("points_here_yes").withFilter("scope", "site_domains"), null, null);
            case POINTS_ELSEWHERE -> new StateLineCell("points_elsewhere", BadgeVariant.WARNING,
                Microcopy.of("points_here_no").withFilter("scope", "site_domains"),
                Microcopy.of("points_elsewhere_detail").withFilter("scope", "site_domains")
                    .withArg("addresses", String.join(", ", reach.addresses())), null);
            case UNRESOLVED -> new StateLineCell("unresolved", BadgeVariant.WARNING,
                Microcopy.of("points_here_unresolved").withFilter("scope", "site_domains"),
                Microcopy.of("points_unresolved_detail").withFilter("scope", "site_domains"), null);
            case UNKNOWN -> new StateLineCell("unknown", BadgeVariant.OUTLINE,
                Microcopy.of("points_here_unknown").withFilter("scope", "site_domains"),
                Microcopy.of("points_unknown_detail").withFilter("scope", "site_domains"), null);
        };
    }

    /** How long the cells of one page may wait for name lookups together. */
    static final long REACH_RENDER_BUDGET_MS = 2_000;

    /** The moment this request's reach cells stop waiting for lookups, set by the first cell drawn. */
    private static final IdentifierKey<Long> REACH_DEADLINE = IdentifierKey.of("hohenheim", "reach_render_deadline");

    /** What this request's next reach cell may still wait: the rest of the page budget, at most one lookup's wait. */
    private static long reachWaitMs(@NonNull PanelRequest request) {
        long now = Now.millis();
        Long deadline;
        try {
            deadline = request.conduit().getAttribute(REACH_DEADLINE);
            if (deadline == null) {
                deadline = now + REACH_RENDER_BUDGET_MS;
                request.conduit().setAttribute(REACH_DEADLINE, deadline);
            }
        } catch (UnsupportedOperationException noAttributes) {
            // A conduit without request attributes (a bare test double) still gets one lookup's bound per cell.
            return HostnameReach.LOOKUP_WAIT_MS;
        }
        return Math.max(0, Math.min(HostnameReach.LOOKUP_WAIT_MS, deadline - now));
    }

    /**
     * What HTTPS gives an exact name, the certificate behind it, and its expiry. A pattern has no single name to
     * check and gets no cell; a TLS passthrough site's names say HTTPS is not this proxy's to give.
     *
     * AIDEV-NOTE: the certificate's NAME and link are set only for a reader the walk lets OPEN the certificate
     * ({@code view}, the question the certificate resource's scope asks): the covering certificate is usually the
     * operator's wildcard, and printing its name to a tenant for whom it is a 404 was a leak.
     */
    static @Nullable DomainCertCell certificateCell(@NonNull Row domain, @NonNull PanelRequest request) {
        boolean passthrough = SiteParts.tlsPassthrough(
            Models.get(SiteModel.class).findById(domain.get(SiteDomainModel.SITE_ID)));
        return certificateCell(domain, passthrough, AppHealth.workingNames(), request.access(),
            request.panelSlug());
    }

    /**
     * {@link #certificateCell(Row, PanelRequest)} over facts already read: the Apps list's HTTPS column draws its main
     * address through it, so the two lists say one name's HTTPS in the same words.
     *
     * @param passthrough whether the name belongs to a TLS passthrough site
     * @param working     the names a working certificate covers ({@link AppHealth#workingNames()})
     * @param access      who reads it, which decides whether the certificate is named and linked
     * @param panelSlug   the panel the link points into
     */
    static @Nullable DomainCertCell certificateCell(@NonNull Row domain, boolean passthrough,
                                                    @NonNull Set<String> working, @NonNull AccessContext access,
                                                    @NonNull String panelSlug) {
        CertCoverage coverage = AppHealth.httpsOf(domain, passthrough, working);
        if (coverage == null) {
            return null;
        }
        Row cert = coverage.hasCertificate()
            ? CertificateCoverage.coveringCertificate(domain.get(SiteDomainModel.HOSTNAME)) : null;
        Microcopy detail = httpsDetail(domain, coverage, cert);
        if (cert == null) {
            return new DomainCertCell(coverage.key(), coverage.badgeVariant(), coverage.label(), detail, null, null,
                null);
        }
        Instant expiresOn = cert.get(CertificateModel.EXPIRES_ON);
        Integer certId = cert.get(CertificateModel.ID);
        boolean canOpen = HohenheimAccess.reachesRecord(access, CertificateModel.MODEL_ID, certId,
            HohenheimAccess.VIEW);
        // The panel this list renders under carries a certificates entry on both faces.
        return new DomainCertCell(coverage.key(), coverage.badgeVariant(), coverage.label(), detail,
            canOpen ? String.valueOf((Object) cert.get(CertificateModel.NICE_NAME)) : null,
            canOpen ? CmsRoutes.detail(panelSlug, HohenheimSlugs.CERTIFICATES, certId).toUrl() : null,
            expiresOn != null ? expiresOn.toString() : null);
    }

    /**
     * Why HTTPS does not (fully) work for one exact name, null when it works: the reason beside the badge, so a "Not
     * working" row says what is wrong and, through the row's actions, what fixes it.
     *
     * AIDEV-NOTE: a name excluded from Let's Encrypt is one another server holds the certificate for, so the row
     * offers no "Get a certificate" (the request action's own applicability); the reason says so instead of leaving
     * a red badge with nothing beside it.
     */
    static @Nullable Microcopy httpsDetail(@NonNull Row domain, @NonNull CertCoverage coverage, @Nullable Row cert) {
        boolean forced = Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL));
        boolean excluded = Boolean.TRUE.equals(domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT));
        return switch (coverage) {
            case ACTIVE -> null;
            case NOT_USED -> domainText("https_passthrough");
            case PENDING -> domainText("https_being_issued");
            case NONE -> domainText(excluded ? "https_uncovered_excluded" : "https_uncovered");
            case ERROR -> cert != null && CertificateModel.STATUS_ERROR.equals(cert.get(CertificateModel.STATUS))
                ? domainText("https_certificate_failing")
                // Stored as working, but the proxy cannot serve it (AppHealth.httpsOf).
                : cert != null && CertificateModel.STATUS_ACTIVE.equals(cert.get(CertificateModel.STATUS))
                ? domainText("https_certificate_unserved")
                : !forced ? domainText("https_uncovered")
                : domainText(excluded ? "https_forced_excluded" : "https_forced_uncovered");
        };
    }

    private static @NonNull Microcopy domainText(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "site_domains");
    }

    /**
     * A twin's form part around its own spec: the quick-add bar with its site preset, the parent prefill and the
     * TLS switches as inline cells.
     *
     * AIDEV-NOTE: every inline write is correct on a ONE-ENTRY map by construction, because the route invariant reads
     * its inputs through {@link SiteDomainModel#effective} rather than off the coerced map. HOSTNAME, PATH, LISTEN_ON
     * and MATCH_TYPE are excluded on purpose: those four ARE the live route claim, and a one-click cell edit would
     * quarantine a hostname the operator still believes they own; that belongs on a form, next to the refusals that
     * explain it.
     */
    private static @NonNull ResourceForm<Row> form(@NonNull FormSpec spec) {
        return ResourceForm.<Row>of(spec)
            .createDefaults(request -> createDefaults(spec, request))
            .quickCreate(QUICK_CREATE)
            .quickCreatePresets(DomainParts::quickCreatePresets)
            .inlineEditable(SiteDomainModel.FORCE_SSL, SiteDomainModel.HSTS_ENABLED,
                SiteDomainModel.HSTS_SUBDOMAINS, SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT)
            .lead(DomainParts::lead)
            .build();
    }

    /**
     * The line under an address's heading: the app it serves, whether the name points here and what HTTPS gives it,
     * the same answers as its row in the Addresses list. None on the create form.
     */
    private static @Nullable RecordLead lead(@NonNull Row domain, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        if (domain.get(SiteDomainModel.ID) == null || conduit == null) {
            return null;
        }
        Row site = Models.get(SiteModel.class).findById(domain.get(SiteDomainModel.SITE_ID));
        String app = site == null ? "" : String.valueOf((Object) site.get(SiteModel.NAME));
        StateLineCell reach = reachCell(domain, HostnameReach.LOOKUP_WAIT_MS);
        CertCoverage https = AppHealth.httpsOf(domain, SiteParts.tlsPassthrough(site), AppHealth.workingNames());
        if (reach == null || https == null) {
            return new RecordLead(Microcopy.of("address_lead_pattern").withFilter("scope", "site_domains")
                .withArg("app", app)
                .resolve(conduit.getLocales(), conduit.getMessageResolver()), null);
        }
        return new RecordLead(Microcopy.of("address_lead").withFilter("scope", "site_domains")
            .withArg("app", app)
            .withArg("reach", reach.label().resolve(conduit.getLocales(), conduit.getMessageResolver()))
            .withArg("https", https.label().resolve(conduit.getLocales(), conduit.getMessageResolver()))
            .resolve(conduit.getLocales(), conduit.getMessageResolver()), null);
    }

    /**
     * A create under a TLS passthrough site (its Domains tab's add link names the site as the create's parent) opens
     * with HTTPS forcing and ACME off: that site terminates no TLS here. The parent field itself is the framework's
     * child create preset.
     *
     * AIDEV-NOTE: the site is read through the panel's own site entry, the caller's scope: a site the caller cannot
     * reach opens the create exactly as no site does, so the form is no probe of another tenant's configuration
     * (GPT review 25 D03).
     */
    private static @NonNull Map<String, Object> createDefaults(@NonNull FormSpec spec, @NonNull PanelRequest request) {
        Map<String, Object> values = new LinkedHashMap<>(spec.defaultValues());
        Integer siteId = CmsSupport.scopedParentId(request.conduit(), CmsEndpoints.PARENT_PARAM.getName(),
            HohenheimSlugs.SITES);
        if (siteId != null && request.panel().entryBySlug(HohenheimSlugs.SITES) instanceof PanelResource<?> sites) {
            Object site = PartsReads.loadRow(request, sites, siteId, request.access());
            if (site instanceof Row row && TlsPassthroughUpstreamKind.ID.toString()
                    .equals(row.get(SiteModel.UPSTREAM_KIND))) {
                values.put(SiteDomainModel.FORCE_SSL.getName(), false);
                values.put(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName(), true);
            }
        }
        return Map.copyOf(values);
    }

    /** The site the bar adds into: the {@code ?site_id=} prefill, else the tab's own record. */
    private static @NonNull Map<String, Object> quickCreatePresets(@NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        if (conduit == null) {
            return Map.of();
        }
        Integer siteId = CmsSupport.scopedParentId(conduit, SiteDomainModel.SITE_ID.getName(),
            HohenheimSlugs.SITES);
        return siteId != null ? Map.of(SiteDomainModel.SITE_ID.getName(), siteId) : Map.of();
    }

    /** Discovered local addresses (refreshed hourly by UpdateSystemIpAddresses); blank = all interfaces. */
    private static @NonNull List<FieldOption<String>> listenOnOptions() {
        List<FieldOption<String>> options = new ArrayList<>();
        for (String address : UpdateSystemIpAddresses.getLocalAddresses()) {
            // An address is data, never a translation key.
            options.add(FieldOption.of(address, Microcopy.literal(address)));
        }
        return options;
    }

    /**
     * The address form, grouped as an operator reads an address: which requests it answers, what it does about HTTPS,
     * and the headers it changes. The app it serves heads the form, outside any section.
     */
    private static @NonNull FormSpec adminFormSpec() {
        return FormSpec.builder()
            .createTitle(CREATE_TITLE)
            .add(RelationPick.of(SiteDomainModel.SITE_ID, SiteModel.MODEL_ID).build())
            .add(SiteDomainModel.HOSTNAME)
            // The select (options, labels, icons) derives from the MATCH_TYPE EnumField, the vocabulary's one home.
            .add(SiteDomainModel.MATCH_TYPE)
            .add(SiteDomainModel.PATH)
            .add(SiteDomainModel.STRIP_PATH)
            .add(Select.of(SiteDomainModel.LISTEN_ON)
                .options(OptionSource.dynamic(ctx -> listenOnOptions()))
                .build())
            .add(SiteDomainModel.FORCE_SSL)
            .add(RelationPick.of(SiteDomainModel.CERTIFICATE_ID, CertificateModel.MODEL_ID).build())
            .add(SiteDomainModel.HSTS_ENABLED)
            .add(SiteDomainModel.HSTS_SUBDOMAINS)
            .add(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteDomainModel.CUSTOM_HEADERS))
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteDomainModel.RESPONSE_HEADERS))
            .section(HohenheimFormSections.open(HohenheimFormSections.ADDRESS_REQUESTS, List.of(
                SiteDomainModel.HOSTNAME.getName(),
                SiteDomainModel.MATCH_TYPE.getName(),
                SiteDomainModel.PATH.getName(),
                SiteDomainModel.STRIP_PATH.getName(),
                SiteDomainModel.LISTEN_ON.getName())))
            .section(HohenheimFormSections.open(HohenheimFormSections.ADDRESS_HTTPS, List.of(
                SiteDomainModel.FORCE_SSL.getName(),
                SiteDomainModel.CERTIFICATE_ID.getName(),
                SiteDomainModel.HSTS_ENABLED.getName(),
                SiteDomainModel.HSTS_SUBDOMAINS.getName(),
                SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName())))
            // Headers are tuning most addresses never need: folded until an operator opens them.
            .section(HohenheimFormSections.collapsed(HohenheimFormSections.ADDRESS_HEADERS, List.of(
                SiteDomainModel.CUSTOM_HEADERS.getName(),
                SiteDomainModel.RESPONSE_HEADERS.getName())))
            .build();
    }

    /**
     * The delegated form. A UX affordance ONLY: the fields it omits are frozen by {@code TenantWrites} on the
     * SiteDomainModel write pipeline, which every writer passes and this form does not.
     */
    private static @NonNull FormSpec manageFormSpec() {
        return FormSpec.builder()
            .createTitle(CREATE_TITLE)
            .add(RelationPick.of(SiteDomainModel.SITE_ID, SiteModel.MODEL_ID).build())
            .add(SiteDomainModel.HOSTNAME)
            .add(SiteDomainModel.FORCE_SSL)
            .add(SiteDomainModel.HSTS_ENABLED)
            .add(SiteDomainModel.HSTS_SUBDOMAINS)
            .add(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT)
            .build();
    }

    /**
     * A soft-deleted site is invisible everywhere, and so are its hostnames.
     *
     * AIDEV-NOTE: the rows are deliberately NOT deleted with the site: they are what a site restore brings its
     * hostnames back from, and {@code DnsClaimReleases} already quarantined the names. This is a VISIBILITY answer,
     * so the same predicate 404s the detail route of an orphaned row. Spelled through the site's SoftDeleteBehaviour
     * because a relation hop runs no find hook.
     */
    private static @NonNull Criteria liveSiteScope() {
        return Criteria.related(SiteDomainModel.SITE, SiteModel.SOFT_DELETE.isNotTrashed());
    }
}
